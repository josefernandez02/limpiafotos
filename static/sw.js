// Offline cache. Same-origin only; nothing is ever sent anywhere.
const VERSION = '__VERSION__';
const CACHE = `limpiafotos-${VERSION}`;
const SHELL = ['./', 'index.html', 'style.css', 'app.js', 'worker.js', 'manifest.webmanifest', 'icon.svg', 'icon-192.png', 'icon-512.png', 'ai/resources.json'];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    const keep = new Set([CACHE, 'limpiafotos-ai']);
    for (const key of await caches.keys()) if (!keep.has(key)) await caches.delete(key);
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  if (event.request.method !== 'GET' || url.origin !== self.location.origin) return;
  // Model chunks are content-addressed (hash names): cache forever across versions.
  const isModelChunk = /\/ai\/[0-9a-f]{64}$/.test(url.pathname);
  const cacheName = isModelChunk ? 'limpiafotos-ai' : CACHE;
  event.respondWith((async () => {
    const cache = await caches.open(cacheName);
    const hit = await cache.match(event.request, { ignoreSearch: true });
    if (hit) return hit;
    const res = await fetch(event.request);
    if (res.ok) cache.put(event.request, res.clone());
    return res;
  })());
});
