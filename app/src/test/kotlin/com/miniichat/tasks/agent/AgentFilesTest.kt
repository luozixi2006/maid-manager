package com.miniichat.tasks.agent

import com.miniichat.tasks.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlinx.serialization.encodeToString

class AgentFilesTest {
    @get:Rule val folder = TemporaryFolder()
    private fun file(name: String, content: String = "hello") = File(folder.root, name).apply { parentFile!!.mkdirs(); writeText(content) }
    private fun rejects(block: () -> Unit) { try { block(); fail("must reject") } catch (_: IllegalStateException) {} catch (_: IllegalArgumentException) {} }
    @Test fun ordinaryFilesMoveWithoutChangingFormat() {
        val source = file("photo.jpg"); val engine = ManagedFiles(folder.root)
        val step = engine.prepare(PhoneStep("move", source.name, "new.jpg", approved = true), "task-1")
        engine.execute(step); assertEquals("hello", fileExisting("new.jpg").readText()); assertFalse(source.exists())
    }
    private fun fileExisting(name: String) = File(folder.root, name)
    @Test fun copyIsVerifiedAndResumable() {
        file("a.zip"); val engine = ManagedFiles(folder.root)
        val step = engine.prepare(PhoneStep("copy", "a.zip", "b.zip", approved = true), "task-1")
        engine.execute(step); engine.execute(taskJson.decodeFromString(taskJson.encodeToString(step)))
        assertTrue(fileExisting("a.zip").exists()); assertEquals("hello", fileExisting("b.zip").readText())
    }
    @Test fun textCreationNeverOverwrites() {
        file("note.md", "old")
        val engine = ManagedFiles(folder.root)
        val step = engine.prepare(PhoneStep("write_text", destination = "note.md", approved = true, arguments = mapOf("text" to "new")), "task-2")
        engine.execute(step); engine.execute(step)
        assertEquals("old", fileExisting("note.md").readText()); assertEquals("new", fileExisting("note (1).md").readText())
    }
    @Test fun trashAndRestoreDoNotDelete() {
        file("original.bin", "bytes")
        val engine = ManagedFiles(folder.root)
        val trash = engine.prepare(PhoneStep("trash", source = "original.bin", approved = true), "task-3")
        engine.execute(trash); assertFalse(fileExisting("original.bin").exists())
        engine.execute(trash)
        val restore = engine.prepare(PhoneStep("restore", source = "task-3", destination = "original.bin", approved = true), "task-4")
        engine.execute(restore); assertEquals("bytes", fileExisting("original.bin").readText())
    }
    @Test fun changedSourceAndConflictingTargetStop() {
        val source = file("a.mp3"); val engine = ManagedFiles(folder.root)
        val step = engine.prepare(PhoneStep("move", "a.mp3", "b.mp3", approved = true), "task-1")
        source.writeText("changed"); rejects { engine.execute(step) }; assertTrue(source.exists())
    }
    @Test fun maliciousModelFieldsAreDiscarded() {
        val step = AgentPolicy.validate(PhoneStep("copy", "a.txt", "b.txt", approved = true, done = true, prepared = true, fingerprint = "forged"))
        assertFalse(step.approved); assertFalse(step.done); assertFalse(step.prepared); assertEquals("", step.fingerprint)
    }
    @Test fun unsafeToolsAndPathsRejected() {
        rejects { AgentPolicy.validate(PhoneStep("shell", arguments = mapOf("command" to "rm -rf /"))) }
        rejects { AgentPolicy.validate(PhoneStep("read_file", source = "../private")) }
        rejects { AgentPolicy.validate(PhoneStep("read_file", source = ".maid-staging/data")) }
        rejects { AgentPolicy.validate(PhoneStep("read_file", source = "nested/.maid-recovery/item")) }
    }
    @Test fun consequentialActionsCannotBeRemembered() {
        for (tool in listOf("trash", "restore", "tap", "type_text", "share_file", "calendar_event", "read_notifications", "read_screen")) {
            assertFalse(AgentPolicy.canRemember(PhoneStep(tool)))
        }
    }
    @Test fun systemPermissionAndOwnAppCannotBeControlled() {
        assertFalse(AgentPolicy.validPackage("com.android.settings"))
        assertFalse(AgentPolicy.validPackage("com.google.android.permissioncontroller"))
        assertFalse(AgentPolicy.validPackage("com.maidmanager.debug"))
        assertTrue(AgentPolicy.validPackage("org.example.notes"))
    }
    @Test fun rememberedApprovalIsScopedToApp() {
        val task = PhoneTask(goal = "x", providerId = "x", providerEndpoint = "x", model = "x", engineVersion = 2)
        assertNotEquals(AgentPolicy.approvalKey(task, PhoneStep("scroll", arguments = mapOf("package" to "one.app"))),
            AgentPolicy.approvalKey(task, PhoneStep("scroll", arguments = mapOf("package" to "two.app"))))
    }
    @Test fun pageActionCannotReuseObservationAfterAnAction() {
        val read = PhoneStep("read_screen", done = true, result = """{"package":"one.app","snapshot":"real"}""")
        val tap = PhoneStep("tap", arguments = mapOf("package" to "one.app", "snapshot" to "real", "node" to "1"))
        val task = PhoneTask(goal = "x", providerId = "p", providerEndpoint = "p", model = "m", steps = listOf(read), cursor = 1)
        AgentPolicy.requireFreshObservation(task, tap)
        rejects { AgentPolicy.requireFreshObservation(task.copy(steps = listOf(read, tap.copy(done = true)), cursor = 2), tap) }
        rejects { AgentPolicy.requireFreshObservation(task, tap.copy(arguments = tap.arguments + ("snapshot" to "forged"))) }
    }
}
