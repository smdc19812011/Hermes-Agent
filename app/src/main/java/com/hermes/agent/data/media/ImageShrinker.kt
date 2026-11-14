package com.hermes.agent.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File

/** Fits images within 1568 px (what vision models use anyway) so uploads stay small; small files pass through untouched. */
object ImageShrinker {
    private const val MAX_SIDE = 1568

    fun shrink(file: File, mime: String): Pair<ByteArray, String> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        val passthrough = mime in setOf("image/jpeg", "image/png", "image/webp", "image/gif")
        if (longest in 1..MAX_SIDE && file.length() < 1_500_000 && passthrough) return file.readBytes() to mime
        if (longest <= 0) return file.readBytes() to mime // undecodable here; let the model try

        var sample = 1
        while (longest / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return file.readBytes() to mime
        val scale = MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
        val sized = if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true) else decoded
        val out = ByteArrayOutputStream()
        val png = sized.hasAlpha()
        sized.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray() to if (png) "image/png" else "image/jpeg"
    }
}
