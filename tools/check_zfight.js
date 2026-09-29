/*
 * Guard against the "一闪一闪的黑" bug.
 *
 * Two opaque quads on exactly the same plane whose rectangles overlap will
 * z-fight: the depth test flips between them per pixel per frame, which the
 * player sees as black flickering while the camera moves.
 *
 * The naive "same plane + overlap" test is useless in practice - a model built
 * from abutting solid boxes is FULL of such pairs, but they are harmless because
 * both quads are buried inside neighbouring solid geometry. So this tool only
 * reports a pair when BOTH quads are actually exposed: for each one it samples a
 * point just outside the face at the centre of the overlap and asks whether any
 * other element's volume strictly contains that point. If either is buried, the
 * pair is skipped.
 *
 * The optional --box adds a virtual solid box standing in for geometry a renderer
 * draws at runtime (e.g. Create's SHAFT_HALF). Use it to answer "would the shaft
 * the renderer draws z-fight with my model?".
 *
 * Usage:
 *   node check_zfight.js <model.json> [...]
 *   node check_zfight.js --box=6,6,0,10,10,8 <model.json> [...]
 */
const fs = require('fs');

const FACES = {
  north: { axis: 'z', sign: -1, u: 'x', v: 'y' },
  south: { axis: 'z', sign: +1, u: 'x', v: 'y' },
  west: { axis: 'x', sign: -1, u: 'z', v: 'y' },
  east: { axis: 'x', sign: +1, u: 'z', v: 'y' },
  down: { axis: 'y', sign: -1, u: 'x', v: 'z' },
  up: { axis: 'y', sign: +1, u: 'x', v: 'z' },
};
const IDX = { x: 0, y: 1, z: 2 };

function volumeOf(el) {
  const a = el.from || [0, 0, 0];
  const b = el.to || [16, 16, 16];
  return [0, 1, 2].map(i => [Math.min(a[i], b[i]), Math.max(a[i], b[i])]);
}

function contains(vol, p) {
  const eps = 1e-3;
  return p.every((c, i) => c > vol[i][0] + eps && c < vol[i][1] - eps);
}

// One record per face, carrying the volume of its own element so we can skip it.
function collectFaces(elements, tag) {
  const out = [];
  elements.forEach((el, i) => {
    const vol = volumeOf(el);
    const name = el.name || `element[${i}]`;
    for (const [face, spec] of Object.entries(FACES)) {
      if (!el.faces || !el.faces[face]) continue;
      const a = IDX[spec.axis], u = IDX[spec.u], v = IDX[spec.v];
      out.push({
        label: `${tag}${name} ${face}`.trim(),
        axis: spec.axis, axisIdx: a, sign: spec.sign,
        coord: spec.sign < 0 ? vol[a][0] : vol[a][1],
        uIdx: u, vIdx: v,
        u0: vol[u][0], u1: vol[u][1], v0: vol[v][0], v1: vol[v][1],
        ownVol: vol,
      });
    }
  });
  return out;
}

function overlapOf(f, g) {
  const du = Math.min(f.u1, g.u1) - Math.max(f.u0, g.u0);
  const dv = Math.min(f.v1, g.v1) - Math.max(f.v0, g.v0);
  return du > 1e-6 && dv > 1e-6 ? { du, dv, uMid: (Math.max(f.u0, g.u0) + Math.min(f.u1, g.u1)) / 2, vMid: (Math.max(f.v0, g.v0) + Math.min(f.v1, g.v1)) / 2 } : null;
}

// Is the face buried? Sample a point just outside it, at the overlap centre.
function buried(face, ov, allElements) {
  const p = [0, 0, 0];
  p[face.axisIdx] = face.coord + face.sign * 0.01;
  p[face.uIdx] = ov.uMid;
  p[face.vIdx] = ov.vMid;
  return allElements.some(el => contains(volumeOf(el), p));
}

const args = process.argv.slice(2);
let box = null;
const files = [];
for (const a of args) {
  const m = a.match(/^--box=(.+)$/);
  if (m) {
    const n = m[1].split(',').map(Number);
    box = { name: 'GHOST', from: n.slice(0, 3), to: n.slice(3, 6), faces: Object.fromEntries(Object.keys(FACES).map(f => [f, {}])) };
  } else {
    files.push(a);
  }
}

let problems = 0;
for (const path of files) {
  const model = JSON.parse(fs.readFileSync(path, 'utf8'));
  let elements = model.elements || [];
  if (box) elements = elements.concat([box]);

  const faces = collectFaces(elements, '');
  const hits = [];
  for (let i = 0; i < faces.length; i++) {
    for (let j = i + 1; j < faces.length; j++) {
      const f = faces[i], g = faces[j];
      if (f.axis !== g.axis || Math.abs(f.coord - g.coord) > 1e-6) continue;
      const ov = overlapOf(f, g);
      if (!ov) continue;
      if (buried(f, ov, elements) || buried(g, ov, elements)) continue;   // 埋起来的接缝，无害
      hits.push(`  [共面打架] ${f.axis}=${f.coord}  ${f.label}  ×  ${g.label}  (重叠 ${ov.du}×${ov.dv})`);
    }
  }
  console.log(`${path}  ->  ${(model.elements || []).length} 个元素${box ? ' + 一个虚拟盒' : ''}，${hits.length} 处可见共面`);
  hits.forEach(h => console.log(h));
  problems += hits.length;
}

console.log('');
if (problems === 0) {
  console.log('NO VISIBLE COPLANAR OVERLAP - 没有会 z-fighting 的可见共面几何');
} else {
  console.log(`${problems} 处可见共面重叠`);
  process.exit(1);
}
