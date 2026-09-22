package com.miniichat.chatdata

import com.miniichat.memory.LongTermMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffMemorySelectionTest {
    private fun memory(
        id: String,
        personaId: String = "p1",
        enabled: Boolean = true,
        status: String = "active"
    ) = LongTermMemory(
        id = id,
        content = "fact-$id",
        category = "cat",
        enabled = enabled,
        personaId = personaId,
        status = status
    )

    @Test
    fun selectsOnlyActiveEnabledMemoriesOfTheConversationPersona() {
        val selected = selectHandoffMemories(
            listOf(
                memory("keep"),
                memory("other-persona", personaId = "p2"),
                memory("unassigned", personaId = ""),
                memory("disabled", enabled = false),
                memory("not-active", status = "archived")
            ),
            assistantId = "p1"
        )
        assertEquals(listOf("keep"), selected.map { it.id })
    }

    @Test
    fun blankAssistantIdNeverYieldsMemories() {
        val selected = selectHandoffMemories(
            listOf(memory("keep"), memory("unassigned", personaId = "")),
            assistantId = ""
        )
        assertTrue(selected.isEmpty())
    }
}
