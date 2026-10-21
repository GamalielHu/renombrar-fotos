# Renombrar Fotos

App Android (Kotlin + Jetpack Compose) que:

1. Elige varias fotos con el PhotoPicker del sistema (sin permisos de almacenamiento).
2. Lee la fecha de captura localmente: EXIF (`DateTimeOriginal` → `DateTimeDigitized` → `DateTime`), luego `MediaStore.DATE_TAKEN`, luego la fecha de modificación.
3. Las ordena y nombra `IMG_AAAA-MM-DD_HHMMSS.ext` (`_1`, `_2`… si coinciden en el mismo segundo).
4. Opcional (activado por defecto): las recomprime a JPEG calidad 60% conservando el EXIF.
5. Las empaqueta en un `.zip` (en `Dispatchers.IO`, dentro de la caché de la app) y permite guardarlo con SAF o compartirlo.

Las fotos originales de la galería no se modifican: los nombres nuevos van dentro del ZIP.

## Descarga
Cada push a `main` compila el APK en GitHub Actions y lo publica en **Releases**. Si la compilación falla, los errores quedan en la rama `registro` (`errores.txt`, `final.txt`).

## Código
- `FechaFoto.kt`: lectura de fecha (EXIF/MediaStore) y asignación de nombres.
- `Compresor.kt`: recompresión a JPEG 60% con copia de EXIF.
- `Empaquetador.kt`: creación del ZIP y guardado con SAF.
- `MainActivity.kt`: pantalla Compose y ViewModel.
