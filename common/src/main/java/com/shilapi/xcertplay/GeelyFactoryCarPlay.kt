// Artwork and label lookup adapted from CoderLin99/DiPlay, commit 138baaf8a71ce57433538801162c599e9061b745.
package com.shilapi.xcertplay

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import com.shilapi.xcertplay.airplay.AirPlayIcon
import org.json.JSONObject
import java.io.File

/** Public car-button artwork and label from the installed Geely CarPlay configuration. */
internal class GeelyFactoryCarPlay private constructor(private val config: JSONObject?) {
    val iconLabel: String = config?.optJSONObject("OemIconInfo")
        ?.optString("OemIconLabel")?.takeIf { it.isNotBlank() } ?: "Geely"

    val icons: List<AirPlayIcon> by lazy {
        listOf(104, 120, 180, 256).mapNotNull { size ->
            val key = "icon_${size}x$size"
            val configured = config?.optJSONObject("OemIconInfo")?.optString(key)
            val file = File(configured?.takeIf { it.isNotBlank() } ?: "$DIRECTORY/$key.png")
            runCatching {
                // Artwork is confined to the factory directory, including symlink resolution.
                if (file.canonicalFile.parentFile != File(DIRECTORY).canonicalFile) return@runCatching null
                val bytes = readBounded(file, MAX_ICON_BYTES) ?: return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outMimeType != "image/png" || bounds.outWidth != size || bounds.outHeight != size) {
                    return@runCatching null
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
                bitmap.recycle()
                AirPlayIcon(size, size, bytes)
            }.getOrNull()
        }
    }

    companion object {
        private const val DIRECTORY = "/vendor/etc/carplay"
        private const val MAX_ICON_BYTES = 512 * 1024
        private val modelCode = Regex("(?i)(?:^|[^a-z0-9])(?:G636|FX11|KX11|E245|Geely)(?:$|[^a-z0-9])")
        private val factory by lazy {
            val config = runCatching {
                readBounded(File("$DIRECTORY/carplay_config.json"), 64 * 1024)?.let { bytes ->
                    val raw = JSONObject(bytes.toString(Charsets.UTF_8))
                    JSONObject().apply {
                        // Retain only public branding sections from the factory configuration.
                        listOf("ManufacturerInfo", "OemIconInfo").forEach { name ->
                            raw.optJSONObject(name)?.let { put(name, it) }
                        }
                    }
                }
            }.getOrNull()
            GeelyFactoryCarPlay(config)
        }

        fun load(context: Context): GeelyFactoryCarPlay? {
            val identity = "${Build.MANUFACTURER} ${Build.BRAND} ${Build.MODEL} ${Build.PRODUCT} ${Build.DEVICE}"
            val modelMatches = modelCode.containsMatchIn(identity)
            val configMatches = factory.config?.optJSONObject("ManufacturerInfo")
                ?.optString("Manufacturer")?.equals("Geely", true) == true
            val receiverInstalled = installed(context, "com.autolink.carplay")
            val factoryServiceInstalled = listOf("com.autolink.carplay.app", "com.geely.service.oneosapi")
                .any { installed(context, it) }
            return factory.takeIf { modelMatches || configMatches || (receiverInstalled && factoryServiceInstalled) }
        }

        private fun installed(context: Context, packageName: String): Boolean = runCatching {
            context.packageManager.getApplicationInfo(packageName, 0)
        }.isSuccess

        private fun readBounded(file: File, limit: Int): ByteArray? {
            if (!file.isFile || file.length() !in 1..limit.toLong()) return null
            return file.inputStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= limit) {
                    val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
                    if (count < 0) return@use output.toByteArray()
                    output.write(buffer, 0, count)
                }
                null
            }
        }
    }
}
