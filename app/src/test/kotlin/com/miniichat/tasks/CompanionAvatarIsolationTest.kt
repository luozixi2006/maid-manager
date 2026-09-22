package com.miniichat.tasks

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Chat and work avatars are separate slots; neither custom avatar may leak into the other mode. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CompanionAvatarIsolationTest {
    private fun resolve(work: Boolean, chatCustom: String = "", workCustom: String = "",
                        taskAvatar: String? = null, personaAvatar: String = "persona.png") =
        CompanionAppearance.resolveForMode(work, chatCustom, workCustom, taskAvatar, personaAvatar)

    @Test fun chatCustomNeverOverridesWorkAvatar() {
        assertEquals("persona.png", resolve(work = true, chatCustom = "chat.png"))
        assertEquals("task.png", resolve(work = true, chatCustom = "chat.png", taskAvatar = "task.png"))
        assertEquals("work.png", resolve(work = true, chatCustom = "chat.png", workCustom = "work.png", taskAvatar = "task.png"))
    }

    @Test fun workCustomNeverOverridesChatAvatar() {
        assertEquals("persona.png", resolve(work = false, workCustom = "work.png", taskAvatar = "task.png"))
        assertEquals("chat.png", resolve(work = false, chatCustom = "chat.png", workCustom = "work.png", taskAvatar = "task.png"))
    }

    @Test fun workModeFallsBackToTaskThenPersona() {
        assertEquals("task.png", resolve(work = true, workCustom = "", taskAvatar = "task.png"))
        assertEquals("persona.png", resolve(work = true, workCustom = "", taskAvatar = ""))
        assertEquals("persona.png", resolve(work = true, workCustom = "", taskAvatar = "   "))
        assertEquals("persona.png", resolve(work = true, workCustom = "", taskAvatar = null))
        assertEquals("work.png", resolve(work = true, workCustom = "work.png", taskAvatar = "task.png"))
    }

    @Test fun clearingTheCustomAvatarRestoresTheFallbackChain() {
        assertEquals("chat.png", resolve(work = false, chatCustom = "chat.png"))
        assertEquals("persona.png", resolve(work = false, chatCustom = ""))
        assertEquals("persona.png", resolve(work = false, chatCustom = "", taskAvatar = "task.png"))
        assertEquals("work.png", resolve(work = true, workCustom = "work.png"))
        assertEquals("persona.png", resolve(work = true, workCustom = ""))
    }
}
