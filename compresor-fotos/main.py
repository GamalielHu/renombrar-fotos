"""Compresor de fotos por lotes: redimensiona, comprime y empaqueta en ZIP."""
import io
import os
import queue
import threading
import zipfile
from concurrent.futures import ProcessPoolExecutor, as_completed
from multiprocessing import freeze_support
from pathlib import Path
from tkinter import (BooleanVar, IntVar, StringVar, Tk, filedialog,
                     messagebox, ttk)

from PIL import Image, ImageOps

EXTENSIONES = {".jpg", ".jpeg", ".png", ".webp"}
RESOLUCIONES = {"1280 px": 1280, "1600 px": 1600, "1920 px": 1920, "Tamaño original": 0}
ORIENTACION_EXIF = 0x0112


# ---------- Procesamiento (se ejecuta en procesos hijos) ----------

def comprimir_imagen(ruta: str, lado_max: int, calidad: int, conservar_exif: bool):
    """Devuelve (nombre_salida, bytes_jpeg) o lanza excepción."""
    with Image.open(ruta) as img:
        img = ImageOps.exif_transpose(img)  # aplica la rotación antes de tocar EXIF
        exif = img.getexif()

        if img.mode in ("RGBA", "LA", "P"):
            img = img.convert("RGBA")
            fondo = Image.new("RGB", img.size, (255, 255, 255))
            fondo.paste(img, mask=img.getchannel("A"))
            img = fondo
        elif img.mode != "RGB":
            img = img.convert("RGB")

        if lado_max and max(img.size) > lado_max:
            img.thumbnail((lado_max, lado_max), Image.Resampling.LANCZOS)

        opciones = {"format": "JPEG", "quality": calidad, "optimize": True, "progressive": True}
        if conservar_exif and exif:
            exif.pop(ORIENTACION_EXIF, None)
            opciones["exif"] = exif.tobytes()

        buffer = io.BytesIO()
        img.save(buffer, **opciones)
    return Path(ruta).stem + ".jpg", buffer.getvalue()


def nombre_unico(nombre: str, usados: set) -> str:
    base, ext = os.path.splitext(nombre)
    candidato, n = nombre, 1
    while candidato.lower() in usados:
        candidato = f"{base}_{n}{ext}"
        n += 1
    usados.add(candidato.lower())
    return candidato


def procesar_lote(rutas, destino_zip, lado_max, calidad, conservar_exif, avisar):
    """Comprime en paralelo y escribe en el ZIP. avisar(tipo, datos) informa a la GUI."""
    total, hechos, errores, usados = len(rutas), 0, [], set()
    with zipfile.ZipFile(destino_zip, "w", zipfile.ZIP_STORED) as zf, \
            ProcessPoolExecutor(max_workers=os.cpu_count()) as pool:
        futuros = {pool.submit(comprimir_imagen, r, lado_max, calidad, conservar_exif): r
                   for r in rutas}
        for futuro in as_completed(futuros):
            try:
                nombre, datos = futuro.result()
                zf.writestr(nombre_unico(nombre, usados), datos)
            except Exception as e:  # imagen corrupta o ilegible
                errores.append(f"{Path(futuros[futuro]).name}: {e}")
            hechos += 1
            avisar("progreso", (hechos, total))
    avisar("fin", errores)


# ---------- Interfaz gráfica ----------

class App:
    def __init__(self, root: Tk):
        self.root = root
        self.rutas: list[str] = []
        self.cola: queue.Queue = queue.Queue()

        root.title("Compresor de fotos")
        root.resizable(False, False)
        marco = ttk.Frame(root, padding=14)
        marco.grid()

        ttk.Button(marco, text="Seleccionar carpeta", command=self.elegir_carpeta).grid(row=0, column=0, sticky="ew")
        ttk.Button(marco, text="Seleccionar archivos", command=self.elegir_archivos).grid(row=0, column=1, sticky="ew", padx=(8, 0))
        self.lbl_sel = StringVar(value="Ninguna imagen seleccionada")
        ttk.Label(marco, textvariable=self.lbl_sel).grid(row=1, column=0, columnspan=2, sticky="w", pady=(6, 10))

        ttk.Label(marco, text="Resolución máxima (lado largo):").grid(row=2, column=0, sticky="w")
        self.resolucion = StringVar(value="1280 px")
        ttk.Combobox(marco, textvariable=self.resolucion, values=list(RESOLUCIONES),
                     state="readonly", width=16).grid(row=2, column=1, sticky="ew", padx=(8, 0))

        self.calidad = IntVar(value=75)
        self.lbl_cal = StringVar(value="Calidad: 75")
        ttk.Label(marco, textvariable=self.lbl_cal).grid(row=3, column=0, sticky="w", pady=(10, 0))
        ttk.Scale(marco, from_=1, to=100, variable=self.calidad, orient="horizontal",
                  command=self.actualizar_calidad).grid(row=3, column=1, sticky="ew", padx=(8, 0), pady=(10, 0))

        self.quitar_exif = BooleanVar(value=True)
        ttk.Checkbutton(marco, text="Eliminar metadatos EXIF (menor peso)",
                        variable=self.quitar_exif).grid(row=4, column=0, columnspan=2, sticky="w", pady=(10, 0))

        self.btn_iniciar = ttk.Button(marco, text="Comprimir y guardar ZIP", command=self.iniciar)
        self.btn_iniciar.grid(row=5, column=0, columnspan=2, sticky="ew", pady=(14, 6))
        self.barra = ttk.Progressbar(marco, length=360, mode="determinate")
        self.barra.grid(row=6, column=0, columnspan=2, sticky="ew")
        self.estado = StringVar(value="Listo")
        ttk.Label(marco, textvariable=self.estado).grid(row=7, column=0, columnspan=2, sticky="w", pady=(6, 0))

    def actualizar_calidad(self, _=None):
        self.calidad.set(round(self.calidad.get()))
        self.lbl_cal.set(f"Calidad: {self.calidad.get()}")

    def fijar_rutas(self, rutas):
        self.rutas = sorted(rutas)
        self.lbl_sel.set(f"{len(self.rutas)} imágenes seleccionadas")

    def elegir_carpeta(self):
        carpeta = filedialog.askdirectory(title="Carpeta con imágenes")
        if carpeta:
            self.fijar_rutas([str(p) for p in Path(carpeta).rglob("*")
                              if p.is_file() and p.suffix.lower() in EXTENSIONES])

    def elegir_archivos(self):
        archivos = filedialog.askopenfilenames(
            title="Imágenes", filetypes=[("Imágenes", "*.jpg *.jpeg *.png *.webp"), ("Todos", "*.*")])
        if archivos:
            self.fijar_rutas(archivos)

    def iniciar(self):
        if not self.rutas:
            messagebox.showwarning("Sin imágenes", "Selecciona una carpeta o archivos primero.")
            return
        destino = filedialog.asksaveasfilename(title="Guardar ZIP", defaultextension=".zip",
                                               initialfile="fotos_comprimidas.zip",
                                               filetypes=[("Archivo ZIP", "*.zip")])
        if not destino:
            return
        self.btn_iniciar.state(["disabled"])
        self.barra.configure(maximum=len(self.rutas), value=0)
        self.estado.set(f"Procesando imagen 0 de {len(self.rutas)}...")
        argumentos = (list(self.rutas), destino, RESOLUCIONES[self.resolucion.get()],
                      self.calidad.get(), not self.quitar_exif.get(),
                      lambda tipo, datos: self.cola.put((tipo, datos)))
        threading.Thread(target=self.trabajo, args=argumentos, daemon=True).start()
        self.destino = destino
        self.root.after(100, self.revisar_cola)

    def trabajo(self, *args):
        try:
            procesar_lote(*args)
        except Exception as e:
            self.cola.put(("error", str(e)))

    def revisar_cola(self):
        try:
            while True:
                tipo, datos = self.cola.get_nowait()
                if tipo == "progreso":
                    hechos, total = datos
                    self.barra.configure(value=hechos)
                    self.estado.set(f"Procesando imagen {hechos} de {total}...")
                elif tipo == "fin":
                    self.terminar(datos)
                    return
                elif tipo == "error":
                    self.btn_iniciar.state(["!disabled"])
                    self.estado.set("Error")
                    messagebox.showerror("Error", datos)
                    return
        except queue.Empty:
            pass
        self.root.after(100, self.revisar_cola)

    def terminar(self, errores):
        self.btn_iniciar.state(["!disabled"])
        ok = len(self.rutas) - len(errores)
        self.estado.set(f"Listo: {ok} imágenes en {Path(self.destino).name}")
        mensaje = f"{ok} imágenes comprimidas en:\n{self.destino}"
        if errores:
            mensaje += f"\n\n{len(errores)} con error:\n" + "\n".join(errores[:10])
        messagebox.showinfo("Completado", mensaje)


def main():
    root = Tk()
    App(root)
    root.mainloop()


if __name__ == "__main__":
    freeze_support()  # necesario para ProcessPoolExecutor dentro del .exe
    main()
