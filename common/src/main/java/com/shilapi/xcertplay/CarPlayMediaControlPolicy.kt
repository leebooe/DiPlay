package com.shilapi.xcertplay

/** Captured once per connection so saved settings take effect together at the next connection. */
internal data class CarPlayMediaControlPolicy(
    val bluetoothAudio: Boolean = false,
    val geely: Boolean = false,
    val rendererFocusEnabled: Boolean = false,
    val mediaKeyFocusDisabled: Boolean = false,
) {
    val manageFocus: Boolean
        get() = !bluetoothAudio && !mediaKeyFocusDisabled && !(geely && rendererFocusEnabled)

    fun canForward(mediaAudioEstablished: Boolean): Boolean =
        !bluetoothAudio && (!geely || mediaAudioEstablished)
}

/** Coalesces the same Geely press delivered through OneOS and Android's media-key path. */
internal class MediaKeyDeduplicator {
    private var lastButton: Int? = null
    private var lastSource: String? = null
    private var lastAt = 0L

    fun accept(button: Int, source: String, now: Long): Boolean {
        if (button == lastButton && source != lastSource && now - lastAt in 0 until 120L) return false
        lastButton = button
        lastSource = source
        lastAt = now
        return true
    }
}
