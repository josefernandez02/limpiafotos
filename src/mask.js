// Pure image helpers that mirror rembg 2.0.67 (u2net + post_process_mask),
// the pipeline Jarvis uses for its covers. No DOM access: testable in Node.

const SIZE = 320;
const MEAN = [0.485, 0.456, 0.406];
const STD = [0.229, 0.224, 0.225];

function lanczos(x) {
  if (x === 0) return 1;
  if (x <= -3 || x >= 3) return 0;
  const px = Math.PI * x;
  return (3 * Math.sin(px) * Math.sin(px / 3)) / (px * px);
}

// Antialiased Lanczos-3 weights for one axis, as Pillow computes them.
function weights(inSize, outSize) {
  const scale = inSize / outSize;
  const filterScale = Math.max(scale, 1);
  const support = 3 * filterScale;
  const result = [];
  for (let i = 0; i < outSize; i += 1) {
    const center = (i + 0.5) * scale;
    const min = Math.max(0, Math.floor(center - support + 0.5));
    const max = Math.min(inSize, Math.floor(center + support + 0.5));
    const w = new Float32Array(max - min);
    let sum = 0;
    for (let j = min; j < max; j += 1) {
      const v = lanczos((j - center + 0.5) / filterScale);
      w[j - min] = v;
      sum += v;
    }
    if (sum !== 0) for (let k = 0; k < w.length; k += 1) w[k] /= sum;
    result.push({ min, w });
  }
  return result;
}

const clamp8 = (v) => (v < 0 ? 0 : v > 255 ? 255 : Math.round(v));

// Separable resize of an interleaved uint8 image (horizontal pass, then
// vertical, rounding to uint8 in between like Pillow).
export function resize(src, sw, sh, channels, stride, dw, dh) {
  const hw = weights(sw, dw);
  const tmp = new Uint8ClampedArray(dw * sh * channels);
  for (let y = 0; y < sh; y += 1) {
    for (let x = 0; x < dw; x += 1) {
      const { min, w } = hw[x];
      for (let c = 0; c < channels; c += 1) {
        let acc = 0;
        for (let k = 0; k < w.length; k += 1) acc += w[k] * src[(y * sw + min + k) * stride + c];
        tmp[(y * dw + x) * channels + c] = clamp8(acc);
      }
    }
  }
  const vw = weights(sh, dh);
  const out = new Uint8ClampedArray(dw * dh * channels);
  for (let y = 0; y < dh; y += 1) {
    const { min, w } = vw[y];
    for (let x = 0; x < dw; x += 1) {
      for (let c = 0; c < channels; c += 1) {
        let acc = 0;
        for (let k = 0; k < w.length; k += 1) acc += w[k] * tmp[((min + k) * dw + x) * channels + c];
        out[(y * dw + x) * channels + c] = clamp8(acc);
      }
    }
  }
  return out;
}

// RGBA pixels -> normalized 1x3x320x320 float tensor data.
export function toInput(rgba, width, height) {
  const small = resize(rgba, width, height, 3, 4, SIZE, SIZE);
  let max = 0;
  for (let i = 0; i < small.length; i += 1) if (small[i] > max) max = small[i];
  max = Math.max(max / 255, 1e-6);
  const plane = SIZE * SIZE;
  const data = new Float32Array(3 * plane);
  for (let i = 0; i < plane; i += 1) {
    for (let c = 0; c < 3; c += 1) {
      data[c * plane + i] = (small[i * 3 + c] / 255 / max - MEAN[c]) / STD[c];
    }
  }
  return data;
}

// Raw model output (first 320x320 plane) -> uint8 mask at full size.
export function toMask(pred, width, height) {
  let mi = Infinity;
  let ma = -Infinity;
  for (let i = 0; i < SIZE * SIZE; i += 1) {
    if (pred[i] < mi) mi = pred[i];
    if (pred[i] > ma) ma = pred[i];
  }
  const range = ma - mi || 1;
  const small = new Uint8ClampedArray(SIZE * SIZE);
  for (let i = 0; i < small.length; i += 1) {
    const v = (pred[i] - mi) / range;
    small[i] = Math.floor((v < 0 ? 0 : v > 1 ? 1 : v) * 255);
  }
  return resize(small, SIZE, SIZE, 1, 1, width, height);
}

// OpenCV MORPH_OPEN with a 3x3 ellipse (a cross), then GaussianBlur 5x5
// sigma 2 (BORDER_REFLECT_101), then threshold at 127.
export function postProcess(mask, width, height) {
  const at = (a, x, y) => a[y * width + x];
  const morph = (src, pick) => {
    const out = new Uint8Array(src.length);
    for (let y = 0; y < height; y += 1) {
      for (let x = 0; x < width; x += 1) {
        let v = at(src, x, y);
        if (x > 0) v = pick(v, at(src, x - 1, y));
        if (x < width - 1) v = pick(v, at(src, x + 1, y));
        if (y > 0) v = pick(v, at(src, x, y - 1));
        if (y < height - 1) v = pick(v, at(src, x, y + 1));
        out[y * width + x] = v;
      }
    }
    return out;
  };
  const opened = morph(morph(mask, Math.min), Math.max);

  const k = [-2, -1, 0, 1, 2].map((d) => Math.exp(-(d * d) / 8));
  const ks = k.reduce((a, b) => a + b, 0);
  for (let i = 0; i < 5; i += 1) k[i] /= ks;
  const reflect = (i, n) => (n === 1 ? 0 : i < 0 ? -i : i >= n ? 2 * n - 2 - i : i);

  const h = new Float32Array(opened.length);
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      let acc = 0;
      for (let d = -2; d <= 2; d += 1) acc += k[d + 2] * opened[y * width + reflect(x + d, width)];
      h[y * width + x] = acc;
    }
  }
  const out = new Uint8Array(opened.length);
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      let acc = 0;
      for (let d = -2; d <= 2; d += 1) acc += k[d + 2] * h[reflect(y + d, height) * width + x];
      out[y * width + x] = Math.round(acc) < 127 ? 0 : 255;
    }
  }
  return out;
}

export const MODEL_SIZE = SIZE;
