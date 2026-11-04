# Limpiafotos

App web instalable (PWA) que, en el propio móvil:

1. **Borra todos los metadatos**: la foto se decodifica a píxeles y se genera un JPEG nuevo; después se eliminan todos los bloques no esenciales (EXIF, GPS, XMP, ICC, MPF, comentarios, datos tras el final de imagen como el vídeo de las "fotos en movimiento"). El nombre de archivo se sustituye por uno aleatorio.
2. **Cambia el fondo** por un color configurable usando un modelo de segmentación (ISNet, vía `@imgly/background-removal`) que se ejecuta en el dispositivo.
3. **Envía** las fotos con el menú de compartir del sistema (Quick Share) o las guarda.

Las fotos nunca salen del dispositivo. El modelo y el runtime se sirven desde el mismo sitio (sin CDNs de terceros) y quedan en caché para uso sin conexión. La salida se limita a 2048 px de lado mayor.

## Uso

Abrir la URL de GitHub Pages en Chrome → menú ⋮ → **Añadir a pantalla de inicio**. La primera apertura descarga el modelo (~88 MB); después funciona sin conexión.

## Desarrollo

```bash
npm ci
npm test
npm run build   # genera dist/
```

Cada push a `main` se prueba, compila y publica en GitHub Pages (`.github/workflows/pages.yml`).

## Licencia

AGPL-3.0, heredada de `@imgly/background-removal`.
