package com.mootmaker.app.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

/** A picked image, re-encoded as a JPEG small enough for the API. */
class PreparedAvatar(val bytes: ByteArray, val contentType: String = "image/jpeg")

/** The API refuses uploads over 2 MiB, and a phone photo is often several times that. */
private const val MAX_BYTES = 2 * 1024 * 1024

/** Avatars are shown at 256 pixels, so there is nothing to gain from sending more than this. */
private const val MAX_SIDE = 1024

/**
 * Reads the picked image and returns it as a JPEG no larger than [MAX_SIDE] pixels a side and
 * [MAX_BYTES] long, whatever format it was in (the Photo Picker also offers WebP and HEIC, which the
 * API doesn't accept). Null if it can't be read as an image. Run off the main thread.
 */
fun prepareAvatar(context: Context, uri: Uri): PreparedAvatar? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
    val decoded = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null

    var quality = 90
    while (true) {
        val out = ByteArrayOutputStream()
        decoded.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (out.size() <= MAX_BYTES || quality <= 30) return PreparedAvatar(out.toByteArray()).takeIf { out.size() <= MAX_BYTES }
        quality -= 20
    }
}
