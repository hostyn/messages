package dev.hostyn.messages.mms

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Builds the parts of an outgoing MMS: a SMIL layout, the images and the text. */
object MmsParts {
    /** Room left for headers, SMIL and text when fitting images into the size limit. */
    private const val OVERHEAD = 8 * 1024

    fun build(context: Context, text: String, attachments: List<Uri>, maxSize: Int): List<PduPart> {
        val media = mutableListOf<PduPart>()
        val budget = ((maxSize - OVERHEAD - text.length * 4) / max(1, attachments.size)).coerceAtLeast(20 * 1024)
        attachments.forEachIndexed { i, uri ->
            attachment(context, uri, i, budget)?.let(media::add)
        }
        val parts = mutableListOf<PduPart>()
        val textPart = text.takeIf { it.isNotBlank() }?.let {
            PduPart(
                contentType = "text/plain", data = it.toByteArray(Charsets.UTF_8), name = "text_0.txt",
                contentId = "text_0", contentLocation = "text_0.txt", charset = CHARSET_UTF8,
            )
        }
        parts += smil(media, textPart)
        parts += media
        textPart?.let(parts::add)
        return parts
    }

    private fun smil(media: List<PduPart>, text: PduPart?): PduPart {
        val body = buildString {
            media.forEach { part ->
                val tag = when {
                    part.contentType.startsWith("image/") -> "img"
                    part.contentType.startsWith("video/") -> "video"
                    part.contentType.startsWith("audio/") -> "audio"
                    else -> "ref"
                }
                append("<par dur=\"5000ms\"><$tag src=\"${part.contentLocation}\" region=\"Image\"/></par>")
            }
            if (text != null) append("<par dur=\"5000ms\"><text src=\"${text.contentLocation}\" region=\"Text\"/></par>")
        }
        val smil = "<smil><head><layout><root-layout/>" +
            "<region id=\"Image\" fit=\"meet\" top=\"0\" left=\"0\" height=\"80%\" width=\"100%\"/>" +
            "<region id=\"Text\" top=\"80%\" left=\"0\" height=\"20%\" width=\"100%\"/>" +
            "</layout></head><body>$body</body></smil>"
        return PduPart("application/smil", smil.toByteArray(), name = "smil.xml", contentId = "smil", contentLocation = "smil.xml")
    }

    private fun attachment(context: Context, uri: Uri, index: Int, budget: Int): PduPart? {
        val type = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val size = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: Long.MAX_VALUE
        // Small images (and animated GIFs, which can't be re-encoded) go as they are.
        if (type.startsWith("image/") && (size <= budget && type in listOf("image/jpeg", "image/png", "image/gif"))) {
            val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            val ext = type.substringAfter('/').replace("jpeg", "jpg")
            return part(type, data, "image_$index.$ext")
        }
        if (type.startsWith("image/")) {
            val data = compressImage(context, uri, budget) ?: return null
            return part("image/jpeg", data, "image_$index.jpg")
        }
        if (size > budget) return null
        val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "file_$index"
        return part(type, data, name)
    }

    private fun part(type: String, data: ByteArray, name: String) =
        PduPart(type, data, name = name, contentId = name.substringBeforeLast('.'), contentLocation = name)

    /** Re-encodes an image as JPEG, shrinking it until it fits in [maxBytes]. */
    fun compressImage(context: Context, uri: Uri, maxBytes: Int): ByteArray? {
        var bitmap = decode(context, uri, 1600) ?: return null
        var quality = 85
        while (true) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= maxBytes || bitmap.width < 200) return out.toByteArray()
            if (quality > 55) {
                quality -= 10
            } else {
                bitmap = Bitmap.createScaledBitmap(bitmap, (bitmap.width * 0.75f).roundToInt(), (bitmap.height * 0.75f).roundToInt(), true)
                quality = 80
            }
        }
    }

    private fun decode(context: Context, uri: Uri, maxDimension: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder also applies the EXIF rotation.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val longest = max(info.size.width, info.size.height)
                if (longest > maxDimension) {
                    val scale = maxDimension.toFloat() / longest
                    decoder.setTargetSize((info.size.width * scale).roundToInt(), (info.size.height * scale).roundToInt())
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDimension) sample *= 2
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    } catch (_: Exception) {
        null
    }
}
