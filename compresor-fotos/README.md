# Compresor de fotos

Comprime lotes de fotos (JPG, PNG, WebP) en paralelo, estilo WhatsApp, y las guarda en un `.zip`.

## Instalar y ejecutar

```bash
pip install -r requirements.txt
python main.py
```

## Compilar a .exe (Windows)

```bash
pip install pyinstaller
pyinstaller --onefile --windowed main.py
```

El ejecutable queda en `dist/main.exe`.
