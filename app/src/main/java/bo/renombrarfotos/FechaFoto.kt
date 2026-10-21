package bo.renombrarfotos

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.exifinterface.media.ExifInterface
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class Origen(val texto: String) { EXIF("EXIF"), MEDIASTORE("MediaStore"), MODIFICADO("Modificación"), SIN_FECHA("Sin fecha") }

data class Foto(
    val uri: Uri,
    val nombreOriginal: String,
    val extension: String,
    val fecha: LocalDateTime?,
    val origen: Origen,
    val nombreNuevo: String = "",
)

/** Lee nombre, extensión y fecha de captura de una foto, sin red. */
object LectorFecha {
    private val formatoExif = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    fun leer(ctx: Context, uri: Uri): Foto {
        val cr = ctx.contentResolver
        var nombre: String? = null
        var tomada: Long? = null
        var modificada: Long? = null
        // El proveedor del PhotoPicker expone DATE_TAKEN; otros proveedores pueden rechazar la columna.
        for (cols in listOf(
            arrayOf(OpenableColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED),
            arrayOf(OpenableColumns.DISPLAY_NAME),
        )) {
            val ok = runCatching {
                cr.query(uri, cols, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        nombre = c.getString(0)
                        if (cols.size > 1) {
                            if (!c.isNull(1)) tomada = c.getLong(1).takeIf { it > 0 }
                            if (!c.isNull(2)) modificada = c.getLong(2).takeIf { it > 0 }
                        }
                    }
                }
            }.isSuccess
            if (ok) break
        }

        val mime = cr.getType(uri)
        val ext = nombre?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() && it.length <= 5 }
            ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.lowercase()
            ?: "jpg"

        val exif = runCatching { cr.openInputStream(uri)?.use { leerExif(ExifInterface(it)) } }.getOrNull()
        val zona = ZoneId.systemDefault()
        val (fecha, origen) = when {
            exif != null -> exif to Origen.EXIF
            tomada != null -> LocalDateTime.ofInstant(Instant.ofEpochMilli(tomada!!), zona) to Origen.MEDIASTORE
            modificada != null -> LocalDateTime.ofInstant(Instant.ofEpochSecond(modificada!!), zona) to Origen.MODIFICADO
            else -> null to Origen.SIN_FECHA
        }
        return Foto(uri, nombre ?: uri.lastPathSegment ?: "foto", if (ext == "jpeg") "jpg" else ext, fecha, origen)
    }

    private fun leerExif(e: ExifInterface): LocalDateTime? =
        listOf(ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_DATETIME)
            .firstNotNullOfOrNull { tag ->
                e.getAttribute(tag)?.trim()?.take(19)
                    ?.takeIf { !it.startsWith("0000") }
                    ?.let { runCatching { LocalDateTime.parse(it, formatoExif) }.getOrNull() }
            }
}

/** Ordena por fecha y asigna IMG_AAAA-MM-DD_HHMMSS.ext, con _1, _2… si coinciden en el mismo segundo. */
object Nombrador {
    private val formato = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")

    fun asignar(fotos: List<Foto>): List<Foto> {
        val usados = HashSet<String>()
        return fotos
            .sortedWith(compareBy<Foto>({ it.fecha == null }, { it.fecha?.format(formato) }, { it.nombreOriginal }))
            .map { f ->
                val base = f.fecha?.let { "IMG_" + it.format(formato) } ?: ("SIN_FECHA_" + f.nombreOriginal.substringBeforeLast('.'))
                var nombre = "$base.${f.extension}"
                var n = 1
                while (!usados.add(nombre.lowercase())) nombre = "${base}_${n++}.${f.extension}"
                f.copy(nombreNuevo = nombre)
            }
    }
}
