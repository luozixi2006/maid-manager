package com.miniichat.proactive.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class RemotePushAddressTest {
    @Test fun acceptsOnlyPrivateTailscaleOrLocalTransport() {
        assertEquals("http://100.123.123.23:8787", RemotePushAddress.validate(" http://100.123.123.23:8787/ "))
        assertEquals("http://127.0.0.1:8787", RemotePushAddress.validate("http://127.0.0.1:8787"))
        assertEquals("https://pc.example.ts.net", RemotePushAddress.validate("https://pc.example.ts.net"))
    }
    @Test fun rejectsPublicHttpRedirectInputsAndCredentialsInUrls() {
        listOf("http://example.com:8787", "http://100.128.1.1:8787", "http://100.63.1.1:8787",
            "http://100.123.999.1", "http://pc.example.ts.net", "file:///private", "http://user:secret@100.123.123.23",
            "http://100.123.123.23?key=x", "http://100.123.123.23/#x", "http://100.123.123.23/v1",
            "http://100.123.123.23:0", "http://100.123.123.23:65536").forEach {
            try { RemotePushAddress.validate(it); fail("must reject unsafe address") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
