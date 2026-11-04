# Limpiafotos

App web instalable (PWA) que, en el propio móvil:

1. **Borra todos los metadatos**: la foto se decodifica a píxeles y se genera un JPEG nuevo; después se eliminan todos los bloques no esenciales (EXIF, GPS, XMP, ICC, MPF, comentarios y datos tras el final de imagen, como el vídeo de las "fotos en movimiento"). El nombre de archivo pasa a ser aleatorio.
2. **Cambia el fondo** por un color configurable con el mismo pipeline que Jarvis: modelo **u2net** y el post-procesado de máscara de rembg 2.0.67 (apertura morfológica, desenfoque gaussiano, umbral).
3. **Envía** las fotos con el menú de compartir del sistema (Quick Share) o las guarda.

Las fotos nunca salen del dispositivo. El modelo y el runtime se sirven desde el mismo sitio, sin CDNs de terceros, y quedan en caché para uso sin conexión. El modelo se verifica con SHA-256 antes de usarse. La salida se limita a 2048 px de lado mayor.

## Uso

Abrir la URL de GitHub Pages en Chrome → menú ⋮ → **Añadir a pantalla de inicio**. La primera apertura descarga el modelo (~176 MB); después funciona sin conexión.

## Desarrollo

```bash
npm ci
npm test
npm run build   # descarga y verifica u2net, genera dist/
```

Publicar (compila en local y sube `dist/` a la rama `gh-pages`, que GitHub Pages sirve tal cual):

```bash
npm run deploy
```

## Licencias

Código: MIT. Modelo u2net: Apache-2.0 (vía rembg). ONNX Runtime Web: MIT.
