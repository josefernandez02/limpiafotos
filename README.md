# Limpiafotos

App web instalable (PWA) que borra todos los metadatos de las fotos en el propio móvil, sin subirlas a ningún sitio.

Cada foto se decodifica a píxeles (aplicando la orientación) y se escribe un JPEG nuevo a resolución completa y calidad 95. Después se eliminan todos los bloques no esenciales del archivo. No sobrevive nada del original:

- EXIF y GPS, XMP, IPTC, ICC, MakerNotes y miniaturas.
- El vídeo de las "fotos en movimiento".
- Las tablas de cuantización propias de la cámara.
- El nombre del archivo, que pasa a ser aleatorio.

Las fotos se envían con el menú de compartir del sistema (Quick Share) o se guardan.

## Uso

Abrir la URL de GitHub Pages en Chrome → menú ⋮ → **Añadir a pantalla de inicio**. Funciona sin conexión.

## Desarrollo

```bash
npm ci
npm test
npm run deploy   # tests + build + publica dist/ en la rama gh-pages
```

## Licencia

MIT.
