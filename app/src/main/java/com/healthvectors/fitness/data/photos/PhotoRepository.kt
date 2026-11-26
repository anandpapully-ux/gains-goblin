package com.healthvectors.fitness.data.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import com.healthvectors.fitness.data.local.dao.PhotoDao
import com.healthvectors.fitness.data.local.dao.ProfileDao
import com.healthvectors.fitness.data.local.entity.ProgressPhotoEntity
import com.healthvectors.fitness.domain.logic.PhotoLogic
import com.healthvectors.fitness.domain.model.PhotoPose
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhotoRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: PhotoDao,
    private val profiles: ProfileDao,
    val store: PhotoStore
) {
    /** Newest first. */
    val photos = dao.observeAll()

    suspend fun get(id: Long) = dao.get(id)

    suspend fun add(p: PhotoStore.Pending, takenOn: LocalDate, pose: PhotoPose, note: String): Long {
        val name = store.save(p)
        return dao.insert(
            ProgressPhotoEntity(
                takenOn = takenOn, pose = pose, fileName = name, width = p.width, height = p.height,
                weightKg = profiles.weightNear(takenOn)?.weightKg, note = note.trim(), createdAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun update(p: ProgressPhotoEntity, takenOn: LocalDate, pose: PhotoPose, note: String) {
        val weight = if (takenOn != p.takenOn) profiles.weightNear(takenOn)?.weightKg else p.weightKg
        dao.update(p.copy(takenOn = takenOn, pose = pose, note = note.trim(), weightKg = weight))
    }

    suspend fun delete(p: ProgressPhotoEntity) {
        dao.delete(p.id)
        store.delete(p.fileName)
    }

    /** Writes every photo as a plain JPEG plus a manifest. Returns how many were written. */
    suspend fun exportZip(uri: Uri): Int = withContext(Dispatchers.IO) {
        val all = dao.all()
        val entries = ArrayList<PhotoLogic.ManifestEntry>()
        val counts = HashMap<String, Int>()
        context.contentResolver.openOutputStream(uri)?.use { out ->
            ZipOutputStream(out.buffered()).use { zip ->
                for (p in all) {
                    val bytes = store.jpegBytes(p.fileName) ?: continue
                    val base = "${p.takenOn}_${p.pose}"
                    val n = (counts[base] ?: 0) + 1; counts[base] = n
                    val name = PhotoLogic.exportName(p.takenOn, p.pose, n)
                    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                    entries += PhotoLogic.ManifestEntry(name, p.takenOn, p.pose, p.weightKg, p.note)
                }
                zip.putNextEntry(ZipEntry(PhotoLogic.MANIFEST)); zip.write(PhotoLogic.manifestJson(entries).toByteArray()); zip.closeEntry()
            }
        } ?: error("Could not write the file")
        entries.size
    }

    /** Imports a ZIP written by [exportZip]. Photos already present (same date, pose and size) are skipped. */
    suspend fun importZip(uri: Uri): Int = withContext(Dispatchers.IO) {
        val files = HashMap<String, ByteArray>()
        var manifest: String? = null
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var e = zip.nextEntry
                var total = 0L
                while (e != null) {
                    if (!e.isDirectory) {
                        val bytes = zip.readBytes()
                        total += bytes.size
                        require(total < 512L * 1024 * 1024) { "This file is too large" }
                        if (e.name == PhotoLogic.MANIFEST) manifest = String(bytes) else files[File(e.name).name] = bytes
                    }
                    e = zip.nextEntry
                }
            }
        } ?: error("Could not read the file")
        val entries = PhotoLogic.parseManifest(manifest ?: error("No photos.json in this file; is it a Gains Goblin photo export?"))
        val existing = dao.all().map { Triple(it.takenOn, it.pose, it.width) }.toMutableSet()
        var added = 0
        for (m in entries) {
            val bytes = files[m.file] ?: continue
            val (name, w, h) = store.saveJpeg(bytes) ?: continue
            if (!existing.add(Triple(m.takenOn, m.pose, w))) { store.delete(name); continue }
            dao.insert(ProgressPhotoEntity(takenOn = m.takenOn, pose = m.pose, fileName = name, width = w, height = h, weightKg = m.weightKg, note = m.note, createdAt = System.currentTimeMillis()))
            added++
        }
        added
    }

    /**
     * Renders a before/after image for sharing (both photos side by side with their dates) into the app's share
     * cache, and returns the file. Only this one image leaves encrypted storage, and only when the user shares it.
     */
    suspend fun renderComparison(before: ProgressPhotoEntity, after: ProgressPhotoEntity, caption: String): File? = withContext(Dispatchers.IO) {
        val a = store.jpegBytes(before.fileName)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } ?: return@withContext null
        val b = store.jpegBytes(after.fileName)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } ?: return@withContext null
        val h = 1200
        val wa = a.width * h / a.height; val wb = b.width * h / b.height
        val gap = 16; val pad = 32; val footer = 140
        val out = Bitmap.createBitmap(wa + wb + gap + 2 * pad, h + pad + footer, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(14, 21, 19))
        c.drawBitmap(a, null, RectF(pad.toFloat(), pad.toFloat(), (pad + wa).toFloat(), (pad + h).toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        c.drawBitmap(b, null, RectF((pad + wa + gap).toFloat(), pad.toFloat(), (pad + wa + gap + wb).toFloat(), (pad + h).toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        val fmt = DateTimeFormatter.ofPattern("d MMM yyyy")
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 44f; typeface = Typeface.DEFAULT_BOLD }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(158, 242, 224); textSize = 36f }
        val y = (pad + h + 64).toFloat()
        c.drawText(before.takenOn.format(fmt), pad.toFloat(), y, label)
        c.drawText(after.takenOn.format(fmt), (pad + wa + gap).toFloat(), y, label)
        c.drawText(caption, pad.toFloat(), y + 52, small)
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val f = File(dir, "progress-comparison.jpg")
        f.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        a.recycle(); b.recycle(); out.recycle()
        f
    }

    /** A temporary file the camera app writes into; [PhotoStore.process] reads it and it is deleted right after. */
    fun cameraTarget(): File = File(File(context.cacheDir, "camera").apply { mkdirs() }, "capture.jpg").also { it.delete() }

    fun clearCameraTarget() { File(File(context.cacheDir, "camera"), "capture.jpg").delete() }
}
