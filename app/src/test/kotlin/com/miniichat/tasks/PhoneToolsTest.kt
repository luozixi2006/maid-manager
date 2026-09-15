package com.miniichat.tasks

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlinx.serialization.encodeToString

class PhoneToolsTest {
    @get:Rule val directory = TemporaryFolder()
    private fun rejects(action: () -> Unit) { try { action(); fail("Should reject") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {} }
    private fun source(name: String = "school.pdf", content: String = "school document") = File(directory.root, name).apply { writeText(content) }
    private fun fact(file: File) = FileFact(file.name, file.length(), file.lastModified())
    @Test fun pathsCannotEscapeScope() {
        listOf("../outside.pdf", "/sdcard/secret.pdf", "a/../b", "C:/user", "a\\b", "a//b", "Android/data/a.pdf", "a\n.pdf").forEach {
            rejects { ToolPolicy.resolve(directory.root, it) }
        }
        assertEquals(File(directory.root, "课程/笔记.pdf").canonicalFile, ToolPolicy.resolve(directory.root, "课程/笔记.pdf"))
    }
    @Test fun modelCannotApproveOrForgeReceipt() {
        val f = source()
        val proposed = PhoneStep("move", f.name, "course.pdf", approved = true, prepared = true, done = true,
            resolvedTarget = "bad.pdf", fingerprint = "forged")
        val safe = ToolPolicy.validate(PhonePlan(listOf(proposed)), listOf(fact(f))).steps.single()
        assertFalse(safe.approved); assertFalse(safe.done); assertFalse(safe.prepared); assertEquals("", safe.fingerprint)
    }
    @Test fun rejectsDeletionAndUnscannedSources() {
        rejects { ToolPolicy.validate(PhonePlan(listOf(PhoneStep("delete", "x.pdf"))), emptyList()) }
        rejects { ToolPolicy.validate(PhonePlan(listOf(PhoneStep("move", "x.pdf", "b.pdf"))), emptyList()) }
    }
    @Test fun rejectsDuplicateFileOperations() {
        val f = source()
        rejects { ToolPolicy.validate(PhonePlan(listOf(PhoneStep("move", f.name, "a.pdf"), PhoneStep("rename", f.name, "b.pdf"))), listOf(fact(f))) }
    }
    @Test fun sameNameKeepsBothFiles() {
        val f = source(); val existing = source("course.pdf", "original")
        val tools = JournaledFiles(directory.root)
        val step = tools.prepare(PhoneStep("move", f.name, existing.name, approved = true), listOf(fact(f)))
        assertEquals("course (1).pdf", step.resolvedTarget)
        tools.execute(step)
        assertEquals("original", existing.readText()); assertEquals("school document", File(directory.root, step.resolvedTarget).readText())
    }
    @Test fun crashAfterMoveResumesWithoutMovingAgain() {
        val f = source(); val tools = JournaledFiles(directory.root)
        val step = tools.prepare(PhoneStep("rename", f.name, "renamed.pdf", approved = true), listOf(fact(f)))
        // Roundtrip exactly as the DB write-ahead checkpoint does before a process dies.
        val restored = taskJson.decodeFromString<PhoneStep>(taskJson.encodeToString(step))
        tools.execute(step)
        assertTrue(tools.execute(restored).contains("已核对恢复"))
        assertEquals(1, directory.root.listFiles()!!.size)
    }
    @Test fun fileChangedAfterScanIsNotMoved() {
        val f = source(); val before = fact(f); f.writeText("changed contents")
        rejects { JournaledFiles(directory.root).prepare(PhoneStep("move", f.name, "b.pdf", approved = true), listOf(before)) }
        assertTrue(f.exists())
    }
    @Test fun fileChangedAfterPreparationIsNotMoved() {
        val f = source(); val tools = JournaledFiles(directory.root)
        val step = tools.prepare(PhoneStep("move", f.name, "b.pdf", approved = true), listOf(fact(f)))
        f.writeText("changed contents"); rejects { tools.execute(step) }; assertTrue(f.exists())
    }
    @Test fun concurrentDestinationNeverOverwritten() {
        val f = source(); val tools = JournaledFiles(directory.root)
        val step = tools.prepare(PhoneStep("move", f.name, "b.pdf", approved = true), listOf(fact(f)))
        val target = source("b.pdf", "another file")
        rejects { tools.execute(step) }; assertTrue(f.exists()); assertEquals("another file", target.readText())
    }
    @Test fun wrongRecoveryTargetStops() {
        val f = source(); val tools = JournaledFiles(directory.root)
        val step = tools.prepare(PhoneStep("move", f.name, "b.pdf", approved = true), listOf(fact(f)))
        tools.execute(step); File(directory.root, "b.pdf").writeText("changed target")
        rejects { tools.execute(step) }
    }
    @Test fun noExecutionWithoutApproval() {
        rejects { JournaledFiles(directory.root).execute(PhoneStep("mkdir", destination = "course", prepared = true, resolvedTarget = "course")) }
        assertFalse(File(directory.root, "course").exists())
    }
    @Test fun mkdirReplayIsIdempotent() {
        val tools = JournaledFiles(directory.root)
        val step = tools.prepare(PhoneStep("mkdir", destination = "课程/数学", approved = true), emptyList())
        tools.execute(step); tools.execute(step); assertTrue(File(directory.root, "课程/数学").isDirectory)
    }
    @Test fun savedTaskContainsCheckpointAndNoApiKey() {
        val task = PhoneTask(goal = "整理课程", providerId = "custom", providerEndpoint = "https://example.test", model = "any",
            state = TaskState.QUESTION, cursor = 1, history = listOf("a.pdf → 数学/a.pdf"), answers = listOf("保留不动"))
        val raw = taskJson.encodeToString(task)
        assertEquals(task, taskJson.decodeFromString<PhoneTask>(raw)); assertFalse(raw.contains("apiKey"))
    }
}
