// Derives the transparent Zen Mode mark from the opaque source render.
//
// `icon.png` is a 1024px product render on a solid near-black plate. That plate
// is a different green-black than the app background, so it shows as a square
// behind the splash logo, and the header downscaled it 28x at runtime, which
// frayed the edges. This script keys out the plate, trims to the mark, and
// writes exact-size header rasters so nothing is resampled on device.
//
// Run: node scripts/build-logo.mjs

import { readFileSync, writeFileSync } from 'node:fs';
import { deflateSync, inflateSync } from 'node:zlib';

const SOURCE = 'assets/images/refuge/icon.png';
const MASTER = 'assets/images/refuge/mark.png';
/** Header renders the mark in a 36pt box; ship 1x/2x/3x so RN never resamples. */
const HEADER = [
  ['assets/images/refuge/mark-header.png', 36],
  ['assets/images/refuge/mark-header@2x.png', 72],
  ['assets/images/refuge/mark-header@3x.png', 108],
];
/** The plate sits at luminance ~10; the darkest part of the stone is ~60. */
const PLATE_MAX = 20;
const EDGE_MAX = 48;

const luma = (r, g, b) => 0.2126 * r + 0.7152 * g + 0.0722 * b;

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
    crc.writeUInt32BE(crc32(Buffer.concat([head.subarray(4), body])) >>> 0, 0);
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

const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});

function crc32(buffer) {
  let c = 0xffffffff;
  for (const byte of buffer) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
  return c ^ 0xffffffff;
}

/**
 * Flood fills the plate inward from the border so dark pixels enclosed by the
 * stone stay opaque, then softens the one-pixel render fringe into real alpha.
 */
function keyPlate({ width, height, data }) {
  const plate = new Uint8Array(width * height);
  const stack = [];
  const push = (x, y) => {
    const i = y * width + x;
    if (plate[i] || luma(data[i * 4], data[i * 4 + 1], data[i * 4 + 2]) > PLATE_MAX) return;
    plate[i] = 1;
    stack.push(i);
  };
  for (let x = 0; x < width; x++) {
    push(x, 0);
    push(x, height - 1);
  }
  for (let y = 0; y < height; y++) {
    push(0, y);
    push(width - 1, y);
  }
  while (stack.length) {
    const i = stack.pop();
    const x = i % width;
    const y = (i - x) / width;
    if (x > 0) push(x - 1, y);
    if (x < width - 1) push(x + 1, y);
    if (y > 0) push(x, y - 1);
    if (y < height - 1) push(x, y + 1);
  }

  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const i = y * width + x;
      if (plate[i]) {
        data[i * 4 + 3] = 0;
        continue;
      }
      const nearPlate =
        (x > 0 && plate[i - 1]) ||
        (x < width - 1 && plate[i + 1]) ||
        (y > 0 && plate[i - width]) ||
        (y < height - 1 && plate[i + width]);
      if (!nearPlate) continue;
      const l = luma(data[i * 4], data[i * 4 + 1], data[i * 4 + 2]);
      const ramp = (l - PLATE_MAX) / (EDGE_MAX - PLATE_MAX);
      data[i * 4 + 3] = Math.round(255 * Math.min(1, Math.max(0, ramp)));
    }
  }
}

function trim({ width, height, data }) {
  let minX = width;
  let minY = height;
  let maxX = -1;
  let maxY = -1;
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      if (data[(y * width + x) * 4 + 3] === 0) continue;
      if (x < minX) minX = x;
      if (x > maxX) maxX = x;
      if (y < minY) minY = y;
      if (y > maxY) maxY = y;
    }
  }
  const w = maxX - minX + 1;
  const h = maxY - minY + 1;
  const out = Buffer.alloc(w * h * 4);
  for (let y = 0; y < h; y++) {
    data.copy(out, y * w * 4, ((y + minY) * width + minX) * 4, ((y + minY) * width + maxX + 1) * 4);
  }
  return { width: w, height: h, data: out };
}

/** Pads to a square so the header box and the splash keep the mark centered. */
function square({ width, height, data }) {
  const size = Math.max(width, height);
  const offsetX = (size - width) >> 1;
  const offsetY = (size - height) >> 1;
  const out = Buffer.alloc(size * size * 4);
  for (let y = 0; y < height; y++) {
    data.copy(out, ((y + offsetY) * size + offsetX) * 4, y * width * 4, (y + 1) * width * 4);
  }
  return { width: size, height: size, data: out };
}

/** Box filter over premultiplied color so transparent pixels add no dark fringe. */
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
      let a = 0;
      let count = 0;
      for (let sy = y0; sy < y1; sy++) {
        for (let sx = x0; sx < x1; sx++) {
          const i = (sy * width + sx) * 4;
          const alpha = data[i + 3] / 255;
          r += data[i] * alpha;
          g += data[i + 1] * alpha;
          b += data[i + 2] * alpha;
          a += alpha;
          count++;
        }
      }
      const to = (y * size + x) * 4;
      out[to] = a > 0 ? Math.round(r / a) : 0;
      out[to + 1] = a > 0 ? Math.round(g / a) : 0;
      out[to + 2] = a > 0 ? Math.round(b / a) : 0;
      out[to + 3] = Math.round((a / count) * 255);
    }
  }
  return { width: size, height: size, data: out };
}

const source = decodePng(readFileSync(SOURCE));
keyPlate(source);
const master = square(trim(source));
writeFileSync(MASTER, encodePng(master));
console.log(`${MASTER} ${master.width}x${master.height}`);
for (const [path, size] of HEADER) {
  writeFileSync(path, encodePng(resize(master, size)));
  console.log(`${path} ${size}x${size}`);
}
