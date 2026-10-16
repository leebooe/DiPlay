package com.shilapi.xcertplay

/**
 * The Geely/Lynk steering-wheel key codes and which of them to subscribe to.
 *
 * No Android types, so the double-fire rule is unit-tested without a device or Robolectric.
 */
internal object GeelyWheelKeyCodes {
    // KeyCode.KEYCODE_R_MEDIA_* in the head unit's com.geely.lib.oneosapi.input.
    const val KEY_PLAY_PAUSE = 200085
    const val KEY_NEXT = 200087
    const val KEY_PREVIOUS = 200088
    // KEYCODE_R_VOICE_ASSIST: the steering-wheel microphone key (long press used to open HiCar).
    const val KEY_VOICE_ASSIST = 200231

    val ALL = intArrayOf(KEY_PLAY_PAUSE, KEY_NEXT, KEY_PREVIOUS, KEY_VOICE_ASSIST)
    val SIRI_ONLY = intArrayOf(KEY_VOICE_ASSIST)

    /**
     * The key codes to subscribe to on the head unit's input service.
     *
     * With CarPlay audio on the car's Bluetooth, the head unit's own AVRCP already drives the
     * iPhone's music, so registering the media keys here too would double-fire play/pause and skip.
     * Only the microphone key is registered then (long press -> Siri); otherwise all of them are.
     */
    fun registeredCodes(bluetoothAudio: Boolean): IntArray =
        if (bluetoothAudio) SIRI_ONLY else ALL

    /** Android aliases also appear inside OneOS callbacks on some FX11 firmware. */
    fun canonicalize(code: Int): Int? = when (code) {
        KEY_PLAY_PAUSE, 85 -> KEY_PLAY_PAUSE
        KEY_NEXT, 87, 110005, 210005 -> KEY_NEXT
        KEY_PREVIOUS, 88, 110006, 210006 -> KEY_PREVIOUS
        KEY_VOICE_ASSIST, 231 -> KEY_VOICE_ASSIST
        else -> null
    }

    /** OneOS uses the stock subscription table; the decoder retains only media keys and long-press Siri. */
    fun oneOsListenerCodes(bluetoothAudio: Boolean): IntArray =
        if (bluetoothAudio) intArrayOf(KEY_VOICE_ASSIST, 231)
        else ((0..283).toList() + listOf(
            110001, 110002, 110003, 110004, 110005, 110006, 110007, 110008, 110009, 110010,
            200003, 200004, 200005, 200006, 200024, 200025, 200082, KEY_PLAY_PAUSE, KEY_NEXT, KEY_PREVIOUS,
            200110, 200164, 200176, KEY_VOICE_ASSIST, 200400, 200600,
            210001, 210002, 210003, 210004, 210005, 210006, 210007, 210008, 210009, 210010,
            210020, 300001, 300002, 300003, 300005, 300010, 300020, 300021, 300022, 300023, 300030, 300031,
        )).distinct().toIntArray()
}
