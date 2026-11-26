# Catálogo

App Android para catalogar artículos con fotos sin metadatos, repartidas entre varios móviles.

- **Principal** (cámara): crea artículos con el primer número libre, hace las fotos con la cámara del sistema y las guarda limpias en el álbum `<prefijo><número>` de cada móvil.
- **Secundarios**: se vinculan al principal y reciben sus álbumes por conexión directa (Nearby Connections, sin internet), cifrada y autenticada.
- **Ventas**: las lee el **PC** en «Mis pedidos › Ventas» (accesibilidad de Windows y ratón humano), solo cada 3 horas con el programa en marcha o cuando lo pides; el móvil las pide y las apunta mientras está abierto. Cuentan las que estén en curso y las finalizadas desde la fecha de inicio. El artículo se reconoce por su anuncio o por el último hashtag (`#SH0012`). La conversación con el comprador no se lee.
  - *En curso*: la prenda sale del stock pero conserva número, datos y fotos; se puede anular (a mano o porque Vinted la cancele).
  - *Completada* (pedido finalizado): definitiva; se borran sus fotos y el número queda libre.

## Estructura

- `core/`: lógica pura y probada (SKU, catálogo, liberaciones, protocolo, autenticación, limpieza de JPEG).
- `app/`: Android (Compose, MediaStore, Nearby).

## Desarrollo

```bash
CORE_ONLY=1 ./gradlew :core:test      # pruebas, sin Android SDK
./gradlew :app:assembleRelease        # APK (requiere Android SDK)
```

Cada push a `main` compila el APK y lo publica en la release `app`.
