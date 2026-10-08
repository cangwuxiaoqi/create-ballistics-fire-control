# Create Big Cannons: Fire Control Ballistics

**机械动力火炮：火控计算弹道学**

Fire control for **Create Big Cannons** — solve firing solutions and command an entire battery of cannons from a single console.

A Minecraft **1.21.1** / **NeoForge** mod, built as an addon for **Create** and **Create Big Cannons**. Every cannon solves its own firing solution, so mixed batteries (different materials, chambers, barrels or loads) each hit their own correct elevation.

- **Blocks** — Fire Control Computer · Fire Control Coordinator · Fire Control Node
- **Items** — Artillery Indicator · Reconnaissance Drone · Drone Remote
- Low / high arc with AUTO fallback, terrain checking along the trajectory, simultaneous impact, rolling fire and area coverage
- Supports cannons mounted on **Create Aeronautics** moving platforms
- Works with vanilla Create Big Cannons ballistics and with **Create Big Cannons: Going Ballistic**

## Download

- **CurseForge** — https://www.curseforge.com/minecraft/mc-mods/create-big-cannons-fire-control-ballistics
- **MC百科** — _（条目待上线）_

This repository holds the source code; build instructions are below.

## Building

Requires **JDK 21**. The mod compiles against the Create / Create Big Cannons / Flywheel / Ponder / Registrate / GeckoLib jars, which are **not** included in this repository — see [`libs/README.md`](libs/README.md) for the exact list and where each one comes from.

```bash
./gradlew build            # jar lands in build/libs/
./gradlew verifySolver     # ballistic solver self-check: 100 numeric checks, no Minecraft needed
```

| Task | What it does |
|---|---|
| `gradlew build` | builds the jar (includes a hard gate on the jarjar metadata) |
| `gradlew verifySolver` | runs the ballistic solver self-check in a plain JVM |
| `node tools/scan_client_refs.js <jar>` | checks that no server-side class touches a client-only class |

## License

**GPL-3.0-only** — see [LICENSE](LICENSE).

This mod reuses art from open source projects. Every file is credited in [NOTICE](NOTICE), together with the full MIT text and a modification record:

- **CBC Ballistics** by Felis (**MIT**) — the fire control node block model and several block textures
- **Superb Warfare** by Atsuishio (**GPL-3.0**) — the artillery indicator icon and scope overlay, the drone model / animations / textures / engine sound, the remote icons and the drone camera HUD textures
- **GeckoLib** (**MIT**) — used to render the drone; bundled unmodified inside the released jar

Thanks to the authors of Create, Create Big Cannons, CBC Ballistics and Superb Warfare.

---

## 中文

「机械动力火炮：火控计算弹道学」是《机械动力：火炮》（Create Big Cannons）的火控附属模组，适用于 Minecraft **1.21.1** / **NeoForge**。

- **三个方块** —— 火控计算机（输入打击坐标、统一下达开火）· 火控协调器（汇总炮位、把目标下发到每一门炮）· 火控节点（探测所在那门炮、独立解算诸元、伺服瞄准与击发）
- **三件物品** —— 火炮指示器（观测 / 标记 / 开火）· 侦测无人机 · 无人机遥控器
- 低伸 / 高抛 / 自动弹道、弹道遮挡校验、同时弹着、轮射、范围覆盖
- 支持架在《机械动力：航空学》载具上的火炮；兼容原版弹道与「弹道射程优化」

**构建**：需要 JDK 21，并需自行准备第三方 jar（清单见 [`libs/README.md`](libs/README.md)）。

```bash
gradlew build            # 产物在 build/libs/
gradlew verifySolver     # 弹道内核数值自检（100 项，不启动 Minecraft）
```

**许可**：**GPL-3.0-only**（见 [LICENSE](LICENSE)）。第三方素材的逐文件署名、MIT 许可全文与修改声明见 [NOTICE](NOTICE)。
