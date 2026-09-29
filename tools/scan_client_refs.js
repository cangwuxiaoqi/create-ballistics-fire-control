// 安全检查：扫出"引用了 net/minecraft/client/… 的类，但那个类本身不是客户端专属"的地方。
// 用途：发布前确认**专用服务器**不会因为加载到客户端类而崩。
// 用法: node scan_client_refs.js <mod.jar>
// 判据：
//   - 类名在 net/createballistics/client/ 下            => 客户端专属，安全（服务端不会加载它）
//   - mixin 的 client 组（CameraMixin/MouseHandlerMixin/PlayerItemInHandLayerMixin）=> 同上
//   - 其它任何类引用了 net/minecraft/client/  => **列出来，逐个人工确认**（惰性解析通常没事，
//     但"字段类型/方法签名/继承/静态初始化"这几种会在类加载期就炸）
const fs = require('fs');
const { execSync } = require('child_process');

const jar = process.argv[2];
if (!jar) { console.error('用法: node scan_client_refs.js <mod.jar>'); process.exit(1); }

// 用 JDK 的 jar 解包到临时目录（避免依赖第三方 zip 库）
const tmp = 'D:\\moddev\\_scan_client_refs';
execSync(`if exist "${tmp}" rmdir /s /q "${tmp}"`, { shell: 'cmd.exe' });
fs.mkdirSync(tmp, { recursive: true });
execSync(`"D:\\moddev\\tools\\jdk\\jdk-21.0.12.1+1\\bin\\jar.exe" xf "${jar}"`, { cwd: tmp, shell: 'cmd.exe' });

function cpStrings(file) {
  const b = fs.readFileSync(file);
  if (b.length < 10 || b.readUInt32BE(0) !== 0xCAFEBABE) return null;
  let p = 8;
  const count = b.readUInt16BE(p); p += 2;
  const out = [];
  for (let i = 1; i < count; i++) {
    const tag = b[p++];
    switch (tag) {
      case 1: { const len = b.readUInt16BE(p); p += 2; out.push(b.slice(p, p + len).toString('utf8')); p += len; break; }
      case 7: case 8: case 16: case 19: case 20: { p += 2; break; }
      case 15: { p += 3; break; }
      case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: { p += 4; break; }
      case 5: case 6: { p += 8; i++; break; }
      default: return out;   // 解析不通就放弃这个文件（只做粗筛）
    }
  }
  return out;
}

function walk(dir, acc) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = dir + '\\' + e.name;
    if (e.isDirectory()) walk(full, acc);
    else if (e.name.endsWith('.class')) acc.push(full);
  }
  return acc;
}

// client 组 mixin 是客户端专属，安全。
// ⚠️ **不要手工维护这份名单**：原来写死了三个类名，后来加了第 4 个 GuiSpyglassOverlayMixin
//    就误报了一次（"忘了同步名单"这种错不该有机会发生）⇒ 直接读产物里的 mixins.json 的 client 数组。
let CLIENT_MIXINS = ['CameraMixin', 'MouseHandlerMixin', 'PlayerItemInHandLayerMixin'];
try {
  const cfg = JSON.parse(fs.readFileSync(tmp + '\\create_ballistics.mixins.json', 'utf8'));
  if (Array.isArray(cfg.client)) CLIENT_MIXINS = cfg.client.map(c => c.split('.').pop());
} catch (e) {
  console.log('（读不到 mixins.json，退回内置的 client 名单）');
}
const files = walk(tmp, []);
const hits = [];
for (const f of files) {
  const strings = cpStrings(f);
  if (!strings) continue;
  const refs = strings.filter(s => s.startsWith('net/minecraft/client/'));
  if (refs.length === 0) continue;
  const cls = f.slice(tmp.length + 1).replace(/\\/g, '/').replace(/\.class$/, '');
  if (cls.startsWith('net/createballistics/client/')) continue;         // 客户端包，安全
  if (CLIENT_MIXINS.some(m => cls.endsWith('/' + m))) continue;          // client 组 mixin，安全
  hits.push({ cls, refs: [...new Set(refs)] });
}

console.log(`扫描 ${files.length} 个 class，发现需要人工确认的：${hits.length} 个\n`);
for (const h of hits) {
  console.log('⚠️ ' + h.cls);
  for (const r of h.refs) console.log('     ' + r);
}
if (hits.length === 0) console.log('✅ 没有任何"非客户端类"引用客户端类 —— 专用服务器安全');
execSync(`rmdir /s /q "${tmp}"`, { shell: 'cmd.exe' });
