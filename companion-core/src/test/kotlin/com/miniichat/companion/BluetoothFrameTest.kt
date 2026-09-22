package com.miniichat.companion

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Framing only: in-memory streams, no sockets, no bonding and no Android services. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class BluetoothFrameTest {
    private fun header(size:Int):ByteArray {
        val out=ByteArrayOutputStream();DataOutputStream(out).apply{writeInt(size);flush()}
        return out.toByteArray()
    }
    private fun frame(body:JSONObject):ByteArray {
        val out=ByteArrayOutputStream();BluetoothLink.write(out,body)
        return out.toByteArray()
    }

    @Test fun unicodePayloadRoundTripsByteExactly() {
        val body=JSONObject().put("id","m-1").put("content","你好，手表 👋 café\ttab\n换行")
        assertEquals(body.toString(),BluetoothLink.read(ByteArrayInputStream(frame(body))).toString())

        // Multi-byte content well under the frame limit keeps its exact length and text.
        val long=JSONObject().put("content","汉字🙂".repeat(5_000))
        val decoded=BluetoothLink.read(ByteArrayInputStream(frame(long)))
        assertEquals(long.getString("content"),decoded.getString("content"))
    }

    @Test fun zeroAndNegativeFrameLengthsAreRejected() {
        for(size in listOf(0,-1,-8,Int.MIN_VALUE))
            assertThrows(IllegalArgumentException::class.java){BluetoothLink.read(ByteArrayInputStream(header(size)))}
    }

    @Test fun oversizeFrameLengthsAreRejectedInsteadOfTruncating() {
        for(size in listOf(BluetoothLink.MAX_FRAME+1,Int.MAX_VALUE))
            assertThrows(IllegalArgumentException::class.java){BluetoothLink.read(ByteArrayInputStream(header(size)))}

        val huge=JSONObject().put("content","a".repeat(BluetoothLink.MAX_FRAME))
        assertThrows(IllegalArgumentException::class.java){BluetoothLink.write(ByteArrayOutputStream(),huge)}
    }

    @Test fun truncatedFramesAreRejectedInsteadOfReturningPartialJson() {
        val bytes=frame(JSONObject().put("content","完整内容"))
        for(cut in 0 until bytes.size)
            assertThrows(EOFException::class.java){BluetoothLink.read(ByteArrayInputStream(bytes.copyOf(cut)))}
    }
}
