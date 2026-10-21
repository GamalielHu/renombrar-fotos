package bo.renombrarfotos

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF2E5E4E))) { Pantalla() }
        }
    }
}


class Modelo(app: Application) : AndroidViewModel(app) {
    var fotos by mutableStateOf<List<Foto>>(emptyList()); private set
    var zip by mutableStateOf<File?>(null); private set
    var ocupado by mutableStateOf(false); private set
    var avance by mutableStateOf(0); private set
    var mensaje by mutableStateOf<String?>(null)
    private var tarea: Job? = null

    fun elegidas(uris: List<Uri>) {
        if (uris.isEmpty()) return
        tarea?.cancel()
        zip = null
        tarea = viewModelScope.launch {
            ocupado = true; avance = 0
            val ctx = getApplication<Application>()
            fotos = withContext(Dispatchers.IO) {
                Nombrador.asignar(uris.distinct().map { LectorFecha.leer(ctx, it) })
            }
            ocupado = false
            val sin = fotos.count { it.origen != Origen.EXIF }
            mensaje = "${fotos.size} fotos ordenadas" + if (sin > 0) " ($sin sin fecha EXIF)" else ""
        }
    }

    fun empaquetar() {
        if (fotos.isEmpty() || ocupado) return
        tarea = viewModelScope.launch {
            ocupado = true; avance = 0
            try {
                zip = Empaquetador.crearZip(getApplication<Application>(), fotos) { avance = it }
                mensaje = "ZIP listo: ${zip!!.name} (${tamano(zip!!.length())})"
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                mensaje = "Error al crear el ZIP: ${e.message}"
            } finally {
                ocupado = false
            }
        }
    }

    fun guardar(destino: Uri) {
        val z = zip ?: return
        viewModelScope.launch {
            ocupado = true
            mensaje = runCatching { Empaquetador.guardar(getApplication<Application>(), z, destino) }
                .fold({ "ZIP guardado" }, { "No se pudo guardar: ${it.message}" })
            ocupado = false
        }
    }

    fun limpiar() {
        tarea?.cancel(); fotos = emptyList(); zip = null; mensaje = null; ocupado = false
    }
}

private fun tamano(b: Long) = when {
    b >= 1 shl 30 -> "%.2f GB".format(b / (1024.0 * 1024 * 1024))
    b >= 1 shl 20 -> "%.1f MB".format(b / (1024.0 * 1024))
    else -> "${b / 1024} KB"
}

private val fechaVista = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")

@Composable
fun Pantalla(m: Modelo = viewModel()) {
    val ctx = LocalContext.current
    val elegir = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { m.elegidas(it) }
    val guardarComo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { m.guardar(it) }
    }
    val aviso = remember { SnackbarHostState() }
    LaunchedEffect(m.mensaje) { m.mensaje?.let { aviso.showSnackbar(it); m.mensaje = null } }

    Scaffold(snackbarHost = { SnackbarHost(aviso) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
            Text("Renombrar fotos por fecha", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("IMG_AAAA-MM-DD_HHMMSS · todo local, sin internet", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { elegir.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !m.ocupado,
                ) { Text("Elegir fotos") }
                if (m.fotos.isNotEmpty()) OutlinedButton(onClick = m::limpiar) { Text("Limpiar") }
            }

            if (m.fotos.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = m::empaquetar, enabled = !m.ocupado, modifier = Modifier.fillMaxWidth()) {
                    Text("Crear ZIP con ${m.fotos.size} fotos")
                }
            }

            m.zip?.let { z ->
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { guardarComo.launch(z.name) }, enabled = !m.ocupado, modifier = Modifier.weight(1f)) {
                        Text("Guardar ZIP")
                    }
                    OutlinedButton(onClick = { compartir(ctx, z) }, enabled = !m.ocupado, modifier = Modifier.weight(1f)) {
                        Text("Compartir")
                    }
                }
            }

            if (m.ocupado) {
                Spacer(Modifier.height(12.dp))
                if (m.avance > 0 && m.fotos.isNotEmpty()) {
                    LinearProgressIndicator(progress = { m.avance / m.fotos.size.toFloat() }, modifier = Modifier.fillMaxWidth())
                    Text("Comprimiendo ${m.avance} de ${m.fotos.size}", style = MaterialTheme.typography.bodySmall)
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }

            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(m.fotos, key = { it.uri.toString() }) { f -> Fila(f) }
            }
        }
    }
}

@Composable
private fun Fila(f: Foto) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text(f.nombreNuevo, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(f.nombreOriginal, style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (f.fecha?.format(fechaVista) ?: "—") + " · " + f.origen.texto,
                style = MaterialTheme.typography.bodySmall,
                color = if (f.origen == Origen.EXIF) Color(0xFF2E5E4E) else Color(0xFFB26A00),
            )
        }
    }
}

private fun compartir(ctx: android.content.Context, zip: File) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".archivos", zip)
    val envio = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = android.content.ClipData.newRawUri(zip.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(envio, "Compartir ZIP"))
}
