import { build } from 'esbuild';
import { cpSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';

const out = 'dist';
rmSync(out, { recursive: true, force: true });
mkdirSync(out, { recursive: true });

await build({
  entryPoints: ['src/app.js', 'src/worker.js'],
  bundle: true, format: 'esm', minify: true, target: 'chrome110',
  outdir: out,
});

cpSync('static', out, { recursive: true });

// Cache-busting version for the service worker shell.
const hash = createHash('sha256');
for (const f of ['app.js', 'worker.js', 'index.html', 'style.css']) hash.update(readFileSync(`${out}/${f}`));
const sw = readFileSync(`${out}/sw.js`, 'utf8').replace('__VERSION__', hash.digest('hex').slice(0, 12));
writeFileSync(`${out}/sw.js`, sw);
writeFileSync(`${out}/.nojekyll`, '');
console.log('built');
