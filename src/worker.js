// Runs entirely on the device. No photo ever leaves this worker except as the
// final, freshly encoded JPEGs returned to the page.
import * as ort from 'onnxruntime-web/wasm';
import { toInput, toMask, postProcess, maskLooksValid, MODEL_SIZE } from './mask.js';
import { stripJpeg } from './strip.js';

const MAX_EDGE = 2048;
const JPEG_QUALITY = 0.94;
const BASE = new URL('./', self.location.href);

ort.env.wasm.wasmPaths = new URL('ort/', BASE).href;
ort.env.wasm.numThreads = 1;
ort.env.wasm.proxy = false;

let sessionPromise;

async function loadModel() {
  const manifest = await (await fetch(new URL('model/manifest.json', BASE))).json();
  const bytes = new Uint8Array(manifest.size);
  let offset = 0;
  for (const part of manifest.parts) {
    const res = await fetch(new URL(`model/${part}`, BASE));
    if (!res.ok) throw new Error('MODEL_DOWNLOAD');
    const chunk = new Uint8Array(await res.arrayBuffer());
    bytes.set(chunk, offset);
    offset += chunk.length;
  }
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
  const hex = Array.from(digest, (b) => b.toString(16).padStart(2, '0')).join('');
  if (offset !== manifest.size || hex !== manifest.sha256) {
    // A corrupted cache must never be reused: drop it so the next run refetches.
    await caches.delete('limpiafotos-model').catch(() => {});
    throw new Error('MODEL_CORRUPT');
  }
  return ort.InferenceSession.create(bytes, { executionProviders: ['wasm'], graphOptimizationLevel: 'all' });
}

function session() {
  sessionPromise ??= loadModel().catch((error) => {
    sessionPromise = undefined;
    throw error;
  });
  return sessionPromise;
}

function parseHex(hex) {
  const n = Number.parseInt(hex.slice(1), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

async function encode(canvas) {
  const blob = await canvas.convertToBlob({ type: 'image/jpeg', quality: JPEG_QUALITY });
  return new Blob([stripJpeg(await blob.arrayBuffer())], { type: 'image/jpeg' });
}

async function processPhoto(file, color) {
  // Decode pixels only. Orientation is applied, then every byte of the
  // original container (EXIF, GPS, XMP, IPTC, ICC, MakerNotes, thumbnails,
  // Samsung motion-photo video) is discarded with it.
  let bitmap;
  try {
    bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' });
  } catch {
    throw new Error('FORMAT');
  }
  const scale = Math.min(1, MAX_EDGE / Math.max(bitmap.width, bitmap.height));
  const width = Math.round(bitmap.width * scale);
  const height = Math.round(bitmap.height * scale);

  const canvas = new OffscreenCanvas(width, height);
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(bitmap, 0, 0, width, height);
  bitmap.close();
  const image = ctx.getImageData(0, 0, width, height);

  const plain = await encode(canvas);

  const s = await session();
  const input = new ort.Tensor('float32', toInput(image.data, width, height), [1, 3, MODEL_SIZE, MODEL_SIZE]);
  const output = await s.run({ [s.inputNames[0]]: input });
  const pred = output[s.outputNames[0]].data;
  const mask = postProcess(toMask(pred, width, height), width, height);

  if (!maskLooksValid(mask, width, height)) return { plain, edited: null };

  const [br, bg, bb] = parseHex(color);
  const px = image.data;
  for (let i = 0, p = 0; i < mask.length; i += 1, p += 4) {
    if (mask[i] === 0) {
      px[p] = br;
      px[p + 1] = bg;
      px[p + 2] = bb;
    }
  }
  ctx.putImageData(image, 0, 0);
  return { plain, edited: await encode(canvas) };
}

self.onmessage = async ({ data }) => {
  const { id, type } = data;
  try {
    if (type === 'preload') {
      await session();
      self.postMessage({ id, ok: true });
      return;
    }
    const result = await processPhoto(data.file, data.color);
    self.postMessage({ id, ok: true, result });
  } catch (error) {
    self.postMessage({ id, ok: false, error: String(error?.message ?? error) });
  }
};
