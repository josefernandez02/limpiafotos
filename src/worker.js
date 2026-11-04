// Runs entirely on the device. The original file is decoded to pixels and a
// brand-new JPEG is written at full resolution; nothing from the original
// container (EXIF, GPS, XMP, ICC, MakerNotes, thumbnails, motion-photo video,
// camera quantization tables) survives.
import { stripJpeg } from './strip.js';

// Only guards against extreme modes (e.g. 200 MP) that would exhaust memory.
const MAX_EDGE = 8192;
const JPEG_QUALITY = 0.95;

async function processPhoto(file) {
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
  const ctx = canvas.getContext('2d');
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(bitmap, 0, 0, width, height);
  bitmap.close();

  const blob = await canvas.convertToBlob({ type: 'image/jpeg', quality: JPEG_QUALITY });
  return new Blob([stripJpeg(await blob.arrayBuffer())], { type: 'image/jpeg' });
}

self.onmessage = async ({ data }) => {
  try {
    self.postMessage({ id: data.id, ok: true, result: await processPhoto(data.file) });
  } catch (error) {
    self.postMessage({ id: data.id, ok: false, error: String(error?.message ?? error) });
  }
};
