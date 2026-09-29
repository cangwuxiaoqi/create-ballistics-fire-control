# libs/ —— 构建所需的第三方 jar（**不在本仓库内**）

这些 jar 的许可**各不相同**，其中：

- `cbc_going_ballistic`、`cbc_at` —— 声明 **All Rights Reserved**
- `sable` 及其 companion / rapier —— **PolyForm Shield**（源码可见，但禁止竞争性使用）

⇒ 因此它们**不随本仓库分发**。要构建本模组，请自行从各自项目取得下列文件，放进这个目录。

| 文件名 | 来自 |
|---|---|
| `create-1.21.1-6.0.10.jar` | Create |
| `flywheel-neoforge-1.21.1-1.0.6.jar` | Flywheel（Create 前置） |
| `ponder-neoforge-1.0.82+mc1.21.1.jar` | Ponder（Create 前置；`net.createmod.catnip.*` 也在这里面） |
| `Registrate-MC1.21-1.3.0+67.jar` | Registrate（Create 前置） |
| `createbigcannons-5.11.7+mc.1.21.1.jar` | Create Big Cannons |
| `ritchiesprojectilelib-2.1.2+mc.1.21.1-neoforge.jar` | Ritchie's Projectile Library（CBC 前置） |
| `geckolib-neoforge-1.21.1-4.7.5.jar` | GeckoLib（MIT；本模组已把它内嵌进发布版 jar，构建时仍需这一份） |
| `cbc_going_ballistic-0.3.1.jar` | Create Big Cannons: Going Ballistic（**可选**） |
| `cbc_at-0.1.4c.jar` | CBC Advanced Technology（**可选**） |
| `sable-neoforge-1.21.1-2.0.5.jar` | Sable（**可选**） |
| `sable-companion-1.21.1-1.6.0.jar` | Sable 的 JarJar 内嵌件（**抽出来才能编译**：要用的 `Pose3dc` 在这里面） |
| `sable-rapier-1.21.1-2.0.5.jar` | 同上，JarJar 内嵌件 |
| `veil-neoforge-1.21.1-4.3.2.jar` | Veil（Sable 前置） |

> **版本号以 `build.gradle` 里 `dependencies { compileOnly files(...) }` 列出的为准** ——
> 升级某一项时，那一行和这里的文件名要一起换。
>
> 全部都是 `compileOnly`：**只用于编译**，运行期由玩家的整合包提供。
> 标「可选」的那几个在运行期用 `ModList` 判断，没装就优雅降级（绝不硬依赖）。
