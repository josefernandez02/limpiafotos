// Rebuilds a JPEG keeping only what is needed to display it: SOI, the JFIF
// header (APP0) and the image segments. Every APP1–APP15 block (EXIF, XMP,
// ICC, MPF, vendor data) and every COM comment is dropped, as is anything
// after the end-of-image marker.
export function stripJpeg(bytes) {
  const src = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  if (src[0] !== 0xff || src[1] !== 0xd8) throw new Error('NOT_JPEG');

  const parts = [src.subarray(0, 2)];
  let i = 2;
  while (i + 4 <= src.length) {
    if (src[i] !== 0xff) throw new Error('BAD_JPEG');
    const marker = src[i + 1];
    if (marker === 0xda) {
      // Start of scan: copy entropy data up to and including EOI, drop the rest.
      let end = src.length - 1;
      while (end > i && !(src[end - 1] === 0xff && src[end] === 0xd9)) end -= 1;
      if (end <= i) throw new Error('BAD_JPEG');
      parts.push(src.subarray(i, end + 1));
      break;
    }
    const length = (src[i + 2] << 8) | src[i + 3];
    const segment = src.subarray(i, i + 2 + length);
    const isMetadata = (marker >= 0xe1 && marker <= 0xef) || marker === 0xfe;
    if (!isMetadata) parts.push(segment);
    i += 2 + length;
  }

  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let o = 0;
  for (const p of parts) { out.set(p, o); o += p.length; }
  return out;
}
