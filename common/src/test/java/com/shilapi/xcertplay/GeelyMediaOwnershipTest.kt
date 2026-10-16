package com.shilapi.xcertplay

import android.media.session.MediaSession
import android.os.Build
import android.os.Looper
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class GeelyMediaOwnershipTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val model = Build.MODEL
    private val controllers = mutableListOf<CarPlayController>()

    @Before fun setUp() {
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "FX11_LOW")
        AirPlayPersistence.saveDisableMediaKeyAudioFocus(app, false)
    }

    @After fun cleanup() {
        controllers.forEach(CarPlayMediaKeys::detach)
        shadowOf(Looper.getMainLooper()).idle()
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", model)
        CarPlayBackgroundSession.clear()
    }

    @Test fun bluetoothMusicKeepsCommandsAndFocusWithTheCarWhileSiriRemainsAvailable() {
        val controller = attach(bluetooth = true, rendererFocus = true)
        media(true)
        assertNull(session())
        assertNull(ReflectionHelpers.getField<Any?>(CarPlayMediaKeys, "focusRequest"))
        assertFalse(CarPlayMediaKeys.ownsHardwareMediaKeys(controller))
        assertFalse(CarPlayMediaKeys.onHardwareMediaKey(controller, CarPlayMediaButton.PLAY_PAUSE, "window"))
        CarPlayMediaKeys.onExternalMediaKey(CarPlayMediaButton.NEXT)
        verify(controller, never()).sendMediaButton(anyInt())
        `when`(controller.requestSiri()).thenReturn(true)
        assertTrue(CarPlayMediaKeys.onExternalSiriKey(controller))
    }

    @Test fun musicMustEstablishItsRouteAndKeepsControlsThroughPause() {
        val controller = attach(rendererFocus = true)
        assertFalse(CarPlayMediaKeys.onExternalMediaKey(CarPlayMediaButton.PLAY_PAUSE))
        media(true)
        val activeSession = requireNotNull(session())
        assertNull(ReflectionHelpers.getField<Any?>(CarPlayMediaKeys, "focusRequest"))
        assertTrue(CarPlayMediaKeys.onExternalMediaKey(CarPlayMediaButton.NEXT))
        media(false)
        assertSame(activeSession, session())
        assertTrue(CarPlayMediaKeys.ownsHardwareMediaKeys(controller))
        assertTrue(CarPlayMediaKeys.onExternalMediaKey(CarPlayMediaButton.PLAY_PAUSE))
        verify(controller).sendMediaButton(CarPlayMediaButton.PLAY_PAUSE)
    }

    @Test fun theSamePressFromOneOsAndTheWindowSendsOnlyOneCommand() {
        val controller = attach(rendererFocus = true)
        media(true)
        assertTrue(CarPlayMediaKeys.onExternalMediaKey(CarPlayMediaButton.NEXT))
        assertFalse(CarPlayMediaKeys.onHardwareMediaKey(controller, CarPlayMediaButton.NEXT, "window"))
        verify(controller, times(1)).sendMediaButton(CarPlayMediaButton.NEXT)
    }

    @Test fun oldQueuedMediaAndHardwareEventsCannotAuthorizeTheReplacementSession() {
        val first = attach(rendererFocus = true)
        CarPlayMediaKeys.onMediaAudioChanged(true)
        val second = attach(rendererFocus = true)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(session())
        assertFalse(CarPlayMediaKeys.ownsHardwareMediaKeys(second))
        assertFalse(CarPlayMediaKeys.onHardwareMediaKey(first, CarPlayMediaButton.NEXT, "window"))
        verify(second, never()).sendMediaButton(anyInt())
    }

    private fun attach(bluetooth: Boolean = false, rendererFocus: Boolean = false): CarPlayController {
        val controller = mock(CarPlayController::class.java)
        `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        `when`(controller.sendMediaButton(anyInt())).thenReturn(true)
        controllers += controller
        CarPlayMediaKeys.attach(app, controller, bluetooth, rendererFocus)
        return controller
    }

    private fun media(active: Boolean) {
        CarPlayMediaKeys.onMediaAudioChanged(active)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun session(): MediaSession? = ReflectionHelpers.getField(CarPlayMediaKeys, "session")
}
