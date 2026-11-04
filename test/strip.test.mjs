import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripJpeg } from '../src/strip.js';

const input = readFileSync(new URL('./fixture.jpg', import.meta.url));
const text = (b) => Buffer.from(b).toString('latin1');

function markers(b) {
  const found = [];
  let i = 2;
  while (i < b.length && b[i + 1] !== 0xda) {
    found.push(b[i + 1]);
    i += 2 + ((b[i + 2] << 8) | b[i + 3]);
  }
  return found;
}

test('fixture really carries metadata', () => {
  for (const s of ['TEST-MODEL', 'TEST-XMP', 'TEST-COMMENT', 'TEST-TRAILER', 'Exif']) assert.ok(text(input).includes(s), s);
});

test('removes every metadata block and trailing data', () => {
  const out = stripJpeg(input);
  const s = text(out);
  for (const leak of ['TEST-MODEL', 'TEST-XMP', 'TEST-COMMENT', 'TEST-TRAILER', 'Exif', 'ICC_PROFILE', 'GPS']) {
    assert.ok(!s.includes(leak), `leaked ${leak}`);
  }
  for (const m of markers(out)) assert.ok(!((m >= 0xe1 && m <= 0xef) || m === 0xfe), `marker 0x${m.toString(16)}`);
  assert.deepEqual([out[0], out[1]], [0xff, 0xd8]);
  assert.deepEqual([out.at(-2), out.at(-1)], [0xff, 0xd9]);
});

test('rejects non-JPEG input', () => {
  assert.throws(() => stripJpeg(new Uint8Array([0x89, 0x50, 0x4e, 0x47])), /NOT_JPEG/);
});
