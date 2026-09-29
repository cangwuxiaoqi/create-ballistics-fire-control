package net.createballistics.cbc;

import net.createballistics.ballistic.ExternalBallistics;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.munitions.big_cannon.propellant.BigCannonPropellantBlock;
import rbasamoyai.createbigcannons.munitions.config.DimensionMunitionPropertiesHandler;
import org.hectoris919.CBCGoingBallistic.Config;
import org.hectoris919.CBCGoingBallistic.ballistics.BallisticProjectileHelper;
import org.hectoris919.CBCGoingBallistic.ballistics.BallisticsMath;
import org.hectoris919.CBCGoingBallistic.ballistics.ProjectilePhysicsMath;
import org.hectoris919.CBCGoingBallistic.data.BallisticsParameterRegistry;
import org.hectoris919.CBCGoingBallistic.data.PropellantProperties;
import org.hectoris919.CBCGoingBallistic.data.PropellantRegistry;

/**
 * 与「弹道射程优化」(going_ballistic, 下称 GB) 的对接层。
 *
 * <h2>⚠️ 这个类的每一个方法都只能在 GB 已加载时调用</h2>
 *
 * 本类直接引用了 GB 的类。GB 在 {@code build.gradle} 里是 <b>compileOnly 可选依赖</b>，
 * 没装它的整合包里这些类根本不存在 —— 一旦被加载就会 {@code NoClassDefFoundError} 崩游戏。
 *
 * <p>所以<b>调用方必须先用 {@code ModList.get().isLoaded("cbc_going_ballistic")} 判断</b>，
 * 见 {@link ActiveBallistics}。Java 的类加载是惰性的：只要那个判断为假，
 * 本类就永远不会被触碰，引用也就不会解析。**不要把这个类里的东西挪到别处。**
 *
 * <h2>为什么初速一定要问它，而不是自己算</h2>
 *
 * GB 的初速是 Robins 内弹道公式：
 * {@code v = robinsConstant · sqrt(药重/(弹重 + 药重/3) · ln(管长/装药长))}，
 * 而"管长/装药长/药重"都来自它的 {@code BIG_CANNON_SHOT_CAPTURE} —— 开火那一刻
 * 从实际装填里捕获的。自己复刻这套输入，实测会偏 <b>1.46 倍</b>
 * （见 {@code SolverSelfCheck} 的实弹回归）。所以直接调它的函数拿真值。
 */
public final class GoingBallisticCompat {

    /** GB 的模组 id。 */
    public static final String MOD_ID = "cbc_going_ballistic";

    private GoingBallisticCompat() {
    }

    /**
     * 炮弹初速（格/tick）—— 优先直接调 GB 自己的函数，拿到与游戏一字不差的值。
     *
     * <p>但 GB 的开火路径里有一段<b>线程内捕获</b>（{@code BIG_CANNON_SHOT_CAPTURE}，
     * 只在 {@code fireShot} 执行期间存在），我们解算时不在那一刻，够不着它。所以当
     * {@code sourceChargePower} 与 {@code capturedChargePower} 不同（= 有别的模组在
     * shoot 调用点改写了初速，见 {@link CbcAtCompat}）时，改走
     * {@link #replicateCaptureBranch} 精确复刻那一支。
     *
     * @param cannon              已组装的炮（GB 要从它估管长）
     * @param shell               炮弹实体。<b>临时造的也行</b>，只需要它的类型与质量
     * @param capturedChargePower 我们数出来的总装药强度（Σ {@code getChargePower}），
     *                            等于 GB 捕获分支里的 {@code capture.capturedChargePower}
     * @param sourceChargePower   游戏在 shoot 调用点真正传出去的初速值。
     *                            没有别的模组改写时它就等于 {@code capturedChargePower}
     */
    public static double muzzleVelocity(AbstractMountedCannonContraption cannon,
                                        Entity shell, float capturedChargePower,
                                        double sourceChargePower,
                                        double capturedPowderMassKg, double capturedChargeLengthMeters,
                                        double capturedVelocityMultiplier) {
        if (cannon == null || shell == null) {
            return sourceChargePower;
        }
        // ⚠️⚠️ **不要走"直接调 GB 自己那个函数"的捷径**（原来在"没有改写者"时会走）。
        //
        // `BallisticProjectileHelper.calculateCannonLaunchVelocityBlocksPerTick` 靠
        // {@code BIG_CANNON_SHOT_CAPTURE} 拿装药数据，而那是 **thread-local、只在 fireShot 执行期间存在** ——
        // 我们在开火之外调它，它**看不到捕获**，只会走「无捕获」的兜底分支。
        //
        // 当年抄这条捷径的理由是"两种算法数学等价"，**但那只在普通发射药上成立**：
        // 定装弹（big_cannon_cartridge）的装药长在捕获分支里恒为 1 米、在兜底分支里却随当量放大
        // （2026-09-20 实弹抓包：18.31 vs 22.45 格/tick，实测弹坑支持后者）。
        // 所以**永远走我们自己的捕获复刻**，两边才一致。
        return replicateCaptureBranch(cannon, shell, capturedChargePower, sourceChargePower,
                capturedPowderMassKg, capturedChargeLengthMeters, capturedVelocityMultiplier);
    }

    /**
     * 一种发射药在 GB 数据里的物理量。
     *
     * <p><b>必须走 GB 自己的 {@code PropellantRegistry}</b>，不要自己从「装药强度」反推：
     * 反推只能假设"每包药强度 = 2"（即 CBC 标准黑火药）。附属模组的发射药并不遵守这个约定
     * （GB 的数据里 `cbc_at` 的定装药是 1.0～1.5 当量、`cbc_enhanced_shells` 的 Mk1～Mk5 是 1.25～2.25 当量），
     * 推错会让<b>装药长</b>偏大，而 Robins 公式要 {@code ln(管长 / 装药长)} ——
     * 装药长一旦接近管长，ln 就趋近 0，<b>初速会被算成 0</b>（2026-09-19 用户实测：短管炮配大装药）。
     *
     * @return 查不到时返回 {@code null}（调用方退回 GB 自己的兜底算法，那是对的）
     */
    @Nullable
    public static ActiveBallistics.PropellantProfile propellantProfile(
            HolderLookup.Provider registries,
            BigCannonPropellantBlock propellant,
            StructureTemplate.StructureBlockInfo info) {
        try {
            ItemStack extracted = propellant.getExtractedItem(info, registries);
            if (extracted == null || extracted.isEmpty()) {
                return null;
            }
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(extracted.getItem());
            PropellantProperties props = PropellantRegistry.get(extracted, registries)
                    .or(() -> PropellantRegistry.get(itemId))
                    .orElse(null);
            if (props == null) {
                return null;
            }
            PropellantProperties.PropellantKind kind = props.kind();
            // 只认大炮的发射药 —— 与 GB 捕获时那道判断逐字对齐
            if (kind != PropellantProperties.PropellantKind.BIG_CANNON_CHARGE
                    && kind != PropellantProperties.PropellantKind.BIG_CANNON_CARTRIDGE) {
                return null;
            }
            double mass = props.resolvedPowderMassKg();
            double length = props.resolvedChargeLengthMeters();
            if (!(mass > 0.0) || !(length > 0.0)) {
                return null;
            }
            return new ActiveBallistics.PropellantProfile(mass, length, props.velocityMultiplier());
        } catch (LinkageError | RuntimeException e) {
            // GB 自己在捕获失败时也是这么兜的 —— 查不到就退回兜底公式，不要往上抛
            return null;
        }
    }

    /**
     * 精确复刻 GB {@code resolveCannonPropellantProfile} 的<b>捕获分支</b>。
     *
     * <p>原版逻辑（{@code BallisticProjectileHelper}）：
     * <pre>
     * capturedPowerScale = total &lt; captured ? total / captured : 1
     * powderMassKg       = capture.powderMassKg × capturedPowerScale
     *                    + (total − captured)/2 × cannonPowderMass      （未捕获的余量走兜底）
     * chargeLengthMeters = capture.chargeLengthMeters                  ← 注意：不随 scale 变
     * 初速 = Robins(弹重, powderMassKg, chargeLengthMeters, 管长, 倍率) × 管身倍率
     * </pre>
     *
     * <p>{@code capture.powderMassKg} 是 Σ {@code resolvedPowderMassKg()}。对 CBC 的标准黑火药
     * （{@code charge_equivalents = 1.0}）它恰好等于 {@code (装药强度/2) × cannonPowderMass}，
     * 装药长同理 —— 所以这里不需要另接它的 PropellantRegistry，直接用同样的关系算。
     * <b>2026-09-17 用日志逐字核对过</b>：captured=8.0 / total=4.186058 时，
     * 复刻结果 254.49863057855492 kg 与 GB 打的 {@code powderMass=254.49863057855492} 完全一致。
     */
    private static double replicateCaptureBranch(AbstractMountedCannonContraption cannon,
                                                 Entity shell,
                                                 float capturedChargePower,
                                                 double sourceChargePower,
                                                 double capturedPowderMassKg,
                                                 double capturedChargeLengthMeters,
                                                 double capturedVelocityMultiplier) {
        if (Config.disableRealisticBallistics()) {
            return sourceChargePower;
        }

        double massKg = BallisticProjectileHelper.getProjectileMassKg(shell);
        var barrel = BallisticProjectileHelper.estimateMountedBarrelProfile(cannon);

        double captured = Math.max(capturedChargePower, 0.0);
        double total = Math.max(sourceChargePower, 0.0);
        double powderPerCharge = BallisticsParameterRegistry.cannonPowderMass();
        double lengthPerCharge = BallisticsParameterRegistry.cannonChargeLength();

        // ⚠️ 有 GB 注册表解析出来的<b>真值</b>就用真值；只有在那种发射药不在 GB 注册表里时才退回
        // 「每 2 点装药强度算一个当量」的兜底 —— 那也正是 GB 自己查不到时会走的那一支。
        boolean resolved = capturedPowderMassKg > 0.0 && capturedChargeLengthMeters > 0.0;
        double capturedPowderMass = resolved
                ? capturedPowderMassKg
                : captured / 2.0 * powderPerCharge;
        double capturedChargeLength = resolved
                ? capturedChargeLengthMeters
                : captured / 2.0 * lengthPerCharge;
        double velocityMultiplier = resolved && capturedVelocityMultiplier > 0.0
                ? capturedVelocityMultiplier : 1.0;

        double uncapturedEquivalent = Math.max(total - captured, 0.0) / 2.0;
        double scale = total < captured ? total / captured : 1.0;

        double powderMassKg = capturedPowderMass * Math.max(scale, 0.0)
                + uncapturedEquivalent * powderPerCharge;
        // ⚠️ 装药长**不随 scale 变** —— 与 GB 的 resolveCannonPropellantProfile 一致
        double chargeLengthMeters = capturedChargeLength
                + uncapturedEquivalent * lengthPerCharge;

        double velocityMps = BallisticsMath.getRobinsVelocityMps(
                massKg, powderMassKg, chargeLengthMeters,
                barrel.lengthMeters(), velocityMultiplier) * barrel.velocityMultiplier();
        float blocksPerTick = BallisticsMath.mpsToBPTFloat(velocityMps);
        return blocksPerTick > 0.0F ? blocksPerTick : sourceChargePower;
    }

    /**
     * 空气物理 —— 二次阻力 + 真实地球重力，系数从 GB 自己的注册表取，都带维度倍率。
     *
     * <p>与 GB 的 {@code AbstractCannonProjectilePhysicsMixin} 完全对齐：
     * <pre>
     * 重力 = earthGravityBlocksPerTickSquared() × gravityMultiplier
     * 阻力 = [0.5 · (airDensity × dragMultiplier) · Cd · πr² / 质量] · v²
     * </pre>
     */
    public static ExternalBallistics ballistics(Level level, Entity shell) {
        double gravityMultiplier = 1.0;
        double dragMultiplier = 1.0;
        if (level != null) {
            var dimension = DimensionMunitionPropertiesHandler.getProperties(level);
            gravityMultiplier = dimension.gravityMultiplier();
            dragMultiplier = dimension.dragMultiplier();
        }

        // 注意 GB 的 earthGravityBlocksPerTickSquared() 自带负号，本 mod 的接口要正值
        double gravity = -ProjectilePhysicsMath.earthGravityBlocksPerTickSquared() * gravityMultiplier;

        double massKg = shell == null
                ? BallisticsParameterRegistry.cannonPowderMass()
                : BallisticProjectileHelper.getProjectileMassKg(shell);
        double radiusMeters = shell == null
                ? ProjectilePhysicsMath.cannonShellRadiusMeters()
                : ProjectilePhysicsMath.referenceRadiusMeters(shell);
        double airDensity = BallisticsParameterRegistry.airDensity() * dragMultiplier;
        double k = ProjectilePhysicsMath.quadraticDragCoefficient(
                massKg, radiusMeters,
                BallisticsParameterRegistry.projectileDragCoefficient(),
                airDensity);

        if (!(gravity > 0.0) || !(k > 0.0)) {
            // 数据包被改坏时别把非法值塞进解算器
            return ExternalBallistics.Quadratic.GB_AP_SHELL;
        }
        return new ExternalBallistics.Quadratic(gravity, k);
    }

    /** GB 的 Robins 常数（m/s）。仅用于日志与自检对照。 */
    public static double robinsConstantMps() {
        return BallisticsParameterRegistry.robinsConstantMps();
    }

    /**
     * GB 自己的<b>卡弹判定</b>（能量模型：火药能量 vs 把弹丸推过剩余管长所需的功）。
     *
     * <p>CBC 原版那条「每格掷骰」的规则被 GB 整个接管了，换成这套宽松得多的模型 ——
     * 但它<b>仍然会判定卡弹</b>：管长越长、装药越弱，越容易卡。
     * 2026-09-20 实测：63 格炮管 + 大型弹药 1/4、2/4、3/4 全部卡弹（火药被烧掉、炮弹留在膛里、
     * 玩家只看到"按了没反应"），只有 4/4 推得动。**这是 GB 的物理，不是我们的 bug** ——
     * 但玩家必须被告知，否则完全无从判断。
     *
     * @param barrelTravelled 弹丸出膛前还要走多少格（炮管格数）
     */
    public static boolean wouldCannonSquib(Entity shell, float chargePower, int barrelTravelled) {
        return shell != null && BallisticProjectileHelper.wouldCannonSquib(shell, chargePower, barrelTravelled);
    }

    /**
     * GB <b>兜底口径</b>下，给定装药强度对应的装药长（米）。
     *
     * <p>GB 查不到发射药数据时用的就是「每 2 点装药强度算 1 个装药当量」，每个当量
     * {@code cannonChargeLength()} 米 —— 见它的 {@code resolveCannonPropellantProfile} 的 else 分支。
     *
     * <p>这条兜底值<b>同样是初速公式真正吃进去的东西</b>，所以任何"我要看装药长"的地方
     * （比如护目镜的加长炮筒提醒）都必须在查不到真值时退到它，否则会算出 0 而让提醒落空。
     */
    public static double fallbackChargeLengthMeters(double chargePower) {
        return Math.max(chargePower, 0.0) / 2.0 * BallisticsParameterRegistry.cannonChargeLength();
    }

    /**
     * GB <b>查不到发射药数据</b>时，按它自己的规矩折算一块发射药的（药重，装药长）。
     *
     * <p>规矩来自 {@code PropellantProperties} 的两个方法，<b>两者不对称</b>：
     *
     * <pre>
     *   resolvedPowderMassKg()     = 装药当量 × 每包药重           ← 两种情况都随当量放大
     *   resolvedChargeLengthMeters()
     *       · 普通发射药 BIG_CANNON_CHARGE    = 当量 × 每包装药长   ← 随当量放大
     *       · 定装弹     BIG_CANNON_CARTRIDGE = 每包装药长（1 米）  ← <b>恒定，不放大</b>
     * </pre>
     *
     * <p>⚠️⚠️ <b>这个不对称害死过人。</b> 2026-09-20 实弹抓包：同一门 63 格炮管的炮、同样 4/4 装药，
     * GB 开火日志分成两种 ——
     * <pre>
     *   有捕获：chargeLength=1.0 m → 初速 22.45 格/tick  （= 实测弹坑反推的 22.7 ✓）
     *   无捕获：chargeLength=4.0 m → 初速 18.31 格/tick
     * </pre>
     * 我们只复刻了<b>无捕获</b>那一支，于是把初速算低 22%、弹道全偏。
     * <b>普通发射药的两种算法结果相同（当量 1.0），所以只有「大型弹药」这类定装弹会暴露。</b>
     *
     * @param cartridge 这一块是不是定装弹（{@code BigCartridgeBlock}）
     */
    public static ActiveBallistics.PropellantProfile fallbackProfile(boolean cartridge, float chargePower) {
        double equivalents = Math.max(chargePower, 0.0) / 2.0;
        double powder = equivalents * BallisticsParameterRegistry.cannonPowderMass();
        double length = cartridge
                ? (equivalents > 0.0 ? BallisticsParameterRegistry.cannonChargeLength() : 0.0)
                : equivalents * BallisticsParameterRegistry.cannonChargeLength();
        return new ActiveBallistics.PropellantProfile(powder, length, 1.0);
    }

    /**
     * GB 从炮架估出的<b>管长</b>（米）—— 就是它初速公式里用的那一个值。诊断用。
     *
     * <p>它的 {@code estimateMountedBarrelProfile} 是从 {@code contraption.getStartPos()} 沿
     * {@code initialOrientation} 逐格累加 {@code CannonComponentProperties.lengthMeters()}，
     * 默认每格 1 米。把它显示出来，就能和「炮身 N 格」对照 —— 两者若不等，初速那一环必有蹊跷。
     */
    public static double barrelLengthMeters(AbstractMountedCannonContraption cannon) {
        return BallisticProjectileHelper.estimateMountedBarrelProfile(cannon).lengthMeters();
    }

    /** m/s → 格/tick。GB 的换算是除以 20（1 格 = 1 米、20 tick/秒）。 */
    public static double mpsToBlocksPerTick(double mps) {
        return BallisticsMath.mpsToBPT(mps);
    }
}
