# Catálogo

App Android para catalogar prendas y venderlas en Vinted. El móvil hace las fotos, las guarda sin metadatos y deja que la IA (Gemini) escriba el anuncio; el programa del PC (no está en este repositorio) sube los borradores a Vinted, lee las ventas y genera las etiquetas de envío.

## Cómo funciona

- **Artículos**: cada uno toma el primer número libre. Su código es el prefijo de la cuenta más el número (`SH0045`) y sus fotos se hacen con la cámara del sistema y se guardan limpias en un álbum con ese código.
- **Cuentas de Vinted**: una principal, que es la que atiende el PC, y las que se quieran añadir, cuyo estado se marca a mano. Las copias de fotos para esas otras cuentas se guardan dentro de la app.
- **PC**: se conecta por la red de casa o Tailscale (HTTP con clave). Dirección y clave se ponen en «Ajustes» y no salen del móvil.
- **Ventas**: las lee el **PC** en «Mis pedidos › Ventas» (accesibilidad de Windows y ratón humano) y el móvil las apunta. La app lo pide con su botón y sola al abrirse si la última lectura tiene más de una hora; sin conexión, lo pide cuando vuelva. El PC lee con el programa abierto, aunque no esté en «Iniciar», cuando no usas el ratón ni el teclado (y, en marcha, además cada 3 horas). Cuentan las ventas de anuncios publicados por el sistema o con su código `#SH0012`; no hay fechas que ajustar. Marcar «Vendido» a mano es solo para ventas fuera de Vinted: la app no une ni anula esas ventas por su cuenta; si Vinted vende el mismo anuncio, avisa y se pueden unir. La conversación con el comprador no se lee.
  - *En curso*: la prenda sale del stock pero conserva número, datos y fotos; se puede anular (a mano o porque Vinted la cancele).
  - *Completada* (pedido finalizado): definitiva; se borran sus fotos y el número queda libre.
- **Etiquetas de envío**: en «Ventas» (todas las pendientes de enviar) o dentro de una venta, la app se las pide al **PC**, que las genera en Vinted, las recorta en vertical a 4×6 y las junta en un PDF con el código de cada prenda. El PDF llega a **Descargas** y «Abrir en 4BarCode» se lo pasa a la app de la impresora (si 4BarCode no se ofrece para abrirlo, deja elegir app; siempre se puede importar desde Descargas). El PC pulsa siempre «Imprimir etiqueta de envío» en Vinted. El A4 de InPost se recorta; las que ya vienen en 4×6 (SEUR) no se tocan y solo se les pone el código; cualquier otro formato va tal cual y se avisa. Si no se pide parar, el PDF se recoge aunque cierres la app, al volver a abrirla. Cuando la venta queda **completada** (tick verde) sus etiquetas se borran solas, en el PC y en Descargas del móvil; un PDF con varias espera a que no quede ninguna en curso.

## Estructura

```
core/   lógica sin Android, con pruebas       (dev.catalogo.core)
  model/     lo que se guarda en el móvil
  catalog/   artículos, inventario, estadísticas y resubidas
  listing/   anuncios, títulos y lectura de etiquetas de la prenda
  ai/        peticiones a Gemini
  vinted/    categorías, tallas y perfil de Vinted
  pc/        datos que se envían al PC
  sales/     ventas y etiquetas de envío
  photo/     limpieza de JPEG y encuadre
app/    aplicación Android                    (dev.catalogo)
  data/      repositorio, almacenamiento (store), fotos, Gemini y conexión con el PC
  ui/        pantallas Compose: articles, photos, sales, pc, settings y common
```

## Desarrollo

```bash
CORE_ONLY=1 ./gradlew :core:test      # pruebas, sin Android SDK
./gradlew :app:assembleRelease        # APK (requiere Android SDK)
```

Cada push a `main` pasa las pruebas, compila el APK y lo publica en la release `app` como `catalogo.apk`. La firma usa los secretos `KEYSTORE_B64` y `KEYSTORE_PASSWORD`.

## Para no perder datos al actualizar

- No cambiar `applicationId` (`dev.catalogo`), la clave de firma ni la ruta de `dev.catalogo.ui.MainActivity`.
- Todo el estado vive en `state.json`. Si se renombra una propiedad guardada, hay que conservar su nombre con `@SerialName`; `SavedStateTest` comprueba que un estado antiguo sigue cargando.
