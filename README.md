# Catálogo

App Android para catalogar artículos con fotos sin metadatos, repartidas entre varios móviles.

- **Principal** (cámara): crea artículos con el primer número libre, hace las fotos con la cámara del sistema y las guarda limpias en el álbum `<prefijo><número>` de cada móvil.
- **Secundarios**: se vinculan al principal y reciben sus álbumes por conexión directa (Nearby Connections, sin internet), cifrada y autenticada.
- **Ventas**: las lee el **PC** en «Mis pedidos › Ventas» (accesibilidad de Windows y ratón humano) y el móvil las apunta. La app lo pide con su botón y sola al abrirse si la última lectura tiene más de una hora; sin conexión, lo pide cuando vuelva. El PC lee con el programa abierto, aunque no esté en «Iniciar», cuando no usas el ratón ni el teclado (y, en marcha, además cada 3 horas). Cuentan las ventas de anuncios publicados por el sistema o con su código `#SH0012`; no hay fechas que ajustar. Lo que dice Vinted manda sobre lo apuntado a mano: mismo código y precio se unen solas; con precio distinto, la app pregunta si es la misma. La conversación con el comprador no se lee.
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
