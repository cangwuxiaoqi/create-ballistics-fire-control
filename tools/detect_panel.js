/*
 * Detect the amber-outlined button wells inside a panel texture.
 *
 * The panel art is a clean background: a dark plate with amber rectangles that
 * mark where buttons go. Rather than eyeballing coordinates, decode the PNG and
 * report the bounding box of every distinct amber rectangle, so the Screen
 * classes can place their widgets exactly on the art.
 *
 * Usage: node detect_panel.js <panel.png> [...]
 */
const fs = require('fs');
const zlib = require('zlib');

function loadPng(path) {
  const data = fs.readFileSync(path);
  if (data.readUInt32BE(0) !== 0x89504e47) throw new Error('not a png');
  let pos = 8, width = 0, height = 0, bitDepth = 0, colorType = 0;
  const idat = [];
  while (pos < data.length) {
    const len = data.readUInt32BE(pos);
    const type = data.toString('ascii', pos + 4, pos + 8);
    const body = data.subarray(pos + 8, pos + 8 + len);
    if (type === 'IHDR') {
      width = body.readUInt32BE(0);
      height = body.readUInt32BE(4);
      bitDepth = body[8];
      colorType = body[9];
    } else if (type === 'IDAT') {
      idat.push(body);
    } else if (type === 'IEND') break;
    pos += 12 + len;
  }
  if (bitDepth !== 8 || (colorType !== 6 && colorType !== 2)) {
    throw new Error(`unsupported png: depth=${bitDepth} color=${colorType}`);
  }
  const bpp = colorType === 6 ? 4 : 3;
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = width * bpp;
  const out = Buffer.alloc(width * height * bpp);
  let prev = Buffer.alloc(stride);
  let p = 0;
  for (let y = 0; y < height; y++) {
    const filter = raw[p++];
    const line = Buffer.from(raw.subarray(p, p + stride));
    p += stride;
    for (let x = 0; x < stride; x++) {
      const a = x >= bpp ? line[x - bpp] : 0;
      const b = prev[x];
      const c = x >= bpp ? prev[x - bpp] : 0;
      let v = line[x];
      if (filter === 1) v = (v + a) & 0xff;
      else if (filter === 2) v = (v + b) & 0xff;
      else if (filter === 3) v = (v + ((a + b) >> 1)) & 0xff;
      else if (filter === 4) {
        const pa = Math.abs(b - c), pb = Math.abs(a - c), pc = Math.abs(a + b - 2 * c);
        const pr = (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c);
        v = (v + pr) & 0xff;
      }
      line[x] = v;
    }
    line.copy(out, y * stride);
    prev = line;
  }
  return { width, height, bpp, pix: out };
}

// amber-ish: clearly reddish-yellow, distinctly brighter than blue
function isAmber(r, g, b) {
  return r > 120 && r > b + 40 && g > b;
}

function analyse(path) {
  const { width, height, bpp, pix } = loadPng(path);
  const at = (x, y) => {
    const i = (y * width + x) * bpp;
    return [pix[i], pix[i + 1], pix[i + 2]];
  };
  const amberRows = new Map();
  for (let y = 0; y < height; y++) {
    const xs = [];
    for (let x = 0; x < width; x++) {
      const [r, g, b] = at(x, y);
      if (isAmber(r, g, b)) xs.push(x);
    }
    if (xs.length) amberRows.set(y, xs);
  }
  console.log(`${path}\n  size ${width}x${height}, amber rows: ${amberRows.size}`);
  const rows = [...amberRows.keys()].sort((a, b) => a - b);
  const bands = [];
  for (const y of rows) {
    if (bands.length && y - bands[bands.length - 1][bands[bands.length - 1].length - 1] <= 2) {
      bands[bands.length - 1].push(y);
    } else bands.push([y]);
  }
  for (const band of bands) {
    const xs = new Set();
    for (const y of band) for (const x of amberRows.get(y)) xs.add(x);
    const sorted = [...xs].sort((a, b) => a - b);
    const runs = [];
    for (const x of sorted) {
      if (runs.length && x - runs[runs.length - 1][runs[runs.length - 1].length - 1] <= 8) {
        runs[runs.length - 1].push(x);
      } else runs.push([x]);
    }
    for (const run of runs) {
      const w = run[run.length - 1] - run[0] + 1;
      const h = band[band.length - 1] - band[0] + 1;
      console.log(`    well  x=${run[0]}..${run[run.length - 1]}  y=${band[0]}..${band[band.length - 1]}`
        + `   ->  pos (${run[0]}, ${band[0]})  size ${w}x${h}`);
    }
  }
}

for (const p of process.argv.slice(2)) analyse(p);
