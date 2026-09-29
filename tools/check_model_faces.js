/*
 * Guard against the "see-through block" bug.
 *
 * In Minecraft block models an UNDEFINED face renders nothing, and a face with
 * texture "#missing" renders nothing too. Either one punches a hole straight
 * through the block. This script fails loudly if any element of a model is
 * missing one of the six faces, or points at #missing.
 *
 * Usage: node check_model_faces.js <model.json> [...]
 */
const fs = require('fs');
const FACES = ['north', 'south', 'east', 'west', 'up', 'down'];

let problems = 0;

for (const path of process.argv.slice(2)) {
  const model = JSON.parse(fs.readFileSync(path, 'utf8'));
  const elements = model.elements || [];
  console.log(`${path}  ->  ${elements.length} 个元素`);
  elements.forEach((el, i) => {
    const label = `element[${i}]${el.name ? ' ' + el.name : ''} ${JSON.stringify(el.from)}..${JSON.stringify(el.to)}`;
    const faces = el.faces || {};
    const missing = FACES.filter(f => !faces[f]);
    const broken = FACES.filter(f => faces[f] && faces[f].texture === '#missing');
    if (missing.length) {
      console.log(`  [缺面] ${label}  未定义: ${missing.join(', ')}`);
      problems++;
    }
    if (broken.length) {
      console.log(`  [#missing] ${label}  无贴图: ${broken.join(', ')}`);
      problems++;
    }
  });
}

console.log('');
if (problems === 0) {
  console.log('ALL MODELS OK - 每个元素六个面齐全');
} else {
  console.log(`${problems} 个元素有问题`);
  process.exit(1);
}
