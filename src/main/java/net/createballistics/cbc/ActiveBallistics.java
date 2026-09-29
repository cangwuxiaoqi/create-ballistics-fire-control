package net.createballistics.cbc;

import net.createballistics.ballistic.ExternalBallistics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.munitions.big_cannon.propellant.BigCannonPropellantBlock;
import rbasamoyai.createbigcannons.munitions.config.DimensionMunitionPropertiesHandler;

/**
 * 决定<b>当前这一炮该用哪套弹道物理与初速</b>。
 *
 * <h2>联动策略</h2>
 *
 * <ul>
 *   <li>装了「弹道射程优化」(going_ballistic) → 用它的二次阻力 + 真实地球重力，
 *       初速交给它自己的 Robins 实现；</li>
 *   <li>没装 → 用 CBC 原版的线性阻力 + 两倍地球重力，初速 = Σ装药强度。</li>
 * </ul>
 *
 * <p>两者用的是<b>同一套解算核心</b>，只是注入的 {@link ExternalBallistics} 不同 ——
 * 所以玩家装不装那个模组都能打准，不需要它当硬前置。
 *
 * <h2>⚠️ 可选依赖的加载顺序</h2>
 *
 * {@link GoingBallisticCompat} 直接引用了 going_ballistic 的类，而它在没装那个模组的整合包里
 * 是<b>不存在</b>的。所以本类里的每一次调用都包在 {@link #goingBallisticLoaded()} 判断里 ——
 * JVM 只在实际执行到那一行时才会去加载那个类。**判断不能省、也不能提前缓存成别的形式。**
 */
public final class ActiveBallistics {

    private ActiveBallistics() {
    }

    /** 整合包里有没有 going_ballistic。 */
    public static boolean goingBallisticLoaded() {
        return ModList.get().isLoaded(GoingBallisticCompat.MOD_ID);
    }

    /** 整合包里有没有 cbc_at（先进技术）。它会在 shoot 调用点改写初速，见 {@link CbcAtCompat}。 */
    public static boolean cbcAtLoaded() {
        return ModList.get().isLoaded(CbcAtCompat.MOD_ID);
    }

    /**
     * 整合包里有没有 <b>Sable</b>（航空学 / 物理结构）。
     *
     * <p>⚠️ <b>每一次调用 {@link SableCompat} 之前都必须先过这一关</b> ——
     * 那个类直接引用了 Sable 的类，没装 Sable 时它<b>连加载都会失败</b>
     * （{@code NoClassDefFoundError} 发生在类验证期，类内部的 try/catch <b>挡不住</b>）。
     * 写成三元表达式 / if 判断即可：JVM 只在实际执行到那一行时才去加载那个类。
     *
     * <p>（{@code SableCompat.MOD_ID} 是编译期常量，会被内联 ⇒ 读它不会触发类加载。）
     */
    public static boolean sableLoaded() {
        return ModList.get().isLoaded(SableCompat.MOD_ID);
    }

    /**
     * 一种发射药的物理量（药重 / 装药长 / 倍率），由 GB 的 {@code PropellantRegistry} 解析。
     *
     * @param powderMassKg         这一包药的药重（kg）
     * @param chargeLengthMeters   这一包药的装药长（米）。<b>Robins 公式的分子分母都靠它</b>
     * @param velocityMultiplier   该发射药的初速倍率
     */
    public record PropellantProfile(double powderMassKg, double chargeLengthMeters,
                                    double velocityMultiplier) {

        /** 查不到时的空值。调用方据此退回 GB 自己的兜底公式。 */
        public static final PropellantProfile NONE = new PropellantProfile(0.0, 0.0, 1.0);
    }

    /**
     * 问 GB 这一包发射药的药重与装药长。
     *
     * <p>没装 GB、或这包药不在 GB 的注册表里（附属模组新加的、GB 没数据）时返回
     * {@link PropellantProfile#NONE} —— 那时 GB 自己也查不到，会走它自己的兜底公式，
     * 我们跟着走同一条路才是一致的。
     */
    public static PropellantProfile propellantProfile(@Nullable Level level,
                                                      @Nullable BigCannonPropellantBlock propellant,
                                                      @Nullable StructureTemplate.StructureBlockInfo info) {
        if (!goingBallisticLoaded() || level == null || propellant == null || info == null) {
            return PropellantProfile.NONE;
        }
        PropellantProfile profile =
                GoingBallisticCompat.propellantProfile(level.registryAccess(), propellant, info);
        return profile == null ? PropellantProfile.NONE : profile;
    }

    /**
     * 当前这套弹道的<b>语言键</b>，只用于护目镜，让人一眼看出这一炮是按哪套物理算的。
     *
     * <p>⚠️ 返回键名而不是译好的字符串：护目镜跑在<b>客户端</b>，而这里是服务端也会走到的
     * 公共代码 —— 在服务端调 {@code Component.getString()} 只会拿到 en_us，中文玩家会看到英文。
     * 交给客户端 {@code Component.translatable()} 去翻才有正确的语言。
     */
    public static String activeModelKey() {
        return goingBallisticLoaded()
                ? "gui.create_ballistics.model.gb"
                : "gui.create_ballistics.model.vanilla";
    }

    /**
     * 炮口初速（格/tick）。
     *
     * @param cannon      已组装的炮。going_ballistic 要从它估管长
     * @param shell       炮弹实体（可以是临时造的）。为 {@code null} 时退回装药强度
     * @param chargePower CBC 算出的总装药强度
     */
    public static double muzzleVelocity(@Nullable Level level,
                                        @Nullable AbstractMountedCannonContraption cannon,
                                        @Nullable Entity shell, float chargePower,
                                        double capturedPowderMassKg, double capturedChargeLengthMeters,
                                        double capturedVelocityMultiplier) {
        if (goingBallisticLoaded() && cannon != null && shell != null) {
            return GoingBallisticCompat.muzzleVelocity(cannon, shell, chargePower,
                    sourceChargePower(level, cannon, chargePower),
                    capturedPowderMassKg, capturedChargeLengthMeters, capturedVelocityMultiplier);
        }
        // CBC 原版：初速 = MountedBigCannonContraption 传进 shoot() 的那个值 = Σ装药强度。
        //
        // ⚠️ 但不能直接返回 chargePower：装了 cbc_at（先进技术）时，它在**同一个 shoot() 调用点**上
        // 用 @WrapOperation 把初速换成了自己的物理化结果（CustomPropellantContext.getVelocity()），
        // 那才是游戏真正注入炮弹的值。GB 在场时它进一步改写；GB 不在场时它就是终点。
        // 2026-09-18 做原版分支时才发现这一环同样适用 —— 之前只在 GB 分支里处理了。
        if (cannon != null) {
            return sourceChargePower(level, cannon, chargePower);
        }
        return chargePower;
    }

    /**
     * 游戏在 shoot 调用点<b>真正传出去</b>的初速值 —— 也就是 going_ballistic 会收到、
     * 并当成「装药强度」去反推药重的那个数。
     *
     * <p>没装 cbc_at 时它就等于 {@code chargePower}（Σ装药强度）；装了就是 cbc_at 自己算的
     * {@code CustomPropellantContext.getVelocity()}，那才是游戏真正用的输入。
     * <b>2026-09-17 实测</b>：青铜炮 4 药包，两者分别是 4.186058 与 8.0，
     * 差这一步会让解算初速从 9.493 变成 12.985（偏高 37%）。
     */
    private static double sourceChargePower(@Nullable Level level,
                                            AbstractMountedCannonContraption cannon,
                                            float chargePower) {
        if (cbcAtLoaded()) {
            double fromCbcAt = CbcAtCompat.barrelVelocity(level, cannon);
            if (fromCbcAt > 0.0) {
                return fromCbcAt;
            }
        }
        return chargePower;
    }

    /**
     * GB 兜底口径下，给定装药强度对应的装药长（米）。没装 GB 时返回 0。
     *
     * <p>见 {@link GoingBallisticCompat#fallbackChargeLengthMeters} —— 初速公式查不到发射药数据时
     * 吃的就是这个值，所以提醒也必须退到它。
     */
    public static double fallbackChargeLengthMeters(double chargePower) {
        return goingBallisticLoaded()
                ? GoingBallisticCompat.fallbackChargeLengthMeters(chargePower) : 0.0;
    }

    /**
     * GB 查不到发射药数据时，按它自己的规矩折算一块发射药的（药重，装药长）。
     * 没装 GB 时返回 {@link PropellantProfile#NONE}。
     *
     * <p>⚠️ 注意「定装弹的装药长<b>不随填充档位放大</b>」这个不对称，见
     * {@link GoingBallisticCompat#fallbackProfile} —— 漏掉它会把初速算低 20% 以上。
     *
     * @param cartridge 这一块是不是定装弹
     */
    public static PropellantProfile fallbackProfile(boolean cartridge, float chargePower) {
        return goingBallisticLoaded()
                ? GoingBallisticCompat.fallbackProfile(cartridge, chargePower)
                : PropellantProfile.NONE;
    }

    /**
     * GB 会不会判定这门炮卡弹。没装 GB 时返回 false（那条规则由 CBC 原版管，见节点里的另一处提醒）。
     *
     * @param barrelTravelled 弹丸出膛前还要走多少格（炮管格数）
     */
    public static boolean wouldCannonSquib(@Nullable Entity shell, float chargePower, int barrelTravelled) {
        return goingBallisticLoaded()
                && GoingBallisticCompat.wouldCannonSquib(shell, chargePower, barrelTravelled);
    }

    /** GB 估出的管长（米）。没装 GB 或拿不到炮时返回 0。 */
    public static double barrelLengthMeters(@Nullable AbstractMountedCannonContraption cannon) {
        return goingBallisticLoaded() && cannon != null ? GoingBallisticCompat.barrelLengthMeters(cannon) : 0.0;
    }

    /**
     * 空气物理。
     *
     * @param shell 炮弹实体，决定二次阻力系数里的质量与迎风面积
     */
    public static ExternalBallistics ballistics(@Nullable Level level, @Nullable Entity shell,
                                                double cbcGravity, double cbcDrag, boolean cbcQuadratic) {
        if (goingBallisticLoaded() && shell != null) {
            return GoingBallisticCompat.ballistics(level, shell);
        }
        // CBC 原版：重力/阻力来自**弹种自己的数据包属性**（由探针经 CBCMunitionPropertiesHandlers 读出），
        // 再乘维度的倍率。与 AbstractCannonProjectile 逐字对齐：
        //   getDefaultGravity() = ballistics().gravity() × gravityMultiplier      （数据包里是负值，如 −0.05）
        //   getDragForce()      = ballistics().drag()    × dragMultiplier × |v|   （quadratic_drag 时再 ×|v|）
        // 注意别退回硬编码：玩家/整合包改数据包时，硬编码就会算偏。
        double gravityMultiplier = 1.0;
        double dragMultiplier = 1.0;
        if (level != null) {
            var dimension = DimensionMunitionPropertiesHandler.getProperties(level);
            gravityMultiplier = dimension.gravityMultiplier();
            dragMultiplier = dimension.dragMultiplier();
        }
        double gravity = -cbcGravity * gravityMultiplier;   // 取反：本接口的重力约定为正值
        double formDrag = cbcDrag * dragMultiplier;
        if (!(gravity > 0.0) || !(formDrag >= 0.0)) {
            return ExternalBallistics.Linear.CBC_DEFAULT;   // 数据包被改坏时别把非法值塞进解算器
        }
        return new ExternalBallistics.Linear(gravity, formDrag, cbcQuadratic);
    }
}
