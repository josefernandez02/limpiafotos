import { build } from 'esbuild';
import { cpSync, mkdirSync, readFileSync, rmSync, writeFileSync, copyFileSync } from 'node:fs';
import { createHash } from 'node:crypto';

const out = 'dist';
rmSync(out, { recursive: true, force: true });
mkdirSync(`${out}/ai`, { recursive: true });

await build({
  entryPoints: ['src/app.js', 'src/worker.js'],
  bundle: true, format: 'esm', minify: true, target: 'chrome110',
  outdir: out, legalComments: 'external',
});

cpSync('static', out, { recursive: true });

// Self-host only what is needed: the "medium" model and the non-threaded
// WASM runtimes (GitHub Pages cannot enable cross-origin isolation).
const KEEP = ['/models/medium', '/onnxruntime-web/ort-wasm-simd.wasm', '/onnxruntime-web/ort-wasm.wasm'];
const dataDir = 'node_modules/@imgly/background-removal-data/dist';
const all = JSON.parse(readFileSync(`${dataDir}/resources.json`, 'utf8'));
const subset = {};
for (const key of KEEP) {
  subset[key] = all[key];
  for (const chunk of all[key].chunks) copyFileSync(`${dataDir}/${chunk.hash}`, `${out}/ai/${chunk.hash}`);
}
writeFileSync(`${out}/ai/resources.json`, JSON.stringify(subset));

// Cache-busting version for the service worker shell.
const hash = createHash('sha256');
for (const f of ['app.js', 'worker.js', 'index.html', 'style.css']) hash.update(readFileSync(`${out}/${f}`));
const sw = readFileSync(`${out}/sw.js`, 'utf8').replace('__VERSION__', hash.digest('hex').slice(0, 12));
writeFileSync(`${out}/sw.js`, sw);
writeFileSync(`${out}/.nojekyll`, '');
console.log('built');
