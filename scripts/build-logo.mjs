// Derives the Zen Mode splash and header art from the opaque source render.
//
// `icon.png` is a 1024px product render standing on a solid plate of
// rgb(8, 12, 9). The app draws it on `night`, rgb(13, 18, 16), so the plate
// showed as a square behind the splash logo, and the header additionally
// clipped that square and rescaled it 28x at run time, which frayed the edges.
//
// The plate cannot be cut to alpha. In the stone's lower-left base the render
// falls to luminance 4-20 against a plate of 10-11, so parts of the stone are
// darker than the plate and are directly connected to it. Every threshold
// either leaks into the stone and eats the flare, or leaves plate residue.
//
// So we never decide. We re-map the render's black point from the plate colour
// to `night` and keep the art opaque. The plate becomes the background, and the
// stone's base fades into it exactly as rendered.
//
// Run: node scripts/build-logo.mjs

import { readFileSync, writeFileSync } from 'node:fs';
import { deflateSync, inflateSync } from 'node:zlib';

const SOURCE = 'assets/images/refuge/icon.png';
const MASTER = 'assets/images/refuge/mark.png';
/** Header draws the mark in a 36pt box; ship 1x/2x/3x so RN never resamples. */
const HEADER = [
  ['assets/images/refuge/mark-header.png', 36],
  ['assets/images/refuge/mark-header@2x.png', 72],
  ['assets/images/refuge/mark-header@3x.png', 108],
];
/** Measured plate colour of the source render, and `night` from palette.json. */
const PLATE = [8, 12, 9];
const NIGHT = [13, 18, 16];
/** Above this luminance the render carries real detail; leave it untouched. */
const REMAP_CEILING = 40;
/** Luminance that is unambiguously stone, used only to find the crop box. */
const STONE_FLOOR = 45;
/** Breathing room around the stone, as a fraction of its longest side. */
const MARGIN = 0.06;

const luma = (r, g, b) => 0.2126 * r + 0.7152 * g + 0.0722 * b;
const PLATE_LUMA = luma(...PLATE);

function decodePng(buffer) {
  let offset = 8;
  const idat = [];
  let width = 0;
  let height = 0;
  let colorType = 0;
  while (offset < buffer.length) {
    const length = buffer.readUInt32BE(offset);
    const type = buffer.toString('ascii', offset + 4, offset + 8);
    const body = buffer.subarray(offset + 8, offset + 8 + length);
    if (type === 'IHDR') {
      width = body.readUInt32BE(0);
      height = body.readUInt32BE(4);
      colorType = body[9];
      if (body[8] !== 8 || body[12] !== 0) throw new Error('expected 8-bit non-interlaced PNG');
      if (colorType !== 2 && colorType !== 6) throw new Error(`unsupported color type ${colorType}`);
    } else if (type === 'IDAT') idat.push(body);
    else if (type === 'IEND') break;
    offset += length + 12;
  }

  const channels = colorType === 6 ? 4 : 3;
  const raw = inflateSync(Buffer.concat(idat));
  const stride = width * channels;
  const out = Buffer.alloc(width * height * 4);
  let previous = Buffer.alloc(stride);
  for (let y = 0; y < height; y++) {
    const filter = raw[y * (stride + 1)];
    const line = Buffer.from(raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1)));
    for (let x = 0; x < stride; x++) {
      const a = x >= channels ? line[x - channels] : 0;
      const b = previous[x];
      const c = x >= channels ? previous[x - channels] : 0;
      if (filter === 1) line[x] = (line[x] + a) & 0xff;
      else if (filter === 2) line[x] = (line[x] + b) & 0xff;
      else if (filter === 3) line[x] = (line[x] + ((a + b) >> 1)) & 0xff;
      else if (filter === 4) {
        const p = a + b - c;
        const pa = Math.abs(p - a);
        const pb = Math.abs(p - b);
        const pc = Math.abs(p - c);
        line[x] = (line[x] + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c)) & 0xff;
      } else if (filter !== 0) throw new Error(`unknown filter ${filter}`);
    }
    for (let x = 0; x < width; x++) {
      const from = x * channels;
      const to = (y * width + x) * 4;
      out[to] = line[from];
      out[to + 1] = line[from + 1];
      out[to + 2] = line[from + 2];
      out[to + 3] = channels === 4 ? line[from + 3] : 255;
    }
    previous = line;
  }
  return { width, height, data: out };
}

const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});

function crc32(buffer) {
  let c = 0xffffffff;
  for (const byte of buffer) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function encodePng({ width, height, data }) {
  const stride = width * 4;
  const raw = Buffer.alloc((stride + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (stride + 1)] = 0;
    data.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride);
  }
  const chunk = (type, body) => {
    const head = Buffer.alloc(8);
    head.writeUInt32BE(body.length, 0);
    head.write(type, 4, 'ascii');
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(Buffer.concat([head.subarray(4), body])), 0);
    return Buffer.concat([head, body, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

/**
 * Slides the plate colour onto `night`. The shift is full strength at plate
 * luminance and tapers to nothing by REMAP_CEILING, so the stone body and the
 * lit cave keep their rendered colour.
 */
function remapBlackPoint({ width, height, data }) {
  for (let i = 0; i < width * height; i++) {
    const at = i * 4;
    const l = luma(data[at], data[at + 1], data[at + 2]);
    const taper = Math.min(1, Math.max(0, 1 - (l - PLATE_LUMA) / (REMAP_CEILING - PLATE_LUMA)));
    for (let c = 0; c < 3; c++) {
      const shifted = data[at + c] + (NIGHT[c] - PLATE[c]) * taper;
      data[at + c] = Math.min(255, Math.max(0, Math.round(shifted)));
    }
    data[at + 3] = 255;
  }
}

/**
 * Crops to the stone plus a margin. Only clearly lit pixels vote, so the noisy
 * dark base cannot drag the box outward, and the margin puts its falloff back.
 */
function crop({ width, height, data }) {
  let minX = width;
  let minY = height;
  let maxX = -1;
  let maxY = -1;
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const at = (y * width + x) * 4;
      if (luma(data[at], data[at + 1], data[at + 2]) < STONE_FLOOR) continue;
      if (x < minX) minX = x;
      if (x > maxX) maxX = x;
      if (y < minY) minY = y;
      if (y > maxY) maxY = y;
    }
  }
  const pad = Math.round(Math.max(maxX - minX, maxY - minY) * MARGIN);
  // Square the box around the stone so the header and splash stay centred.
  const cx = (minX + maxX) / 2;
  const cy = (minY + maxY) / 2;
  const half = Math.max(maxX - minX, maxY - minY) / 2 + pad;
  const x0 = Math.max(0, Math.round(cx - half));
  const y0 = Math.max(0, Math.round(cy - half));
  const x1 = Math.min(width - 1, Math.round(cx + half));
  const y1 = Math.min(height - 1, Math.round(cy + half));
  const w = x1 - x0 + 1;
  const h = y1 - y0 + 1;
  const out = Buffer.alloc(w * h * 4);
  for (let y = 0; y < h; y++) {
    data.copy(out, y * w * 4, ((y + y0) * width + x0) * 4, ((y + y0) * width + x1 + 1) * 4);
  }
  return { width: w, height: h, data: out };
}

/** Box filter down to an exact output size. */
function resize({ width, height, data }, size) {
  const out = Buffer.alloc(size * size * 4);
  for (let y = 0; y < size; y++) {
    const y0 = Math.floor((y * height) / size);
    const y1 = Math.max(y0 + 1, Math.floor(((y + 1) * height) / size));
    for (let x = 0; x < size; x++) {
      const x0 = Math.floor((x * width) / size);
      const x1 = Math.max(x0 + 1, Math.floor(((x + 1) * width) / size));
      let r = 0;
      let g = 0;
      let b = 0;
      let count = 0;
      for (let sy = y0; sy < y1; sy++) {
        for (let sx = x0; sx < x1; sx++) {
          const i = (sy * width + sx) * 4;
          r += data[i];
          g += data[i + 1];
          b += data[i + 2];
          count++;
        }
      }
      const to = (y * size + x) * 4;
      out[to] = Math.round(r / count);
      out[to + 1] = Math.round(g / count);
      out[to + 2] = Math.round(b / count);
      out[to + 3] = 255;
    }
  }
  return { width: size, height: size, data: out };
}

const source = decodePng(readFileSync(SOURCE));
remapBlackPoint(source);
const master = crop(source);
writeFileSync(MASTER, encodePng(master));
console.log(`${MASTER} ${master.width}x${master.height}`);
for (const [path, size] of HEADER) {
  writeFileSync(path, encodePng(resize(master, size)));
  console.log(`${path} ${size}x${size}`);
}
