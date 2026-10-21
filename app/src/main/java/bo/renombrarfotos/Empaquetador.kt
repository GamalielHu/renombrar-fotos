package bo.renombrarfotos

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Crea y exporta el ZIP. Todo en Dispatchers.IO y en la caché propia (sin permisos de almacenamiento). */
object Empaquetador {
    fun carpeta(ctx: Context) = File(ctx.cacheDir, "zip").apply { mkdirs() }

    suspend fun crearZip(ctx: Context, fotos: List<Foto>, progreso: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        carpeta(ctx).listFiles()?.forEach { it.delete() }
        val nombre = "Fotos_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")) + ".zip"
        val destino = File(carpeta(ctx), nombre)
        try {
            ZipOutputStream(destino.outputStream().buffered(1 shl 16)).use { zip ->
                zip.setLevel(Deflater.NO_COMPRESSION) // JPG/HEIC ya vienen comprimidos: así es mucho más rápido
                fotos.forEachIndexed { i, f ->
                    ensureActive()
                    zip.putNextEntry(ZipEntry(f.nombreNuevo).apply {
                        f.fecha?.let { time = it.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }
                    })
                    ctx.contentResolver.openInputStream(f.uri)?.use { it.copyTo(zip, 1 shl 16) }
                        ?: error("No se pudo leer ${f.nombreOriginal}")
                    zip.closeEntry()
                    withContext(Dispatchers.Main) { progreso(i + 1) }
                }
            }
        } catch (e: Throwable) {
            destino.delete()
            throw e
        }
        destino
    }

    /** Copia el ZIP al destino elegido con SAF (ACTION_CREATE_DOCUMENT). */
    suspend fun guardar(ctx: Context, zip: File, destino: Uri) = withContext(Dispatchers.IO) {
        ctx.contentResolver.openOutputStream(destino, "w")?.use { out ->
            zip.inputStream().use { it.copyTo(out, 1 shl 16) }
        } ?: error("No se pudo abrir el destino")
    }
}
