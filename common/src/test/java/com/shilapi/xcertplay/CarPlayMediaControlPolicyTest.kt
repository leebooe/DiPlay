package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayMediaControlPolicyTest {
    @Test fun bluetoothMusicKeepsBothMediaCommandsAndFocusWithTheCar() {
        for (geely in listOf(false, true)) {
            val policy = CarPlayMediaControlPolicy(bluetoothAudio = true, geely = geely)
            assertFalse(policy.manageFocus)
            assertFalse(policy.canForward(false))
            assertFalse(policy.canForward(true))
        }
    }

    @Test fun geelyControlsWaitForAnActualMediaRouteAndShareItsFocusOwner() {
        val policy = CarPlayMediaControlPolicy(geely = true, rendererFocusEnabled = true)
        assertFalse(policy.canForward(false))
        assertTrue(policy.canForward(true))
        assertFalse(policy.manageFocus)
        assertTrue(policy.copy(rendererFocusEnabled = false).manageFocus)
        assertFalse(policy.copy(rendererFocusEnabled = false, mediaKeyFocusDisabled = true).manageFocus)
    }

    @Test fun ordinaryHeadUnitsRetainTheMediaKeyFocusPolicy() {
        val policy = CarPlayMediaControlPolicy(rendererFocusEnabled = true)
        assertTrue(policy.canForward(false))
        assertTrue(policy.manageFocus)
    }

    @Test fun crossSourceDuplicatesAreSuppressedWhileNewPressesRemainAvailable() {
        val dedup = MediaKeyDeduplicator()
        assertTrue(dedup.accept(3, "geely-wheel", 1_000))
        assertFalse(dedup.accept(3, "media-session", 1_010))
        assertFalse(dedup.accept(3, "window", 1_020))
        assertTrue(dedup.accept(3, "geely-wheel", 1_100))
        assertTrue(dedup.accept(3, "window", 1_230))
        assertTrue(dedup.accept(4, "media-session", 1_240))
    }
}
