package com.healthvectors.fitness.data.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.util.LruCache
import com.healthvectors.fitness.data.local.DbKeyProvider
import com.healthvectors.fitness.domain.logic.PhotoLogic
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Progress photo files. Every photo is re-encoded on the way in (upright, at most [PhotoLogic.FULL_EDGE] px,
 * which also drops all EXIF metadata such as GPS location) and written to app-private storage encrypted with
 * AES-GCM under a Keystore-protected key. Nothing is ever written to the shared gallery.
 *
 * Each photo is two files: `<name>.img` (full size) and `<name>.thumb` (grid thumbnail).
 */
@Singleton
class PhotoStore @Inject constructor(@ApplicationContext private val context: Context, private val keys: DbKeyProvider) {
    private val dir = File(context.filesDir, "photos").apply { mkdirs() }
    private val key by lazy { SecretKeySpec(keys.photoKey(), "AES") }
    private val random = SecureRandom()
    private val thumbs = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(k: String, v: Bitmap) = v.byteCount
    }

    /** A processed photo held in memory until the user saves or discards it. */
    class Pending(val full: ByteArray, val thumb: ByteArray, val width: Int, val height: Int, val takenOn: LocalDate?, val preview: Bitmap)

    /** Reads, rotates, shrinks and re-encodes an image the user picked or shot. Null if it is not a readable image. */
    suspend fun process(uri: Uri): Pending? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val (orientation, takenOn) = runCatching {
            resolver.openInputStream(uri)?.use { s ->
                val exif = ExifInterface(s)
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) to exifDate(exif)
            }
        }.getOrNull() ?: (ExifInterface.ORIENTATION_NORMAL to null)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // A bounds-only decode always returns null; the size lands in [bounds].
        val opened = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds); true } ?: false
        if (!opened || bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        val opts = BitmapFactory.Options().apply { inSampleSize = PhotoLogic.sampleSize(bounds.outWidth, bounds.outHeight, PhotoLogic.FULL_EDGE) }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return@withContext null
        val upright = rotate(decoded, orientation)
        val full = scale(upright, PhotoLogic.FULL_EDGE)
        val thumb = scale(full, PhotoLogic.THUMB_EDGE)
        Pending(jpeg(full, 85), jpeg(thumb, 80), full.width, full.height, takenOn, full).also { if (thumb !== full) thumb.recycle() }
    }

    /** Encrypts and stores a processed photo; returns its file name. */
    suspend fun save(p: Pending): String = withContext(Dispatchers.IO) {
        val name = UUID.randomUUID().toString()
        File(dir, "$name.img").writeBytes(encrypt(p.full))
        File(dir, "$name.thumb").writeBytes(encrypt(p.thumb))
        name
    }

    /** Stores plain JPEG bytes (from an import), making the thumbnail here. */
    suspend fun saveJpeg(bytes: ByteArray): Triple<String, Int, Int>? = withContext(Dispatchers.IO) {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
        val full = scale(bmp, PhotoLogic.FULL_EDGE)
        val p = Pending(jpeg(full, 85), jpeg(scale(full, PhotoLogic.THUMB_EDGE), 80), full.width, full.height, null, full)
        Triple(save(p), full.width, full.height)
    }

    /** Decrypted image, from the thumbnail cache when [thumb]. Null if the file is missing or unreadable. */
    suspend fun load(name: String, thumb: Boolean): Bitmap? {
        if (thumb) thumbs.get(name)?.let { return it }
        return withContext(Dispatchers.IO) {
            val bytes = decryptedBytes(name, thumb) ?: return@withContext null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { if (thumb) thumbs.put(name, it) }
        }
    }

    /** The stored JPEG, decrypted (for export and sharing). */
    suspend fun jpegBytes(name: String): ByteArray? = withContext(Dispatchers.IO) { decryptedBytes(name, false) }

    suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        thumbs.remove(name)
        File(dir, "$name.img").delete(); File(dir, "$name.thumb").delete()
    }

    private fun decryptedBytes(name: String, thumb: Boolean): ByteArray? {
        val f = File(dir, if (thumb) "$name.thumb" else "$name.img")
        if (!f.exists()) return null
        return runCatching { decrypt(f.readBytes()) }.getOrNull()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return iv + c.doFinal(plain)
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12)) }
        return c.doFinal(blob, 12, blob.size - 12)
    }

    private fun exifDate(exif: ExifInterface): LocalDate? {
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME) ?: return null
        return runCatching { LocalDateTime.parse(raw.trim(), DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")).toLocalDate() }.getOrNull()
    }

    private fun rotate(b: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return b
        }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true).also { if (it !== b) b.recycle() }
    }

    private fun scale(b: Bitmap, edge: Int): Bitmap {
        val (w, h) = PhotoLogic.fit(b.width, b.height, edge)
        return if (w == b.width && h == b.height) b else Bitmap.createScaledBitmap(b, w, h, true)
    }

    private fun jpeg(b: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
}
