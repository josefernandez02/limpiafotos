// Runs entirely on the device. No photo ever leaves this worker except as the
// final, freshly encoded JPEG returned to the page.
import { segmentForeground, preload } from '@imgly/background-removal';
import { stripJpeg } from './strip.js';

const MAX_EDGE = 2048;
const JPEG_QUALITY = 0.9;

const config = {
  publicPath: new URL('./ai/', self.location.href).href,
  model: 'medium',
  proxyToWorker: false,
  output: { format: 'image/x-alpha8' },
};

function parseHex(hex) {
  const n = Number.parseInt(hex.slice(1), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
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
  const raw = new Blob([image.data], {
    type: `image/x-rgba8;width=${width};height=${height}`,
  });
  const maskBlob = await segmentForeground(raw, config);
  const mask = new Uint8Array(await maskBlob.arrayBuffer());
  if (mask.length !== width * height) throw new Error('MASK');

  const [br, bg, bb] = parseHex(color);
  const px = image.data;
  for (let i = 0, p = 0; i < mask.length; i += 1, p += 4) {
    const a = mask[i] / 255;
    px[p] = px[p] * a + br * (1 - a);
    px[p + 1] = px[p + 1] * a + bg * (1 - a);
    px[p + 2] = px[p + 2] * a + bb * (1 - a);
    px[p + 3] = 255;
  }
  ctx.putImageData(image, 0, 0);

  // A brand-new JPEG, then stripped of every non-image block the encoder adds.
  const encoded = await canvas.convertToBlob({ type: 'image/jpeg', quality: JPEG_QUALITY });
  const clean = stripJpeg(await encoded.arrayBuffer());
  return new Blob([clean], { type: 'image/jpeg' });
}

self.onmessage = async ({ data }) => {
  const { id, type } = data;
  try {
    if (type === 'preload') {
      await preload(config);
      self.postMessage({ id, ok: true });
      return;
    }
    const blob = await processPhoto(data.file, data.color);
    self.postMessage({ id, ok: true, blob });
  } catch (error) {
    self.postMessage({ id, ok: false, error: String(error?.message ?? error) });
  }
};
