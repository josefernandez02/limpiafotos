// Offline cache. Same-origin only; nothing is ever sent anywhere.
const VERSION = '__VERSION__';
const SHELL_CACHE = `limpiafotos-${VERSION}`;
const MODEL_CACHE = 'limpiafotos-model';
const SHELL = [
  './', 'index.html', 'style.css', 'app.js', 'worker.js', 'manifest.webmanifest',
  'icon.svg', 'icon-192.png', 'icon-512.png', 'model/manifest.json',
  'ort/ort-wasm-simd-threaded.mjs', 'ort/ort-wasm-simd-threaded.wasm',
];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(SHELL_CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    for (const key of await caches.keys()) {
      if (key !== SHELL_CACHE && key !== MODEL_CACHE) await caches.delete(key);
    }
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  if (event.request.method !== 'GET' || url.origin !== self.location.origin) return;
  // Model parts are content-named: cached once, kept across app updates.
  const isModelPart = /\/model\/u2net-[0-9a-f]{12}-\d+\.bin$/.test(url.pathname);
  const cacheName = isModelPart ? MODEL_CACHE : SHELL_CACHE;
  event.respondWith((async () => {
    const cache = await caches.open(cacheName);
    const hit = await cache.match(event.request, { ignoreSearch: true });
    if (hit) return hit;
    const res = await fetch(event.request);
    if (res.ok) cache.put(event.request, res.clone());
    return res;
  })());
});
