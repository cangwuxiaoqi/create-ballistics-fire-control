package net.createballistics.cbc;

import net.createballistics.ballistic.ExternalBallistics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedBigCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.cannons.big_cannons.BigCannonBlock;
import rbasamoyai.createbigcannons.cannons.big_cannons.IBigCannonBlockEntity;
import rbasamoyai.createbigcannons.cannons.big_cannons.breeches.BigCannonBreechStrengthHandler;
import rbasamoyai.createbigcannons.index.CBCMunitionPropertiesHandlers;
import rbasamoyai.createbigcannons.munitions.big_cannon.FuzedBigCannonProjectile;
import rbasamoyai.createbigcannons.munitions.big_cannon.config.BigCannonCommonShellProperties;
import rbasamoyai.createbigcannons.munitions.big_cannon.config.InertBigCannonProjectileProperties;
import rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile;
import rbasamoyai.createbigcannons.munitions.big_cannon.ProjectileBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.propellant.BigCannonPropellantBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.propellant.BigCartridgeBlock;

/**
 * 从 CBC 读出<b>一门已组装火炮的真实参数</b>。
 *
 * <p>做法照抄参考模组 {@code firecontrol} 的 {@code scanCannon()} —— 那是唯一一处
 * 「自动读炮」的成熟实现：从 CBC 的 contraption 里沿炮管逐格扫，药包累加装药强度、
 * 弹丸读弹道属性。本 mod 不自己猜任何参数。
 *
 * <h2>炮弹生成点的世界坐标怎么算</h2>
 *
 * 严格复刻 CBC {@code MountedBigCannonContraption.fireShot} 里的三步：
 * <pre>
 * spawnPos = toGlobalVector(atCenterOf(炮口方块.relative(炮轴)))     // 炮口再往外一格的中心
 * spawnPos -= normalize(spawnPos − toGlobalVector(atCenterOf(原点))) × 2   // 沿炮轴再推 2 格
 * </pre>
 * 所以炮弹实际生成在<b>炮口外约 3 格</b>处。{@link ProbedCannon#barrelLength} 就是把这个点
 * 与炮座中心的距离量出来 —— 它是<b>目标无关</b>的标量，解算时只要"枢轴 + 该距离 × 瞄准方向"
 * 就能还原生成点。
 *
 * <h2>为什么要把炮弹实体也带走</h2>
 *
 * 装了 going_ballistic 时，初速不是"Σ装药强度"，而是它按 Robins 内弹道公式算出来的，
 * 且那个函数需要一个<b>炮弹实体</b>当参数。所以这里用 CBC 的弹丸工厂临时造一个
 * （{@code getProjectile} 只是 {@code new} 一个实体，不进世界），随快照一起交给上层，
 * 用完即弃。<b>这是拿到"和游戏一字不差"初速的唯一办法</b> —— 自己复刻 Robins 会偏 46%。
 */
public final class CannonProbe {

    /** 沿炮管扫描的上限（格）。CBC 自己用 64，这里放宽到 512 覆盖超长炮。 */
    public static final int MAX_SCAN = 512;

    private CannonProbe() {
    }

    /**
     * 从探针结果还原<b>图幅坐标</b>的炮轴枢轴。
     *
     * <p>⚠️ 探针出口已经把 {@code originX/Y/Z} 转成**世界**坐标了（见那段出口转换），
     * 而"每 tick 都要重算世界位置"的地方（航空学载具在动时的实时重解、诊断日志）
     * 需要的是**图幅**枢轴 —— 拿它过一次 {@code SableCompat.toWorld} 才是当前世界位置。
     *
     * <p>⚠️ 这里是这个换算的<b>唯一实现</b>（原来在几处诊断里各抄了一遍）——
     * 改了探针的枢轴定义就只会改到这里。
     */
    public static Vec3 plotPivot(ProbedCannon probed) {
        return Vec3.atCenterOf(probed.mountPos()).add(0.0, probed.pivotOffsetY(), 0.0);
    }

    /**
     * 探一门炮。<b>没有缓存几何</b>的版本 —— 只在"这门炮还从没被精确反解过"时用。
     *
     * @see #probe(Level, BlockPos, double, double)
     */
    public static ProbedCannon probe(Level level, BlockPos mountPos) {
        return probe(level, mountPos, Double.NaN, 0.0);
    }

    /**
     * 探一门炮。任何一环不满足（没炮座 / 没组装 / 没朝向）都安静地返回 {@code null}。
     *
     * @param level    服务端世界
     * @param mountPos 炮座方块坐标
     * @param fallbackBarrelLength 上一次<b>精确反解</b>出来的炮轴长度；没有就传 {@link Double#NaN}。
     *                             <p>炮口仰到 80° 以上时反解会被跳过（除以 {@code cos} 会放大噪声），
     *                             那时候<b>沿用这个值</b>而不是退回直线距离 ——
     *                             否则前后两次探测的起点会差 2 格，足以让地形校验在炮口附近的
     *                             边界上反复翻面，AUTO 模式就会卡在切换弹道的死循环里
     *                             （2026-09-21 用户实测）
     * @param fallbackPivotOffsetY 同上，配套的支点竖直偏移
     */
    @Nullable
    public static ProbedCannon probe(Level level, BlockPos mountPos,
                                     double fallbackBarrelLength, double fallbackPivotOffsetY) {
        if (level == null || level.isClientSide) {
            return null;
        }
        if (!(level.getBlockEntity(mountPos) instanceof CannonMountBlockEntity mount)) {
            return null;
        }
        PitchOrientedContraptionEntity poce = mount.getContraption();
        if (poce == null) {
            return null;
        }
        if (!(poce.getContraption() instanceof AbstractMountedCannonContraption cannon)) {
            return null;
        }

        Direction dir = cannon.initialOrientation();
        BlockPos startPos = cannon.getStartPos();
        if (dir == null || startPos == null) {
            return null;
        }

        // ---- 沿炮管逐格扫：累加装药强度、抓弹丸、记住最后一个实心格（= 炮口） ----
        float chargePower = 0.0F;
        /**
         * 起步散布 = {@code Σ propellant.getSpread(info)} —— <b>照 CBC 原文（{@code PropellantContext.addPropellant}）</b>。
         *
         * <p>⚠️⚠️ 曾经写死成 {@code chargeCount × 2.0}（"黑火药 2/包"）。那只对**标准黑火药**成立，
         * 对**大型弹药**差 4 倍：它填充 4/4 时 {@code getSpread = powerMultiplier × powerFromData(4)
         * × added_spread(2) = 8}，而写死值是 2。后果是长短炮筒**都被压到材质下限**、
         * 护目镜上无论炮管多长都显示同一个数（2026-09-27 用户实测："不管多还是少，都是 ±0"）。
         * ⇒ 起步散布这类量一律**问 CBC**，别猜（同"初速 9.5 之谜"那一族的教训）。
         */
        float chargeSpread = 0.0F;
        int chargeCount = 0;
        int barrelBlocks = 0;
        int chamberBlocks = 0;
        String materialName = null;
        float matMinSpread = 0.0F;
        float matSpreadReduction = 0.0F;
        /** 材质的装药上限（不含炮闩）。与炮闩强度分开报，才能说清是哪一环卡住了上限。 */
        int matMaxStress = 0;
        /** 卡弹系数：`炮筒格数 > 装药强度 / 本值` 就会卡弹（CBC 原版规则）。见 {@link ProbedCannon}。 */
        float matMinVelPerBarrel = 0.0F;
        /** 已装填弹丸的方块语言键。只存键名，客户端自己翻译 —— 服务端翻译只会拿到英文。 */
        String shellItemId = null;
        /** 已装填发射药的方块语言键（最后一包为准）。和弹种一样，只存键名给客户端翻译。 */
        String propellantItemId = null;
        /** 定装弹（大型弹药）的当前填充档位与上限。不是定装弹时都是 0。 */
        int propellantPower = 0;
        int propellantMaxPower = 0;
        // 装药的真实物理量（由 GB 的发射药注册表解析）。三者一起构成 GB 的「捕获体」——
        // 查不到时全为 0 / 1，那时初速那一环会退回 GB 自己的兜底公式。
        double capturedPowderMassKg = 0.0;
        double capturedChargeLengthMeters = 0.0;
        double capturedVelocityMultiplier = 1.0;
        /** 弹丸的方块破坏爆炸威力，用来估「最近安全距离」。 */
        float shellExplosionPower = 0.0F;
        // 弹种的弹道属性（CBC 原版物理用）。默认值 = CBC 内置炮弹数据包的值，读不到就退回它。
        double cbcGravity = -0.05;
        double cbcDrag = 0.01;
        boolean cbcQuadratic = false;
        AbstractBigCannonProjectile shell = null;
        BlockPos pos = startPos;
        BlockPos muzzle = startPos;

        for (int i = 0; i < MAX_SCAN; i++) {
            if (!(cannon.presentBlockEntities.get(pos) instanceof IBigCannonBlockEntity cbe)) {
                break;
            }
            StructureTemplate.StructureBlockInfo contained = cbe.cannonBehavior().block();

            // ⚠️ 炮身材质必须从「炮架方块」读，**不能**从炮膛内容读：
            // `cannonBehavior().block()` 返回的是膛里装的东西（药包 / 炮弹 / 空气），
            // 那永远不是 BigCannonBlock —— 2026-09-17 就是这么写成"材质未知"的。
            StructureTemplate.StructureBlockInfo shellInfo = cannon.getBlocks().get(pos);
            if (shellInfo != null) {
                Block shellBlock = shellInfo.state().getBlock();
                if (shellBlock instanceof BigCannonBlock cannonBlock) {
                    if (materialName == null) {
                        materialName = cannonBlock.getCannonMaterial().name().getPath();
                        // 散布参数也在材质上 —— 决定这门炮能打多准
                        var props = cannonBlock.getCannonMaterial().properties();
                        matMinSpread = props.minimumSpread();
                        matSpreadReduction = props.spreadReductionPerBarrel();
                        matMaxStress = props.maxSafePropellantStress();
                        matMinVelPerBarrel = (float) props.minimumVelocityPerBarrel();
                    }
                    // 数炮腔格数 —— 玩家实际能装几包药由它决定（而不是材质允许的上限）
                    if (BuiltInRegistries.BLOCK.getKey(shellBlock).getPath().contains("cannon_chamber")) {
                        chamberBlocks++;
                    }
                }
            }

            Block block = contained.state().getBlock();

            // ⚠️ 这一支要排除「同时也是炮弹」的方块 —— 与 CBC 的 fireShot 逐字对齐：
            //     CBC 那边是 `if (发射药) { if (!(炮弹)) { ...按发射药处理... } }  if (炮弹) { ... }`
            // 定装弹就是这种方块。少了这个排除，它会被只认成发射药、**永远认不出弹种** ——
            // 那样这门炮会被报成"未装填"、恒无解。
            if (block instanceof BigCannonPropellantBlock propellant && !(block instanceof ProjectileBlock)) {
                chargePower += Math.max(0.0F, propellant.getChargePower(contained));
                // ⭐ 起步散布也**问 CBC**（见 chargeSpread 的说明）—— 它是接口方法，公开可用
                chargeSpread += Math.max(0.0F, propellant.getSpread(contained));
                chargeCount++;
                // ⚠️ 用外层的 block 而不是 propellant —— 后者是接口（BigCannonPropellantBlock），
                // 拿不到 ItemStack。显示名要的是方块物品的名字（大型弹药 / 发射药 Mk.IV 之类）。
                propellantItemId = new ItemStack(block).getDescriptionId();

                // 顺便问 GB：这一包药的<b>真实</b>药重与装药长是多少。
                // ⚠️ 不能拿「装药强度 ÷ 2」去反推 —— 那假设了"每包强度 = 2"（只有 CBC 标准黑火药成立），
                // 附属模组的发射药不遵守（cbc_at 的定装药 1.0~1.5 当量、增强型弹药 Mk1~Mk5 是 1.25~2.25）。
                // 推错会让装药长偏大，而 Robins 要 ln(管长/装药长) —— 装药长逼近管长时 ln 趋近 0，
                // 初速被算成 0（2026-09-19 用户实测：短管炮 + 大装药 → 极限射程显示 2 格）。
                ActiveBallistics.PropellantProfile profile =
                        ActiveBallistics.propellantProfile(level, propellant, contained);
                if (profile == null || profile.powderMassKg() <= 0.0 || profile.chargeLengthMeters() <= 0.0) {
                    // ⚠️ 注册表查不到（**定装弹的组件选择器常常匹配不上**）时，不能直接放弃 ——
                    // 按 GB 自己的兜底规矩折算。**定装弹的装药长恒为 1 米、不随档位放大**，
                    // 这条漏了会把初速算低 20%+（2026-09-20 实弹抓包定位，见 fallbackProfile 的注释）。
                    profile = ActiveBallistics.fallbackProfile(
                            block instanceof BigCartridgeBlock, propellant.getChargePower(contained));
                }
                capturedPowderMassKg += profile.powderMassKg();
                capturedChargeLengthMeters += profile.chargeLengthMeters();
                if (profile.velocityMultiplier() > 0.0) {
                    capturedVelocityMultiplier *= profile.velocityMultiplier();
                }
                // 大型弹药（定装弹）还要报「火药填充 x / 上限」。
                // 数据来源与 CBC 物品 tooltip 完全同源（CBCTooltip.appendBigCartridgePropellantPowerText）：
                // 当前档位读方块 NBT 的 "Power"，上限问 BigCartridgeBlock 自己的属性表。
                if (block instanceof BigCartridgeBlock cartridge) {
                    propellantPower += (int) BigCartridgeBlock.getPowerFromData(contained);
                    propellantMaxPower = cartridge.getMaximumPowerLevels();
                }
            } else if (block instanceof ProjectileBlock<?> projectileBlock && shell == null) {
                shell = projectileBlock.getProjectile(level, ItemStack.EMPTY);
                // 弹种名：存方块的 descriptionId，客户端再翻译。附属模组的新弹种会自动带上它自己的
                // 翻译（武器拓展那几种已汉化），查不到就退回键名，不会显示成乱码。
                shellItemId = new ItemStack(projectileBlock).getDescriptionId();
                // 读弹种自带的弹道属性（CBC 原版物理用）—— 照抄参考模组 firecontrol 的 scanCannon()。
                // `getBallisticProperties()` 在 CBC 里是 protected，外部只能经 CBCMunitionPropertiesHandlers
                // 按弹丸类型取；引信弹和惰性弹是两套 properties，所以要分两支。
                if (shell instanceof FuzedBigCannonProjectile fuzed) {
                    BigCannonCommonShellProperties p =
                            CBCMunitionPropertiesHandlers.COMMON_SHELL_BIG_CANNON_PROJECTILE.getPropertiesOf(fuzed);
                    cbcGravity = p.ballistics().gravity();
                    cbcDrag = p.ballistics().drag();
                    cbcQuadratic = p.ballistics().isQuadraticDrag();
                    chargePower += Math.max(0.0F, p.bigCannonProperties().addedChargePower());
                    // 爆炸威力（方块破坏）—— 估「最近安全距离」用。惰性弹没有这一项，保持 0。
                    shellExplosionPower = p.explosion().blockDamagePower();
                } else if (shell instanceof AbstractBigCannonProjectile inert) {
                    InertBigCannonProjectileProperties p =
                            CBCMunitionPropertiesHandlers.INERT_BIG_CANNON_PROJECTILE.getPropertiesOf(inert);
                    cbcGravity = p.ballistics().gravity();
                    cbcDrag = p.ballistics().drag();
                    cbcQuadratic = p.ballistics().isQuadraticDrag();
                    chargePower += Math.max(0.0F, p.bigCannonProperties().addedChargePower());
                }
            }

            muzzle = pos;
            barrelBlocks++;
            pos = pos.relative(dir);
        }

        // ---- 炮闩 ----
        // 位置判据抄 CBC 自己（`MountedBigCannonContraption.fireShot` 里的
        // `endPos = startPos.relative(initialOrientation.getOpposite())`），不要自创别的。
        BlockPos breechPos = startPos.relative(dir.getOpposite());
        StructureTemplate.StructureBlockInfo breechInfo = cannon.getBlocks().get(breechPos);
        Block breechBlock = breechInfo == null ? null : breechInfo.state().getBlock();
        // 强度表里没有的方块（附属模组的新炮闩）退回材质上限 —— 与 CBC 的 CBCTooltip 同一取法。
        int breechStrength = breechBlock == null
                ? matMaxStress
                : BigCannonBreechStrengthHandler.getStrength(breechBlock, matMaxStress);
        String breechName = breechBlock == null ? "" : breechBlock.getDescriptionId();

        // ---- 复刻 CBC 的生成点 ----
        Vec3 spawnRaw = poce.toGlobalVector(Vec3.atCenterOf(muzzle.relative(dir)), 0.0F);
        Vec3 contraptionOrigin = poce.toGlobalVector(Vec3.atCenterOf(BlockPos.ZERO), 0.0F);
        Vec3 axis = spawnRaw.subtract(contraptionOrigin);
        if (axis.lengthSqr() < 1.0E-6) {
            return null;
        }
        axis = axis.normalize();
        Vec3 spawn = spawnRaw.subtract(axis.scale(2.0));

        Vec3 pivot = Vec3.atCenterOf(mountPos);

        // ---- 反解「炮轴支点」与「炮管长」 ----
        //
        // ⚠️ 这里是 2026-09-17 修掉的一个真缺陷：原先直接把 spawn 到 pivot 的**直线距离**
        // 当成"沿炮轴的长度"用。但炮轴支点并不在炮座方块中心（实测低约 2 格），于是
        //   ① 那个距离里混进了支点偏移；
        //   ② 它还随**探测那一刻炮的仰角**变化 —— 同一门炮在不同时刻探测会得到不同的解算结果。
        // 正确几何是： 炮口(p) = 枢轴 + (0, Δ, 0) + L·(cos p, sin p)
        // 而探测时 p = p0 已知（读当前俯仰角），spawn 的水平/竖直分量正好给出两个方程：
        //   |d|_水平 = L·cos p0        d.y = Δ + L·sin p0
        // 三发实弹标定反推出的 L≈21 / Δ≈−2 让三发全部落在散布内（见 SolverSelfCheck 的实弹回归）。
        double probePitchRad = Math.toRadians(mount.getPitchOffset(0.0F));
        Vec3 d = spawn.subtract(pivot);
        double dHorizontal = Math.hypot(d.x, d.z);
        double cosProbe = Math.cos(probePitchRad);

        // 炮轴支点相对炮座方块中心的竖直偏移。**直接采用几何实测值**（这门炮是 +2.01，
        // 即生成点在炮座中心上方 2 格 —— 与玩家给的方块坐标一致：炮座 y=173、炮腔 y=175）。
        //
        // ⚠️ 曾经一度在这里写成 `-offset`（取反，等价于起点降到 171.5），那是**误诊**：
        // 当时拿「弹坑中心的 y=169」当地面高度去标定，而弹坑是爆炸往下挖了 4 层之后的坑底，
        // **真实地面顶面是 173**（2026-09-17 玩家用 F3 对着脚下方块确认：草方块本体 y=172）。
        // 拿坑底当地面，就会把「目标高度少算 4 格」的账错记到「起点高度多算 4 格」上。
        //
        // 判据是**自洽**：解算用的起点必须等于炮弹真实的起点，落差才对。
        // 现在目标高度由 `resolveAimHeight()` 吸附到真实地形高度，两边都是真值，落差自然正确。
        boolean geometryExact = false;
        double barrelLength = spawn.distanceTo(pivot);
        double pivotOffsetY = 0.0;
        if (cosProbe > 0.15) {
            double length = dHorizontal / cosProbe;
            double offset = d.y - length * Math.sin(probePitchRad);
            // ⚠️ 上限必须**大于 CBC 允许的最长炮**：CBC 的极限长度是 64 格，再加炮口外推的 2~3 格，
            // 合法值能到 67 左右。原来卡在 64.0，于是「62 个炮筒」这种顶配长炮<b>反解直接被跳过</b>，
            // 几何退回成「枢轴到生成点的直线距离 + 支点偏移 = 0」那个已知错误的旧算法 ——
            // 而那个距离随仰角变化，所以偏差在近距离几乎看不出来、越远越离谱
            // （2026-09-20 实测：150 格偏 6、800 格偏 310）。
            if (length > 1.0 && length < 128.0 && Math.abs(offset) < 8.0) {
                barrelLength = length;
                pivotOffsetY = offset;
                geometryExact = true;
            }
        }

        // ⚠️⚠️⚠️ **有缓存就一律用缓存，连这次反解出来的值也不采纳。**
        //
        // 这一条是 2026-09-21 第三次震荡的根因所在。反解要除以 `cos(探测时的仰角)`：
        // 数学上那个仰角本该抵消，但 `getPitchOffset()` 是 float、`toGlobalVector()` 走的是
        // contraption 的实际变换，两者有微小不一致，被 `1/cos` 放大 ⇒
        // **炮一转动，两次探测算出的长度就差最后几位**。
        //
        // 而节点那边 `probeAndCache` 是 `Double.compare(barrelLength, …)` **精确**比对"参数变了没有"——
        // 于是每 2 秒都判定"参数变了"→ 强制重解 → 地形校验的边界判定翻面 → AUTO 切弹道 → 炮动
        // → 下次探测又差一点 → …… **自激死循环**。
        // 症状就是火炮仰头低头来回摆、甚至剧烈抽搐（2026-09-21 用户实测三次）。
        //
        // 几何是这门炮的**静态属性**，本来就只需要测准一次。所以：有缓存就用缓存，
        // 缓存失效交给节点 —— 它会在"炮身格数/炮腔/材质变了"（= 玩家重搭过）时清掉重测。
        if (Double.isFinite(fallbackBarrelLength) && fallbackBarrelLength > 0.0) {
            barrelLength = fallbackBarrelLength;
            pivotOffsetY = fallbackPivotOffsetY;
        }
        // 连缓存都没有（首次探测、或存档加载后还没测过）→ 就用这次的结果；
        // 仰角太高导致反解没跑成时只能用直线距离兜着，等炮过一趟低角度就自动修正回来。

        // ---- 射界 ----
        // ⚠️ maximumDepression() 返回的是「俯角的大小」（正值），不是 pitch 坐标系里的负端点。
        // 2026-09-17 实机踩到：这里原来写成 Math.min(depression, elevation)，于是水平炮座的射界
        // 被报成 [30°, 60°]（CBC 默认 maximum_depression=30 / maximum_elevation=60），
        // 而 500 格目标的低伸解只有 3.6° —— 被判成"射界外"，解算直接作废。
        // CBC 自己是这么用的（CannonMountBlockEntity.applyRotation）：
        //     cannonPitch = Mth.clamp(pitch, -maximumDepression(), maximumElevation())
        float minPitch = -poce.maximumDepression();   // 无参版，内部自己传 controller
        float maxPitch = poce.maximumElevation();

        // ---- 最终散布（复刻 CBC 的算法）----
        //
        // CBC 的 spread 从「Σ每个发射药的 getSpread」起步（**问 CBC 拿，见 chargeSpread**），
        // 然后沿炮管**逐格递减** spreadReductionPerBarrel，直到 minimumSpread 为止。
        // ⚠️ 2026-09-18 之前我漏了递减那一步，把「6 包药的 12」直接当 inaccuracy 用，
        // 算出的散布比实测高一个数量级（界面显示 ±62 格，而三发实弹极差只有 5 格）。
        double finalSpread = Math.max(chargeSpread - barrelBlocks * matSpreadReduction, matMinSpread);
        if (!(finalSpread > 0.0)) {
            finalSpread = matMinSpread;
        }
        // 「把散布压到材质下限」所需的炮管格数 —— 护目镜的「炮管太短」提醒要用它。
        // 解 `装药数×2 − 格数×每格减散 ≤ 材质下限`：格数 ≥ (装药数×2 − 材质下限) ÷ 每格减散。
        // ⚠️ 每格减散 ≤ 0（材质数据缺失）→ 给 0 = "不知道阈值" ⇒ 上层**不提醒**，绝不瞎报。
        // ⚠️ 算出来是 0（装药本身就比材质下限还轻）也当作"没什么可减的" ⇒ 同样不提醒 ✓
        int spreadFreeBarrelBlocks = matSpreadReduction > 0.0F
                ? (int) Math.ceil(Math.max(0.0, chargeSpread - matMinSpread) / matSpreadReduction)
                : 0;

        // ---- 加长炮筒提醒要用的装药长 ----
        //
        // ⚠️ 不能只看 GB 注册表解析出来的值。附属模组的发射药不在 GB 的数据里时，那个值是 0，
        // 判定就被跳过、提醒<b>永远不会出现</b>（2026-09-19 实测：1 腔 1 筒 + 火药填充 3/4、
        // 初速 0.0、极限射程 2 格，却没提醒）。而初速公式这时吃的是 GB 的<b>兜底口径</b>，
        // 所以提醒必须退到同一个口径，才能和"真正算出来的初速"对得上。
        double effectiveChargeLength = capturedChargeLengthMeters > 0.0
                ? capturedChargeLengthMeters
                : ActiveBallistics.fallbackChargeLengthMeters(chargePower);

        // ---- 初速与空气物理：交给当前整合包装配的那一套 ----
        double muzzleVelocity = ActiveBallistics.muzzleVelocity(level, cannon, shell, chargePower,
                capturedPowderMassKg, capturedChargeLengthMeters, capturedVelocityMultiplier);
        ExternalBallistics ballistics = ActiveBallistics.ballistics(level, shell, cbcGravity, cbcDrag, cbcQuadratic);

        // ⚠️⚠️ **速射炮闩是单发机构**：CBC 的 ponder 原文是"先装载发射物，<b>然后</b>装载发射剂并关闭炮闩"，
        //    一次只装一发 ⇒ 不管摆了几格炮腔，实际装药上限都是 **1**。
        //    而 CBC 的 {@code getMaxSafeCharges()} 只回答"材质 / 炮闩强度允许多少"，压根不管这件事
        //    （2026-09-24 用户实测：2 格炮腔 + 速射炮闩，界面报「装药 1 / 2」，实际只装得进 1）。
        //
        //    ⚠️ 不能只当成"显示不准"：这个数同时喂给**解算器**（{@code CannonSpec.maxCharges}），
        //       不改的话它会给出一个用 2 包药的解 —— 而那发炮弹玩家**根本装不上**。
        //
        //    判据用的是方块的注册名（CBC 的三档速射炮闩都叫 {@code *_quickfiring_breech}）——
        //    与上面数炮腔用的是同一种做法（{@code contains("cannon_chamber")}），因为 CBC 没有
        //    "这个炮闩是不是单发"的 API。
        boolean quickFiring = breechBlock != null
                && BuiltInRegistries.BLOCK.getKey(breechBlock).getPath().contains("quickfiring");
        // 安全装药上限只在 MountedBigCannonContraption 上有（= min(材质上限, 炮闩强度)）
        int maxSafeCharges = cannon instanceof MountedBigCannonContraption big ? big.getMaxSafeCharges() : 0;
        if (quickFiring && maxSafeCharges > 0) {
            maxSafeCharges = 1;
        }

        // ---- 出口：弹道起点从「图幅坐标」转到「世界坐标」----
        //
        // ⚠️ 炮在航空学物理结构上时，上面反解出来的 pivot 是**图幅坐标**，
        //    而玩家在计算机里输入的打击坐标是**世界坐标** ——
        //    不转这一下，解算就是在拿两套坐标系相减（P0 勘察的结论，见 {@link SableCompat}）。
        //
        // ⚠️ 转的只是这个**点**；`barrelLength` / `pivotOffsetY` **不转** ——
        //    前者是"沿炮轴的刚体长度"、后者是图幅内的竖直偏移，两者都留在图幅系里
        //    （反解本身也是在图幅系里做的：炮的朝向就是图幅系的）。
        //    跨坐标系使用这个长度要求**结构缩放 ≈ 1**，由上层检查并明说。
        //
        // ⚠️ 没装 Sable、或这门炮不在物理结构上时，toWorld **原样返回** ⇒ 逐字维持既有行为。
        // ⚠️ `ActiveBallistics.sableLoaded()` 这一关**不能省**：SableCompat 直接引用了 Sable 的类，
        //    没装 Sable 时它连加载都会失败，而那发生在**类验证期**，类内部的 try/catch 挡不住。
        //    JVM 只在实际执行到这一行时才去加载那个类。
        Vec3 plotOrigin = new Vec3(pivot.x, pivot.y + pivotOffsetY, pivot.z);
        Vec3 origin = ActiveBallistics.sableLoaded()
                ? SableCompat.toWorld(level, plotOrigin)
                : plotOrigin;

        return new ProbedCannon(
                mountPos.immutable(),
                origin.x, origin.y, origin.z,
                barrelLength,
                pivotOffsetY,
                minPitch, maxPitch,
                chargePower, chargeCount, barrelBlocks, chamberBlocks, materialName,
                matMaxStress, breechStrength, breechName, matMinVelPerBarrel,
                finalSpread,
                spreadFreeBarrelBlocks,
                maxSafeCharges,
                muzzleVelocity,
                shellItemId, shellExplosionPower,
                propellantItemId, propellantPower, propellantMaxPower,
                effectiveChargeLength, ActiveBallistics.barrelLengthMeters(cannon),
                // GB 接管了卡弹判定，但它**仍然会判卡弹** —— 管长越长、装药越弱越容易。
                // 炮弹走的是"炮弹之后那些空格"，本探针能数的就是炮筒格数（炮腔里装的是药/弹，不算）。
                ActiveBallistics.wouldCannonSquib(shell, chargePower,
                        Math.max(0, barrelBlocks - chamberBlocks)),
                geometryExact,
                ballistics,
                shell);
    }
}
