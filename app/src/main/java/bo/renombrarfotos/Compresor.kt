package bo.renombrarfotos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.time.format.DateTimeFormatter

/** Recomprime una foto a JPEG con la calidad indicada y conserva sus metadatos EXIF. */
object Compresor {
    const val CALIDAD = 60
    private const val MAX_PIXELES = 25_000_000L // por encima se reduce a la mitad para no agotar la memoria
    private val formatoExif = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    private val etiquetas = listOf(
        ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED, ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
        ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
    )

    /** Escribe la foto comprimida en [destino]. Devuelve false si no se pudo decodificar. */
    fun comprimir(ctx: Context, f: Foto, destino: File): Boolean {
        val cr = ctx.contentResolver
        val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(f.uri)?.use { BitmapFactory.decodeStream(it, null, limites) }
        if (limites.outWidth <= 0 || limites.outHeight <= 0) return false
        var muestra = 1
        while (limites.outWidth.toLong() * limites.outHeight / (muestra.toLong() * muestra) > MAX_PIXELES) muestra *= 2

        val bmp = cr.openInputStream(f.uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = muestra })
        } ?: return false
        try {
            destino.outputStream().buffered(1 shl 16).use { bmp.compress(Bitmap.CompressFormat.JPEG, CALIDAD, it) }
        } finally {
            bmp.recycle()
        }

        // Los píxeles se guardan sin rotar, así que se copia también la orientación.
        runCatching {
            val origen = cr.openInputStream(f.uri)?.use { ExifInterface(it) }
            val nuevo = ExifInterface(destino)
            origen?.let { o -> etiquetas.forEach { t -> o.getAttribute(t)?.let { nuevo.setAttribute(t, it) } } }
            if (nuevo.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) == null) {
                f.fecha?.let { nuevo.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, it.format(formatoExif)) }
            }
            nuevo.saveAttributes()
        }
        return true
    }
}
