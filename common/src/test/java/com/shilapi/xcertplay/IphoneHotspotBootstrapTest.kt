package com.shilapi.xcertplay

import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationException
import com.shilapi.xcertplay.transport.*
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class IphoneHotspotBootstrapTest {
    @Test fun routerBootstrapStillWaitsForThePhoneToRequestStart() {
        val phone = Phone()
        val result = phone.run(false)
        assertEquals(0, result.carPlayStartSessionsSent)
        assertTrue(phone.sent.none { it.messageId == 0x4301 })
    }

    @Test fun iphoneHotspotStartsOnceAfterAuthenticationAndSubscriptions() {
        val phone = Phone()
        var ready = false
        var starts = 0
        val result = phone.run(true, beforeStart = { assertTrue(ready) },
            onReady = { ready = true }, onStart = { starts++ })
        assertEquals(1, starts)
        assertEquals(1, result.carPlayStartSessionsSent)
        assertEquals(0x4301, phone.sent.last().messageId)
        assertEquals(Iap2WirelessControlStage.CARPLAY_START_SENT, result.stage)
        assertEquals(Iap2WirelessControlTerminal.CHANNEL_CLOSED, result.terminal)
    }

    @Test fun proactiveStartKeepsWifiRepliesAndLaterPhoneStartRequests() {
        val phone = Phone(afterAuthentication = listOf(
            Iap2Frame(0x5702, byteArrayOf()), Iap2Frame(0x4300, byteArrayOf()),
        ))
        val result = phone.run(true)
        assertEquals(2, result.carPlayStartSessionsSent)
        assertEquals(1, result.wifiConfigurationsSent)
        assertEquals(listOf(0x4301, 0x5703, 0x4301), phone.sent.takeLast(3).map { it.messageId })
    }

    @Test fun failedAuthenticationAndLostWifiBothPreventTheProactiveStart() {
        val rejected = Phone(authenticated = false)
        assertThrows(Iap2MfiAuthenticationException::class.java) { rejected.run(true) }
        assertTrue(rejected.sent.none { it.messageId == 0x4301 })

        val disconnected = Phone()
        var starts = 0
        assertThrows(IOException::class.java) {
            disconnected.run(true, beforeStart = { throw IOException("Wi-Fi lost") }, onStart = { starts++ })
        }
        assertEquals(0, starts)
        assertTrue(disconnected.sent.none { it.messageId == 0x4301 })
    }

    private class Phone(authenticated: Boolean = true, afterAuthentication: List<Iap2Frame> = emptyList()) {
        val sent = mutableListOf<Iap2Frame>()
        private val session = mock(Iap2Session::class.java)
        private val replies = ArrayDeque(listOf(
            Iap2Frame(0x1d00, byteArrayOf()), Iap2Frame(0x1d02, byteArrayOf()),
            Iap2Frame(if (authenticated) 0xaa05 else 0xaa04, byteArrayOf()),
        ) + afterAuthentication)
        private val authenticator = object : MfiAuthenticator {
            override fun protocolMajor() = 3
            override fun readCertificate(maximumOutputLength: Int) = byteArrayOf(1)
            override fun signChallenge(challenge: ByteArray) = byteArrayOf(1)
        }

        init {
            `when`(session.awaitReady(anyLong())).thenReturn(true)
            `when`(session.recv(anyLong())).thenAnswer { replies.removeFirstOrNull() }
            `when`(session.isClosed).thenAnswer { replies.isEmpty() }
            doAnswer { sent += it.getArgument<Iap2Frame>(0); null }
                .`when`(session).send(any(Iap2Frame::class.java), anyLong())
        }

        fun run(proactive: Boolean, beforeStart: () -> Unit = {}, onReady: () -> Unit = {},
                onStart: () -> Unit = {}): Iap2WirelessControlResult = Iap2WirelessControlClient(
            session, Iap2MfiAuthenticationClient(authenticator),
        ).run(
            identification = Iap2IdentificationConfig("DiPlay", "DiPlay", "DiPlay", "test", "1", "1",
                wireless = Iap2WirelessIdentification("AA:BB:CC:DD:EE:FF", "Phone hotspot")),
            endpoint = Iap2WirelessCarPlayEndpoint("Phone hotspot", "password123", 36,
                Iap2WirelessSecurity.WPA_WPA2, listOf("172.20.10.2"), 7000, "dev-1", "aabbcc", "1.0"),
            onReady = onReady,
            beforeStartSession = beforeStart,
            onStartSessionSent = { onStart() },
            startSessionAfterAuthentication = proactive,
        )
    }
}
