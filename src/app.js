const DEFAULT_COLOR = '#7F8283';
const STORAGE_KEY = 'limpiafotos.color';

const $ = (selector) => document.querySelector(selector);
const picker = $('#picker');
const pickBtn = $('#pick');
const grid = $('#grid');
const statusEl = $('#status');
const sendBtn = $('#send');
const saveBtn = $('#save');
const settings = $('#settings');
const colorInput = $('#color');
const hexInput = $('#hex');
const swatch = $('#swatch');

let results = [];
let busy = false;

// ---------- settings ----------
function readColor() {
  try {
    const v = localStorage.getItem(STORAGE_KEY);
    if (v && /^#[0-9a-f]{6}$/i.test(v)) return v.toUpperCase();
  } catch {}
  return DEFAULT_COLOR;
}
function writeColor(v) {
  try { localStorage.setItem(STORAGE_KEY, v); } catch {}
}
function applyColor(v) {
  colorInput.value = v.toLowerCase();
  hexInput.value = v;
  swatch.style.background = v;
}
applyColor(readColor());

$('#open-settings').addEventListener('click', () => settings.showModal());
colorInput.addEventListener('input', () => {
  const v = colorInput.value.toUpperCase();
  applyColor(v);
  writeColor(v);
});
hexInput.addEventListener('input', () => {
  let v = hexInput.value.trim();
  if (!v.startsWith('#')) v = `#${v}`;
  if (/^#[0-9a-f]{6}$/i.test(v)) {
    v = v.toUpperCase();
    colorInput.value = v.toLowerCase();
    swatch.style.background = v;
    writeColor(v);
  }
});
$('#reset-color').addEventListener('click', () => {
  applyColor(DEFAULT_COLOR);
  writeColor(DEFAULT_COLOR);
});

// ---------- worker ----------
const worker = new Worker('./worker.js', { type: 'module' });
let seq = 0;
const pending = new Map();
worker.onmessage = ({ data }) => {
  const p = pending.get(data.id);
  if (!p) return;
  pending.delete(data.id);
  data.ok ? p.resolve(data.blob) : p.reject(new Error(data.error));
};
function call(msg) {
  return new Promise((resolve, reject) => {
    const id = ++seq;
    pending.set(id, { resolve, reject });
    worker.postMessage({ id, ...msg });
  });
}

// ---------- helpers ----------
function randomName() {
  const bytes = crypto.getRandomValues(new Uint8Array(6));
  return `${Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')}.jpg`;
}
function setStatus(text) { statusEl.textContent = text; }
function setActions(enabled) {
  sendBtn.disabled = !enabled;
  saveBtn.disabled = !enabled;
}
function clearResults() {
  for (const r of results) URL.revokeObjectURL(r.url);
  results = [];
  grid.replaceChildren();
}

// ---------- flow ----------
pickBtn.addEventListener('click', () => { if (!busy) picker.click(); });

picker.addEventListener('change', async () => {
  const files = Array.from(picker.files ?? []);
  picker.value = '';
  if (!files.length) return;

  busy = true;
  pickBtn.disabled = true;
  setActions(false);
  clearResults();

  const color = readColor();
  const cells = files.map(() => {
    const cell = document.createElement('div');
    cell.className = 'cell loading';
    grid.append(cell);
    return cell;
  });

  let failed = 0;
  let formatError = false;
  for (let i = 0; i < files.length; i += 1) {
    setStatus(`Procesando ${i + 1} de ${files.length}…`);
    try {
      const blob = await call({ type: 'process', file: files[i], color });
      const file = new File([blob], randomName(), { type: 'image/jpeg' });
      const url = URL.createObjectURL(file);
      results.push({ file, url });
      const img = document.createElement('img');
      img.src = url;
      img.alt = '';
      cells[i].className = 'cell';
      cells[i].append(img);
    } catch (error) {
      failed += 1;
      if (error.message === 'FORMAT') formatError = true;
      cells[i].className = 'cell error';
      cells[i].textContent = '✕';
    }
  }

  busy = false;
  pickBtn.disabled = false;
  setActions(results.length > 0);

  const n = results.length;
  if (!failed) setStatus(`${n} ${n === 1 ? 'lista' : 'listas'} · sin metadatos`);
  else if (formatError) setStatus(`${failed} no se pudieron abrir. Usa fotos JPEG (Cámara → Ajustes → Formatos de imagen → desactiva HEIF).`);
  else setStatus(`${n} ${n === 1 ? 'lista' : 'listas'}, ${failed} con error. Vuelve a intentarlo con esas.`);
});

sendBtn.addEventListener('click', async () => {
  const files = results.map((r) => r.file);
  if (navigator.canShare?.({ files })) {
    try { await navigator.share({ files }); } catch (e) {
      if (e.name !== 'AbortError') setStatus('No se pudo compartir. Usa "Guardar".');
    }
  } else {
    setStatus('Este navegador no permite compartir. Usa "Guardar".');
  }
});

saveBtn.addEventListener('click', () => {
  for (const r of results) {
    const a = document.createElement('a');
    a.href = r.url;
    a.download = r.file.name;
    a.click();
  }
});

// Warm up the model in the background so the first batch is quicker.
setStatus('Preparando…');
call({ type: 'preload' })
  .then(() => { if (!busy && !results.length) setStatus('Listo'); })
  .catch(() => { if (!busy && !results.length) setStatus('Sin conexión: abre la app una vez con internet.'); });

if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('./sw.js').catch(() => {});
}
