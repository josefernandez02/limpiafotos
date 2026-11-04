import { test } from 'node:test';
import assert from 'node:assert/strict';
import { postProcess, maskLooksValid, resize, toMask } from '../src/mask.js';

const W = 64;
const H = 64;
const blank = () => new Uint8Array(W * H);

test('post-process removes isolated specks and binarizes', () => {
  const m = blank();
  m[10 * W + 10] = 255; // lone speck
  for (let y = 20; y < 50; y += 1) for (let x = 20; x < 50; x += 1) m[y * W + x] = 200;
  const out = postProcess(m, W, H);
  assert.equal(out[10 * W + 10], 0);
  assert.equal(out[35 * W + 35], 255);
  assert.ok(out.every((v) => v === 0 || v === 255));
});

test('guard rejects empty and full masks, accepts a centered item', () => {
  assert.equal(maskLooksValid(blank(), W, H), false);
  assert.equal(maskLooksValid(new Uint8Array(W * H).fill(255), W, H), false);
  const m = blank();
  for (let y = 16; y < 48; y += 1) for (let x = 16; x < 48; x += 1) m[y * W + x] = 255;
  assert.equal(maskLooksValid(m, W, H), true);
});

test('resize keeps a flat image flat', () => {
  const src = new Uint8ClampedArray(10 * 10 * 4).fill(128);
  const out = resize(src, 10, 10, 3, 4, 7, 13);
  assert.equal(out.length, 7 * 13 * 3);
  assert.ok(out.every((v) => v === 128));
});

test('model output is normalized to the full 0..255 range', () => {
  const pred = new Float32Array(320 * 320).fill(0.2);
  pred[0] = 0.9;
  const mask = toMask(pred, 320, 320);
  assert.equal(mask[0], 255);
  assert.equal(mask[320 * 160 + 160], 0);
});
