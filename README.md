# Catálogo

App Android para catalogar artículos con fotos sin metadatos, repartidas entre varios móviles.

- **Principal** (cámara): crea artículos con el primer número libre, hace las fotos con la cámara del sistema y las guarda limpias en el álbum `<prefijo><número>` de cada móvil.
- **Secundarios**: se vinculan al principal y reciben sus álbumes por conexión directa (Nearby Connections, sin internet), cifrada y autenticada.
- **Vendido**: borra el artículo en todos los móviles; el número vuelve a quedar libre cuando todos lo confirman.

## Estructura

- `core/`: lógica pura y probada (SKU, catálogo, liberaciones, protocolo, autenticación, limpieza de JPEG).
- `app/`: Android (Compose, MediaStore, Nearby).

## Desarrollo

```bash
CORE_ONLY=1 ./gradlew :core:test      # pruebas, sin Android SDK
./gradlew :app:assembleRelease        # APK (requiere Android SDK)
```

Cada push a `main` compila el APK y lo publica en la release `app`.
