package com.shilapi.xcertplay

import com.shilapi.xcertplay.GeelyWheelEventDecoder.Operation
import org.junit.Assert.*
import org.junit.Test

class GeelyWheelEventDecoderTest {
    @Test fun rawAliasesAndShortClickSendOneOperation() {
        val decoder = GeelyWheelEventDecoder()
        assertEquals(Operation.NEXT, decoder.accept(1, 87, 0, 1_000))
        assertNull(decoder.accept(1, 200087, 0, 1_010))
        assertNull(decoder.accept(1, 87, 1, 1_050))
        assertNull(decoder.accept(2, 200087, -1, 1_070))
        assertEquals(Operation.NEXT, decoder.accept(1, 87, 0, 1_100))
    }

    @Test fun gestureOnlyFirmwareSupportsRepeatedPressesAndSeekAliases() {
        val decoder = GeelyWheelEventDecoder()
        assertEquals(Operation.PLAY_PAUSE, decoder.accept(2, 85, -1, 1_000))
        assertNull(decoder.accept(2, 200085, -1, 1_020))
        assertEquals(Operation.PLAY_PAUSE, decoder.accept(2, 85, -1, 1_200))
        assertEquals(Operation.NEXT, decoder.accept(2, 110005, -1, 1_300))
        assertEquals(Operation.PREVIOUS, decoder.accept(2, 210006, -1, 1_400))
        assertNull(decoder.accept(2, 24, -1, 1_500)) // Volume belongs to the car.
    }

    @Test fun gestureBeforeRawCallbackAlsoSendsOneOperation() {
        val decoder = GeelyWheelEventDecoder()
        assertEquals(Operation.PREVIOUS, decoder.accept(2, 88, -1, 100))
        assertNull(decoder.accept(1, 200088, 0, 110))
        assertNull(decoder.accept(1, 200088, 1, 120))
    }

    @Test fun siriRequiresLongPressAndPreservesLongGestureAfterRawEvents() {
        val decoder = GeelyWheelEventDecoder()
        assertNull(decoder.accept(1, 231, 0, 1_000))
        assertNull(decoder.accept(1, 231, 1, 1_100))
        assertNull(decoder.accept(2, 200231, -1, 1_110))
        assertEquals(Operation.SIRI, decoder.accept(5, 200231, -1, 1_900))
        assertNull(decoder.accept(5, 231, -1, 1_910))
        assertNull(decoder.accept(1, 231, 0, 2_000))
        assertEquals(Operation.SIRI, decoder.accept(5, 231, -1, 2_800))
    }

    @Test fun musicLongAndDoubleGesturesLeaveTheOriginalPressUnchanged() {
        val decoder = GeelyWheelEventDecoder()
        assertEquals(Operation.PLAY_PAUSE, decoder.accept(1, 85, 0, 100))
        assertNull(decoder.accept(5, 85, -1, 900))
        assertNull(decoder.accept(6, 85, -1, 950))
    }
}
