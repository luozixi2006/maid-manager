package com.miniichat.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class LegacyChatMemoryTest {
    @Test fun unassignedOldChatRemainsUsableWithoutBorrowingAnyPersonaMemory() = runBlocking {
        val repository=MemoryRepository(RuntimeEnvironment.getApplication())
        try { assertTrue(repository.relevant("", "以前的聊天仍然能继续").isEmpty()) }
        finally { repository.close() }
    }
}
