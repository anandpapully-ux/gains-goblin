package com.healthvectors.fitness

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.healthvectors.fitness.data.local.DbKeyProvider
import com.healthvectors.fitness.data.photos.PhotoStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDate

/** Photos must come out upright, small, without metadata, and be unreadable on disk. */
@RunWith(AndroidJUnit4::class)
class PhotoStoreTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = PhotoStore(ctx, DbKeyProvider(ctx))

    @Test fun processRotatesShrinksStripsMetadataAndEncrypts() = runBlocking<Unit> {
        // A 3000x2000 landscape JPEG the camera marked "rotate 90" with a date and a GPS position.
        val src = File(ctx.cacheDir, "test-src.jpg")
        val bmp = Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 120, 80)) }
        src.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(src.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:05 07:30:00")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "52/1,22/1,12/1"); setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "4/1,53/1,24/1"); setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
            saveAttributes()
        }

        val p = store.process(Uri.fromFile(src))!!
        assertEquals(LocalDate.of(2026, 10, 5), p.takenOn)
        assertEquals(1600, maxOf(p.width, p.height))
        assertTrue("upright: portrait after rotation", p.height > p.width)
        assertNotNull("the test image really had a location", ExifInterface(src.absolutePath).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        val out = ExifInterface(ByteArrayInputStream(p.full))
        assertNull(out.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(out.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))

        val name = store.save(p)
        val onDisk = File(File(ctx.filesDir, "photos"), "$name.img").readBytes()
        assertFalse("not a plain JPEG on disk", onDisk[0] == 0xFF.toByte() && onDisk[1] == 0xD8.toByte())
        assertArrayEquals(p.full, store.jpegBytes(name))
        val thumb = store.load(name, thumb = true)!!
        assertEquals(360, maxOf(thumb.width, thumb.height))

        store.delete(name)
        assertNull(store.jpegBytes(name))
        src.delete()
    }
}
