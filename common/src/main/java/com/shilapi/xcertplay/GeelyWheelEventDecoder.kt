package com.shilapi.xcertplay

/**
 * OneOS key aliases and raw/gesture callbacks can describe the same physical press.
 * Adapted from CoderLin99/DiPlay's GeelySteeringEventDecoder; see docs/BOYUE-L-CONTROLS.md.
 */
internal class GeelyWheelEventDecoder {
    enum class Operation { PLAY_PAUSE, NEXT, PREVIOUS, SIRI }

    private val down = mutableSetOf<Int>()
    private val releasedAt = mutableMapOf<Int, Long>()
    private val clickedAt = mutableMapOf<Int, Long>()
    private var voiceLongAt: Long? = null

    fun accept(transaction: Int, rawCode: Int, action: Int, now: Long): Operation? {
        val code = GeelyWheelKeyCodes.canonicalize(rawCode) ?: return null
        if (transaction == RAW_KEY) {
            when (action) {
                DOWN -> {
                    if (!down.add(code)) return null
                    if (code == GeelyWheelKeyCodes.KEY_VOICE_ASSIST) voiceLongAt = null
                    if (clickedAt[code]?.let { now - it in 0 until DUPLICATE_MILLIS } == true) return null
                    return mediaOperation(code)
                }
                UP -> {
                    down.remove(code)
                    releasedAt[code] = now
                }
            }
        } else if (transaction == SHORT_CLICK) {
            if (code in down || releasedAt[code]?.let { now - it in 0 until GESTURE_MILLIS } == true ||
                clickedAt[code]?.let { now - it in 0 until DUPLICATE_MILLIS } == true) return null
            clickedAt[code] = now
            return mediaOperation(code)
        } else if (transaction == LONG_PRESS && code == GeelyWheelKeyCodes.KEY_VOICE_ASSIST) {
            if (voiceLongAt?.let { now - it in 0 until GESTURE_MILLIS } == true) return null
            voiceLongAt = now
            return Operation.SIRI
        }
        return null
    }

    private fun mediaOperation(code: Int): Operation? = when (code) {
        GeelyWheelKeyCodes.KEY_PLAY_PAUSE -> Operation.PLAY_PAUSE
        GeelyWheelKeyCodes.KEY_NEXT -> Operation.NEXT
        GeelyWheelKeyCodes.KEY_PREVIOUS -> Operation.PREVIOUS
        else -> null
    }

    companion object {
        const val RAW_KEY = 1
        const val SHORT_CLICK = 2
        const val LONG_PRESS = 5
        const val DOWN = 0
        const val UP = 1
        private const val DUPLICATE_MILLIS = 120L
        private const val GESTURE_MILLIS = 1_500L
    }
}
