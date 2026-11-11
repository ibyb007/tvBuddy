package com.example

import com.example.data.network.WireGuardConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireGuardConfigTest {

    private val sample = """
        [Interface]
        PrivateKey = aaaa
        Address = 10.0.0.2/32
        DNS = 1.1.1.1
        PostUp = iptables -A FORWARD -i %i -j ACCEPT
        ExcludedApplications = com.other.app

        [Peer]
        PublicKey = bbbb
        AllowedIPs = 0.0.0.0/0
        Endpoint = vpn.example.com:51820 # server
    """.trimIndent()

    @Test
    fun validConfigPasses() {
        assertNull(WireGuardConfig.precheck(sample))
        assertEquals("vpn.example.com:51820", WireGuardConfig.endpoint(sample))
        assertEquals("vpn.example.com", WireGuardConfig.endpointHost("vpn.example.com:51820"))
        assertEquals("2001:db8::1", WireGuardConfig.endpointHost("[2001:db8::1]:51820"))
    }

    @Test
    fun brokenConfigsAreRejected() {
        assertNotNull(WireGuardConfig.precheck(""))
        assertNotNull(WireGuardConfig.precheck("[Interface]\nAddress = 10.0.0.2/32"))
        assertNotNull(WireGuardConfig.precheck("[Interface]\nPrivateKey = a\n"))
        assertNotNull(WireGuardConfig.precheck("[Interface]\nPrivateKey = a\n[Peer]\nPublicKey = b\n"))
    }

    @Test
    fun forAppRestrictsTunnelToThisPackage() {
        val out = WireGuardConfig.forApp(sample, "com.tvbuddy.canary")
        assertTrue(out.contains("IncludedApplications = com.tvbuddy.canary"))
        assertFalse(out.contains("ExcludedApplications"))
        assertFalse(out.contains("PostUp"))
        assertTrue(out.contains("PrivateKey = aaaa"))
        assertTrue(out.contains("Endpoint = vpn.example.com:51820"))
        // the line goes right under [Interface], not under [Peer]
        assertTrue(out.indexOf("IncludedApplications") < out.indexOf("[Peer]"))
    }
}
