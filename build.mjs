import { build } from 'esbuild';
import { cpSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';

// u2net, the same model Jarvis uses through rembg 2.0.67 (Apache-2.0).
const MODEL_URL = 'https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2net.onnx';
const MODEL_SHA256 = '8d10d2f3bb75ae3b6d527c77944fc5e7dcd94b29809d47a739a7a728a912b491';
const MODEL_CACHE = '.cache/u2net.onnx';
const PART_SIZE = 24 * 1024 * 1024;

const sha256 = (buf) => createHash('sha256').update(buf).digest('hex');

async function model() {
  if (!existsSync(MODEL_CACHE)) {
    mkdirSync('.cache', { recursive: true });
    const res = await fetch(MODEL_URL);
    if (!res.ok) throw new Error(`model download failed: ${res.status}`);
    writeFileSync(MODEL_CACHE, Buffer.from(await res.arrayBuffer()));
  }
  const bytes = readFileSync(MODEL_CACHE);
  if (sha256(bytes) !== MODEL_SHA256) throw new Error('model checksum mismatch');
  return bytes;
}

const out = 'dist';
rmSync(out, { recursive: true, force: true });
mkdirSync(`${out}/model`, { recursive: true });
mkdirSync(`${out}/ort`, { recursive: true });

await build({
  entryPoints: ['src/app.js', 'src/worker.js'],
  bundle: true, format: 'esm', minify: true, target: 'chrome110',
  outdir: out, legalComments: 'external',
});

cpSync('static', out, { recursive: true });

for (const f of ['ort-wasm-simd-threaded.wasm', 'ort-wasm-simd-threaded.mjs']) {
  cpSync(`node_modules/onnxruntime-web/dist/${f}`, `${out}/ort/${f}`);
}

// Split the model so no file is near hosting limits; part names carry the
// hash so a future model never collides with a cached one.
const bytes = await model();
const parts = [];
for (let i = 0, n = 0; i < bytes.length; i += PART_SIZE, n += 1) {
  const name = `u2net-${MODEL_SHA256.slice(0, 12)}-${n}.bin`;
  writeFileSync(`${out}/model/${name}`, bytes.subarray(i, i + PART_SIZE));
  parts.push(name);
}
writeFileSync(`${out}/model/manifest.json`, JSON.stringify({ sha256: MODEL_SHA256, size: bytes.length, parts }));

// Cache-busting version for the service worker shell.
const hash = createHash('sha256');
for (const f of ['app.js', 'worker.js', 'index.html', 'style.css', 'model/manifest.json']) hash.update(readFileSync(`${out}/${f}`));
const sw = readFileSync(`${out}/sw.js`, 'utf8').replace('__VERSION__', hash.digest('hex').slice(0, 12));
writeFileSync(`${out}/sw.js`, sw);
writeFileSync(`${out}/.nojekyll`, '');
console.log('built');
