package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.airplay.AirPlayIcon
import com.shilapi.xcertplay.host.R
import java.io.ByteArrayOutputStream

/** The settings preview and the CarPlay handshake use the same icon selection. */
internal object CarButtonIcons {
    data class Selection(val icons: List<AirPlayIcon>, val statusRes: Int, val custom: Boolean = false)

    fun load(context: Context, report: (String) -> Unit = {}): Selection {
        val customBytes = runCatching {
            AirPlayPersistence.loadCustomAirPlayIconFile(context)?.readBytes()
        }.getOrNull()
        if (customBytes != null) {
            decode(customBytes, report)?.let {
                return Selection(listOf(it), R.string.car_button_icon_custom, custom = true)
            }
            AirPlayPersistence.clearCustomAirPlayIcon(context)
        }

        val factory = GeelyFactoryCarPlay.load(context)
        val factoryIcons = factory?.icons.orEmpty().mapNotNull { decode(it.data, report) }
        if (factoryIcons.isNotEmpty()) return Selection(factoryIcons, R.string.settings_car_button_factory_icon)

        val bytes = if (factory != null) homeIcon(context) else {
            context.resources.openRawResource(R.raw.ic_car_home).use { it.readBytes() }
        }
        val icon = decode(bytes, report) ?: error("Packaged AirPlay icon is invalid")
        return Selection(listOf(icon), R.string.default_icon)
    }

    private fun decode(encoded: ByteArray, report: (String) -> Unit): AirPlayIcon? {
        val (size, bytes) = AirPlayIconSizing.fit(encoded) ?: return null
        if (bytes !== encoded) report("CarPlay car-button icon resized bytes=${encoded.size}->${bytes.size} pixels=$size")
        return AirPlayIcon(size, size, bytes)
    }

    private fun homeIcon(context: Context): ByteArray {
        val size = AirPlayIconSizing.MAX_PIXELS
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        return try {
            requireNotNull(ContextCompat.getDrawable(context, R.drawable.ic_car_home_fallback)).apply {
                setBounds(0, 0, size, size)
                draw(Canvas(bitmap))
            }
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
