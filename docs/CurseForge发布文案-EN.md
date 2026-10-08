# CurseForge 发布文案（英文）

> **页面上所有面向玩家的文字都必须是英文**（CurseForge 的硬性要求）。中文名可以出现在描述里，但不能只有中文。
> 这份文件按 CF 的**页面顺序**排：① 建项页 → ② 描述页 → ③ 上传文件页 → ④ 项目链接。
> 标 ⚠️ 的是**会卡住或者会被审核问到**的点。

---

## ① 建项页（Project name / Logo / Summary / Class / Categories）

### Project name ⭐ 推荐直接填这个

```
Create Big Cannons: Fire Control Ballistics
```

- 与模组的**英文显示名逐字一致**（游戏里创造模式物品栏、按键分类、`NOTICE` 抬头都是这个名字）。
- ⚠️ 表单上那句 "Avoid including the game name, category, or class (like Minecraft, Mod, Modpack, Vanilla)" —— 我们**没有**踩：没有 "Minecraft"、没有 "Mod"。
- ⚠️ 以 "Create Big Cannons" 开头是**附属模组的通行写法**（CF 上大量 Create/CBC 附属都这么起名），没问题。
- 想更短的话可以只用 `Fire Control Ballistics`，但会丢掉"我是 CBC 附属"这个信息，**不推荐**。

### Logo ✅ 你已经传好了

- 用的就是我们做的那张封面 ✓ 名称与主体都是自己的，符合 CF 的三条禁区（不是纯色/渐变、不是游戏 logo、不是 NSFW）。
- ⚠️ 那张封面里用到了 **CBC Ballistics（MIT）** 与 **卓越前线（GPLv3）** 的素材 —— 两者都是开源许可、且我们已在 jar 内 `NOTICE` 里逐项署名。**万一审核问起，把这条说明发给他即可**（不是盗用素材）。

### Summary ⭐ 三选一（都是英文、一句话）

**推荐（功能 + 卖点，最像 CF 上的标准写法）**
```
Fire control for Create Big Cannons — solve firing solutions and command an entire battery of cannons from one console.
```

**更短**
```
Fire control for Create Big Cannons: solve firing solutions, lay the guns and fire the whole battery.
```

**更强调"计算"**
```
A fire control network for Create Big Cannons that computes firing solutions for every cannon in your battery.
```

### Class / Categories ✅ 你已经选对了

- **Class**: `Mods` ✓
- **Main category**: `Create` ✓
- **Additional**: `Automation` ✓ + `Armor, Tools, and Weapons` ✓（我们确实加了三件物品：指示器 / 无人机 / 遥控器）
- 可选再加：`Technology`（想要也行，不加也够准）

---

## ② 描述页（Description —— ⭐ **整段复制这一块**）

> **下面这一整块就是最终版**：英文全文 → `---` 分隔线 → 中文。
> **一次复制、一次粘贴，别拆开、别调换顺序** —— CF 官方规定 *"Include translations if desired,
> but **English should be listed first**"*，英文必须在前面。
> **`Source` 那行已经填好**（用的是你的 GitHub 地址），不用再改。
> ⚠️ 粘贴前先把编辑器右上角从 **`WYSIWYG`** 切成 **`Markdown`**，否则 `#`、`**` 会原样显示出来。
>
> ⚠️ **两个联动模组的名字是核对出来的、不是猜的**（都读自其 jar 里的 `mods.toml`）：
> · 航空学 = **`Create Aeronautics`**（displayName 原文就是不带冒号的这个写法；作者 **Simulated Team**，这个 jar 是 bundled 版，内含 Simulated / Aeronautics / Offroad）
> · 它底层的物理库 = **`Sable`**（作者 **RyanHCode**）
> 如果你在 CF 或 MC百科 上看到的是带冒号的「Create: Aeronautics」，**按那边的写法改一下即可**。
> ⚠️ 另外**不要把 Sable 的许可当成开源**：它是 **PolyForm Shield 1.0.0**（源码可见但禁止竞争性使用）。我们只是**编译期链接 + 运行期探测**，不分发它的代码或素材 —— 所以描述里写的是"不包含它们的任何代码或素材"，这句话是准确的，别改成"基于 Sable 开发"之类。

```markdown
# Create Big Cannons: Fire Control Ballistics

Fire control for **Create Big Cannons** — aim and fire an entire battery of cannons from a single console.

Every cannon solves its own firing solution, so mixed batteries (different materials, chambers, barrels or loads) still each hit their own correct elevation.

## Blocks

- **Fire Control Computer** — Enter the target coordinates, choose the firing arc and the fire mode, then give the order to fire. Scans a 64 block radius and drives up to 4 coordinators. Needs a shaft on its back; 64 SU per RPM.
- **Fire Control Coordinator** — Registers every fire control node within 32 blocks (up to 10 cannons, nearest first) and relays the target to each of them. 32 SU per RPM.
- **Fire Control Node** — Place it against any face of a cannon mount. It probes that cannon (material, chamber length, barrel length, propellant, shell) and solves its own firing solution, then lays the gun and fires it. 8 SU per RPM.

All three require rotational power; the goggles tell you exactly what a node is doing and why it cannot shoot.

## Fire control

- **Two arcs plus AUTO** — low arc, high arc, or AUTO, which falls back to the high arc when the low one is blocked by terrain or outside the gun's elevation limits.
- **Terrain checking** along the trajectory. It reports the real reason a shot cannot be taken: muzzle blocked, target too close, elevation limit reached, cannon not loaded, and so on.
- **Simultaneous impact** — the shots are staggered so that every shell lands at the same moment.
- **Fire modes** — salvo, simultaneous impact, rolling fire (adjustable interval) and area coverage (adjustable radius, the battery walks its fire across a grid).
- **Loading safety** — per-material charge limits, barrel-to-charge matching, jam and burst warnings, and an estimated spread.

## Observation and spotting

- **Artillery Indicator** — Raise it to spot; zoom with the scroll wheel, mark a target with the middle mouse button and hold the left mouse button to fire. It binds to one Fire Control Computer. In the offhand it works together with the drone.
- **Reconnaissance Drone** + **Drone Remote** — Bind the remote to a drone, then fly it in first person to range, mark and fire at targets beyond your line of sight. The drone has durability: it loses some on impact, and it self-destructs if it flies beyond your view or the simulation distance.

## Ballistics

If **Create Big Cannons: Going Ballistic** is installed, solutions follow its realistic ballistics (quadratic drag, real gravity). Without it, vanilla Create Big Cannons ballistics (linear drag) are used. Both are supported out of the box — no configuration needed.

## Moving platforms (Create Aeronautics)

Cannons built on a Create Aeronautics ship or aircraft work as well. Because a shell fired from a moving platform does not inherit the platform's velocity, the mod applies a lead along the direction of travel and keeps refining that lead from the shots it actually observes. Moving platform against a static target is fully supported.

Nothing has to be enabled for this: if Create Aeronautics and Sable are installed they are detected at runtime. No code or assets from them are included in this mod.

## Why shots scatter

Scatter is a vanilla Create Big Cannons mechanic, not an aiming error. A cannon's spread starts from the propellant it is loaded with (more charge, more starting spread) and is then reduced by every barrel block, down to a floor set by the cannon's material. A short barrel carrying a big charge therefore keeps a wide spread no matter how accurately the gun is laid.

This mod does not change any of that. It reads Create Big Cannons' own numbers and shows you the resulting spread — both across the line of fire and along the trajectory — so you know what to expect before firing. The goggles also warn you when the barrel is too short for the loaded charge, and how many more barrel blocks are needed to reach that material's minimum spread.

So if your shots scatter, look at the barrel-to-charge combination first: add barrel sections, or use less propellant. The mod can lay the gun as accurately as the gun and its ammunition allow — it cannot make a short barrel with a heavy charge shoot tight.

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.0 or newer
- Create 6.0.0 or newer
- Create Big Cannons 5.11.0 or newer
- Ritchie's Projectile Library 2.1.0 or newer

**GeckoLib is bundled inside this mod** — you do not have to install it separately. If you already have a compatible GeckoLib, yours is used instead.

Optional: Create Big Cannons: Going Ballistic (realistic ballistics), CBC Advanced Technology, Sable — all detected at runtime, all optional.

## Notes

- A firing solution is only as good as what is loaded. The goggles show the charge limit for the cannon's material, whether the barrel matches the charge, and whether the shell will jam.
- The terrain check only looks at blocks in the world. Blocks on a moving contraption are **not** checked, so firing from a ship will not warn you about hitting your own hull.

## Credits and license

This mod is licensed under **GPL-3.0-only**. The source is available at: https://github.com/cangwuxiaoqi/create-ballistics-fire-control

It reuses art from open source projects, credited file by file in the `NOTICE` file inside the jar:

- **CBC Ballistics** by Felis (MIT) — the fire control node block model and several block textures.
- **Superb Warfare** by Atsuishio (GPL-3.0) — the artillery indicator icon and scope overlay, the drone model, animations, textures and engine sound, the remote icons, and the drone camera HUD textures.
- **GeckoLib** (MIT) — used to render the drone, bundled unmodified inside this jar.

Thanks to the authors of Create, Create Big Cannons, CBC Ballistics and Superb Warfare.

---

## 中文说明（简体中文）

**机械动力火炮：火控计算弹道学** —— 为《机械动力：火炮》（Create Big Cannons）提供火控系统：在一台操控台上解算整个炮群的射击诸元，并统一下达开火。

每一门炮各自解算诸元，所以材质、装填、身管长度不同的混编炮群，也能各自瞄准正确的仰角。

### 三个方块

- **火控计算机** —— 输入打击坐标、选择弹道与火力模式、统一下达开火。识别半径 64 格，最多带 4 台协调器。背面接传动轴，每 1 RPM 消耗 64 SU。
- **火控协调器** —— 登记 32 格内的火控节点（最多 10 门炮，超出时取最近的），把目标坐标下发给每一门炮。每 1 RPM 消耗 32 SU。
- **火控节点** —— 贴着炮座任意一面摆放。它会探测这门炮（材质、炮腔格数、炮筒长度、装药、弹种）并独立解算诸元，然后伺服瞄准并击发。每 1 RPM 消耗 8 SU。

三件都需要转速；戴上护目镜就能看到每个节点正在做什么、以及打不出去的原因。

### 火控功能

- **低伸 / 高抛 / 自动** —— 自动模式在低伸被地形挡住或超出射界时，改走高抛。
- **弹道遮挡校验** —— 会给出打不了的具体原因：炮口被堵、目标太近、仰角到顶、未装填等。
- **同时弹着** —— 按每一发的飞行时间错峰开火，让所有炮弹同时落地。
- **火力模式** —— 齐射、同时弹着、轮射（可设间隔）、范围覆盖（可设半径，落点按网格铺开）。
- **装填安全** —— 材质装药上限、炮管与装药的匹配、卡弹与炸膛预警、落点散布估算。

### 观测与校射

- **火炮指示器** —— 举起后作为观测镜使用：滚轮缩放、中键标记目标、长按左键开火；可与一台火控计算机绑定，放在副手时能与无人机配合使用。
- **侦测无人机 + 无人机遥控器** —— 绑定后进入无人机视角，飞到视距之外测距、标记并开火。无人机有耐久，撞到东西会扣，飞出视距或模拟距离会自爆。

### 弹道

装有《Create Big Cannons: Going Ballistic》时，按它的真实弹道（二次阻力 + 真实重力）解算；未安装时按 Create Big Cannons 原版弹道（线性阻力）解算。两者开箱即用，不需要改配置。

### 航空学载具上的火炮（Create Aeronautics）

架在《机械动力：航空学》的船或飞机上的炮同样可用。由于平台在动、而炮弹**不继承载具速度**，模组会沿行进方向自动施加提前量，并根据实际观测到的落点不断修正它。**「动平台打静止目标」已完整支持。**

不需要任何配置：装有 Create Aeronautics 与 Sable 时自动识别、运行期接线（**没有包含它们的任何代码或素材**）。

### 为什么打不准／散布为什么大

散布是 Create Big Cannons **本身的机制，不是瞄不准**。一门炮的散布从所装发射药起算（药越多、起步散布越大），然后**每增加一格炮筒递减一次**，一直减到这门炮材质的散布下限为止。
⇒ **炮筒短 + 装药多 ⇒ 散布一直很大** —— 这时就算瞄准完全正确，落点也会散。

本模组**不修改这个机制**，而是**读 CBC 自己的数值**、把最终的散布估算显示出来（沿射线的横向分量＋弹道方向的分量），让你开火前就知道大概会散多大；护目镜还会在「炮筒对当前装药太短」时预警，并告诉你**再加几格炮筒**才能压到该材质的散布下限。

所以散布大时，先看**炮筒与装药的搭配**：加长炮筒，或者减少装药。火控能把炮瞄到「这门炮 + 这份弹药允许的最准」，但改变不了炮与弹药本身的散布。

### 前置

- Minecraft 1.21.1 · NeoForge 21.1.0 及以上
- 机械动力（Create）6.0.0 及以上 · 机械动力：火炮（Create Big Cannons）5.11.0 及以上
- Ritchie's Projectile Library 2.1.0 及以上

**GeckoLib 已内嵌在本模组内**，不需要单独安装（若你已装有兼容版本，则使用你那份）。

### 注意事项

- 诸元的精度取决于装填：护目镜会显示这门炮材质的装药上限、炮管与装药是否匹配、以及是否会卡弹。
- 遮挡校验只检查世界中的方块；**载具（contraption）上的方块不在检查范围内**，因此在船上开炮时不会提示会打到自己船体。

### 鸣谢与许可

本模组以 **GPL-3.0-only** 分发，源码地址：https://github.com/cangwuxiaoqi/create-ballistics-fire-control

它使用了两个开源项目的素材（已在 jar 内 `NOTICE` 中逐文件署名并附许可全文）：**CBC Ballistics**（作者 Felis，MIT）与 **卓越前线 Superb Warfare**（作者 Atsuishio，GPL-3.0）；**GeckoLib**（MIT）内嵌用于渲染无人机。

感谢 Create、Create Big Cannons、CBC Ballistics 与卓越前线的作者。
```

---

## ③ 上传文件页（Files → Upload）

| 字段 | 填什么 |
|---|---|
| Game version | **1.21.1** |
| Mod loader | **NeoForge** |
| Release type | **Release**（这是正式版；以后有测试版再选 Beta） |
| File | 上传 `[机械动力火炮：火控计算弹道学]Create_Big_Cannons_Fire_Control_Ballistics-2.0.0+mc1.21.1.jar` |
| Display name | 留空即可（默认用文件名） |

**Changelog（首次发布，直接粘）**
```
Initial release.

Fire control for Create Big Cannons: a computer, coordinator and node network that solves firing solutions, lays and fires the guns, with low/high arc selection, terrain checking, simultaneous impact, rolling fire and area coverage. Includes the artillery indicator and a reconnaissance drone for spotting targets beyond line of sight.

Requires Create Big Cannons. GeckoLib is bundled.
```

### 这一页有三个 ⚠️

1. **License 选择**：CF 的许可下拉里选 **GNU General Public License v3.0**。
   我们的实际许可是 **GPL-3.0-*only***（不是 or-later）—— CF 的下拉可能分不出这个区别，所以：**在许可之外，描述与 jar 内的 `NOTICE` 都已写明 "GPL-3.0-only"**，这就够了。
2. **文件名带中文和全角冒号**：CF 的下载链路上可能出现百分号编码的长文件名，一般能用。要是它报错或难看得离谱，**换个 ASCII 文件名（例如 `create_ballistics-2.0.0.jar`）再传一份即可** —— 文件名不影响模组本身。
3. **JarJar 内嵌了 GeckoLib**：CF 偶尔会问"文件里是不是内嵌了别的模组"。
   回答：**是，内嵌的是 GeckoLib，MIT 许可、允许再分发**，许可全文随内嵌 jar 一起分发（jar 内 `META-INF/jarjar/`，其根目录有 `LICENSE_GeckoLib 4`）。

---

## ④ 项目链接（Project links / 源链接）—— ✅ 仓库已建好，只差**填进去**

**源码仓库（已推送完成）**：
```
https://github.com/cangwuxiaoqi/create-ballistics-fire-control
```
`refs/heads/main` = `cba3d9972b1d5b2a00b116d162cf084293c37748`，共 **174 个文件**（第三方 jar / 别人的封面参考图 / 构建产物都已 gitignore，一个没进去）。

GPL-3.0 第 6 条要求"分发 jar 时必须能让拿到它的人拿到源码"，CF 的项目页有 **Source** 链接位 —— 就是履行该条的地方。

### ✅ 2026-10-08：CurseForge 审核已通过

正式页面：**https://www.curseforge.com/minecraft/mc-mods/create-big-cannons-fire-control-ballistics**

⇒ 下面几处已经/需要体现这个链接：
1. ✅ **`README.md` 的 Download 段** —— 已填上 CF 链接并推送到仓库
2. ⬜ **GitHub 仓库页右上角的 About**（这个我没法改，你在网页上点齿轮填）：
   - **Website** = 上面那个 CF 链接
   - **Topics** 建议：`minecraft` `neoforge` `create-mod` `create-big-cannons` `fire-control` `ballistics`
3. ⬜ **MC百科** 条目的「相关链接」—— 等条目上线后填 CF + 仓库两个地址
4. ⬜ `README.md` 里的 MC百科 一行仍写着「条目待上线」，上线后告诉我补上
