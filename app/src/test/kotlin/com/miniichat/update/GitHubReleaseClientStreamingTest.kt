package com.miniichat.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val APK_URL =
    "https://github.com/miniichat/maid-manager/releases/download/v3.0.13/maid-manager.apk"
private const val SENTINEL = "PREVIOUS-APK-SENTINEL-CONTENT"
private const val GATE_TIMEOUT_SECONDS = 3L
private const val MIB = 1024L * 1024L

/**
 * Regression tests for the bounded, streaming APK download of [GitHubReleaseClient].
 *
 * Every response body is generated lazily by a [Source] fixture, so no test ever
 * materialises a large body in memory and no test touches the network: an OkHttp
 * application interceptor answers the HTTPS request with a synthetic [Response].
 */
class GitHubReleaseClientStreamingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val clients = mutableListOf<HttpClient>()

    @After
    fun closeClients() {
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }

    @Test(timeout = 120_000)
    fun `lazily generated large apk streams to disk with bounded progress`() {
        val totalBytes = 144L * MIB
        val firstGateBytes = 64L * 1024L
        val progressObserved = CountDownLatch(1)
        val nonzeroProgressObserved = CountDownLatch(1)
        val progress = Collections.synchronizedList(mutableListOf<Int?>())
        val body = SyntheticApkBody(
            declaredBytes = totalBytes,
            gates = listOf(
                // Gate 1: a progress report must have been observed before the fixture has
                // served 64 KiB. A client that buffers the complete body before its first
                // progress report (for example get() instead of a scoped statement) never
                // releases this latch, so the gate fails with an IOException after 3 s
                // instead of hanging the test.
                ProgressGate(firstGateBytes, progressObserved, "progress callback"),
                // Gate 2: the first non-zero percentage must be reported before half of the
                // body has been served. The margin is deliberately wide (72 MiB) so that the
                // reader's bounded look-ahead can never trip it, while an implementation that
                // buffers most of the APK before reporting progress still fails.
                ProgressGate(totalBytes / 2L, nonzeroProgressObserved, "non-zero progress callback")
            )
        )
        val client = client { request -> contentLengthResponse(request, body) }
        val target = File(tempFolder.newFolder("streaming"), "maid-manager.apk")

        var failure: Throwable? = null
        runBlocking {
            try {
                GitHubReleaseClient(client).download(APK_URL, target) { percent ->
                    progress.add(percent)
                    if (percent != null) progressObserved.countDown()
                    if (percent != null && percent > 0) nonzeroProgressObserved.countDown()
                }
            } catch (error: Throwable) {
                failure = error
            }
        }

        assertNull("download failed: $failure", failure)
        assertEquals("target must contain the full body", totalBytes, target.length())
        assertTrue("body must be read incrementally", body.readCount > 1)
        assertFalse("partial file must not survive a success", partialOf(target).exists())

        val recorded = synchronized(progress) { progress.toList() }
        assertTrue("progress must be reported", recorded.isNotEmpty())
        assertTrue("progress must always carry a percentage", recorded.all { it != null })
        assertTrue("progress must stay within 0..100", recorded.all { it!! in 0..100 })
        assertTrue("progress must never decrease", recorded.zipWithNext().all { (a, b) -> b!! >= a!! })
        assertTrue("progress must reach non-zero values", recorded.any { it!! > 0 })
        assertEquals("progress must end at 100", 100, recorded.last()!!)

        assertContentAt(target, 0L)
        assertContentAt(target, firstGateBytes)
        assertContentAt(target, totalBytes / 2L)
        assertContentAt(target, totalBytes - 8L)
    }

    @Test(timeout = 60_000)
    fun `truncated body fails and preserves the previous target`() {
        val body = SyntheticApkBody(declaredBytes = 4L * MIB, producedBytes = MIB)
        val client = client { request -> contentLengthResponse(request, body) }
        val target = File(tempFolder.newFolder("truncated"), "maid-manager.apk")
            .apply { writeText(SENTINEL) }

        val error = downloadFailure(client, target)

        assertTrue("expected UpdateException, got $error", error is UpdateException)
        // Truncation may be caught by the stream wrapper (network) or by the
        // declared-length comparison (integrity); both are acceptable rejections.
        val reason = (error as UpdateException).reason
        assertTrue(
            "unexpected reason $reason",
            reason == UpdateFailureReason.APK_INTEGRITY_FAILED ||
                reason == UpdateFailureReason.DOWNLOAD_NETWORK_FAILED
        )
        assertEquals("previous target must be preserved", SENTINEL, target.readText())
        assertFalse("partial file must be removed", partialOf(target).exists())
    }

    @Test(timeout = 60_000)
    fun `cancellation from progress removes partial file and keeps target`() {
        val body = SyntheticApkBody(declaredBytes = 8L * MIB)
        val client = client { request -> contentLengthResponse(request, body) }
        val target = File(tempFolder.newFolder("cancelled"), "maid-manager.apk")
            .apply { writeText(SENTINEL) }

        var progressPercent: Int? = null
        var error: Throwable? = null
        runBlocking {
            try {
                GitHubReleaseClient(client).download(APK_URL, target) { percent ->
                    // Cancelling on the first non-zero percentage means bytes were already
                    // received and written, so the partial file cleanup is exercised.
                    if (percent != null && percent > 0) {
                        progressPercent = percent
                        throw CancellationException("download cancelled by test")
                    }
                }
            } catch (throwable: Throwable) {
                error = throwable
            }
        }

        assertTrue("expected CancellationException, got $error", error is CancellationException)
        assertNotNull("progress must be reported before cancellation", progressPercent)
        assertEquals("previous target must be preserved", SENTINEL, target.readText())
        assertFalse("partial file must be removed", partialOf(target).exists())
    }

    @Test(timeout = 60_000)
    fun `http 404 is classified with status and never replaces the target`() {
        val client = client { request ->
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(404)
                .message("Not Found")
                .header("Content-Length", "32")
                .body(fixedBody(ByteArray(32) { 0x7A }))
                .build()
        }
        val target = File(tempFolder.newFolder("not-found"), "maid-manager.apk")
            .apply { writeText(SENTINEL) }

        val error = downloadFailure(client, target)

        assertTrue("expected UpdateException, got $error", error is UpdateException)
        val update = error as UpdateException
        assertEquals(UpdateFailureReason.DOWNLOAD_NETWORK_FAILED, update.reason)
        assertEquals("httpStatus", 404, update.httpStatus!!.toInt())
        assertEquals("error body must not be persisted", SENTINEL, target.readText())
        assertFalse("partial file must be removed", partialOf(target).exists())
    }

    @Test(timeout = 60_000)
    fun `empty body is rejected as an integrity failure`() {
        val body = SyntheticApkBody(declaredBytes = 0L)
        val client = client { request -> contentLengthResponse(request, body) }
        val target = File(tempFolder.newFolder("empty"), "maid-manager.apk")

        val error = downloadFailure(client, target)

        assertTrue("expected UpdateException, got $error", error is UpdateException)
        assertEquals(UpdateFailureReason.APK_INTEGRITY_FAILED, (error as UpdateException).reason)
        assertFalse("no target may be produced from an empty body", target.exists())
        assertFalse("partial file must be removed", partialOf(target).exists())
    }

    @Test(timeout = 60_000)
    fun `oversized declared length is rejected without consuming the body`() {
        val body = SyntheticApkBody(declaredBytes = 512L * MIB + 1L)
        val client = client { request -> contentLengthResponse(request, body) }
        val target = File(tempFolder.newFolder("oversized"), "maid-manager.apk")

        val error = downloadFailure(client, target)

        assertTrue("expected UpdateException, got $error", error is UpdateException)
        assertEquals(UpdateFailureReason.APK_INTEGRITY_FAILED, (error as UpdateException).reason)
        // Ktor's engine may prefetch a few bounded chunks before delivering headers.
        // That is safe: the application must neither consume the body nor save it.
        assertTrue("only bounded transport prefetch is allowed", body.readCount <= 8)
        assertFalse("no target may be produced", target.exists())
        assertFalse("partial file must be removed", partialOf(target).exists())
    }

    @Test(timeout = 60_000)
    fun `response without content length is streamed to disk`() = runBlocking {
        val size = 3L * MIB
        val body = SyntheticApkBody(declaredBytes = -1, producedBytes = size)
        val http = client { request ->
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body).build()
        }
        val target = File(tempFolder.newFolder("unknown-length"), "maid-manager.apk")
        val progress = mutableListOf<Int?>()
        GitHubReleaseClient(http).download(APK_URL, target) { progress += it }
        assertEquals(size, target.length())
        assertNull(progress.first())
        assertEquals(100, progress.last())
        assertContentAt(target, size - 8)
        assertFalse(partialOf(target).exists())
    }

    @Test(timeout = 60_000)
    fun `metadata picks manifest rather than watch apk and preserves phone identity`() = runBlocking {
        val manifestUrl = "https://github.com/miniichat/maid-manager/releases/download/v3.0.14/update-manifest.json"
        val releaseJson = """{"tag_name":"v3.0.14","assets":[
            {"name":"watch.apk","browser_download_url":"https://github.com/miniichat/maid-manager/releases/download/v3.0.14/watch.apk"},
            {"name":"update-manifest.json","browser_download_url":"$manifestUrl"}]}"""
        val manifestJson = """{"versionCode":300000014,"versionName":"3.0.14","applicationId":"com.maidmanager.debug","apkUrl":"$APK_URL","sha256":"${"a".repeat(64)}"}"""
        val visited = mutableListOf<String>()
        val http = client { request ->
            val url = request.url.toString()
            visited += url
            val payload = when (url) {
                "https://api.github.com/repos/miniichat/maid-manager/releases/latest" -> releaseJson
                manifestUrl -> manifestJson
                else -> throw IOException("unexpected test request")
            }.toByteArray()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").header("Content-Length", payload.size.toString())
                .body(fixedBody(payload)).build()
        }
        val result = GitHubReleaseClient(http).latestManifest("miniichat", "maid-manager")
        assertEquals("com.maidmanager.debug", result.applicationId)
        assertEquals(300000014, result.versionCode)
        assertEquals(APK_URL, result.apkUrl)
        assertEquals(2, visited.size)
    }

    @Test(timeout = 60_000)
    fun `unknown length oversized metadata is bounded before parsing`() = runBlocking {
        val body = SyntheticApkBody(declaredBytes = -1, producedBytes = 4L * MIB)
        val http = client { request ->
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body).build()
        }
        var failure: Throwable? = null
        try {
            GitHubReleaseClient(http).latestManifest("miniichat", "maid-manager")
        } catch (error: Throwable) {
            failure = error
        }
        assertTrue(failure is UpdateException)
        assertEquals(UpdateFailureReason.MANIFEST_INVALID, (failure as UpdateException).reason)
        assertTrue("metadata reader must stop at its limit", body.readCount < 160)
    }

    private fun client(reply: (Request) -> Response): HttpClient =
        interceptingClient(Interceptor { chain -> reply(chain.request()) })

    private fun interceptingClient(interceptor: Interceptor): HttpClient {
        val created = HttpClient(OkHttp) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                socketTimeoutMillis = 10_000
            }
            engine {
                config {
                    addInterceptor(interceptor)
                }
            }
        }
        clients += created
        return created
    }

    private fun downloadFailure(client: HttpClient, target: File): Throwable? {
        var failure: Throwable? = null
        runBlocking {
            try {
                GitHubReleaseClient(client).download(APK_URL, target) { }
            } catch (error: Throwable) {
                failure = error
            }
        }
        return failure
    }

    private fun contentLengthResponse(request: Request, body: SyntheticApkBody): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Length", body.contentLength().toString())
            .body(body)
            .build()

    private fun fixedBody(payload: ByteArray): ResponseBody = object : ResponseBody() {
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = payload.size.toLong()
        override fun source(): BufferedSource = Buffer().apply { write(payload) }
    }

    private fun partialOf(target: File): File = File(target.parentFile, "${target.name}.part")

    private fun assertContentAt(file: File, offset: Long) {
        val expected = ByteArray(8) { SyntheticApkBody.expectedByteAt(offset + it) }
        val actual = ByteArray(8)
        RandomAccessFile(file, "r").use { randomAccess ->
            randomAccess.seek(offset)
            randomAccess.readFully(actual)
        }
        assertArrayEquals("unexpected content at offset $offset", expected, actual)
    }
}

/** One lazily produced byte per offset: `(offset and 0xFF).toByte()`. */
private class SyntheticApkBody(
    private val declaredBytes: Long,
    producedBytes: Long = declaredBytes,
    gates: List<ProgressGate> = emptyList()
) : ResponseBody() {
    private val generated = GeneratedApkSource(producedBytes, gates)
    private val buffered: BufferedSource = generated.buffer()

    /** Number of [Source.read] calls, i.e. how often the body was actually pulled. */
    val readCount: Int get() = generated.readCount

    override fun contentType(): MediaType? = null

    override fun contentLength(): Long = declaredBytes

    override fun source(): BufferedSource = buffered

    companion object {
        fun expectedByteAt(offset: Long): Byte = (offset and 0xFFL).toByte()
    }
}

/**
 * A boundary that fails the download unless [latch] was released before
 * [thresholdBytes] were served. The wait is bounded, so a client that never
 * reports progress fails instead of hanging the test.
 */
private class ProgressGate(
    val thresholdBytes: Long,
    val latch: CountDownLatch,
    val label: String
) {
    var satisfied = false
}

private class GeneratedApkSource(
    private val totalBytes: Long,
    private val gates: List<ProgressGate>,
    private val chunkSize: Int = 8 * 1024
) : Source {
    private val scratch = ByteArray(chunkSize)
    private var produced = 0L
    private var closed = false
    private var reads = 0

    val readCount: Int get() = reads

    override fun read(sink: Buffer, byteCount: Long): Long {
        if (closed) throw IOException("synthetic body source is closed")
        if (byteCount == 0L) return 0L
        awaitProgressGates()
        reads++
        if (produced >= totalBytes) return -1L
        val toWrite = minOf(byteCount, chunkSize.toLong(), totalBytes - produced).toInt()
        val base = produced
        for (index in 0 until toWrite) {
            scratch[index] = ((base + index) and 0xFFL).toByte()
        }
        sink.write(scratch, 0, toWrite)
        produced += toWrite
        return toWrite.toLong()
    }

    private fun awaitProgressGates() {
        for (gate in gates) {
            if (gate.satisfied || produced < gate.thresholdBytes) continue
            if (!gate.latch.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw IOException(
                    "no ${gate.label} before ${gate.thresholdBytes} bytes were served"
                )
            }
            gate.satisfied = true
        }
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        closed = true
    }
}
