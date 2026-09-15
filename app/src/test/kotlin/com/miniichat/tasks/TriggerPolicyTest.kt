package com.miniichat.tasks

import org.junit.Assert.*
import org.junit.Test

class TriggerPolicyTest {
    @Test fun ordinaryContactDefaultsToNoticeNotFileTask() {
        val decision = taskJson.decodeFromString<EventDecision>("{\"contact\":true,\"reason\":\"该休息一下了\"}")
        assertTrue(decision.contact)
        assertFalse(decision.offerFileTask)
    }
    @Test fun initialSnapshotNeverSpams() {
        assertFalse(TriggerPolicy.changed(TriggerKind.FILES, "", "school.pdf:100:200"))
        assertFalse(TriggerPolicy.changed(TriggerKind.CHARGING, "", "on"))
    }
    @Test fun newFilesNotFileRemovalTrigger() {
        assertTrue(TriggerPolicy.changed(TriggerKind.FILES, "ready:a.pdf", "a.pdf\nb.pdf"))
        assertFalse(TriggerPolicy.changed(TriggerKind.FILES, "ready:a.pdf\nb.pdf", "a.pdf"))
        assertFalse(TriggerPolicy.changed(TriggerKind.FILES, "ready:a.pdf", "a.pdf"))
    }
    @Test fun wifiChargingAndAppOnlyOnRisingEdge() {
        for (kind in listOf(TriggerKind.WIFI, TriggerKind.CHARGING, TriggerKind.APP)) {
            assertTrue(TriggerPolicy.changed(kind, "ready:off", "on"))
            assertFalse(TriggerPolicy.changed(kind, "ready:on", "off"))
            assertFalse(TriggerPolicy.changed(kind, "ready:on", "on"))
        }
    }
    @Test fun dailyTimeDoesNotRepeatOrFireBeforeTime() {
        assertTrue(TriggerPolicy.changed(TriggerKind.TIME, "ready:before", "2026-09-15"))
        assertFalse(TriggerPolicy.changed(TriggerKind.TIME, "ready:2026-09-15", "2026-09-15"))
        assertFalse(TriggerPolicy.changed(TriggerKind.TIME, "ready:2026-09-15", "before"))
    }
    @Test fun cooldownIncludesClockRollback() {
        assertFalse(TriggerPolicy.cooledDown(10000, 9000))
        assertFalse(TriggerPolicy.cooledDown(10000, 20000))
        assertTrue(TriggerPolicy.cooledDown(10000, 3610000))
    }
}
