package net.createballistics.block.entity;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import java.util.List;
import java.util.Locale;
import net.createballistics.ballistic.ArcMode;
import net.createballistics.ballistic.BallisticSolver;
import net.createballistics.ballistic.CannonSpec;
import net.createballistics.ballistic.FireMode;
import net.createballistics.ballistic.FiringSolution;
import net.createballistics.ballistic.TrajectorySimulator;
import net.createballistics.CreateBallistics;
import net.createballistics.cbc.ActiveBallistics;
import net.createballistics.cbc.CannonProbe;
import net.createballistics.cbc.ProbedCannon;
import net.createballistics.cbc.SableCompat;
import net.createballistics.cbc.TerrainCheck;
import net.createballistics.registry.ModBlockEntities;
import net.createballistics.util.PosDisplay;
import net.createballistics.util.Texts;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import net.minecraft.server.level.ServerLevel;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;

/**
 * 火控节点 —— 一门炮的炮位控制器。
 *
 * <h2>它做什么</h2>
 * <ol>
 *   <li><b>找炮</b>：挨着炮座<b>任意一面</b>放都行 —— 扫六个邻居找 {@link CannonMountBlockEntity}。</li>
 *   <li><b>纳入火控系统</b>：被协调器登记进网络后，负责这门炮的俯仰与回旋。</li>
 *   <li><b>自己解算</b>：只收<b>目标坐标</b>，用 {@link CannonProbe} 探自己那门炮再解算 ——
 *       多炮因此各自准。</li>
 *   <li><b>平滑瞄准</b>：照抄参考模组 {@code firecontrol} 的增量伺服，见 {@link #stepTowards}。</li>
 *   <li><b>开火</b>：走 CBC 自己的红石发射路径，不需要 Mixin。</li>
 * </ol>
 *
 * <h2>⚠️ 为什么存 {@code mountPos} 而不是方块实体引用</h2>
 *
 * 护目镜的 {@code addToGoggleTooltip} 跑在<b>客户端</b>。方块实体引用没法同步过去，
 * 客户端那边会永远是 null，护目镜就会报"没贴炮座" —— 2026-09-17 实机踩过这个坑。
 * 所以这里只存坐标（能进 NBT、能同步），两端各自用 {@link #cannon()} 解析。
 * 同理，护目镜要用的探针结果也缓存成标量一起同步。
 *
 * <h2>为什么瞄准要"限速逼近"而不是直接赋值</h2>
 *
 * CBC 的 {@code CannonMountBlockEntity.tick()} 自己也在按转速积分 {@code cannonPitch/Yaw}
 * 并夹紧到射界。所以必须每 tick 都写（否则被推走 → 炮塔抖），但也不能瞬移（那是卡顿，
 * 而且会和 CBC 自己的积分打架）。做法是读当前角、算误差、每 tick 只挪一小步。
 */
public class FireControlNodeBlockEntity extends KineticBlockEntity implements IHaveGoggleInformation {

    /** 应力基准值：每 1 RPM 消耗多少 SU。实际消耗 = 本值 × |转速|，乘转速由 Create 做。 */
    public static final float STRESS_BASE = 8.0F;

    /**
     * 每 tick 最大转角 = |转速| × 本值（度）。
     *
     * <p>抄自参考模组 {@code firecontrol} 的 0.03，但<b>折半成 0.015</b> —— 节点实测跑在
     * 256 RPM 时，0.03 相当于 7.7°/tick（≈154°/秒），转得太急；0.015 是它的一半，观感更稳。
     */
    private static final float SLEW_PER_RPM = 0.015F;
    /** 步长下限（度/tick）。照抄 firecontrol 的 0.3 —— 保证误差再小也能收敛到位。 */
    private static final float MIN_STEP_DEG = 0.3F;
    /** 到位判定（度）。两个轴都小于它就停手。 */
    private static final float SETTLE_DEG = 0.05F;
    /** 重新解算的间隔（tick）。 */
    private static final int RECALC_INTERVAL = 20;
    /**
     * 刷新"探针缓存"的间隔（tick）。
     *
     * <p>探针结果（装药数/初速/射界）<b>必须独立于瞄准任务刷新</b> —— 否则没下过军令时
     * 护目镜里全是 0（2026-09-17 实机踩过）。刷新要造一个临时弹丸去问 going_ballistic 要初速，
     * 所以节流到 2 秒一次，不每 tick 跑。
     */
    private static final int PROBE_INTERVAL = 40;

    /**
     * 「装药长 / 管长」超过这个比例就提醒玩家<b>加长炮筒</b>。
     *
     * <p>初速公式里是 {@code ln(管长/装药长)}：比例到 1.0 时 ln 归零、初速变成 0。
     * 取 0.6 而不是 1.0，是想在<b>还能打、但已经很吃力</b>的时候就提醒 ——
     * 等到真的归零，玩家看到的就是"极限射程 2 格"这种完全摸不着头脑的数了。
     */
    private static final float CHARGE_LENGTH_WARN_RATIO = 0.6F;

    /**
     * 「极限射程短到这个数以下」且装药长/管长都算不出来时，也要提醒一句 —— 那是"打不出去"的意思。
     *
     * <p>取 20 格：一门正常装填好的炮，哪怕是最短的管子也不该只有这么点射程。
     */
    private static final float ABSURD_RANGE = 20.0F;

    /** 挨着哪一面都行 —— 六个邻居里找到的炮座坐标。见类注释的警告。 */
    @Nullable
    private BlockPos mountPos;

    // ---- 探针缓存（护目镜与协调器界面要用，必须同步到客户端）----
    private int probedCharges;
    private int probedMaxCharges;
    /**
     * 炮身格数 —— 沿炮轴扫到的方块数。
     *
     * <p>它参与散布计算（CBC 的药包散布沿炮管逐格递减，见 {@code CannonProbe}），
     * 同时也直接告诉玩家这门炮有多长。
     */
    private int probedBarrelBlocks;
    /** 炮身材质名（CBC 的 material id path，如 bronze / nethersteel）。 */
    private String probedMaterial = "";
    /** 炮腔格数 —— 决定这门炮**实际**能装几包药（材质上限往往比它大）。 */
    private int probedChambers;
    /** 预计落点散布（±格）—— <b>横向</b>（方位）分量。给玩家一个"这门炮能打多准"的预期。 */
    private float probedSpread;
    /**
     * 落点散布的<b>横向地板</b>（格）—— 与散布无关的那一部分横向误差。
     *
     * <p>⚠️ 2026-09-27 用用户 34 发实测反推出来的（5 格炮筒：预测典型值 0.7、实测平均 1.9）：
     * {@code 横向 = √(散布² + 地板²)}，地板 ≈ 1.5 格 ⇒ √(0.7²+1.8²) = 1.9 ✓ 对上了。
     * <b>纵向不需要地板</b> —— 它吃的是同一个 σ，而实测吻合（0.8×）。
     *
     * <p>物理来源：<b>方位（yaw）方向的定位离散</b> —— 伺服步进、{@code AIM_SYNC_EPS} 量化、
     * 炮座朝向的离散化，都会给横向加一个"不随炮管变化"的底噪；它随射程线性放大
     * （1.5 格 / 118 格 ≈ 0.7°）。
     * 直接证据：9 格炮筒那批<b>预测 ±0.1、实测 0.4~1.0</b> —— 那些全是地板，不是散布。
     *
     * <p>⚠️ 这是**经验常数**（5 格那批反推 1.78、9 格那批反推 ~1.0，折中取 1.5）。用户拍板。
     */
    private static final double LATERAL_FLOOR_BLOCKS = 1.5;
    /**
     * 预计落点散布的<b>纵向</b>（射程）分量（±格）。
     *
     * <p>⚠️⚠️ 必须与横向分开算（2026-09-27 用户实测定案）：`Projectile#shoot` 的 inaccuracy
     * <b>同时扰动方向向量的三个分量</b> ⇒ 落点误差有两个独立分量：
     * <ul>
     *   <li><b>横向</b> ≈ {@code 0.0172275 × 散布 × 水平射程} —— <b>与弹道形状无关</b>；</li>
     *   <li><b>纵向</b> ≈ {@code |dR/dθ| × σ角} —— 低伸的 {@code dR/dθ} 很小（几度才差几格），
     *       而<b>高抛被放大十几倍</b>。</li>
     * </ul>
     * 原来只报了横向 ⇒ 打 81° 吊射时护目镜报 ±7 格、实测散到 ±30~57 格（差 6 倍）。
     * **这个缺项只在打高抛时暴露**，平射看不出来。
     */
    private float probedSpreadInLine;
    /**
     * 把散布压到材质下限所需的<b>炮管格数</b>（0 = 没有这个阈值 / 数据缺失）。
     *
     * <p>炮管不足这么多格时，护目镜要额外说一句「炮管太短 ⇒ 散布压不下去，加长才更准」
     * （2026-09-27 用户拍板）。⚠️ 它由 (材质, 炮管格数, 装药数) 推出，而这三个都已在
     * 探针缓存的"变了才刷新"判据里 ⇒ <b>不必再加进那个判据</b>。
     */
    private int probedSpreadFree;
    private float probedVelocity;
    private float probedMinPitch;
    private float probedMaxPitch;

    // ---- 组建信息（护目镜的「这门炮是怎么搭的」，以及新手向的风险提醒）----
    /** 材质装药上限，<b>不含炮闩</b>。与炮闩强度分开报，玩家才知道是哪一环卡住了。 */
    private int probedMaterialMaxStress;
    /** 炮闩强度。实际装药上限 = min(材质上限, 本值)。 */
    private int probedBreechStrength;
    /** 炮闩方块的语言键。只存键名，客户端自己翻译（服务端翻译只会拿到英文）。 */
    private String probedBreechName = "";
    /**
     * 卡弹系数。CBC 原版规则是 {@code 炮筒格数 > 装药强度 / 本值} 就逐 tick 掷骰卡弹。
     *
     * <p>⚠️ 只在<b>没装 going_ballistic</b> 时才会拿它给玩家报警告 —— 装了 GB 时这个判定
     * 由 GB 接管且宽松得多，照原版规则报警是错误信息。见 {@link #appendBuildWarnings}。
     */
    private float probedMinVelPerBarrel;
    /** 已装填的总装药强度（Σ 每包 {@code getChargePower}，黑火药 = 包数 × 2）。卡弹阈值要用它。 */
    private float probedChargePower;
    /** 已装填弹丸方块的语言键。 */
    private String probedShellItemId = "";
    /**
     * 已装填发射药的方块语言键。
     *
     * <p>为什么要显示它：初速那套 Robins 公式对<b>装药长</b>极其敏感（{@code ln(管长/装药长)}），
     * 而装药长由发射药种类决定 —— CBC 的「大型弹药」按填充档位就是 1/2/3/4 米，
     * 短管炮塞高档位会让 ln 趋近 0、初速被算成 0。不把发射药摆出来，玩家看到"初速 0.0"
     * 根本无从下手（2026-09-19 实际发生过）。
     */
    private String probedPropellantId = "";
    /** 定装弹（大型弹药）的当前填充档位 / 上限。不是定装弹时都是 0。 */
    private int probedPropellantPower;
    private int probedPropellantMaxPower;
    /** 装药长与管长（米）。装药长逼近管长时 Robins 的 ln 趋近 0、初速归零 —— 提醒要用它们。 */
    private float probedChargeLengthMeters;
    private float probedBarrelMeters;
    /** GB 判定这门炮会卡弹（打不出去）。见 {@link ProbedCannon#squib()}。 */
    private boolean probedSquib;
    /** 弹丸的方块破坏爆炸威力，用来估「最近安全距离」。惰性弹为 0。 */
    private float probedExplosionPower;
    /** 水平极限射程（格）。<b>只在炮的参数变化时重算</b> —— 它比单次解算贵得多。 */
    private float probedMaxRange;
    /** 最近安全距离（格）：再近的话弹着爆炸可能波及炮位自身。估算值，见 {@link #safeRange}。 */
    private float probedMinSafeRange;
    /**
     * 上次算极限射程时用的炮轴长度。
     *
     * <p>它不是给人看的，只是「参数变了没有」的判据之一 —— 炮轴长度变了，射程也会变。
     * 因此<b>不需要同步到客户端</b>（探针只在服务端跑）。
     */
    private double probedBarrelLengthForRange = -1.0;

    /**
     * 上线到哪一台火控协调器 —— 由协调器扫描时<b>写回</b>（见 {@code FireControlCoordinatorBlockEntity.scanNodes}）。
     *
     * <p>火控网络的登记是<b>协调器单向扫描</b>的，节点自己并不知道谁登记了它。要显示
     * 「是否已接入火控网络」就必须反向写回这个坐标 —— 不能靠"附近恰好有个协调器"去猜。
     */
    @Nullable
    private BlockPos masterPos;

    // ---- 火力任务 ----
    private boolean hasTarget;
    private double targetX;
    private double targetY;
    private double targetZ;
    /** 实际用来解算的高度 —— 玩家填的高度被「吸附到地面」之后的值。护目镜要显示它。 */
    private double aimHeight;
    /** 解出来的目标角，<b>已经转成 CBC 裸 cannonPitch/Yaw 的空间</b>。 */
    private float targetPitch;
    private float targetYaw;
    private boolean aiming;
    private boolean fireQueued;

    /** 最近一发有没有真的打出去（CBC 卡弹/炸膛会整发消耗掉却不出弹，见 {@code tryFire}）。 */
    private boolean lastFireFailed;
    private boolean solvable;
    private int recalcCooldown;
    private int probeCooldown;

    // ---- 弹道弧线的选择（见 chooseArc）----
    /** 弹道模式 —— 由协调器随目标一起下发。 */
    private ArcMode arcMode = ArcMode.AUTO;
    /** 上次解算实际用了哪条：0 = 没打成，1 = 低伸，2 = 高抛。护目镜显示用。 */
    private byte arcChoice;
    /** 上次解算的结果说明（语言键）。空串 = 没什么可说的（正常命中）。 */
    private String arcNoteKey = "";
    /** 说明里的两个数（撞到的距离 / 需要多少度 / 射界的端点）。 */
    private double arcNoteA;
    private double arcNoteB;
    /**
     * 上次"打不了"诊断用的输入指纹。
     *
     * <p>诊断要放宽射界解两次（每次扫七百多个仰角），而解不出来的炮会每 20 tick 重解一次 ——
     * 输入没变就不必重算，同一个目标同一门炮的结论不可能变。见 {@link #diagnoseUnreachable}。
     */
    private String diagKey = "";

    /**
     * 上一次<b>精确反解</b>出来的炮轴长度与支点偏移（没有就 {@link Double#NaN}）。
     *
     * <p>炮口仰到 80° 以上时探针的反解会被跳过（要除以 {@code cos}，噪声会被放大）——
     * 那时候必须<b>沿用这里的好值</b>，不能让几何退回到"直线距离 + 偏移 0"。
     * 否则同一个目标在"炮放平"和"炮仰起"两种状态下会得到<b>差 2 格</b>的起点，
     * 足以让地形校验在炮口附近的边界上反复翻面，AUTO 模式就会卡在切换弹道的死循环里
     * （2026-09-21 用户实测：炮口前一格有方块时仰头低头来回摆）。
     *
     * <p>刻意<b>不持久化</b>：存档加载后第一次探针如果炮正好仰着，就用直线距离兜一次；
     * 把炮放平一次就自动修正回来了。
     */
    private double goodBarrelLength = Double.NaN;
    private double goodPivotOffsetY = 0.0;

    /**
     * 「所在的物理结构被非均匀缩放」的警告是否已经发过（每节点一次）。
     * 刻意不持久化 —— 重启后重发一次无所谓，总比刷屏好。见 {@link #warnIfStructureScaled}。
     */
    private boolean warnedScaled = false;

    /**
     * 载具速度（格/tick，世界系）；由**完整解算**那条路刷新，增量路径复用。
     * 用途见 {@link #LEAD_PER_AIR_TICK}。
     */
    private double platformVelX;
    private double platformVelY;
    private double platformVelZ;

    /**
     * ⭐ <b>提前量（tick）</b> = <b>射程比例误差 k × 解算射程 ÷ 载具水平速度</b>。
     *
     * <h2>为什么是这个形式（四轮实弹排查的结论）</h2>
     *
     * 飞艇上打静止目标，落点**沿行进方向偏前**，量级 ∝ 射程（130 格偏 7.3、180 格偏 10.4
     * ⇒ 比例稳定在 ≈5%）。逐项排除（位置时序差 0.07~0.23 tick、炮塔伺服无滞后、
     * 前馈只有 0.1°、炮弹初速对得上 ±1%）之后，唯一剩下的解释：
     * <b>解算器的弹道模型把射程算短了约 5%</b>。
     *
     * <p>于是"该补多少"就是一道几何题：模型少算了 {@code k × 射程} 格 ⇒ 把解算用的枢轴
     * **前移同样多格**，落点就正好补回来；换算成 tick 就是除以载具的水平速度。
     * <ul>
     *   <li>用实测数据验算：{@code 0.05 × 130 ÷ 6.9 = 0.94}、{@code 0.05 × 177 ÷ 7.0 = 1.26}
     *       —— 与实测需要的 0.90~1.01 / 1.25~1.35 **逐组吻合** ✓</li>
     *   <li>⚠️ 之前那个 `系数 × 飞行时间` 的形式只是它在 `弹速 ≈ 载具速度` 时的近似
     *       （这门炮恰好两者同量级 6.6 / 6.9 ⇒ 才work）；换成 `k × 射程 ÷ 速度` 之后
     *       **与弹速无关、与平台速度无关**，物理上才是对的。</li>
     * </ul>
     *
     * <h2>k 从哪来 —— <b>它自己学</b>（{@link ShotCalibration}）</h2>
     *
     * k 是**弹道模型自己的属性**，所以可以从每一发的落点反推并累积：
     * {@code k_观测 = 落点沿行进方向的偏移 ÷ 解算射程}。每打一发学一点，存在节点 NBT 里。
     * ⇒ **没有手动标定文件、也没有魔法常数**（用户 2026-09-26 拍板："既然能做到自动计算，
     * 就没必要这个手动标定文件了"）。
     *
     * <p>⚠️ 完整解算与增量解算**必须用同一个公式**（原来是两处各写一遍，靠注释提醒"必须一致"）
     * —— 现在收成这一个方法，结构上就不可能再漂。
     * ⚠️ 静止平台（水平速度 ≈ 0）给 0：那时提前量本来就没意义（也没有可补的位移）。
     */
    private double leadTicksNow() {
        double speedH = Math.hypot(platformVelX, platformVelZ);
        if (speedH < 0.02 || solutionRange <= 0.0) {
            return 0.0;
        }
        return calibration.k() * solutionRange / speedH;
    }

    // ------------------------------------------------------------------
    // ⭐ 实时计算（航空学载具在动 ❯ 每 tick 增量重解）—— P2
    // ------------------------------------------------------------------

    /**
     * 探针给的**图幅**枢轴。⭐ <b>这是产品状态，不是诊断</b>：
     * <ul>
     *   <li>{@link #updateMotionFlags} 拿它问 Sable "这一点的速度是多少"（⇒ 两个动/静旗子）；</li>
     *   <li>{@link #cheapResolve} 拿它换算**当前**世界枢轴 —— 探针 40 tick 才刷一次，
     *       世界位置早就过时了（实测差到过 948 格）。见 {@code CannonProbe.plotPivot}。</li>
     * </ul>
     */
    private double probedPlotPivotX;
    private double probedPlotPivotY;
    private double probedPlotPivotZ;
    /** 上一次的完整解 —— 增量重解的初值（它的装药数/弧线类型也一起沿用）。 */
    private FiringSolution lastSolution;
    /** 上一次解算用的炮参数 —— 增量路径复用，不重新探炮。 */
    private CannonSpec lastSpec;
    /** 载具是否"真的在飞"—— <b>只用于状态文案</b>（阈值高，见 {@link #TEXT_ENTER}）。 */
    private boolean platformMoving;

    // ⭐ 实弹标定（产品功能，不是诊断）：从每一发的落点反推"射程比例误差 k"，越打越准。
    //    见 {@link net.createballistics.cbc.ShotCalibration} 的类注释（含三道防呆）。
    private final net.createballistics.cbc.ShotCalibration calibration =
            new net.createballistics.cbc.ShotCalibration();

    /**
     * 最近一次解算用的**射程**（枢轴 → 目标，水平，格）—— 提前量按它换算（见 {@link #leadTicksNow()}）。
     * 每 tick 都在重解 ⇒ 它一直新鲜。
     */
    private double solutionRange;

    // ------------------------------------------------------------------
    // ⭐ P3：炮塔"跟不上目标"（转速不够）—— 判定、同步、以及给玩家看的数
    // ------------------------------------------------------------------

    /**
     * 炮塔是否**持续跟不上**（= 需要提示玩家加转速）。
     *
     * <p>判据见 {@link #stepTowards}：伺服每 tick 最多转 {@code |转速| × SLEW_PER_RPM} 度，
     * 而这个上限**被夹住**、并且误差**没有在缩小** ⇒ 就是"追不上"，不是"正在转过去"。
     */
    private boolean rpmShort;
    /** 跟得上所需的转速（RPM，向上取整）。只在 {@link #rpmShort} 为真时有意义。 */
    private int rpmNeeded;
    /** 连续"被夹住"的 tick 数（滞回用，见 {@link #RPM_SHORT_TICKS}）。 */
    private int rpmShortRun;
    /** 连续"没被夹住"的 tick 数（滞回的另一半）。 */
    private int rpmOkRun;
    /** 上一次"误差没在缩小"的采样结果（见 {@link #RPM_TREND_INTERVAL}）。 */
    private boolean rpmTrendWorse;
    /** 上一次采样误差模长的时刻/数值。 */
    private long rpmTrendTick;
    private double rpmTrendError;
    /** 上一次**同步出去**的 {@link #rpmNeeded} 与时刻（避免每 tick 发包）。 */
    private int rpmNeededSynced;
    private long rpmSyncTick;
    /** 提示期间刷新那个数的最小间隔（tick）—— 半秒一次足够，玩家看的是"大概要多少"。 */
    private static final int RPM_SYNC_INTERVAL = 10;

    /**
     * 连续被夹住多少 tick 才敢说"跟不上"（＝ 1 秒）。
     *
     * <p>⚠️ 不能一削顶就报：给一个**全新的远目标**时，误差本来就是一大截，伺服要靠削顶
     * 一步步转过去 —— 那是"正在转"、不是"跟不上"（{@link #RPM_TREND_INTERVAL} 那一路
     * 才是分辨这两者的判据，这个计数是第二道保险）。
     */
    private static final int RPM_SHORT_TICKS = 20;
    /** 连续多久没削顶才清掉提示（＝ 1 秒，滞回的另一半）。 */
    private static final int RPM_OK_TICKS = 20;
    /** 每多少 tick 采一次"误差模长"，用来判断它有没有在按伺服应有的速度缩小。 */
    private static final int RPM_TREND_INTERVAL = 10;
    /**
     * 这一段里误差至少缩掉"**伺服本来能缩的量**"的百分之几，才算"在收敛"。
     *
     * <p>⚠️ 必须按 {@code step × ticks} 折算，**不能写死百分比** —— 转速越低，
     * 同样一段时间能缩的越少，写死百分比会把"正在慢慢转过去"误判成"跟不上"
     * （第一版就是这么错的，用户看到几千 RPM）。
     */
    private static final double RPM_TREND_PROGRESS = 0.5;
    /** 瞄准是否**需要持续刷新** —— 用于解算节奏（阈值低，见 {@link #MOVE_ENTER}）。 */
    private boolean aimTracking;
    /** 目标角**这一 tick 漂了多少**（度）—— 供 {@link #stepTowards} 前馈（只在增量路径里算）。 */
    private float aimDriftYaw;
    private float aimDriftPitch;
    /** 增量路径的同步节流（见 {@link #applySolution}）。 */
    private long lastAimSyncTick;

    /**
     * 「瞄准需要持续刷新」的速度阈值（格/tick ≈ 0.4 m/s）—— <b>低</b>，因为这件事几乎不要钱
     * （每 tick 5~9 次弹道积分），而且晚一步就要吃"诸元过期"的亏。
     */
    private static final double MOVE_ENTER = 0.02;
    /** 上面那个的退出阈值 —— 比进入阈值低，构成滞回。 */
    private static final double MOVE_EXIT = 0.01;
    /**
     * 「显示成实时计算」的速度阈值（格/tick ≈ 6 m/s）—— <b>高</b>。
     *
     * <p>⚠️ 为什么不跟 {@link #MOVE_ENTER} 用同一个：<b>停着的飞艇本身就有残余漂移</b>
     * （2026-09-25 实测 0.051~0.077 格/tick —— 它是个浮空物理体，永远到不了绝对静止）。
     * 而"真的在飞"是 6.7 格/tick，两者差 <b>一百倍</b> ⇒ 阈值放在中间就分得干干净净。
     * 拿低阈值去显示，玩家把艇停稳了却看到「实时计算」（实机就是这么报的 ✗）。
     */
    private static final double TEXT_ENTER = 0.3;
    /** 上面那个的退出阈值（滞回）。 */
    private static final double TEXT_EXIT = 0.15;
    /** 增量路径的同步间隔（tick）：每 tick 重解，但**不能**每 tick 都发数据包。 */
    private static final int CHEAP_SYNC_INTERVAL = 5;
    /** 角度变化小于它就认为"没动"，不必同步（度）。 */
    private static final float AIM_SYNC_EPS = 0.01F;

    /**
     * 自动模式<b>对当前目标已经定下来的</b>弹道（0 = 还没定，1 = 低伸，2 = 高抛），
     * 以及它所对应的输入指纹（换了目标/装填就重新决策）。
     *
     * <p>为什么要"定下来就不改"：地形校验必然有边界，而炮转动时探针读到的几何会有微抖动，
     * 于是"挡住/没挡住"能自激翻面 ⇒ AUTO 跟着来回切 ⇒ 炮上下抽。
     * <b>一次火力任务本来就该用一套诸元</b>，中途改弹道对着同一个目标是没意义的。
     * 重新点一次「确认坐标并开始计算」（{@link #aimAt}）即可清掉它、重新决策。
     */
    private byte arcLatched;
    private String arcLatchKey = "";

    /**
     * 当前这套诸元的<b>飞行时间</b>（tick，炮弹从出膛到落地）。
     *
     * <p>同一条目标、同一门炮，这个数由解算给出。同时弹着（TOT）要靠它反推各炮的击发时刻：
     * <b>飞得久的先打</b>，落点才会同时。也让玩家在护目镜上看得见"这发要飞多久"。
     */
    private double solutionAirTime;

    /** 求 {@code dR/dθ} 时的仰角扰动幅度（度）。太小会被积分步长吃掉，太大差商就不线性了。 */
    private static final double DELTA_DEG = 0.25;

    /**
     * 某条解在<b>沿射向</b>方向上的散布（±格）—— 落点误差的<b>纵向</b>分量。
     *
     * <p>做法：把仰角左右各扰动 {@link #DELTA_DEG}，看"落在瞄准高度上的水平距离"变化多少，
     * 得到 {@code dR/dθ}（格/度）；再乘上角度的扰动幅度
     * {@code σ角 = toDegrees(0.0172275 × inaccuracy)}（度）。
     *
     * <p>⚠️ 为什么值得单算：这是个<b>纯几何放大</b> —— 低伸时 {@code dR/dθ} 只有几格/度，
     * 高抛时能到十几格/度 ⇒ 同一个 inaccuracy，纵向散布差一个数量级。
     * 用户实测（2026-09-27）：3 格炮筒 + 81.6° 吊射，护目镜原来只报横向 ±7 格，
     * 而纵向 ≈ ±47 格、实测散到 ±30~57 格。
     *
     * <p>两侧有一侧解不出来时返回 0 —— <b>不虚报</b>。
     */
    private static double inLineSpread(FiringSolution solution, ProbedCannon probed, double aimY,
                                       double targetRange) {
        final double sigmaDeg = Math.toDegrees(0.0172275 * probed.finalSpread());
        // ⚠️⚠️ **穿越点分支只判一次**（按原解那个仰角判），扰动时强制用同一支。
        //
        // 原来是对每个扰动后的仰角各自"挑离目标近的穿越点" —— 而仰角一动，上升段穿越点与
        // 下降段落点的远近关系就可能**翻转**（下降段在几百格开外）⇒ 差商里混进一个完全
        // 不相干的量 ⇒ 纵向散布被高估好几倍。
        // 实测证据（2026-09-27）：3 格炮筒那批报 ±84.7，而实测射程差平均只有 9 格 ——
        // 按 triangle 分布"典型偏差 = σ/3"反推，真值该在 ±28 上下，正好差约 3 倍。
        final boolean useAscending = prefersAscending(solution.pitch(), probed, aimY, targetRange);
        Double plus = crossingAt(solution.pitch() + DELTA_DEG, probed, aimY, useAscending);
        Double minus = crossingAt(solution.pitch() - DELTA_DEG, probed, aimY, useAscending);
        if (plus == null || minus == null) {
            return 0.0;
        }
        return Math.abs((plus - minus) / (2.0 * DELTA_DEG)) * sigmaDeg;
    }

    /** 这条解的目标穿越点是**上升段**那一个吗（另一支是下降段 / 远处落点）。 */
    private static boolean prefersAscending(double pitch, ProbedCannon probed, double aimY,
                                            double targetRange) {
        TrajectorySimulator.Flight flight = TrajectorySimulator.simulate(pitch,
                probed.muzzleVelocity(), probed.barrelLength(), probed.originY(), aimY,
                probed.ballistics());
        if (flight == null || Double.isNaN(flight.ascendRange())) {
            return false;
        }
        return Math.abs(flight.ascendRange() - targetRange)
                <= Math.abs(flight.horizontalRange() - targetRange);
    }


    /**
     * 把仰角扰动一点之后，炮弹会落在瞄准高度上的**指定那一支**穿越点的水平距离。
     *
     * <p>⚠️ 一条弹道穿过瞄准高度有<b>两次</b>（上升段 / 下降段），**必须两支之间只选一支、
     * 且同一组差商里不许换支** —— 挑错会得到一个完全不相干的差商（近垂直吊射的两次穿越能差几百格）。
     *
     * @param useAscending {@code true} = 取上升段穿越点；{@code false} = 取下降段（远处落点）
     * @return 该支不存在（升不到这个高度 / 弹道不可达）时返回 {@code null}
     */
    @Nullable
    private static Double crossingAt(double pitch, ProbedCannon probed, double aimY,
                                     boolean useAscending) {
        TrajectorySimulator.Flight flight = TrajectorySimulator.simulate(pitch,
                probed.muzzleVelocity(), probed.barrelLength(), probed.originY(), aimY,
                probed.ballistics());
        if (flight == null) {
            return null;
        }
        if (!useAscending) {
            return flight.horizontalRange();
        }
        final double ascending = flight.ascendRange();
        return Double.isNaN(ascending) ? null : ascending;
    }

    /**
     * 排定在哪个游戏刻开火（{@code 0} = 没排）。<b>同时弹着用的延时击发</b>。
     *
     * <p>为什么要它：{@code fireQueued} 是"立刻打"，而 TOT 需要"过 N tick 再打"。
     * 而且这段时间里<b>目标不能被清掉</b> —— 一清就让节点撒手放炮座，炮会在等待中途漂走，
     * 到点点火时打的就不是原来那个角度了。<b>清目标的时机由计算机掌握。</b>
     */
    private long pendingFireTick;

    /**
     * <b>本轮射击的全部炮弹落地时刻</b>（{@code 0} = 没在打）。
     *
     * <p>为什么节点也要知道它：同时弹着 / 轮射里，<b>先打完的那几门炮在整轮结束前不该进入下一状态</b> ——
     * 它们该显示"等待本轮射击结束"，否则界面会立刻跳成"未装填"，看着像打完了、其实整轮还在进行。
     *
     * <p>由计算机在排期时一起下发（见 {@code scheduleFire}）。
     */
    private long salvoEndTick;

    /**
     * 本轮的火力模式（齐射 / 同时弹着 / 轮射）。
     *
     * <p>节点自己不靠它干活（排期是计算机算好直接给的），<b>只用来把话说准</b>：
     * "等待轮射结束"和"等待同时弹着结束"是两回事。
     */
    private FireMode fireMode = FireMode.SALVO;

    public FireControlNodeBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public FireControlNodeBlockEntity(BlockPos pos, BlockState state) {
        this(ModBlockEntities.FIRE_CONTROL_NODE.get(), pos, state);
    }

    // ------------------------------------------------------------------ 应力

    @Override
    public float calculateStressApplied() {
        return STRESS_BASE;
    }

    /** 有没有达到最低转速要求。判定完全交给 Create。 */
    public boolean hasRotationPower() {
        return isSpeedRequirementFulfilled();
    }

    // ------------------------------------------------------------------ 找炮

    /** 当前这一侧的炮座实体（服务端与客户端各自解析）。 */
    @Nullable
    public CannonMountBlockEntity cannon() {
        if (mountPos == null || level == null) {
            return null;
        }
        return level.getBlockEntity(mountPos) instanceof CannonMountBlockEntity mount ? mount : null;
    }

    /** 扫六个邻居找炮座。放方块、邻块变化、加载时、以及 tick 里发现丢了都会调。 */
    public void refreshCannon() {
        if (level == null) {
            return;
        }
        BlockPos found = null;
        for (Direction d : Direction.values()) {
            if (level.getBlockEntity(worldPosition.relative(d)) instanceof CannonMountBlockEntity) {
                found = worldPosition.relative(d).immutable();
                break;
            }
        }
        if (found == null ? mountPos != null : !found.equals(mountPos)) {
            mountPos = found;
            setChanged();
            sendData();
        }
    }

    /** 节点是否可用：挨着炮座、火炮已组装、并且有转速。 */
    public boolean isOperational() {
        CannonMountBlockEntity mount = cannon();
        return mount != null && mount.isRunning() && mount.getContraption() != null && hasRotationPower();
    }

    @Nullable
    public BlockPos getMountPos() {
        return mountPos;
    }

    // ------------------------------------------------------------------ 上线关系

    /**
     * 由火控协调器在扫描时<b>写回</b>：告诉这个节点「你被我登记了」。
     *
     * <p>传 {@code null} 表示被除名（协调器拆了、节点搬走了、转速掉了）。
     */
    public void setMaster(@Nullable BlockPos master) {
        if (master == null ? masterPos == null : master.equals(masterPos)) {
            return;
        }
        this.masterPos = master == null ? null : master.immutable();
        setChanged();
        sendData();
    }

    /** 上线到哪台火控协调器；{@code null} = <b>没有接入火控网络</b>，护目镜要靠它提醒玩家。 */
    @Nullable
    public BlockPos getMasterPos() {
        return masterPos;
    }

    /**
     * 载具此刻是否在动 —— 「实时计算」那个状态的依据。
     *
     * <p>由计算机汇总（"有任意一门炮在动"就进实时态），所以它必须能被**同 JVM 的服务端**
     * 读到，也必须**同步到客户端**（那段同步在 {@code write(...)} 的 clientPacket 分支里）。
     */
    public boolean isPlatformMoving() {
        return platformMoving;
    }

    /**
     * ⭐ P3：这门炮的炮塔是否**持续跟不上目标**（转速不够）。
     *
     * <p>为真时：它**不参与本轮射击**（`isAimed()` 永远不成立），而"为什么"就该说给玩家 ——
     * 协调器界面的状态列、节点护目镜都用这一个判据（三处判据只此一份）。
     */
    public boolean isRpmShort() {
        return rpmShort;
    }

    /** 跟得上所需的转速（RPM）。只在 {@link #isRpmShort()} 为真时有意义。 */
    public int getRpmNeeded() {
        return rpmNeeded;
    }

    /** 有没有接入火控网络。护目镜与协调器界面都用它。 */
    public boolean isOnline() {
        return masterPos != null;
    }


    /**
     * 复核「上线到哪台协调器」是否还有效 —— 协调器被拆掉后自己变回「未接入」。
     *
     * <p>只在下级方块<b>已加载</b>时才判定：区块没加载不等于被拆了，那种情况保持原样。
     * 有了这一步，协调器<b>不需要</b>在拆除时挨个通知自己名下的炮。
     */
    private void verifyMaster() {
        if (masterPos == null || level == null || !level.isLoaded(masterPos)) {
            return;
        }
        if (!(level.getBlockEntity(masterPos) instanceof FireControlCoordinatorBlockEntity)) {
            setMaster(null);
        }
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void tick() {
        super.tick();
        tickBody();
    }

    /** {@link #tick} 的本体 —— 拆出来只为了给它计时。 */
    private void tickBody() {
        if (level == null || level.isClientSide) {
            return;
        }

        // ⭐ 实弹标定：推一下"这一发的落点"跟踪（落地 ⇒ 反推 k 并更新）。
        //    刻意放在"这门炮还在不在"的判断**之前** —— 炮弹已经出去了，而炮可能刚被拆掉，
        //    那种情况也得把这一发的账记完（学到的值存在这个方块实体上，拆了会跟着丢）。
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevelForCalibration) {
            calibration.tick(serverLevelForCalibration);
        }

        CannonMountBlockEntity mount = cannon();
        if (mount == null || mount.isRemoved()) {
            refreshCannon();
            mount = cannon();
        }
        if (mount == null) {
            return;
        }

        // 探针缓存独立刷新 —— 不依赖有没有军令
        if (--probeCooldown <= 0) {
            probeCooldown = PROBE_INTERVAL;
            probeAndCache(mount);
            verifyMaster();
        }

        // 每 tick 判一次"载具动没动"（两个阈值不同的旗子，见 updateMotionFlags）
        updateMotionFlags();

        // ---- ⭐ 实时计算：瞄准需要持续刷新 ⇒ **每 tick 增量重解**（P2）----
        //
        // ⚠️⚠️ 刻意**不看 `aiming`**：炮转到位后 `aiming` 会置 false（撒手），
        //     而载具还在动 ⇒ **诸元每 tick 都在过期** —— 这正是实弹测出来的最大误差源
        //     （船速 6.73 格/tick × 20 tick 的解算间隔 = 135 格）。原来的代码一旦就位就
        //     再也不解算，于是"越准的炮越会一直用那个过期的解"。
        //
        // ⚠️ 增量路径跟不住时（跑出射界/射程）由下面的完整解算接手判断原因 ——
        //     所以这里**不关 `solvable`**，只是这一次不更新。
        if (hasTarget && hasRotationPower() && aimTracking) {
            // ⚠️⚠️ 逼近**必须每 tick 都做**，不能"只在这 tick 增量重解成功时才做"：
            //    增量重解碰上难解的目标会**隔几 tick 才成功一次**，那样炮就变成"走一 tick 停一 tick"
            //    —— 玩家看到的是"**一卡一卡地转**、而且慢**一半**"（2026-09-25 实机：
            //    近距离转高抛时如此；远距离低伸因为好解所以看不出 ✗）。
            //    即使这次没重解成功，`targetYaw/Pitch` 也还是上一次**有效**的诸元 ⇒ 照走不误 ✓
            if (solvable && cheapResolve()) {
                // 重解成功（`keepControl=true`：跟踪期间**不能撒手**，否则瞄准立刻开始飘）
            } else if (--recalcCooldown <= 0) {
                recalcCooldown = RECALC_INTERVAL;
                solveForTarget(mount);
            }
            if (solvable) {
                stepTowards(mount, true);
            }
        } else if (aiming && hasTarget && hasRotationPower()) {
            // 原路径：载具没动（或没装 Sable）⇒ 每 RECALC_INTERVAL 解一次，其余时间交还给 CBC
            if (--recalcCooldown <= 0) {
                recalcCooldown = RECALC_INTERVAL;
                solveForTarget(mount);
            }
            if (solvable) {
                stepTowards(mount, false);
            }
        }

        // ---- 同时弹着：等待期间盯着角度别被扰动跑掉 ----
        // 排期可能长达几十秒（远近混编时近炮要干等），而这段时间炮座是交还给 CBC 的。
        // 万一被什么推动（玩家手推、其他机械、红石），到点点火打的就是偏的了。
        // 所以趁等待顺手纠回去 —— stepTowards 到位时本来就是空操作，不会抖。
        if (pendingFireTick != 0 && hasTarget && solvable && hasRotationPower()) {
            // 等待期间同样按"载具动没动"决定要不要撒手
            stepTowards(mount, aimTracking);
        }

        // ---- 同时弹着的延时击发：到点了才把"立刻打"那个旗子立起来 ----
        // ⚠️ 等待期间 `aiming` 保持原样（已就位就是 false），炮座是交还给 CBC 的 ——
        // 所以**目标绝不能在等待期间被清掉**，一清节点就彻底撒手，任何扰动都会让炮位漂走，
        // 到点点火时打的已经不是原来解出来的那个角度了。清目标的时机由计算机掌握。
        if (pendingFireTick != 0 && level.getGameTime() >= pendingFireTick) {
            pendingFireTick = 0;
            fireQueued = true;
            setChanged();
        }

        if (fireQueued && !aiming) {
            tryFire(mount);
        }
    }

    /**
     * 探一次炮并把结果存进缓存（护目镜要用，所以变了就同步）。
     *
     * <p>唯一的探针入口 —— {@code tick} 里定时调它刷缓存，解算时也调它拿参数。
     *
     * @return 探不到（没组装 / 世界未加载）时返回 {@code null}
     */
    @Nullable
    private ProbedCannon probeAndCache(CannonMountBlockEntity mount) {
        ProbedCannon probed = CannonProbe.probe(level, mount.getBlockPos(),
                goodBarrelLength, goodPivotOffsetY);
        // ⚠️ 几何只在**还没有缓存**时播种一次，之后就固定不动。
        // 反解要除以 `cos(探测时的仰角)`，炮一转动结果就差最后几位 ——
        // 而下面那句 `Double.compare(barrelLength, …)` 是**精确**比对，会把那点差别当成
        // "参数变了"、每 2 秒强制重解一次，进而把 AUTO 推进自激死循环（见 CannonProbe 的说明）。
        // 缓存失效交给下面"炮身/炮腔/材质/弹种变了"那条 —— 那才是真的重搭过。
        if (probed != null && probed.geometryExact() && !Double.isFinite(goodBarrelLength)) {
            goodBarrelLength = probed.barrelLength();
            goodPivotOffsetY = probed.pivotOffsetY();
        }
        int charges = probed == null ? 0 : probed.chargeCount();
        int maxCharges = probed == null ? 0 : probed.maxCharges();
        float velocity = probed == null ? 0.0F : (float) probed.muzzleVelocity();
        float minP = probed == null ? 0.0F : probed.minPitch();
        float maxP = probed == null ? 0.0F : probed.maxPitch();
        int barrel = probed == null ? 0 : probed.barrelBlocks();
        String mat = probed == null || probed.materialName() == null ? "" : probed.materialName();
        int chambers = probed == null ? 0 : probed.chamberBlocks();
        int matStress = probed == null ? 0 : probed.materialMaxStress();
        int breechStrength = probed == null ? 0 : probed.breechStrength();
        String breechName = probed == null || probed.breechName() == null ? "" : probed.breechName();
        float minVelPerBarrel = probed == null ? 0.0F : probed.minVelocityPerBarrel();
        float chargePower = probed == null ? 0.0F : probed.chargePower();
        String shellId = probed == null || probed.shellItemId() == null ? "" : probed.shellItemId();
        String propellantId =
                probed == null || probed.propellantItemId() == null ? "" : probed.propellantItemId();
        int propellantPower = probed == null ? 0 : probed.propellantPower();
        int propellantMaxPower = probed == null ? 0 : probed.propellantMaxPower();
        float chargeLen = probed == null ? 0.0F : (float) probed.chargeLengthMeters();
        float barrelM = probed == null ? 0.0F : (float) probed.barrelMeters();
        boolean squib = probed != null && probed.squib();
        // 炮又装填好了 ⇒ 把"上一发没打出去"的警告收掉（那件事已经过去了）。
        // ⚠️ 不看"装了多少药"而看"膛里有没有东西"：卡弹之后膛是空的，玩家补药那一刻就该消警。
        if (charges > 0 && lastFireFailed) {
            lastFireFailed = false;
            setChanged();
            sendData();
        }
        float explosion = probed == null ? 0.0F : probed.shellExplosionPower();
        double barrelLength = probed == null ? 0.0 : probed.barrelLength();

        if (charges != probedCharges || maxCharges != probedMaxCharges
                || chambers != probedChambers
                || !mat.equals(probedMaterial)
                || barrel != probedBarrelBlocks
                || Float.compare(velocity, probedVelocity) != 0
                || Float.compare(minP, probedMinPitch) != 0 || Float.compare(maxP, probedMaxPitch) != 0
                || matStress != probedMaterialMaxStress
                || breechStrength != probedBreechStrength
                || !breechName.equals(probedBreechName)
                || Float.compare(minVelPerBarrel, probedMinVelPerBarrel) != 0
                || Float.compare(chargePower, probedChargePower) != 0
                || !shellId.equals(probedShellItemId)
                || !propellantId.equals(probedPropellantId)
                || propellantPower != probedPropellantPower
                || propellantMaxPower != probedPropellantMaxPower
                || Float.compare(chargeLen, probedChargeLengthMeters) != 0
                || Float.compare(barrelM, probedBarrelMeters) != 0
                || squib != probedSquib
                || Float.compare(explosion, probedExplosionPower) != 0
                // 炮轴长度不显示给玩家，但它决定极限射程 —— 变了也要重算
                || Double.compare(barrelLength, probedBarrelLengthForRange) != 0) {
            // ⚠️ 炮身结构变了（等于玩家重搭过）⇒ 之前缓存的那套几何作废，下一轮探针重新测。
            // **只有这几个结构性字段能作废几何** —— 几何是静态属性，炮转个角度不算变。
            if (barrel != probedBarrelBlocks || chambers != probedChambers || !mat.equals(probedMaterial)) {
                goodBarrelLength = Double.NaN;
                goodPivotOffsetY = 0.0;
            }
            probedCharges = charges;
            probedMaxCharges = maxCharges;
            probedBarrelBlocks = barrel;
            probedSpreadFree = probed == null ? 0 : probed.spreadFreeBarrelBlocks();
            probedMaterial = mat;
            probedChambers = chambers;
            probedVelocity = velocity;
            probedMinPitch = minP;
            probedMaxPitch = maxP;
            probedMaterialMaxStress = matStress;
            probedBreechStrength = breechStrength;
            probedBreechName = breechName;
            probedMinVelPerBarrel = minVelPerBarrel;
            probedChargePower = chargePower;
            probedShellItemId = shellId;
            probedPropellantId = propellantId;
            probedPropellantPower = propellantPower;
            probedPropellantMaxPower = propellantMaxPower;
            probedChargeLengthMeters = chargeLen;
            probedBarrelMeters = barrelM;
            probedSquib = squib;
            probedExplosionPower = explosion;
            // ⭐ 诊断：**装填一变就打一行** —— 含"炮膛内容顺序"（炮尾→炮口）。
            //    玩家反馈"动力臂自动装填偶尔卡弹，怀疑先装药后装弹"，判据只能是顺序。
            try {
                AbstractMountedCannonContraption bigCannon = mount.getContraption() != null
                        && mount.getContraption().getContraption() instanceof AbstractMountedCannonContraption m
                        ? m : null;
            } catch (Throwable t) {
            }

            // ⚠️ 极限射程要扫上百个仰角，比一次解算贵得多 —— 只在这里（参数真的变了）算，
            // 绝不能放进每 40 tick 都会跑的那条路径里。
            BallisticSolver.RangeLimit limit = computeMaxRange(probed, charges, barrelLength);
            probedMaxRange = (float) limit.range();
            probedMinSafeRange = safeRange(explosion, barrelLength);
            probedBarrelLengthForRange = barrelLength;

            // ⚠️⚠️ 炮的参数变了就必须**重新解算**。
            //
            // 节点一旦转到位就撒手（`aiming = false`），而 tick 里的解算条件是
            // `aiming && hasTarget && ...` —— 于是**它此后再也不会重算**。
            // 玩家换了装药 / 换了弹种 / 加了炮管，探针每 2 秒照常刷新，
            // 护目镜上的初速、极限射程都会跟着变，**唯独解算好的目标角停在旧值上** ——
            // 界面显示"已就位"，打出去却是按旧初速瞄的，必偏。
            // 2026-09-20 实测：同一门炮装 1/4（初速 11.4）和 3/4（初速 16.8），
            // 护目镜给出的仰角都是 1.87° —— 差 47% 的初速不可能要同一个仰角。
            if (hasTarget) {
                this.aiming = true;      // 重新接管，下一 tick 重解
                this.recalcCooldown = 0;
            }
            setChanged();
            sendData();
        }

        // ⚠️⚠️ 这一份**图幅**枢轴**每次探针都必须刷新**，绝不能放进上面那个"参数变了没有"的
        //      if 里。理由：它是一个**位置**，载具一动就变；而那一组判据里
        //      **没有任何一项是位置**（装药/初速/几何/弹种…）⇒ 放进去会让它整局停在第一次
        //      探针的值上（2026-09-25 飞艇实测：`炮弹出生 … 位置距枢轴=947.98格`，
        //      而真实炮口离枢轴只有几格）。
        // ⚠️ 判空：这一段在"炮没组装好 / 探不到炮"时也会跑（那时 probed == null）。
        if (probed != null) {
            Vec3 plotPivot = CannonProbe.plotPivot(probed);
            probedPlotPivotX = plotPivot.x;
            probedPlotPivotY = plotPivot.y;
            probedPlotPivotZ = plotPivot.z;
        }
        return probed;
    }

    /**
     * 这门炮在当前装填下的水平极限射程（格）。0 表示算不出来（没装填 / 没炮）。
     *
     * <p>spec 的构造与 {@link #solveForTarget} 保持<b>完全一致</b> —— 两处若不一致，
     * 护目镜报的极限射程就会和"实际能不能打到"对不上。
     */
    private static BallisticSolver.RangeLimit computeMaxRange(@Nullable ProbedCannon probed, int charges,
                                                              double barrelLength) {
        if (probed == null || !probed.loaded() || !(barrelLength > 0.0)) {
            return BallisticSolver.RangeLimit.NONE;
        }
        int loaded = Math.max(1, charges);
        CannonSpec spec = new CannonSpec(
                probed.muzzleVelocity() / loaded,
                barrelLength,
                probed.minPitch(), probed.maxPitch(),
                loaded, loaded,
                probed.ballistics());
        BallisticSolver.RangeLimit limit = BallisticSolver.maxRange(spec);
        return Double.isFinite(limit.range()) && limit.range() > 0.0
                ? limit : BallisticSolver.RangeLimit.NONE;
    }

    /**
     * <b>最近安全距离</b>（格）—— 目标再近，弹着点的爆炸就可能波及炮位自己。
     *
     * <p>这是<b>保守估算</b>，不是精确的杀伤半径：
     *
     * <pre>
     *   安全距离 ≈ 爆炸威力 × 3 + 炮轴长
     *                └────┬────┘   └──┬──┘
     *        原版爆炸的波及半径约 威力×2，  炮弹至少要先飞出
     *        再乘 1.5 的余量          自己的炮口
     * </pre>
     *
     * <p>惰性弹（固体弹，{@code explosive_power = 0}）没有爆炸，就只留「飞出炮口」这一项。
     *
     * <p>⚠️ 系数 3 是拍的，实机看过炸膛/自伤的表现后可以调。
     */
    private static float safeRange(float explosionPower, double barrelLength) {
        double radius = explosionPower > 0.0F ? explosionPower * 3.0 : 0.0;
        return (float) (radius + Math.max(0.0, barrelLength));
    }

    // ------------------------------------------------------------------ 解算

    /**
     * 玩家填的高度落在目标点地面高度 ±这么多格内，就认为「他想打地面」，直接把高度吸附到地形上。
     * 超过这个范围就当他是**故意**打空中的点（飞行器、塔顶之类），尊重原值。
     */
    private static final double GROUND_SNAP_TOLERANCE = 10.0;

    /**
     * <b>无视上面那条容差、一律吸附到地面高度</b> —— 覆盖打击专用。
     *
     * <p>为什么它非有不可：覆盖打击给每门炮分的是**不同的点**，而点的高度统一传的是主目标的 Y。
     * 陡坡上相邻点（半径 50 格）地面高度差**很容易超过 10 格**，那条容差就会判定"玩家想打空中"、
     * 拒绝吸附 ⇒ **半个圆的炮弹按错误的基准高度解算，整片打偏**。
     * 覆盖打击的语义本来就是"打在那片地上"，没有"打空中"的余地，所以强制吸附。
     */
    private boolean forceGroundAlign;

    /**
     * 把玩家输入的目标高度「吸附」到目标点<b>实际的地面高度</b>。
     *
     * <p>这是让**玩家不需要懂弹道**的关键一步 —— 否则他打偏了只会觉得"这模组不准"。
     *
     * <h2>为什么非做不可</h2>
     *
     * 玩家填坐标时，填的永远是他**看得见的**数字（自己站的方块、小地图读到的高度），
     * 而炮弹实际是撞在目标点**方块顶面**上，两者常差 2~5 格。而炮弹是<b>穿过</b>瞄准点之后
     * 才落到地面的，这个高度差会被放大成水平落点误差，倍数 = {@code 1/tan(落角)}：
     *
     * <pre>
     *  目标    发射仰角   落角    3 格高度差 → 多飞
     *  300 格    2.2°     3.3°        62 格    ← 近距离放大十几倍
     *  500 格    3.8°     4.6°        40 格
     *  2000 格  17.3°    18.6°         9 格
     *  3000 格  31.1°    33.6°         5 格    ← 远距离几乎无感
     * </pre>
     *
     * 2026-09-17 六发实弹印证：玩家填 172（他站的位置）而该处地面在 169，300 格那发偏了 52 格
     * —— 完全由这 3 格造成，解算本身没错。**所以这个坑必须由模组兜掉，不能指望玩家懂。**
     */
    private double resolveAimHeight() {
        if (level == null) {
            return targetY;
        }
        BlockPos target = BlockPos.containing(targetX, targetY, targetZ);
        if (!level.isLoaded(target)) {
            return targetY;   // 目标区块没加载，查不到地形
        }
        int groundY = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, target).getY();
        // ⚠️ 覆盖打击要**强制**吸附（见 forceGroundAlign 的说明），不走那条 ±10 容差
        if (forceGroundAlign) {
            return groundY;
        }
        return Math.abs(groundY - targetY) <= GROUND_SNAP_TOLERANCE ? groundY : targetY;
    }

    /**
     * 探自己这门炮、解算、把解转成 CBC 的角度空间。
     *
     * <p>每个节点探的是<b>自己那门炮</b>，所以同一个目标坐标下，多门炮各自得到自己的诸元。
     */
    private void solveForTarget(CannonMountBlockEntity mount) {
        PitchOrientedContraptionEntity contraption = mount.getContraption();
        if (contraption == null) {
            // ⚠️ 解不出<b>不能</b>把 aiming 关掉 —— tick 里的接管条件是
            // `aiming && hasTarget && hasRotationPower()`，一关就再也不解算，
            // 只能等玩家重新下发军令（2026-09-17 踩到）。保留 aiming 让它按
            // RECALC_INTERVAL 自己重试，炮装填好/区块加载回来后就能自愈。
            solvable = false;
            setChanged();
            sendData();
            return;
        }

        ProbedCannon probed = probeAndCache(mount);
        if (probed == null || !probed.loaded()) {
            solvable = false;   // 同上：别关 aiming，等它装填好自己重试
            // ⚠️ 说明"是没装填"而不是留空 —— 留空的话协调器界面只能武断地报"超出射程"，
            // 而实际原因常常是刚打空（2026-09-22 用户实测：协调器与护目镜说得不是一回事）。
            if (probed != null) {
                arcNoteKey = ARC_NOT_LOADED;   // probed == null 是"探不到炮"，另有含义，别混
                arcNoteA = 0.0;
                arcNoteB = 0.0;
            }
            setChanged();
            sendData();
            return;
        }

        warnIfStructureScaled(probed);

        // 当前装填就当作唯一的装药档 —— velocityPerCharge 按装药块数均摊，
        // 使 velocityFor(n) 正好等于实测初速
        int charges = Math.max(1, probed.chargeCount());
        CannonSpec spec = new CannonSpec(
                probed.muzzleVelocity() / charges,
                probed.barrelLength(),
                probed.minPitch(), probed.maxPitch(),
                charges, charges,
                probed.ballistics());

        double aimY = resolveAimHeight();
        aimHeight = aimY;   // 缓存供护目镜显示（玩家要能看到"实际瞄的是哪个高度"）

        // 预计落点散布的**横向**分量 —— 让玩家知道"这门炮能打多准"。
        // 公式取自 Minecraft 的 Projectile#shoot：方向向量上每轴加 triangle(0, 0.0172275 × inaccuracy)。
        // inaccuracy 用探针复刻 CBC 算出的 finalSpread（含「沿炮管递减」那一步，见 CannonProbe）。
        // ⚠️ 2026-09-18 修正：之前漏了递减那一步，界面显示 ±62 格，而三发实弹极差只有 5 格。
        // ⚠️ 2026-09-27：横向这条公式**与弹道形状无关**（它就是"方位角被扰动 σ 弧度、在 R 处的横移"），
        //    而**纵向**分量与弹道形状强相关，下面 chooseArc 之后单独算（见 probedSpreadInLine）。
        double spreadDist = Math.hypot(targetX - probed.originX(), targetZ - probed.originZ());
        // ⭐ 横向 = √(散布² + 地板²) —— 见 {@link #LATERAL_FLOOR_BLOCKS}。
        //    ⚠️ 地板只加在横向：**纵向用的是同一个 σ，而 34 发实测显示它吻合**（不需要地板）。
        probedSpread = (float) Math.hypot(0.0172275 * probed.finalSpread() * spreadDist,
                LATERAL_FLOOR_BLOCKS);
        // ⭐ 同一个距离就是"解算射程" —— 提前量（{@link #leadTicksNow()}）与实弹标定都要它
        solutionRange = spreadDist;

        // ---- ⭐ 载具速度 + 半 tick 提前量（2026-09-25 用户拍板做）----
        //
        // 背景：飞艇上实时瞄准时，**落点会往行进方向偏一点点**（实测 78 格偏 4.8 格）。
        // 那不是解算错 —— 解算发生在这一 tick 的某个时刻、**开火在另一个时刻**，
        // 飞艇在这段间隙里又往回挪了一小段 ⇒ 炮弹是从"更前面一点"的炮口出发的 ⇒ 落点往前偏。
        //
        // ⇒ 把解算用的枢轴按"载具速度 × 半个 tick"前移：偏差**减半并居中**
        //    （从 0~1 tick 变成 ±0.5 tick）。**不可能归零** —— 那是时序的物理下限，
        //    除非开火恰好发生在解算的那一瞬间。
        // ⚠️ 载具不动 / 没装 Sable / 不在物理结构上时它为零矢量 ⇒ 这里整体退化成原样 ✓
        platformVelX = 0.0;
        platformVelY = 0.0;
        platformVelZ = 0.0;
        if (ActiveBallistics.sableLoaded()) {
            Vec3 pv = SableCompat.velocityAt(level, Vec3.atCenterOf(probed.mountPos()));
            platformVelX = pv.x;
            platformVelY = pv.y;
            platformVelZ = pv.z;
        }
        final double leadTicks = leadTicksNow();
        double leadX = probed.originX() + platformVelX * leadTicks;
        double leadY = probed.originY() + platformVelY * leadTicks;
        double leadZ = probed.originZ() + platformVelZ * leadTicks;

        // ⚠️⚠️ **这里曾经在"静止平台"时也沿方位线前移 k×射程 —— 那是错的，已撤（2026-09-27 实弹定案）。**
        //
        // 当时的推断：解算射程比实际短约 5%（k 的初值 0.05），于是把原点往目标推 k×射程 去补偿。
        // **4 发实弹把它否掉了**（301 格、两门炮、低伸/高抛各两发，全部通过火控开火）：
        //   射程差 **−8.3 ~ −14.6 格**（全部落短），而 **顶点差只有 +0.0 ~ +1.0 格**、
        //   **飞行时间比 1.00 ~ 1.03** ⇒ **弹道模型本身是准的**；
        //   而短板**几乎正好等于当时施加的 `前移 15.08 格`** ⇒ 撤掉它，落点回到 +0.5 ~ +6.8（基本正中）。
        //
        // ⇒ 结论：**"弹道模型少了 k×射程"这个偏差并不存在**（当年那个"低伸偏长 5%"的推断也是错的，
        //   那批数据是**目标一带地形 / 切向敏感性**造成的，不是模型误差）。
        // ⇒ `k` **只该用于动平台的行进方向提前量**（上面那条，实弹验证过）。
        // ⚠️ 别再"补"回来 —— 它每 300 格就会带来 15 格的系统性落短。

        // ---- ⚠️⚠️ 这里**故意不做**"载具动量补偿"（2026-09-25 实弹两次定案）----
        //
        // 我一度加过"瞄准点前移 −载具速度×飞行时间"，**被实弹打回**：
        // 船以 6.73 格/tick 巡航时，那一下把瞄准点推了 **+241 格**，
        // 炮照着偏移点打 ⇒ 炮弹沿那条方位飞了 857 格（目标只有 155 格）✗✗ 是我造的偏差。
        //
        // **实测结论：炮弹【不】继承载具速度。** 直接证据（同一次高速测试）：
        //   出生时 |速度|=6.313 速度=(5.22, −0.47, 3.52)
        //   一秒钟后从弹道采样反推 每 tick 位移=(5.20, −0.71, 3.52)   ← **逐分量一致**
        // 即炮弹的地面速度 = 它出生时的速度，载具那 6.73 格/tick 一点没带上。
        // ⚠️ 我曾把上一次（船速 0.33 格/tick）那 17.1 格偏差**劈成两半**（8.4 继承 + 9.4 过期），
        //    那是个**算术巧合当证据**的错误 —— 事实上 0.334×59tick ≈ 19.7 格 **全部**是诸元过期。
        //    ⇒ 教训：一个观测同时支持两个假设时，**要设计能把它们分开的测量**，不能靠"拆账"。
        //
        // ⇒ **真正要治的只有一项：诸元过期**（载具越快越致命：6.73 格/tick 时、
        //    20 tick 的解算间隔 = 135 格漂移）—— 那是 P2「每 tick 重解」的活。

        // ⭐ 诊断：完整解算的**输入**（每发/每次重解一行）—— 大包里"打不准"要能一眼看出是哪一层错了。
        final AbstractMountedCannonContraption bigCannon =
                contraption.getContraption() instanceof AbstractMountedCannonContraption m ? m : null;

        List<FiringSolution> solutions = BallisticSolver.solve(spec,
                leadX, leadY, leadZ,
                targetX, aimY, targetZ);


        // ⚠️ 2026-09-26 用户拍板：**自动模式维持原样** ——
        //    「弹道默认低伸；没有障碍就低伸；有障碍就自动高抛」。
        //    我一度加过"飞行超过 30 秒的高抛在 AUTO 里直接剔掉、宁可报打不着"，
        //    用户明确不要这个设置 ⇒ 已撤掉（那种近乎垂直的解只打一条 WARN，见下面 applySolution）。

        // ⚠️⚠️ 这里**曾经有一句 `if (solutions.isEmpty()) return;`** —— 它在 chooseArc 存在之前写的，
        // 结果是"一条解都没有"时**根本不进 chooseArc**，于是 arcNoteKey 保持空串，
        // 护目镜和界面上只能报那句通用文案「解不出诸元（超出射程，或火炮未装填）」——
        // **目标太近（比如 10 格，比炮口前伸量还短）也会被错报成"超出射程"**
        // （2026-09-21 用户实测：20 格以内三种模式全报超出射程）。
        // chooseArc 本来就处理空列表（转 diagnoseUnreachable），所以这句提前返回既多余又有害。

        // ---- 选哪条弹道（低伸 / 高抛 / 自动）----
        // 这是本机自己的判断：每门炮位置不同、遮挡不同，一门被山挡不代表另一门被挡。
        ArcSelection pick = chooseArc(spec, solutions, probed, aimY);
        arcChoice = pick.choice();
        arcNoteKey = pick.noteKey();
        arcNoteA = pick.noteA();
        arcNoteB = pick.noteB();
        // ⭐ 纵向散布分量 —— 必须**在选定弹道之后**算（见 probedSpreadInLine：它吃的是这条弹道自己的
        //    dR/dθ，低伸与高抛能差十几倍）。
        probedSpreadInLine = pick.solution() == null ? 0.0F
                : (float) inLineSpread(pick.solution(), probed, aimY, spreadDist);

        if (pick.solution() == null) {
            // 一条都打不了 —— 原因已经记在 noteKey 里，护目镜会照着说清楚，
            // 而不是只丢一句"解不出诸元"让玩家自己猜（2026-09-20 用户明确要求）。
            solvable = false;
            solutionAirTime = 0.0;
            // ⚠️ 原因**必须取 `pick.noteKey()`**，不能取 `arcNoteKey` 字段 ——
            //    后者是在**这个 return 之前**才赋值的（见上面几行），在这里读只会拿到上一次的旧值。
            setChanged();
            sendData();
            return;
        }

        // 解出来的仰角是"正值向上"，而 CBC 的 cannonPitch 裸字段**本身就是正值向上** ——
        // 直接写进去，不要再乘任何朝向符号。理由见下面这段（2026-09-19 实弹定案）。
        //
        // ⚠️⚠️ 这里曾经乘过一个「朝向符号」sgn = (axisDirection==POSITIVE)==(axis==X) ? 1 : -1，
        // 是从 CBC 的 `CannonMountBlockEntity.applyRotation()` 里读到后照搬的：
        //     mountedContraption.pitch = this.cannonPitch * sgn;      // 写进去时乘一次
        // 但 CBC 自己**读出来显示**时又乘了一次同一个 sgn
        // （`ExtendsCannonMount.addCannonInfoToTooltip`：`float pitch = flag ? c.pitch : -c.pitch`）——
        // 两次抵消，所以 cannonPitch 与「世界仰角」是同一个数。我们多乘的那一次净效果就是取反。
        //
        // 实弹印证（2026-09-19 多炮验证，两门钢炮打 (−500,63,0)）：
        //   两门炮都是**朝西（−X）**，sgn = −1，于是解出的 +1.21° / +1.36° 被写成 −1.18° / −1.35°，
        //   炮口朝下 → 两发都在 ~100 格处砸地（−102 / −94）。按 GB 物理正解恰是 +1.21° / +1.36°。
        //   **青铜炮那次没暴露**：它朝**北（−Z）**，而 sgn 在朝北时恰好是 +1（等于没乘）。
        // ⇒ 教训：抄 CBC 的符号时，要确认它乘的是哪个字段、以及**读回来的路径是否又乘了一次**。
        // 缓存下来供"每 tick 增量重解"用（见 cheapResolve）：它拿上一次的解当牛顿初值。
        lastSpec = spec;
        lastSolution = pick.solution();
        applySolution(pick.solution(), true);
    }

    /**
     * 把一条**世界系**的解落到炮上 —— 世界角 → 图幅角、写字段、（可选）同步给客户端。
     *
     * <p>⚠️⚠️ <b>这是这个换算的唯一实现</b>：完整解算与"每 tick 增量重解"两条路共用它。
     * 本项目已经栽过多次"同义逻辑写两份、改了一处漏了另一处"（通则 1）。
     *
     * <p>⚠️ 解算全程是**世界系**（炮弹出生在世界系），而 CBC 的 {@code cannonPitch/cannonYaw}
     * 只认**炮自己所在的坐标系** —— 炮在航空学物理结构上时那是**图幅系**。
     * 所以传给 SableCompat 的**必须是图幅坐标**（它靠这个定位物理结构）：
     * 用节点自己的 {@code mountPos}（炮座的**图幅**方块坐标），
     * <b>不能</b>用探针那三个世界坐标。返回 null（不在结构上 / 没装 Sable）
     * ⇒ 原样用世界角 = 既有行为，逐字不变。
     *
     * <p>⚠️ 下游的 {@code stepTowards}（限速逼近）、到位判定、护目镜、协调器的炮位行
     * 全都继续用图幅角，一行都不用改。
     *
     * @param syncNow 立刻同步。增量路径**每 tick 都跑**，40 门炮每秒 20 个数据包太浪费 ⇒
     *                它传 false，由 {@link #CHEAP_SYNC_INTERVAL} 节流、且只在实际动了时才发。
     */
    private void applySolution(FiringSolution sol, boolean syncNow) {
        // ⚠️ sableLoaded() 这一关不能省（没装 Sable 时那个类连加载都会失败）。
        float[] localAim = mountPos != null && ActiveBallistics.sableLoaded()
                ? SableCompat.worldAimToLocal(level, Vec3.atCenterOf(mountPos), sol.yaw(), sol.pitch())
                : null;
        float newYaw = localAim == null ? (float) sol.yaw() : localAim[0];
        float newPitch = localAim == null ? (float) sol.pitch() : localAim[1];

        boolean moved = Math.abs(newPitch - targetPitch) > AIM_SYNC_EPS
                || Math.abs(wrapDegrees(newYaw - targetYaw)) > AIM_SYNC_EPS;

        targetYaw = newYaw;
        targetPitch = newPitch;
        solutionAirTime = sol.airTime();
        solvable = true;
        // ⚠️ 前馈量在这里清零：它只该由"每 tick 一次"的增量路径赋值
        //    （完整解算的间隔是 20 tick，用它当前馈会一步冲过头）。
        aimDriftYaw = 0.0F;
        aimDriftPitch = 0.0F;

        if (syncNow) {
            // ⚠️ 近乎垂直的高抛 = 实战打不出去：飞行近一分钟、顶点冲上 2000+ 格
            //    （2026-09-26 实测：玩家"等了很久没见炮弹落地"）。**只警告、不改行为** ——
            //    要不要给高抛设陡度上限是玩法取舍，等用户拍板。
            if (sol.airTime() > WARN_LONG_FLIGHT_TICKS) {
            }
            // ⭐ 诊断：完整解算的**输出**（与上面那条"输入"配对）—— 含世界角→图幅角的换算结果，
            //    航空学场景里这两套角度的换算就是"炮瞄错方向"的头号嫌疑。
            double leadTicksNowValue = leadTicksNow();
        }
        long now = level == null ? 0L : level.getGameTime();
        if (syncNow || (moved && now - lastAimSyncTick >= CHEAP_SYNC_INTERVAL)) {
            lastAimSyncTick = now;
            setChanged();
            sendData();
        }
    }

    /**
     * 每 tick 判一次"载具动没动" —— 同时喂<b>两个阈值不同</b>的旗子：
     * <ul>
     *   <li>{@link #aimTracking}（低阈值 {@link #MOVE_ENTER}）—— 决定<b>解算节奏</b>：
     *       需要就一直每 tick 增量重解（这件事几乎不要钱，晚一步就吃诸元过期的亏）；</li>
     *   <li>{@link #platformMoving}（高阈值 {@link #TEXT_ENTER}）—— 决定<b>状态文案</b>：
     *       实时计算 / 计算完毕。⚠️ 不能用低阈值：停着的飞艇残余漂移就有 0.05~0.08 格/tick。</li>
     * </ul>
     *
     * <p>⚠️ 两者都带<b>滞回</b>（进入阈值 &gt; 退出阈值）：判据来自浮点速度，贴着阈值反复翻面
     * 会让文案来回跳 —— 本项目在 AUTO 弹道切换上为此栽过三次。
     *
     * <p>没装 Sable、没挨着炮座、或探针还没跑过 ⇒ 一律当"不在动"（走原来的 20 tick 节奏）。
     */
    private void updateMotionFlags() {
        if (level == null || mountPos == null || !ActiveBallistics.sableLoaded()) {
            aimTracking = false;
            platformMoving = false;
            return;
        }
        double speed = SableCompat.velocityAt(level,
                new Vec3(probedPlotPivotX, probedPlotPivotY, probedPlotPivotZ)).length();
        boolean wasTracking = aimTracking;
        boolean wasMoving = platformMoving;
        aimTracking = aimTracking ? speed > MOVE_EXIT : speed > MOVE_ENTER;
        platformMoving = platformMoving ? speed > TEXT_EXIT : speed > TEXT_ENTER;
        // ⭐ 诊断：只报"旗子翻转"（逐 tick 报会刷屏，而翻转才是状态的真相）。
        //    ⚠️ 速度取的是**图幅枢轴那一点**的——若这里读到的速度恒为 0，
        //    而炮明明在飞，嫌疑就在 Sable 那侧的坐标（见 [诊断:aero]）。
        if (aimTracking != wasTracking || platformMoving != wasMoving) {
        }
    }

    /**
     * ⭐ 每 tick 的<b>廉价增量重解</b> —— 只更新"炮的世界位置"，其余参数复用上次的探针结果。
     *
     * <p>为什么必须每 tick：<b>诸元过期是航空学场景里最大的误差源</b> ——
     * 实测船速 6.73 格/tick、20 tick 的解算间隔 ⇒ 漂 <b>135 格</b>（而 0.33 格/tick 时也有 19.7 格）。
     * 完整解算太贵（粗扫 + 8 轮细化 ≈ 328 次弹道积分，40 门炮每 tick 跑不动），
     * 所以走 {@link BallisticSolver#solveNear}（牛顿 5~9 次，便宜两个数量级）。
     *
     * <p>⚠️ 增量路径<b>只负责"跟得住"</b>：弹道被挡 / 炮口被堵 / 目标太近 / 目标跑出射程
     * 这些判断仍留在完整解算那条路上 —— 它每 {@link #RECALC_INTERVAL} 照常跑一次。
     *
     * @return 成功重解并已落到炮上；{@code false} 表示这次跟不住（让完整解算接手判断原因）
     */
    private boolean cheapResolve() {
        if (level == null || mountPos == null || lastSolution == null || lastSpec == null
                || !ActiveBallistics.sableLoaded()) {
            return false;
        }
        Vec3 worldPivot = SableCompat.toWorld(level,
                new Vec3(probedPlotPivotX, probedPlotPivotY, probedPlotPivotZ));
        // ⭐ 提前量 —— 与完整解算那条路**必须用同一个公式**（两边都调 {@link #leadTicksNow()}，
        //    结构上保证一致），否则增量与完整解算算出的角会差一点点、两者一交替就抖一下。
        // ⚠️ 射程要取**未前移**的那个枢轴算（那才是炮弹真实出生点附近），必须放在下面 add 之前
        solutionRange = Math.hypot(targetX - worldPivot.x, targetZ - worldPivot.z);
        final double leadTicks = leadTicksNow();
        worldPivot = worldPivot.add(platformVelX * leadTicks, platformVelY * leadTicks,
                platformVelZ * leadTicks);

        FiringSolution fresh = BallisticSolver.solveNear(lastSpec, lastSolution,
                worldPivot.x, worldPivot.y, worldPivot.z, targetX, aimHeight, targetZ);
        if (fresh == null) {
            return false;
        }
        lastSolution = fresh;

        // ⚠️ 前馈量必须取"**这一 tick** 目标角漂了多少"，所以只在增量路径里算
        //    （完整解算的间隔是 20 tick，把 20 tick 的差值当 1 tick 用会一步冲过头）。
        float beforeYaw = targetYaw;
        float beforePitch = targetPitch;
        applySolution(fresh, false);
        aimDriftYaw = wrapDegrees(targetYaw - beforeYaw);
        aimDriftPitch = targetPitch - beforePitch;
        return true;
    }

    /**
     * 物理结构被**非均匀缩放**时警告一次 —— 那种情况下解算结果不可信，必须明说而不是硬算。
     *
     * <p>为什么：Sable 的 {@code transformNormal/Inverse} 会乘/除 {@code scale}，
     * 而我们的炮口几何（{@code CannonProbe} 反解出来的 {@code barrelLength}）是**沿炮轴的刚体长度** ——
     * 非均匀缩放会让它随方向变化，跨坐标系（图幅 ↔ 世界）不能再直接用。
     * 均匀缩放（三轴相同）不受影响，比例因子会被方向归一化自然约掉。
     *
     * <p>只警告一次（每个节点一次）：它在每 40 tick 的探针路径上跑，不能刷屏。
     */
    private void warnIfStructureScaled(ProbedCannon probed) {
        // ⚠️ 先过 sableLoaded() 这一关（见 SableCompat 的类注释），没装 Sable 的整合包里本方法直接返回。
        if (warnedScaled || level == null || !ActiveBallistics.sableLoaded()) {
            return;
        }
        if (!SableCompat.isOnPhysicsStructure(level, probed.mountPos())) {
            return;
        }
        if (SableCompat.scaleIsUsable(level, Vec3.atCenterOf(probed.mountPos()))) {
            return;
        }
        warnedScaled = true;
        CreateBallistics.LOGGER.warn("[航空学] 节点 {} 所在的物理结构被非均匀缩放："
                + "炮口几何跨坐标系后不再准确，本节点的解算结果不可信", worldPosition);
    }

    // ------------------------------------------------------------ 弹道弧线的选择
    private static final String ARC = "block.create_ballistics.fire_control_node.arc.";
    /** 「弹道模式：%s」这一行的标签（带 %s，自己拼模式名，见护目镜那段）。 */
    private static final String ARC_MODE_LABEL = ARC + "mode";
    /** 自动模式：低伸被挡，已改用高抛。 */
    private static final String ARC_AUTO_HIGH = ARC + "auto_high";
    /** 自动模式：**炮口跟前**有方块挡着低伸弹道，已改用高抛。 */
    private static final String ARC_AUTO_HIGH_MUZZLE = ARC + "auto_high_muzzle";
    /** 自动模式：低伸压根不在射界/射程里，直接用高抛。 */
    private static final String ARC_AUTO_HIGH_ONLY = ARC + "auto_high_only";
    /** 自动模式：低伸被挡，高抛又超出仰角上限。 */
    private static final String ARC_AUTO_NONE = ARC + "auto_none";
    /** 自动模式：两条弹道都会被方块挡住。 */
    private static final String ARC_AUTO_BOTH_BLOCKED = ARC + "auto_both_blocked";
    /** 自动模式：低伸在射界里压根不存在，而高抛又被挡住。 */
    private static final String ARC_AUTO_BLOCKED = ARC + "auto_blocked";

    /**
     * 解算飞行时间超过它（tick）**只打一条 WARN**，不做任何行为改动。
     *
     * <p>⚠️ 2026-09-26：我一度拿这个门槛在自动模式里**剔掉**那种候选（宁可报打不着），
     * 用户明确不要（"自动模式我不需要这么设置，和以前一样"）⇒ 已改成纯日志。
     * 取 30 秒：落点要等半分钟 —— 值得在日志里显眼，但**不该替玩家做决定**。
     */
    private static final double WARN_LONG_FLIGHT_TICKS = 600.0;
    /** 强制某条弹道，但它会撞到方块（照样打，只是先告诉玩家）。 */
    private static final String ARC_FORCED_BLOCKED = ARC + "forced_blocked";
    /** 目标那一段地形没加载，判断不了遮挡。 */
    private static final String ARC_TERRAIN_UNKNOWN = ARC + "terrain_unknown";
    /** 想要的弹道需要更低的炮口（低于俯角下限）。 */
    private static final String ARC_FLAT_OUTSIDE = ARC + "flat_outside";
    /** 想要的弹道需要更高的仰角（高于仰角上限）。 */
    private static final String ARC_HIGH_OUTSIDE = ARC + "high_outside";
    /** 同上，但<span>只差一点点</span> —— 这种要说"炮口压到最低也够不着"的因果，别报"要 X°、上限 Y°"。 */
    private static final String ARC_FLAT_AT_LIMIT = ARC + "flat_at_limit";
    private static final String ARC_HIGH_AT_LIMIT = ARC + "high_at_limit";
    /** 目标比这门炮的<b>最近射程</b>还近 —— 把所有角度放宽到 ±89° 都解不出来。 */
    private static final String ARC_TOO_CLOSE = ARC + "too_close";
    /**
     * <b>火炮没装填</b>（缺炮弹或缺发射药）—— 连解算的前提都不满足，谈不上弹道。
     *
     * <p>为什么单列一条：以前这种情况没有任何说明，落到那句"解不出诸元（超出射程，或火炮未装填）"里，
     * 于是**协调器界面只能武断地说"超出射程"**，而实际原因可能是打空了。
     * 2026-09-22 用户实测指出：协调器与护目镜说的不是一回事，两边该同步。
     */
    private static final String ARC_NOT_LOADED = ARC + "not_loaded";
    /**
     * <b>炮口被方块堵住了</b> —— 障碍就在炮口跟前几格里，炮弹一出膛就撞上。
     *
     * <p>和"半路上有山"刻意分开说：那种还能换弹道，这种换哪条都一样，
     * 玩家该做的是<b>去把那个方块挖掉</b>（或者把炮挪个位置）。
     */
    private static final String ARC_MUZZLE_BLOCKED = ARC + "muzzle_blocked";

    /**
     * 需要多少度才算"只差一点点"（度）。
     *
     * <p>仰角上限抬到 89.9° 之后会出现一种<b>看着像在逗人</b>的报法：
     * 「高抛弹道要 90.0° 的仰角，这门炮最高只能到 89.9°」—— 数字没错，但玩家读成"你差 0.1° 你说个啥"。
     * 实际上那种情况的真因是<b>目标太近</b>（89.9° 只能打 21 格以外）。
     * 所以差得比这个值还小的时候改说因果（2026-09-21 用户的判断）。
     */
    private static final double NEAR_LIMIT_DEG = 2.0;

    /**
     * 打不到的<b>原因分类</b>。
     *
     * <p>计算机面板离炮很远，塞不下护目镜上那句完整的说明，所以只显示一行简短结论。
     * 分类由 {@link #getArcFail()} 从 {@code arcNoteKey} 推出来 —— <b>不额外占同步字段</b>，
     * 因为那一句说明本来就同步到客户端了。
     */
    public enum ArcFail {
        /** 够不着（超出射程，或火炮没装填）。 */
        OUT_OF_RANGE("gui.create_ballistics.stage_nosolution"),
        /** 低伸被挡住，而高抛又抬不到那么高（炮座限角）—— 最常见的组合。 */
        FLAT_BLOCKED("gui.create_ballistics.stage_flat_blocked"),
        /** 低伸和高抛都会被方块挡住。 */
        BOTH_BLOCKED("gui.create_ballistics.stage_both_blocked"),
        /** 只有高抛能打，但它也被挡住了。 */
        HIGH_BLOCKED("gui.create_ballistics.stage_high_blocked"),
        /** 低伸超出这门炮的射界（炮口压不了那么低）。 */
        FLAT_OUTSIDE("gui.create_ballistics.stage_flat_outside"),
        /** 高抛超出这门炮的射界（仰角上限不够）。 */
        HIGH_OUTSIDE("gui.create_ballistics.stage_high_outside"),
        /**
         * 低伸弹道压到最低也够不着 —— 真因是<b>目标太近或太低</b>，不是"射界配小了"。
         *
         * <p>和 {@link #HIGH_AT_LIMIT} <b>刻意分成两类</b>：两者的正确做法<b>方向相反</b>
         * （低伸这边是"目标太近"，高抛那边是"目标得再远些"）。合成一句就必然说错一半。
         */
        FLAT_AT_LIMIT("gui.create_ballistics.stage_flat_at_limit"),
        /**
         * 高抛弹道抬到最高也够不着 —— 真因是<b>这门炮的仰角上限不够高</b>，得把目标放远些
         * （越远，高抛解需要的仰角越低）。
         */
        HIGH_AT_LIMIT("gui.create_ballistics.stage_high_at_limit"),
        /**
         * 目标比这门炮的<b>最近射程</b>还近 —— 把角度放宽到 ±89° 都解不出来。
         *
         * <p>单独一类，<b>绝不能并进 {@link #OUT_OF_RANGE}</b>：那会让玩家以为目标太远、
         * 往更远处找，而正确的做法是<b>把目标挪远一点</b>（或者换门炮）。
         */
        TOO_CLOSE("gui.create_ballistics.stage_too_close"),
        /**
         * <b>火炮没装填</b>（缺炮弹或缺发射药）。
         *
         * <p>刻意单列：以前这种情况并进"超出射程"，于是协调器界面只能说"超出射程"，
         * 而实际原因可能是刚打空。**玩家照着"超出射程"去改目标就完全走错了方向。**
         */
        NOT_LOADED("gui.create_ballistics.stage_not_loaded"),
        /**
         * <b>炮口被方块堵住</b> —— 障碍就在炮口跟前，换哪条弹道都没用。
         *
         * <p>单独一类，因为玩家要做的事完全不同：不是"改弹道"也不是"挪目标"，
         * 而是<b>去把那个方块挖掉</b>。
         */
        MUZZLE_BLOCKED("gui.create_ballistics.stage_muzzle_blocked");

        private final String langKey;

        ArcFail(String langKey) {
            this.langKey = langKey;
        }

        /**
         * 简短结论的语言键。
         *
         * <p>⚠️ <b>映射只此一份</b>：计算机界面和计算机护目镜都从这里取。
         * 曾经两边各写一份 switch —— 结果 2026-09-21 改界面时漏了护目镜，
         * 界面说实话、护目镜还在说"超出射程"，正是这个坑。
         */
        public String langKey() {
            return langKey;
        }

        /**
         * <b>一行短标签</b>的语言键 —— 给"炮位列表的那一列"用（协调器界面每门一行）。
         *
         * <p>与 {@link #langKey()} 的<b>长句</b>是两回事：长句是护目镜与「计算过程」用的完整说明，
         * 塞不进列表的一行。两个映射都在本枚举里，**界面不许自己再 switch 一遍**
         * （2026-09-21 就是两边各写一份，改了一边漏了另一边，界面和护目镜对不上）。
         *
         * <p>键名直接由枚举名推出：{@code gui.create_ballistics.why.<枚举名小写>}。
         * 这样**改枚举名就必然改到键**，不会出现"改了分类、忘了改文案"；
         * 缺键会被构建流程里的语言键交叉核对报出来。
         */
        public String shortKey() {
            return "gui.create_ballistics.why." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * 这一发打不到，<b>是为什么</b> —— 给计算机面板显示一行简短结论用。
     *
     * <p>说明为空（真够不着、没有别的话可说）就归到 {@link ArcFail#OUT_OF_RANGE}，
     * 那正是既有通用文案的意思。
     */
    public ArcFail getArcFail() {
        return switch (arcNoteKey) {
            case ARC_AUTO_NONE -> ArcFail.FLAT_BLOCKED;
            case ARC_AUTO_BOTH_BLOCKED -> ArcFail.BOTH_BLOCKED;
            case ARC_AUTO_BLOCKED -> ArcFail.HIGH_BLOCKED;
            case ARC_FLAT_OUTSIDE -> ArcFail.FLAT_OUTSIDE;
            case ARC_HIGH_OUTSIDE -> ArcFail.HIGH_OUTSIDE;
            // "炮口到极限也够不着"是**另一回事**（目标太近/太低），不能并进"超出射界" ——
            // 那会让玩家以为是炮座配得不对，跑去改炮座（2026-09-21 用户的判断）
            case ARC_FLAT_AT_LIMIT -> ArcFail.FLAT_AT_LIMIT;
            case ARC_HIGH_AT_LIMIT -> ArcFail.HIGH_AT_LIMIT;
            case ARC_TOO_CLOSE -> ArcFail.TOO_CLOSE;
            case ARC_MUZZLE_BLOCKED -> ArcFail.MUZZLE_BLOCKED;
            case ARC_NOT_LOADED -> ArcFail.NOT_LOADED;
            default -> ArcFail.OUT_OF_RANGE;
        };
    }

    /** 打不到的完整说明（语言键）与它的两个参数 —— 计算机护目镜要用（那里地方够，可以写全）。 */
    public String getArcNoteKey() {
        return arcNoteKey;
    }

    public double getArcNoteA() {
        return arcNoteA;
    }

    public double getArcNoteB() {
        return arcNoteB;
    }

    /**
     * 一次弧线选择的结果。
     *
     * @param solution 选中的诸元；<b>{@code null} 表示这条目标打不了</b>
     * @param choice   用了哪条：0 = 没打成，1 = 低伸，2 = 高抛
     * @param noteKey  要写给玩家看的原因（语言键）；空串表示没什么可说的
     * @param noteA    原因里的第一个数（撞到的距离 / 需要多少度 …）
     * @param noteB    原因里的第二个数
     */
    private record ArcSelection(@Nullable FiringSolution solution, byte choice,
                               String noteKey, double noteA, double noteB) {
    }

    /**
     * 按当前<b>弹道模式</b>从候选解里挑一条。
     *
     * <p>三种模式的语义（2026-09-20 与用户逐条确认）：
     * <ul>
     *   <li><b>自动</b>：默认低伸；<b>只有地形校验真的识别出低伸被挡住</b>才改用高抛。
     *       地形判断不了（目标那一段区块没加载）时退回低伸并如实说，<b>不假装没障碍</b>。</li>
     *   <li><b>低伸 / 高抛</b>：强制。<b>解不出来就报原因，绝不自作主张换另一条</b> ——
     *       "我选了低伸你却给我高抛"是最容易被骂的那种设计。</li>
     * </ul>
     *
     * <p>⚠️ 无论哪种模式，只要真的要打，都会跑一次地形校验并如实报告 ——
     * 强制低伸的玩家更需要知道"你这条弹道要撞山了"。
     */
    private ArcSelection chooseArc(CannonSpec spec, List<FiringSolution> solutions,
                                   ProbedCannon probed, double aimY) {

        FiringSolution flat = findArc(solutions, FiringSolution.Arc.FLAT);
        FiringSolution high = findArc(solutions, FiringSolution.Arc.HIGH);

        // 候选解一条都没有 → 先分清"超出射程"还是"角度超出射界"，两者的文案完全不同
        if (flat == null && high == null) {
            return diagnoseUnreachable(spec, probed, aimY);
        }
        diagKey = "";   // 又能解出来了，把上次那份诊断记忘掉（下次真失败时要重新问一遍）

        switch (arcMode) {
            case FLAT -> {
                if (flat == null) {
                    return unavailable(spec, probed, aimY, FiringSolution.Arc.FLAT);
                }
                return report(flat, probed, aimY, (byte) 1);
            }
            case HIGH -> {
                if (high == null) {
                    return unavailable(spec, probed, aimY, FiringSolution.Arc.HIGH);
                }
                return report(high, probed, aimY, (byte) 2);
            }
            default -> {
                // ---- 自动：默认低伸，只有它真的不可用才换高抛 ----
                //
                // ⚠️⚠️ 决策**一次定下来就不再中途改**（除非换了目标或换了装填）。
                //
                // 这不是"防抖补丁"，而是本来就应该这样：**一次火力任务该用一套诸元** ——
                // 炮转到一半忽然改弹道，对着的目标又没变，那是无意义的。
                //
                // 更实际的原因是它会把炮逼疯：地形校验必然存在**边界**（方块紧贴炮口、
                // 山脊线正好擦着弹道…），而炮在转动时探针读到的几何会有微抖动，
                // 于是"挡住 / 没挡住"来回翻面 ⇒ AUTO 跟着来回切 ⇒
                // 炮仰头→低头→仰头，甚至剧烈抽搐（2026-09-21 用户实测两次）。
                // **只要判定能自激，加多少余量都堵不住；让决策不再依赖每一次的判定才是解法。**
                //
                // 想重新决策：重新点一次「确认坐标并开始计算」即可（aimAt 会清掉这个闩）。
                String latchKey = arcLatchFingerprint(probed);
                if (latchKey.equals(arcLatchKey) && arcLatched != 0) {
                    FiringSolution latched = arcLatched == 1 ? flat : high;
                    if (latched != null) {
                        // 仍然跑一次校验，只为把**当前**状态如实说给玩家听，**不用它改选择**
                        TerrainCheck.Result r = checkTerrain(latched, probed, aimY);
                        if (r.blocked()) {
                            return atMuzzle(r)
                                    ? muzzleBlocked(r)
                                    : new ArcSelection(latched, arcLatched,
                                            ARC_FORCED_BLOCKED, r.distance(), 0.0);
                        }
                        return r.unknown()
                                ? new ArcSelection(latched, arcLatched, ARC_TERRAIN_UNKNOWN, 0.0, 0.0)
                                : new ArcSelection(latched, arcLatched, "", 0.0, 0.0);
                    }
                    // 原来那条没了（装填/射界变了）→ 落下去重新决策
                }

                ArcSelection decided = decideAuto(spec, probed, aimY, flat, high);
                if (decided.choice() != 0) {
                    arcLatched = decided.choice();
                    arcLatchKey = latchKey;
                }
                return decided;
            }
        }
    }

    /**
     * 弧线闩的输入指纹 —— <b>只装"真的换了就该重新决策"的东西</b>。
     *
     * <p>⚠️ 刻意<b>不含炮轴长度与瞄准高度</b>：那两个会随炮的姿态微抖
     * （反解要除以 {@code cos(仰角)}，而瞄准高度取自地形高度图）。
     * 把它们装进来，闩就<b>永远闩不上</b>，等于没闩 —— 2026-09-21 实测就是这么翻车的。
     *
     * <p>所以只认：弹道模式、目标坐标、装药数、弹种、仰角上限。
     * 这些变了才说明是"另一发"；炮转个角度不算。
     */
    private String arcLatchFingerprint(ProbedCannon probed) {
        // ⚠️⚠️ 目标坐标要**粗化到 16 格（1 区块）**，不能用原值（2026-09-25 实测定案）。
        //
        // 为什么：近距离目标常常"低伸解恰好压在俯仰下限上"（实测：目标 79.6 格 →
        // 低伸解 −29.895°，而下限正好是 −30°）⇒ 目标挪几格就在"有解/无解"之间翻面。
        // 而**用无人机重新报一次点就是一个新目标** ⇒ 闩被重置 ⇒ 重新决策 ⇒
        // 在两个解之间来回跳（世界角一会儿 −29.9°、一会儿 +88.7°）⇒
        // 玩家看到的是"往上抬也不是、往下低也不是，卡住了"，而炮实际误差一度到 **103°** ✗。
        //
        // 粗化之后，同一小片区域内的重新报点**不会**重置闩 ⇒ 一次火力任务用一套诸元 ✓
        // —— 这正是这个闩本来就想做的事（见它的类注释）。
        // ⚠️ 真换了地方（隔了区块）、或换了弹道模式，闩照样重置 ✓
        return arcMode + "|" + Math.round(targetX / 16.0) + "|" + Math.round(targetY / 16.0)
                + "|" + Math.round(targetZ / 16.0) + "|"
                + probed.chargeCount() + "|" + probed.shellItemId() + "|" + probed.maxPitch();
    }

    /** 自动模式的决策本体 —— 只在"还没定过"或"定过的那条不可用了"时跑。 */
    private ArcSelection decideAuto(CannonSpec spec, ProbedCannon probed, double aimY,
                                    @Nullable FiringSolution flat, @Nullable FiringSolution high) {
        TerrainCheck.Result flatCheck = flat == null ? null : checkTerrain(flat, probed, aimY);

        if (flat != null && !flatCheck.blocked()) {
            return flatCheck.unknown()
                    ? new ArcSelection(flat, (byte) 1, ARC_TERRAIN_UNKNOWN, 0.0, 0.0)
                    : new ArcSelection(flat, (byte) 1, "", 0.0, 0.0);
        }

        TerrainCheck.Result highCheck = high == null ? null : checkTerrain(high, probed, aimY);
        if (high != null && !highCheck.blocked()) {
            // 低伸被挡住（或压根不存在）→ 改用高抛
            // ⚠️ 两种情形都要留下说明。以前"低伸压根不存在"那种是不吭声就换的，
            // 玩家只会看到仰角莫名其妙变成了高抛角（2026-09-21 自查发现）。
            if (flat == null) {
                return highCheck.unknown()
                        ? new ArcSelection(high, (byte) 2, ARC_TERRAIN_UNKNOWN, 0.0, 0.0)
                        : new ArcSelection(high, (byte) 2, ARC_AUTO_HIGH_ONLY, 0.0, 0.0);
            }
            // 挡在**炮口跟前**的要单独说 —— 那种不是"低伸半路有山"，
            // 而是"炮管口被方块顶着"，玩家该知道是这一回事（2026-09-21 用户提出）
            if (atMuzzle(flatCheck)) {
                return new ArcSelection(high, (byte) 2, ARC_AUTO_HIGH_MUZZLE,
                        flatCheck.beyondMuzzle(), 0.0);
            }
            return new ArcSelection(high, (byte) 2, ARC_AUTO_HIGH, flatCheck.distance(), 0.0);
        }

        // ---- 两条都用不了 ----
        // ⚠️ 自动模式的目的是"挑一条能打的"。一条都没有时**必须拒发** ——
        // 照旧打出去的话炮弹只会撞在山上，玩家却看见"已就位"，完全无从判断。
        //
        // 被挡在**炮口跟前**的要单独说：那种换哪条弹道都一样，玩家该做的是把方块挖掉。
        if (flat != null && high != null) {
            if (atMuzzle(flatCheck)) {
                return muzzleBlocked(flatCheck);
            }
            if (atMuzzle(highCheck)) {
                return muzzleBlocked(highCheck);
            }
            return new ArcSelection(null, (byte) 0, ARC_AUTO_BOTH_BLOCKED,
                    flatCheck.distance(), highCheck.distance());
        }
        if (flat != null) {
            // 高抛超出仰角上限 —— 把"需要多少度"和"上限多少度"一起说清楚。
            // （低伸被挡的那个距离如果只有几格，玩家自己看得出是炮口有东西）
            return new ArcSelection(null, (byte) 0, ARC_AUTO_NONE,
                    flatCheck.distance(), spec.maxPitch());
        }
        if (atMuzzle(highCheck)) {
            return muzzleBlocked(highCheck);
        }
        return new ArcSelection(null, (byte) 0, ARC_AUTO_BLOCKED,
                highCheck == null ? 0.0 : highCheck.distance(), 0.0);
    }

    /** 选定一条解，并顺手做一次地形校验 —— 被挡住的话照样用，但要在护目镜上说破。 */
    private ArcSelection report(FiringSolution chosen, ProbedCannon probed, double aimY, byte choice) {
        TerrainCheck.Result r = checkTerrain(chosen, probed, aimY);
        if (r.blocked()) {
            return atMuzzle(r)
                    ? muzzleBlocked(r)
                    : new ArcSelection(chosen, choice, ARC_FORCED_BLOCKED, r.distance(), 0.0);
        }
        if (r.unknown()) {
            return new ArcSelection(chosen, choice, ARC_TERRAIN_UNKNOWN, 0.0, 0.0);
        }
        return new ArcSelection(chosen, choice, "", 0.0, 0.0);
    }

    /**
     * 炮口被方块堵住 —— 换哪条弹道都一样，所以直接说破，别让玩家去折腾弹道。
     *
     * <p>参数用 {@link TerrainCheck.Result#beyondMuzzle()}（<b>距炮口</b>多少格），
     * 不是 {@code distance()}（距枢轴多少格）—— 后者会把"炮口前 3 格"报成"23 格"。
     */
    private static ArcSelection muzzleBlocked(TerrainCheck.Result r) {
        return new ArcSelection(null, (byte) 0, ARC_MUZZLE_BLOCKED, r.beyondMuzzle(), 0.0);
    }

    private static boolean atMuzzle(TerrainCheck.Result r) {
        return r != null && r.atMuzzle();
    }

    private TerrainCheck.Result checkTerrain(FiringSolution solution, ProbedCannon probed, double aimY) {
        if (level == null) {
            return new TerrainCheck.Result(TerrainCheck.Kind.UNKNOWN, 0.0, 0.0);
        }
        List<TrajectorySimulator.PathPoint> path = TrajectorySimulator.samplePath(
                solution.pitch(), probed.muzzleVelocity(), probed.barrelLength(),
                probed.originY(), aimY, probed.ballistics(), TerrainCheck.STEP_BLOCKS);
        // ⚠️⚠️ 必须把**目标距离**一起交给校验（2026-09-27 修）：
        //    折线的落点是"这条弹道自己落到瞄准高度的位置"，目标比炮口高时低伸解是从**上升段**
        //    穿过目标的，落点在目标之外老远（实测 80.3 格的目标、落点 313.9 格）。
        //    不给目标距离的话，末段保护带会落在目标之外，**目标脚下那块地反被判成障碍**
        //    ⇒ 每一条低伸解都被判"撞地形" ⇒ 自动模式全退高抛。详见 TerrainCheck#check。
        final double targetRange = Math.hypot(targetX - probed.originX(), targetZ - probed.originZ());
        TerrainCheck.Result result = TerrainCheck.check(level, probed.originX(), probed.originY(),
                probed.originZ(), solution.yaw(), targetRange, path);
        return result;
    }

    /** 想要的这条弹道在射界内不存在 —— 分清"超出射程"和"角度不够"，并给出需要多少度。 */
    private ArcSelection unavailable(CannonSpec spec, ProbedCannon probed, double aimY,
                                     FiringSolution.Arc want) {
        // ⚠️ 与 diagnoseUnreachable 同一个坑：放宽射界重解很贵，而这条路径**会每 20 tick 反复走到**
        // —— 玩家选了高抛、目标又常年超出仰角上限时就是这种情况。输入没变就不重复问。
        String key = want.name() + "|" + diagFingerprint(spec, probed, aimY);
        if (key.equals(diagKey)) {
            return new ArcSelection(null, (byte) 0, arcNoteKey, arcNoteA, arcNoteB);
        }
        diagKey = key;

        Double need = requiredPitch(spec, probed, aimY, want);
        if (need == null) {
            // 真的够不着 —— 空串让它退回那条既有的通用文案（"超出射程，或火炮未装填"），
            // 这里不另造一条重复的说法
            return new ArcSelection(null, (byte) 0, "", 0.0, 0.0);
        }
        return outsidePitch(want, need, spec);
    }

    /**
     * 想要的弹道超出射界 —— <b>差得远就说角度，只差一点就说因果</b>。
     *
     * <p>差得远（比如要 75° 而上限 60°）时，「要 X°、这门炮最高只能到 Y°」最有用：
     * 玩家一眼看出是<b>炮座限角</b>，不是自己哪儿弄错了。
     *
     * <p>只差一点点时那句话就变成废话了 —— 仰角上限抬到 89.9° 之后，
     * 「要 90.0°、上限 89.9°」看着像在逗人。那种情况的真因是<b>目标太近</b>
     * （89.9° 只能打到 21 格以外），所以改说因果（2026-09-21 用户提出）。
     */
    private static ArcSelection outsidePitch(FiringSolution.Arc want, double need, CannonSpec spec) {
        final boolean flat = want == FiringSolution.Arc.FLAT;
        final double limit = flat ? spec.minPitch() : spec.maxPitch();
        // 低伸是"需要的角度比下限还低"，高抛是"比上限还高"，两个方向的差额都取正值。
        // ⚠️ 下限卡在 0：差额为**负**说明需要的角度其实在射界内 —— 那就不该说"到极限也够不着"。
        //    正常路径走不到这儿（在射界内的话解算本来就解得出来），但算式不能靠"走不到"来保证正确。
        final double shortfall = flat ? limit - need : need - limit;

        if (shortfall >= 0.0 && shortfall < NEAR_LIMIT_DEG) {
            // 参数只给极限角（第一个）—— 这两条文案只引一个 %s
            return new ArcSelection(null, (byte) 0,
                    flat ? ARC_FLAT_AT_LIMIT : ARC_HIGH_AT_LIMIT, limit, 0.0);
        }
        return new ArcSelection(null, (byte) 0,
                flat ? ARC_FLAT_OUTSIDE : ARC_HIGH_OUTSIDE, need, limit);
    }

    /**
     * 昂贵诊断的输入指纹 —— 这一组输入没变，诊断结论就不会变，不必重算。
     *
     * <p>指纹里必须是<b>所有会改变解的因素</b>：目标坐标、实际瞄准高度、初速、炮轴长度、射界。
     * 漏一个就会出现"换了装药，护目镜却还报旧的打不到原因"这种鬼话。
     */
    private String diagFingerprint(CannonSpec spec, ProbedCannon probed, double aimY) {
        return targetX + "|" + aimY + "|" + targetZ + "|"
                + probed.muzzleVelocity() + "|" + probed.barrelLength() + "|"
                + spec.minPitch() + "|" + spec.maxPitch();
    }

    /** 限定射界内一条解都没有 —— 问问放宽以后能不能打，能打就说明是"角度不够"，不能就是"太远了"。 */
    private ArcSelection diagnoseUnreachable(CannonSpec spec, ProbedCannon probed, double aimY) {
        // ⚠️ 这条路径很贵（放宽到 ±89° 要解两次，每次扫七百多个仰角），而它**会反复走到**：
        // 解不出来的炮每 RECALC_INTERVAL 就重解一次，`aiming` 一直是真的。
        String key = "unreachable|" + diagFingerprint(spec, probed, aimY);
        if (key.equals(diagKey)) {
            return new ArcSelection(null, (byte) 0, arcNoteKey, arcNoteA, arcNoteB);
        }
        diagKey = key;

        // ⭐⭐ 先用**便宜判据**把两端摘出去（2026-09-25 日志实测定案：这一步一次要 **8 ms**，
        //     用户用无人机连续报点（目标每秒都在变）时**每秒卡一次** —— 那就是"卡几帧"的元凶）。
        //   · 比"炮口抬到最高时的落点"还近 ⇒ 铁定太近（一次 simulate，几微秒）
        //   · 超出极限射程             ⇒ 铁定太远（查缓存值即可）
        //   **只有中间那段模糊区**才值得放宽到 ±89° 解两次 —— 那两次是本方法贵的唯一原因。
        final double distNow = Math.hypot(targetX - probed.originX(), targetZ - probed.originZ());
        final double closest = minReachableRange(probed, aimY);
        if (closest > 0.0 && distNow < closest) {
            return new ArcSelection(null, (byte) 0, ARC_TOO_CLOSE, closest, 0.0);
        }
        if (probedMaxRange > 0.0F && distNow > probedMaxRange) {
            // 空串 ⇒ 退回既有那条通用文案（"超出射程，或火炮未装填"），不另造重复说法
            return new ArcSelection(null, (byte) 0, "", 0.0, 0.0);
        }

        Double needFlat = requiredPitch(spec, probed, aimY, FiringSolution.Arc.FLAT);
        Double needHigh = requiredPitch(spec, probed, aimY, FiringSolution.Arc.HIGH);
        if (needFlat == null && needHigh == null) {
            // 射界放宽到 ±89° 都解不出来 —— 只剩两种可能：**太远**，或者**太近**。
            //
            // ⚠️ 不能一律报"超出射程"：目标落在极限射程**以内**却没有解，那只可能是
            // **比这门炮的最近射程还近**（炮口抬到最高时都打过头）。目标在 20 格以内时
            // 尤其明显 —— 炮口本身的前伸量就有约 20 格，炮弹一出发就已经飞过它了。
            // 那种情况报"超出射程"是彻头彻尾的假话（2026-09-21 用户实测：20 格以内三种模式全这么报）。
            final double dist = Math.hypot(targetX - probed.originX(), targetZ - probed.originZ());
            if (probedMaxRange > 0.0F && dist <= probedMaxRange) {
                return new ArcSelection(null, (byte) 0, ARC_TOO_CLOSE,
                        minReachableRange(probed, aimY), 0.0);
            }
            // 真的够不着 —— 空串让它退回那条既有的通用文案（"超出射程，或火炮未装填"），
            // 这里不另造一条重复的说法
            return new ArcSelection(null, (byte) 0, "", 0.0, 0.0);
        }
        double need = needFlat != null ? needFlat : needHigh;
        boolean flatSide = needFlat != null;
        return outsidePitch(flatSide ? FiringSolution.Arc.FLAT : FiringSolution.Arc.HIGH, need, spec);
    }

    /**
     * 把炮口抬到最高时能打到多远 —— 也就是这门炮的<b>最近射程</b>。
     *
     * <p>高抛弹道是越抬越近的：抬到极限角时落点最近（65° 时还有 5073 格，89.9° 时只剩 21 格）。
     * 目标比这个还近，就<b>任何角度都够不着</b>了 —— 报错时要把这个数告诉玩家，
     * 否则"目标太近"四个字他也没法判断到底该挪多远。
     */
    private double minReachableRange(ProbedCannon probed, double aimY) {
        TrajectorySimulator.Flight f = TrajectorySimulator.simulate(
                probed.maxPitch(), probed.muzzleVelocity(), probed.barrelLength(),
                probed.originY(), aimY, probed.ballistics());
        return f == null ? 0.0 : f.horizontalRange();
    }

    /**
     * 这条弹道<b>真正需要</b>的仰角 —— 把射界放宽到 ±89° 重解一次拿到。
     *
     * <p>只在<b>要报错误原因</b>时才调用（正常路径不跑），所以这一次额外解算的开销可以接受。
     * 报"需要 75°，而这门炮最高只能到 60°"比报"高抛打不了"有用得多 ——
     * 玩家至少知道是炮座的角度不够，而不是自己哪儿弄错了。
     */
    @Nullable
    private Double requiredPitch(CannonSpec spec, ProbedCannon probed, double aimY,
                                 FiringSolution.Arc want) {
        CannonSpec wide = spec.withPitchRange(-89.0, 89.0);
        for (FiringSolution s : BallisticSolver.solve(wide,
                probed.originX(), probed.originY(), probed.originZ(),
                targetX, aimY, targetZ)) {
            if (s.arc() == want) {
                return s.pitch();
            }
        }
        return null;
    }

    @Nullable
    private static FiringSolution findArc(List<FiringSolution> solutions, FiringSolution.Arc arc) {
        for (FiringSolution s : solutions) {
            if (s.arc() == arc) {
                return s;
            }
        }
        return null;
    }

    /**
     * 限速逼近 —— <b>照抄参考模组 {@code firecontrol} 的伺服写法</b>。
     *
     * <pre>
     * 读当前角 → 算误差 → 每 tick 只挪 clamp(误差, ±步长) 一步
     * </pre>
     *
     * ① 误差小于一步时 {@code clamp} 给出精确误差，一 tick 落位、不会冲过头（所以不抖）；
     * ② 步长有下限，误差再小也能收敛；③ 方位角误差先归一，跨 ±180° 时不会绕远路。
     */
    private void stepTowards(CannonMountBlockEntity mount, boolean keepControl) {
        float currentYaw = mount.getYawOffset(0.0F);
        float currentPitch = mount.getPitchOffset(0.0F);

        float yawError = wrapDegrees(targetYaw - currentYaw);
        float pitchError = targetPitch - currentPitch;
        boolean settled = Math.abs(yawError) <= SETTLE_DEG && Math.abs(pitchError) <= SETTLE_DEG;

        if (settled && aiming) {
        }

        if (settled) {
            if (aiming) {
                aiming = false;   // 到位：开火判据（isAimed）要用它
                setChanged();
                sendData();
            }
            if (!keepControl) {
                // 不在跟踪 ⇒ 把炮座还给 CBC（原有行为，一个字不变）
                return;
            }
            // ⚠️ 在跟踪则**不能撒手**：一撒手瞄准就开始飘（载具还在动）
        }

        float step = Math.max(MIN_STEP_DEG, Math.abs(getSpeed()) * SLEW_PER_RPM);

        // ---- ⭐ 稳态滞后补偿：把"目标这一 tick 漂了多少"前馈进这一步 ----
        //
        // 追一个**动目标**时，只朝目标走一步会让伺服永远落在目标后面：
        // 目标每 tick 漂 D、伺服每 tick 最多走 step ⇒ 稳态误差 ≈ D ——
        // 而 D 常常远大于 0.05° 的到位判定 ⇒ `aiming` 永远不归零 ⇒ **开火闸门永远不开**
        // （2026-09-25 实机：飞艇一开就卡在「正在计算」、开不了炮，而炮确实在转）。
        // 把 D 前馈进这一步，稳态误差就回到 0（判定才可能成立）。
        // ⚠️ 仍然整体 clamp 在 ±step 内 ⇒ **绝不会比原来转得更快**：
        //    真跟不上的时候照样跟不上，那种情况由 P3 的"所需转速"提醒去说。
        // ⚠️ D 只在**每 tick 增量重解**那条路上算（见 cheapResolve）—— 完整解算间隔是 20 tick，
        //    把它的差值当前馈会一步冲过去。
        mount.setYaw(currentYaw + clamp(yawError + aimDriftYaw, step));
        mount.setPitch(currentPitch + clamp(pitchError + aimDriftPitch, step));
        mount.notifyUpdate();

        updateRpmShort(yawError, pitchError, step);
    }

    /**
     * ⭐ P3：判定"炮塔**持续**跟不上目标"（＝ 该提醒玩家加转速了）。
     *
     * <h2>为什么要"持续"两个字</h2>
     *
     * 需求侧这一步是 {@code max(|方位要走的|, |俯仰要走的|)}，供给侧是
     * {@code step = max(MIN_STEP_DEG, |转速| × SLEW_PER_RPM)}。被夹住（需求 &gt; 供给）**本身不是病**：
     * 给一个全新的远目标时误差一大截，伺服就是要靠削顶一步步转过去 —— 那是"**正在转**"。
     * 真正的病是**追不上**：误差不再缩小、每次都削顶 ⇒ 稳态滞后永远存在、`aiming` 永不归零、
     * 这门炮从此不参与任何一轮射击，而玩家完全看不出为什么。
     *
     * ⇒ 两个条件同时成立才算：**削顶连续 {@link #RPM_SHORT_TICKS} tick**，
     * 并且**误差模长没在按"伺服应有的速度"缩小**（{@link #RPM_TREND_INTERVAL} 采一次，
     * 判据见 {@link #RPM_TREND_PROGRESS}）。
     * 两边都带滞回，避免贴着阈值反复翻面（本项目在 AUTO 弹道切换上为此栽过三次）。
     *
     * <p>报出去的数 = {@code needed / SLEW_PER_RPM} ⇒ **"在这个转速下我们就不会再削顶"**
     * —— 玩家照着它加转速，提示自己就会消失（验收时就是拿这个闭环自检的）。
     * ⚠️⚠️ 这个数**必须按 Create 的转速上限夹一下**：`needed` 里含着"还差多少度"，
     * 大角度起步时它能有几十度 ⇒ 换算出来是**几千 RPM**，而 Create 最高只有
     * {@code maxRotationSpeed}（默认 256）⇒ 报一个不可能达到的数只会误导（2026-09-26 用户实测）。
     */
    private void updateRpmShort(float yawError, float pitchError, float step) {
        // 需求侧：这一 tick "想走"的幅度（与上面真正应用的量逐字同源）
        double needed = Math.max(Math.abs(yawError + aimDriftYaw), Math.abs(pitchError + aimDriftPitch));
        boolean clipped = needed > step;
        long now = level == null ? 0L : level.getGameTime();
        double error = Math.hypot(yawError, pitchError);

        if (clipped) {
            if (rpmShortRun == 0) {
                // ⚠️ 刚进入一次"被夹住"的连续段 ⇒ **趋势账本从这一刻重新起算**：
                //    不重算的话会拿很久以前（甚至上一次任务）的采样来比，
                //    于是新任务一开局就可能立刻被判成"恶化"（假报警）。
                rpmTrendTick = now;
                rpmTrendError = error;
                rpmTrendWorse = false;
            }
            rpmShortRun++;
            rpmOkRun = 0;
            long ticks = now - rpmTrendTick;
            if (ticks >= RPM_TREND_INTERVAL) {
                // ⭐ 判"在不在收敛"要拿**伺服这一段时间能走多少**来比，不能拿固定百分比比：
                //    削顶时误差每 tick 最多缩 `step` ⇒ 一段 `ticks` 里最多缩 `step × ticks`。
                //    缩了其中一半以上 ⇒ 它正在使劲往目标靠（**正在转**）；
                //    几乎没缩 ⇒ 目标在跑、误差追不掉（**跟不上**）。
                // ⚠️ 我第一版写的是"缩不到 10% 就算恶化" —— **转速越低越会误判**：
                //    10 RPM 走 1 秒只缩 6°，而误差有 30° ⇒ 只缩了 20% ⇒ 被误报成
                //    "需要 2000 RPM"（用户 2026-09-26 实机看到"几千几千"就是这个）。
                rpmTrendWorse = (rpmTrendError - error) < RPM_TREND_PROGRESS * step * ticks;
                rpmTrendTick = now;
                rpmTrendError = error;
            }
        } else {
            rpmOkRun++;
            rpmShortRun = 0;
        }

        boolean want = rpmShortRun >= RPM_SHORT_TICKS && rpmTrendWorse;
        if (want) {
            // 提示期间把数刷新成当前值（它随滞后收敛而变小 ⇒ 玩家照它加转速是收敛的）
            rpmNeeded = (int) Math.ceil(needed / SLEW_PER_RPM);
        } else if (rpmShort) {
            rpmNeeded = 0;
        }
        // ⚠️ 只在"旗子翻转"或"提示期间数变了且隔了半秒"时才同步 —— stepTowards 每 tick 都跑，
        //    每 tick setChanged 会让 40 门炮每秒刷 800 个包。
        boolean flipped = rpmShort != want;
        boolean valueMoved = want && rpmNeeded != rpmNeededSynced
                && now - rpmSyncTick >= RPM_SYNC_INTERVAL;
        if (!flipped && !valueMoved) {
            return;
        }
        if (flipped) {
        }
        rpmShort = want;
        rpmNeededSynced = rpmNeeded;
        rpmSyncTick = now;
        setChanged();
        sendData();
    }

    private static float clamp(float value, float step) {
        return Math.max(-step, Math.min(step, value));
    }

    private static float wrapDegrees(float deg) {
        return net.minecraft.util.Mth.wrapDegrees(deg);
    }

    /** 真正击发 —— 走 CBC 自己的红石发射路径。 */
    private void tryFire(CannonMountBlockEntity mount) {
        if (aiming) {
            return;
        }
        // ⚠️ 无论下面成不成功，这条排队都必须<b>先消费掉</b>。若留在"开火失败就 return"之后，
        // fireQueued 会一直挂在那里 —— 炮一旦重新组装好（玩家补上被炸掉的炮管之类），
        // 它会在下一 tick <b>立刻自动开火</b>，哪怕早已换了目标，甚至根本没下过军令。
        // 多炮齐射时这就是一发"迟到的鬼炮"，而且不会在界面上留下任何痕迹。
        fireQueued = false;
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return;
        }
        PitchOrientedContraptionEntity contraption = mount.getContraption();
        if (contraption == null || !(contraption.getContraption() instanceof AbstractMountedCannonContraption mounted)) {
            return;
        }
        // togglePower = true, firePower = 3：CBC 内部即 fireShot()
        mounted.onRedstoneUpdate(serverLevel, contraption, true, 3, mount);

        // ⚠️⚠️ CBC 的 fireShot 是 void，而且里面有好几条"<b>只消耗、不出弹</b>"的早退路：
        //    卡弹 squib / 炸膛 fail —— **两者都是按概率掷骰子的**（见 CBC 的 fireShot）。
        //    所以击发之后<b>立刻在炮口附近找一次弹丸实体</b>：找不到就说明这一发根本没出去。
        //    ⇒ 不记这一笔的话，玩家看到的是"膛空了、什么也没飞"，只能猜是自己卡了或者是 bug
        //      （2026-09-23 用户实测，前后困惑了两轮）。
        AbstractBigCannonProjectile shell = nearestShell(serverLevel, contraption);
        boolean failed = shell == null;
        if (failed) {
            // ⚠️ 只在"这一发根本没出去"时报（CBC 的卡弹/炸膛会静默吃掉整发）——
            //    正常出弹不刷日志：那是每发都有的例行事件，不该占玩家的日志。
            CreateBallistics.LOGGER.info("[火控] 节点 {} 击发后炮口附近找不到弹丸 —— 这一发没打出去（CBC 判定卡弹/炸膛）",
                    worldPosition);
        }
        if (shell != null) {
            // ⭐ 实弹标定：记下这一发（枢轴 / 目标 / 射程 / 载具速度 / 解算飞行时间），
            //    之后每 tick 跟它的落点，落地时按落点反推并更新"射程比例误差 k"。
            //    ⚠️ 枢轴用**未前移**的那个（那才是炮弹真实出生点附近）。
            Vec3 velNow = ActiveBallistics.sableLoaded() && mountPos != null
                    ? SableCompat.velocityAt(serverLevel, Vec3.atCenterOf(mountPos))
                    : Vec3.ZERO;
            int mountX = mountPos == null ? 0 : mountPos.getX();
            int mountY = mountPos == null ? 0 : mountPos.getY();
            int mountZ = mountPos == null ? 0 : mountPos.getZ();
            Vec3 pivotUnled = new Vec3(probedPlotPivotX, probedPlotPivotY, probedPlotPivotZ);
            if (ActiveBallistics.sableLoaded()) {
                pivotUnled = SableCompat.toWorld(level, pivotUnled);
            } else {
                pivotUnled = new Vec3(mountX, mountY, mountZ);
            }
            calibration.recordShot(serverLevel, pivotUnled,
                    new Vec3(targetX, targetY, targetZ), velNow, solutionAirTime, shell);
        }
        if (failed != lastFireFailed) {
            lastFireFailed = failed;
            setChanged();
            sendData();
        }
    }

    /**
     * 炮口附近离炮最近的那一发弹丸 —— {@code null} 表示刚才那一发根本没出去。
     *
     * <p>弹丸是在 {@code fireShot} 里<b>同步</b>加进世界的（{@code addFreshEntity}），
     * 所以同一 tick 就能查到；慢一步它就飞远了，所以这个检查必须紧跟着击发做。
     *
     * <p>返回实体而不只是布尔：调用方还要用它报"这一发到底出没出去"（见 {@code callCbcFireShot}）。
     */
    @Nullable
    private static AbstractBigCannonProjectile nearestShell(ServerLevel level,
                                                            PitchOrientedContraptionEntity contraption) {
        // ⚠️⚠️ 在航空学物理结构上时，`contraption.getBoundingBox()` 是**图幅坐标**，
        //    而炮弹一出生就被 Sable 搬到**世界坐标**了（`ServerLevel.addFreshEntity` 的 HEAD 注入，
        //    见 `sable.mixin.entity.entity_kicking.ServerLevelMixin`）⇒
        //    不把搜索框也跟着转过去，**每一发都会误报「弹丸生成=false」（"这一发没出去"）**。
        //
        // ⚠️ 结构有旋转时，轴对齐包围盒转过去不再是轴对齐的 ⇒ 取两角的世界坐标重新取 min/max
        //    （结果偏大一圈，而这里本来就是"往周围找 40 格"，宽松无害）。
        // ⚠️ 不在物理结构上（或没装 Sable）时 toWorld 原样返回 ⇒ 逐字维持既有行为。
        AABB plotBox = contraption.getBoundingBox();
        Vec3 cornerA = new Vec3(plotBox.minX, plotBox.minY, plotBox.minZ);
        Vec3 cornerB = new Vec3(plotBox.maxX, plotBox.maxY, plotBox.maxZ);
        // ⚠️ sableLoaded() 这一关不能省（见 SableCompat 的类注释）。
        if (ActiveBallistics.sableLoaded()) {
            cornerA = SableCompat.toWorld(level, cornerA);
            cornerB = SableCompat.toWorld(level, cornerB);
        }
        AABB search = new AABB(
                Math.min(cornerA.x, cornerB.x), Math.min(cornerA.y, cornerB.y), Math.min(cornerA.z, cornerB.z),
                Math.max(cornerA.x, cornerB.x), Math.max(cornerA.y, cornerB.y), Math.max(cornerA.z, cornerB.z))
                .inflate(40.0);

        Vec3 center = cornerA.lerp(cornerB, 0.5);
        AbstractBigCannonProjectile nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (AbstractBigCannonProjectile shell : level.getEntitiesOfClass(AbstractBigCannonProjectile.class, search)) {
            double distSq = shell.position().distanceToSqr(center);
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = shell;
            }
        }
        return nearest;
    }

    // ------------------------------------------------------------------ 指令

    /** 下达打击坐标 —— 这是节点收到的唯一军令形式，诸元由它自己解。 */
    public void aimAt(double x, double y, double z, ArcMode mode) {
        aimAt(x, y, z, mode, false);
    }

    /**
     * 下达打击坐标（可要求强制吸附地面高度）。
     *
     * @param forceGround 覆盖打击传 {@code true} —— 见 {@link #forceGroundAlign}：
     *                    那种情况下每个点的高度差可能超过那条 ±10 容差，
     *                    不强制吸附就会让半个圆的炮弹按错的基准高度解算。
     */
    public void aimAt(double x, double y, double z, ArcMode mode, boolean forceGround) {
        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
        this.arcMode = mode;
        this.forceGroundAlign = forceGround;
        // 新目标 = 新任务 → 把"上次定下来的弹道"清掉，让它重新决策一次
        this.arcLatched = 0;
        this.arcLatchKey = "";
        // 换了目标 = 换了任务 → 之前排好的那一发作废
        this.pendingFireTick = 0;
        this.salvoEndTick = 0;
        this.hasTarget = true;
        this.aiming = true;
        this.recalcCooldown = 0;   // 下一 tick 立刻解算

        // ⚠️⚠️ 增量解算的"种子"必须一起作废（2026-09-25 用户实测的"炮瞄到别处去"）。
        //
        // 病因：每 tick 增量重解走的是**牛顿法**，初值取"上一个目标的那条解"。
        // 换成一个**远处的新目标**后，从旧解出发很容易收敛到**另一支解**（高抛而不是低伸）、
        // 甚至收敛到一个压根不是解的点 ⇒ 炮就朝别处瞄了。而"多换几个目标就好了" ——
        // 因为每次换目标后完整解算会接手，种子被刷对了。
        // ⇒ 把种子清掉 ⇒ 下一次一定走完整解算（它才会重新判定弧线、射界、遮挡）。
        this.lastSolution = null;
        this.aimDriftYaw = 0.0F;
        this.aimDriftPitch = 0.0F;
        setChanged();
        sendData();
    }

    /**
     * 只换弹道模式并<b>逼它重解</b>。
     *
     * <p>为什么必须重新解算而不是等下次：节点转到位就撒手（{@code aiming = false}），
     * tick 里的解算条件是 {@code aiming && hasTarget && …} —— <b>此后再也不会重算</b>。
     * 只改字段的话，玩家切了模式、界面显示新模式，炮却仍按旧弹道瞄着
     * （和「换装药后不重解」是同一个坑，2026-09-20 已踩过一次）。
     *
     * <p>没有目标时只记下来，等下次下发军令自然生效。
     */
    public void setArcMode(ArcMode mode) {
        if (this.arcMode == mode) {
            return;
        }
        this.arcMode = mode;
        if (hasTarget) {
            this.aiming = true;
            this.recalcCooldown = 0;
            // ⚠️ 同 aimAt：弧线可能整个换掉（低伸↔高抛），留着旧种子会让牛顿收敛回旧弧线
            this.lastSolution = null;
        }
        setChanged();
        sendData();
    }

    /**
     * 排队开火 —— <b>只有已就位的炮才接受</b>。
     *
     * <p>⚠️ 这道守卫是多炮齐射的<b>安全网</b>。计算机点「开火！」的顺序是
     * 「先给各节点排队，紧接着 {@link #clearTarget()}」，而 {@code clearTarget()} 会把
     * {@code aiming} 置 false —— 那正是 {@code tick} 里开火条件 {@code fireQueued && !aiming}
     * 的另一半。单炮时玩家总等「已就位 1/1」才按，所以看不出问题；多炮时各炮到位时刻不同，
     * 少了这道守卫，一门<b>还在转</b>的炮会立刻朝<b>当前</b>炮口方向打出去。
     *
     * @return 有没有接受（未就位返回 false，调用方据此统计齐射了几门）
     */
    public boolean fire() {
        if (!isAimed()) {
            return false;
        }
        this.fireQueued = true;
        setChanged();
        return true;
    }

    /**
     * 排定在某个游戏刻开火 —— <b>同时弹着（TOT）的延时击发</b>。
     *
     * <p>只有<b>已经就位</b>的炮才接受排期（道理同 {@link #fire()}：还在转的炮排了也打不准）。
     * 排完之后目标<b>必须留着</b>，否则节点会撒手、炮位在等待中途漂走。
     *
     * @param gameTick 目标游戏刻（{@code level.getGameTime()} 的口径）
     * @return 有没有接受
     */
    public boolean scheduleFire(long gameTick, long salvoEnd, FireMode mode) {
        if (!isAimed()) {
            return false;
        }
        this.pendingFireTick = Math.max(gameTick, 1L);
        this.salvoEndTick = salvoEnd;
        this.fireMode = mode;
        setChanged();
        sendData();
        return true;
    }

    /** 排定的开火时刻（{@code 0} = 没排）。界面上要拿它显示倒计时。 */
    public long getPendingFireTick() {
        return pendingFireTick;
    }

    /** 本轮射击的落地时刻（{@code 0} = 没在打）。 */
    public long getSalvoEndTick() {
        return salvoEndTick;
    }

    public FireMode getFireMode() {
        return fireMode;
    }

    /**
     * 这一门<b>已经打完、但整轮还没结束</b>。
     *
     * <p>界面据此显示"等待本轮射击结束"，而不是立刻跳成"未装填" ——
     * 那会让玩家以为整轮打完了（2026-09-22 用户要求）。
     */
    public boolean awaitingSalvoEnd(long gameTime) {
        return salvoEndTick != 0 && pendingFireTick == 0 && gameTime <= salvoEndTick;
    }

    /**
     * 这一门<b>正在参加本轮射击</b>（还没轮到它开火，或者已经打完在等整轮结束）。
     *
     * <p>⚠️ 为什么要它：打完的炮**膛里是空的**，于是 {@code solvable = false}，
     * 从而被"收到目标但解不出诸元"那条判据收进去、在计算机界面上被点名成「打不到的炮」——
     * 可它明明正在打（2026-09-22 用户实测：同时弹着倒计时还剩 36.9 秒，已经开火的两门却被列成打不着）。
     * **"打不着"和"正在打、还轮不到/已经打完"是两回事，统计时必须分开。**
     */
    public boolean inSalvo(long gameTime) {
        return pendingFireTick != 0 || awaitingSalvoEnd(gameTime);
    }

    /** 这套诸元的飞行时间（tick）。同时弹着靠它反推各炮的击发时刻。 */
    public double getSolutionAirTime() {
        return solutionAirTime;
    }

    /**
     * 最近一发<b>没打出去</b>（CBC 判定卡弹 / 炸膛 —— 整发被消耗掉了，但世界上一颗弹丸都没有）。
     *
     * <p>用来在计算机界面与护目镜上如实说出来。装填好了就自动收掉这条警告
     * （见 {@code probeAndCache}）—— 那件事已经过去了。
     */
    public boolean isLastFireFailed() {
        return lastFireFailed;
    }

    /** 停火并交还炮座控制权 —— 玩家主动取消时用，<b>会取消已排队的开火</b>。 */
    public void ceaseFire() {
        this.aiming = false;
        this.fireQueued = false;
        this.pendingFireTick = 0;
        this.salvoEndTick = 0;
        this.hasTarget = false;
        setChanged();
        sendData();
    }

    /**
     * 清除目标：停手、交还炮座，但<b>保留已排队的开火</b>。
     *
     * <p>计算机点完「开火！」会立刻清目标（让界面回到"待输入打击坐标"），这里若把
     * {@code fireQueued} 一起清掉，那一炮就打不出去了。而 {@code tick} 里的开火条件是
     * {@code fireQueued && !aiming} —— 本方法把 {@code aiming} 置 false，反而让它在当 tick 就开火。
     */
    public void clearTarget() {
        this.aiming = false;
        this.hasTarget = false;
        // 目标没了，排好的那一发也一并作废 —— 不然炮会在没有目标的情况下照点开火
        this.pendingFireTick = 0;
        this.salvoEndTick = 0;
        setChanged();
        sendData();
    }

    public boolean isAimed() {
        return hasTarget && !aiming && solvable;
    }

    public boolean hasTarget() {
        return hasTarget;
    }

    public boolean isSolvable() {
        return solvable;
    }

    public boolean isAiming() {
        return aiming;
    }

    // 下面这组是给协调器界面读的（那边遍历登记进来的节点逐个显示）

    public int getProbedCharges() {
        return probedCharges;
    }

    /** 火炮等级 —— 用 CBC 的安全装药上限表示。 */
    public int getProbedMaxCharges() {
        return probedMaxCharges;
    }

    public float getProbedVelocity() {
        return probedVelocity;
    }

    public float getProbedMinPitch() {
        return probedMinPitch;
    }

    public float getProbedMaxPitch() {
        return probedMaxPitch;
    }

    public float getTargetPitch() {
        return targetPitch;
    }

    /** 炮腔格数。 */
    public int getProbedChambers() {
        return probedChambers;
    }

    /** 沿炮身扫到的总格数（<b>含炮腔</b>，不含炮闩）。 */
    public int getProbedBarrelBlocks() {
        return probedBarrelBlocks;
    }

    /**
     * <b>炮筒</b>格数 —— 只数炮管，不含炮腔与炮闩。
     *
     * <p>⚠️ 探针的 {@code barrelBlocks} 是「沿炮身扫到的所有格」，<b>含炮腔</b>。
     * 卡弹判定要的是「炮弹之后还得走多少格」，所以必须减掉炮腔 ——
     * 用整段炮身长度去判会<b>误报卡弹</b>（8 包药的炮身 21 格里有 9 格是装药的炮腔，
     * 实际只走 12 格炮筒，却按 21 格算就会超阈值）。
     */
    public int barrelOnly() {
        return Math.max(0, probedBarrelBlocks - probedChambers);
    }

    /** 已装填的总装药强度。卡弹阈值与界面显示用。 */
    public float getProbedChargePower() {
        return probedChargePower;
    }

    public int getProbedBreechStrength() {
        return probedBreechStrength;
    }

    /** 炮闩方块的语言键（客户端自己翻译）。 */
    public String getProbedBreechName() {
        return probedBreechName;
    }

    /** 材质的装药上限，不含炮闩。 */
    public int getProbedMaterialMaxStress() {
        return probedMaterialMaxStress;
    }

    /** 已装填弹丸方块的语言键；空串表示没装弹。 */
    public String getProbedShellItemId() {
        return probedShellItemId;
    }

    /** 水平极限射程（格）。0 = 没装填或算不出来。 */
    public float getProbedMaxRange() {
        return probedMaxRange;
    }

    /** 最近安全距离（格）。见 {@link #safeRange}。 */
    public float getProbedMinSafeRange() {
        return probedMinSafeRange;
    }

    public float getTargetYaw() {
        return targetYaw;
    }

    // ------------------------------------------------------------------ 持久化与同步

    // 注意：不能覆盖 saveAdditional/loadAdditional —— SmartBlockEntity 把它们标了 final。
    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);

        tag.putBoolean("HasMount", mountPos != null);
        if (mountPos != null) {
            tag.putInt("MountX", mountPos.getX());
            tag.putInt("MountY", mountPos.getY());
            tag.putInt("MountZ", mountPos.getZ());
        }

        tag.putInt("ProbedCharges", probedCharges);
        tag.putInt("ProbedMaxCharges", probedMaxCharges);
        tag.putInt("ProbedBarrelBlocks", probedBarrelBlocks);
        tag.putString("ProbedMaterial", probedMaterial);
        tag.putInt("ProbedChambers", probedChambers);
        tag.putFloat("ProbedSpread", probedSpread);
        tag.putFloat("ProbedSpreadInLine", probedSpreadInLine);
        tag.putInt("ProbedSpreadFree", probedSpreadFree);
        tag.putFloat("ProbedVelocity", probedVelocity);
        tag.putFloat("ProbedMinPitch", probedMinPitch);
        tag.putFloat("ProbedMaxPitch", probedMaxPitch);

        tag.putInt("ProbedMaterialMaxStress", probedMaterialMaxStress);
        tag.putInt("ProbedBreechStrength", probedBreechStrength);
        tag.putString("ProbedBreechName", probedBreechName);
        tag.putFloat("ProbedMinVelPerBarrel", probedMinVelPerBarrel);
        tag.putFloat("ProbedChargePower", probedChargePower);
        tag.putString("ProbedShellItemId", probedShellItemId);
        tag.putString("ProbedPropellantId", probedPropellantId);
        tag.putInt("ProbedPropellantPower", probedPropellantPower);
        tag.putInt("ProbedPropellantMaxPower", probedPropellantMaxPower);
        tag.putFloat("ProbedChargeLengthMeters", probedChargeLengthMeters);
        tag.putBoolean("ProbedSquib", probedSquib);
        tag.putFloat("ProbedBarrelMeters", probedBarrelMeters);
        tag.putFloat("ProbedExplosionPower", probedExplosionPower);
        tag.putFloat("ProbedMaxRange", probedMaxRange);
        tag.putFloat("ProbedMinSafeRange", probedMinSafeRange);

        // 上线关系（协调器写回）—— 护目镜靠它显示「已接入火控网络」
        tag.putBoolean("HasMaster", masterPos != null);
        if (masterPos != null) {
            tag.putInt("MasterX", masterPos.getX());
            tag.putInt("MasterY", masterPos.getY());
            tag.putInt("MasterZ", masterPos.getZ());
        }

        tag.putBoolean("HasTarget", hasTarget);
        tag.putDouble("TargetX", targetX);
        tag.putDouble("TargetY", targetY);
        tag.putDouble("TargetZ", targetZ);
        tag.putDouble("AimHeight", aimHeight);
        tag.putFloat("TargetPitch", targetPitch);
        tag.putFloat("TargetYaw", targetYaw);
        tag.putBoolean("Aiming", aiming);
        tag.putBoolean("Solvable", solvable);

        tag.putString("ArcMode", arcMode.name());
        tag.putByte("ArcChoice", arcChoice);
        tag.putString("ArcNoteKey", arcNoteKey);
        tag.putDouble("ArcNoteA", arcNoteA);
        tag.putDouble("ArcNoteB", arcNoteB);

        tag.putDouble("AirTime", solutionAirTime);
        // ⭐ 实弹标定学到的"射程比例误差"要存档 —— 那是这台炮自己攒出来的精度，重启不能丢
        calibration.save(tag);
        // 排定的开火时刻要一起存 —— 存档往返时倒计时不能凭空消失
        tag.putLong("PendingFireTick", pendingFireTick);
        tag.putLong("SalvoEndTick", salvoEndTick);
        tag.putString("FireMode", fireMode.name());

        if (clientPacket) {
            // ⭐「实时计算」那行要在客户端显示（计算机界面 / 护目镜 / 指示器 HUD 都从方块上取词），
            //    而"载具在不在动"只有服务端知道 ⇒ 这个旗子必须同步过去，否则客户端会把
            //    实时跟踪误报成「计算完毕」（2026-09-25 才发现：这一段字段少一个就会静默说谎）。
            // ⚠️ 刻意**不存档**：它是瞬时状态，读回存档时由第一 tick 重新判定。
            tag.putBoolean("PlatformMoving", platformMoving);
            // ⭐ P3：同理瞬时 —— 协调器界面的状态列与节点护目镜都要它
            tag.putBoolean("RpmShort", rpmShort);
            tag.putInt("RpmNeeded", rpmNeeded);
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);

        mountPos = tag.getBoolean("HasMount")
                ? new BlockPos(tag.getInt("MountX"), tag.getInt("MountY"), tag.getInt("MountZ"))
                : null;

        probedCharges = tag.getInt("ProbedCharges");
        probedMaxCharges = tag.getInt("ProbedMaxCharges");
        probedBarrelBlocks = tag.getInt("ProbedBarrelBlocks");
        probedMaterial = tag.getString("ProbedMaterial");
        probedChambers = tag.getInt("ProbedChambers");
        probedSpread = tag.getFloat("ProbedSpread");
        probedSpreadInLine = tag.getFloat("ProbedSpreadInLine");
        probedSpreadFree = tag.getInt("ProbedSpreadFree");
        probedVelocity = tag.getFloat("ProbedVelocity");
        probedMinPitch = tag.getFloat("ProbedMinPitch");
        probedMaxPitch = tag.getFloat("ProbedMaxPitch");

        probedMaterialMaxStress = tag.getInt("ProbedMaterialMaxStress");
        probedBreechStrength = tag.getInt("ProbedBreechStrength");
        probedBreechName = tag.getString("ProbedBreechName");
        probedMinVelPerBarrel = tag.getFloat("ProbedMinVelPerBarrel");
        probedChargePower = tag.getFloat("ProbedChargePower");
        probedShellItemId = tag.getString("ProbedShellItemId");
        probedPropellantId = tag.getString("ProbedPropellantId");
        probedPropellantPower = tag.getInt("ProbedPropellantPower");
        probedPropellantMaxPower = tag.getInt("ProbedPropellantMaxPower");
        probedChargeLengthMeters = tag.getFloat("ProbedChargeLengthMeters");
        probedSquib = tag.getBoolean("ProbedSquib");
        probedBarrelMeters = tag.getFloat("ProbedBarrelMeters");
        probedExplosionPower = tag.getFloat("ProbedExplosionPower");
        probedMaxRange = tag.getFloat("ProbedMaxRange");
        probedMinSafeRange = tag.getFloat("ProbedMinSafeRange");

        masterPos = tag.getBoolean("HasMaster")
                ? new BlockPos(tag.getInt("MasterX"), tag.getInt("MasterY"), tag.getInt("MasterZ"))
                : null;

        hasTarget = tag.getBoolean("HasTarget");
        targetX = tag.getDouble("TargetX");
        targetY = tag.getDouble("TargetY");
        targetZ = tag.getDouble("TargetZ");
        aimHeight = tag.getDouble("AimHeight");
        targetPitch = tag.getFloat("TargetPitch");
        targetYaw = tag.getFloat("TargetYaw");
        aiming = tag.getBoolean("Aiming");
        solvable = tag.getBoolean("Solvable");

        arcMode = ArcMode.byName(tag.getString("ArcMode"));
        arcChoice = tag.getByte("ArcChoice");
        arcNoteKey = tag.getString("ArcNoteKey");
        arcNoteA = tag.getDouble("ArcNoteA");
        arcNoteB = tag.getDouble("ArcNoteB");

        solutionAirTime = tag.getDouble("AirTime");
        calibration.load(tag);
        pendingFireTick = tag.getLong("PendingFireTick");
        salvoEndTick = tag.getLong("SalvoEndTick");
        fireMode = FireMode.byName(tag.getString("FireMode"));
        // 瞬时状态，只有客户端包里有（存档里没有 ⇒ 读回时是 false，第一 tick 会重新判定）
        platformMoving = tag.getBoolean("PlatformMoving");
        rpmShort = tag.getBoolean("RpmShort");
        rpmNeeded = tag.getInt("RpmNeeded");
    }

    // ------------------------------------------------------------------ 生命周期

    public void onRemoved() {
        mountPos = null;
        masterPos = null;
        aiming = false;
        fireQueued = false;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level == null || level.isClientSide) {
            return;
        }
        refreshCannon();
        // ⚠️ 有目标就<b>重新解算一次</b>，不要相信从 NBT 还原出来的「已就位」。
        //
        // 节点的 `isAimed()` 是 `hasTarget && !aiming && solvable` —— 这三个布尔都会被持久化。
        // 于是加载存档时它们可能一起为 true，节点立刻对外宣称「已就位」，却<b>没有任何东西
        // 验证过那个角度</b>：它是上一个会话、甚至<b>上一版代码</b>算出来的。开火按键会照常接受。
        //
        // 最典型的就是换 jar 重进存档：世界存档里存着旧代码算的 targetPitch，
        // 而炮座自己也被存了旧角度 —— 两者一致地错，界面上看不出任何异常。
        //
        // 把它置成 `aiming = true` 让节点重新接管：下一 tick 重新探炮、重新解算、重新伺服。
        // 角度本来就对的话误差是 0，当 tick 就再次放手，不会有可见动作；不对的话它自己会纠回来。
        if (hasTarget) {
            this.aiming = true;
            this.recalcCooldown = 0;
        }
    }

    // ------------------------------------------------------------------ 护目镜

    /**
     * 炮身材质的显示名（青铜 / 铸铁 / 钢制 / 下界合金…）。
     *
     * <p>用语言键查翻译；<b>查不到就退回原始 id</b>（比如附属模组加了新材质），
     * 而不是把 {@code gui.create_ballistics.material.xxx} 这种键名糊在玩家脸上。
     */
    private String materialText() {
        if (probedMaterial.isEmpty()) {
            return Component.translatable("gui.create_ballistics.material.unknown").getString();
        }
        String key = "gui.create_ballistics.material." + probedMaterial;
        String translated = Component.translatable(key).getString();
        return translated.equals(key) ? probedMaterial : translated;
    }

    /** 炮身材质名（原始 id path），供协调器界面显示。 */
    public String getProbedMaterial() {
        return probedMaterial;
    }

    /**
     * 装药上限的<b>实际</b>值 —— 取「材质允许」与「炮腔格数」的较小者。
     *
     * <p>CBC 报的是材质允许值（下界钢 10 包），但玩家有几个炮腔就只能塞几包 ——
     * 2026-09-17 玩家摆了 6 格炮腔、界面显示「6/10」而困惑。这里显示他真正装得下的数。
     */
    public int chargeCapacity() {
        return probedChambers > 0 ? Math.min(probedMaxCharges, probedChambers) : probedMaxCharges;
    }

    /** 炮闩的显示名。查不到翻译就退回原始 id（附属模组加了新炮闩时）。 */
    private String breechText() {
        if (probedBreechName.isEmpty()) {
            return Component.translatable("gui.create_ballistics.material.unknown").getString();
        }
        String translated = Component.translatable(probedBreechName).getString();
        return translated.equals(probedBreechName) ? probedBreechName : translated;
    }

    /**
     * 已装填发射药的显示名。定装弹（大型弹药）还要跟上<b>火药填充档位</b>。
     *
     * <p>格式<b>刻意与物品自己的 tooltip 一致</b>：名字 + 「火药填充 x / 上限」，
     * 连标签都直接复用 CBC 的语言键（{@code block.createbigcannons.propellant.tooltip.power}），
     * 这样"物品上怎么写的、护目镜上就怎么显示"，玩家不用在两种说法之间换算。
     *
     * <p>这个档位很要紧：CBC 的「大型弹药」按档位就是 1/2/3/4 米的装药长，而初速那套 Robins
     * 公式要 {@code ln(管长/装药长)} —— 短管炮塞高档位会让它趋近 0、初速被算成 0
     * （2026-09-19 实际发生过：极限射程显示 2 格）。
     *
     * <p>没装填时返回空组件（不占位置）。
     */
    private Component propellantText() {
        if (probedPropellantId.isEmpty()) {
            return Component.empty();
        }
        String name = Texts.translatedOrShort(probedPropellantId);
        if (probedPropellantMaxPower > 0) {
            return Component.translatable(
                    "block.create_ballistics.fire_control_node.propellant_power",
                    name,
                    Component.translatable("block.createbigcannons.propellant.tooltip.power"),
                    probedPropellantPower, probedPropellantMaxPower);
        }
        return Component.translatable(
                "block.create_ballistics.fire_control_node.propellant", name);
    }

    /**
     * 弹种的显示名。
     *
     * <p>只存了方块物品的 {@code descriptionId}，这里才翻译 —— 附属模组的新弹种自带翻译；
     * 万一没有，退回键名的最后一段（{@code block.createbigcannons.ap_shell} → {@code ap_shell}），
     * 而不是把一长串语言键糊在玩家脸上。
     */
    private Component shellText() {
        if (probedShellItemId.isEmpty()) {
            return Component.translatable("block.create_ballistics.fire_control_node.shell_none");
        }
        String translated = Component.translatable(probedShellItemId).getString();
        if (!translated.equals(probedShellItemId)) {
            return Component.literal(translated);
        }
        int dot = probedShellItemId.lastIndexOf('.');
        return Component.literal(dot >= 0 ? probedShellItemId.substring(dot + 1) : probedShellItemId);
    }

    /**
     * 组建风险提醒 —— <b>只在真有问题时出现</b>。
     *
     * <p>正常组装的炮一行都不会多；一旦快炸膛或快卡弹，这里会告诉玩家<b>是哪一环卡住的</b>。
     * 新手最常在这里栽跟头（钢制是唯一"材质 8 但炮闩有 4/8 两档"的材质，
     * 拿了楔式炮闩就只能装 4 包 —— 2026-09-18 玩家本人就踩过）。
     */
    private void appendBuildWarnings(List<Component> tooltip) {
        // ---- 1) 炸膛：装药超过 min(材质上限, 炮闩强度), CBC 掷骰触发 ----
        if (probedCharges > chargeCapacity()) {
            tooltip.add(Component.translatable("block.create_ballistics.fire_control_node.overloaded",
                    chargeCapacity()));

            // 指明是哪一环卡住的上限。炮腔格数也要算进来 —— 三个上限取最小。
            Component reason;
            int tightest = Math.min(probedBreechStrength, probedMaterialMaxStress);
            if (probedChambers > 0 && probedChambers < tightest) {
                reason = Component.translatable(
                        "block.create_ballistics.fire_control_node.overloaded.chambers", probedChambers);
            } else if (probedBreechStrength < probedMaterialMaxStress) {
                reason = Component.translatable(
                        "block.create_ballistics.fire_control_node.overloaded.breech",
                        probedBreechStrength, probedMaterialMaxStress);
            } else {
                reason = Component.translatable(
                        "block.create_ballistics.fire_control_node.overloaded.material", probedMaterialMaxStress);
            }
            tooltip.add(Component.literal("    ").append(reason));
            tooltip.add(Component.translatable("block.create_ballistics.fire_control_node.material_table"));
        }

        // ---- 2) 卡弹（CBC 原版规则：炮筒格数 > 装药强度 ÷ 系数）----
        //
        // ⚠️ **只在没装 going_ballistic 时才提醒**。装了 GB 时卡弹判定由它接管、且宽松得多
        // （2026-09-18 实测：同一门炮 GB 下打十几发没事，禁用 GB 后立刻连卡两次）——
        // 那种情况下这条警告是纯粹的错误信息，不如不说。
        if (!ActiveBallistics.goingBallisticLoaded()
                && probedMinVelPerBarrel > 0.0F && probedChargePower > 0.0F) {
            int maxBarrel = (int) Math.floor(probedChargePower / probedMinVelPerBarrel);
            if (barrelOnly() > maxBarrel) {
                tooltip.add(Component.translatable("block.create_ballistics.fire_control_node.jam",
                        barrelOnly(),
                        String.format(Locale.ROOT, "%.0f", probedChargePower),
                        String.format(Locale.ROOT, "%.2f", probedMinVelPerBarrel),
                        maxBarrel));
            }
        }

        // ---- 3) 装药长逼近管长 → Robins 没有膨胀行程，初速趋近 0 ----
        //
        // 这是「大型弹药」（CBC 定装弹）最容易踩的坑：它按填充档位就是 1/2/3/4 米的装药长，
        // 而初速公式要 ln(管长 / 装药长) —— 短管炮塞高档位会让 ln 趋近 0，炮等于打不出去。
        // 玩家在护目镜上只会看到"极限射程 2 格"，完全摸不着头脑（2026-09-19 实际发生过），
        // 所以必须把「装药长 / 管长」这对数摆出来并告诉他加长炮筒。
        // 触发条件分两档：
        //   ① 装药长 / 管长都拿得到 → 按比例判，还能给出"再加几格"的具体建议；
        //   ② 数拿不到，但**炮已装填、极限射程却短得离谱** → 也得说一声。
        //      否则玩家只会看到「极限射程 2 格」这种完全摸不着头脑的数（2026-09-19 实际发生过）。
        boolean ratioKnown = probedChargeLengthMeters > 0.0F && probedBarrelMeters > 0.0F;
        boolean tooMuchCharge = ratioKnown
                && probedChargeLengthMeters >= probedBarrelMeters * CHARGE_LENGTH_WARN_RATIO;
        boolean absurdlyShort = !ratioKnown
                && probedMaxRange > 0.0F && probedMaxRange < ABSURD_RANGE;

        if (tooMuchCharge || absurdlyShort) {
            // ⚠️ 文案**不能绑定某种装药**。它一度写成「火药填充 4/4 对这根炮管来说太多」——
            // 那是为「大型弹药」量身定的，可定装弹的装药长修正之后那个组合不再触发；
            // 而**原版弹道（没装 GB 时）照样会卡弹**，只是走另一条分支。
            // 所以这里只说「装药」，任何发射药、任何弹道分支下读起来都通顺。
            tooltip.add(Component.translatable(
                    "block.create_ballistics.fire_control_node.barrel_too_short"));

            if (ratioKnown) {
                // 建议格数：目标是把装药长压到管长的 2/3 左右（ln ≈ 0.4，初速回到可用区间）
                int needMore = (int) Math.ceil(probedChargeLengthMeters * 1.5 - probedBarrelMeters);
                tooltip.add(Component.translatable(
                        "block.create_ballistics.fire_control_node.barrel_too_short.hint",
                        Math.max(1, needMore)));
            }
        }

        // ---- 4) GB 判定会卡弹：按了开火也打不出去 ----
        //
        // 卡弹时炮弹**根本出不了膛**，而且火药会先被烧掉、炮弹留在膛里 ——
        // 玩家看到的是"按了开火什么都没发生"，完全无从判断（2026-09-20 实测：
        // 63 格炮管 + 大型弹药 1/4~3/4，四档里只有 4/4 推得动）。所以必须直接说破。
        if (probedSquib) {
            tooltip.add(Component.translatable("block.create_ballistics.fire_control_node.squib",
                    barrelOnly()));
            tooltip.add(Component.translatable("block.create_ballistics.fire_control_node.squib.hint"));
        }

        // ---- 5) 转速不够 ----
        //
        // 两种，意思完全不同，**必须分开说**（2026-09-26 P3）：
        //  ① 转速**没到门槛** ⇒ Create 判定不干活（本来就是它管的），这里只是照实复述；
        //  ② 转速**够干活、但跟不上目标** ⇒ 炮塔一直在追、`isAimed()` 永远不成立 ⇒
        //     这门炮**不参与本轮射击**，而玩家在界面上只会看到「正在计算 6 / 10」。
        //     ⚠️ 只说"转不动"会把人引去查应力；真正要做的是**给炮座更多转速**。
        if (!hasRotationPower()) {
            tooltip.add(Component.translatable("block.create_ballistics.fire_control_node.rpm_low",
                    rpmText(Math.abs(getSpeed()))));
        }
        if (rpmShort) {
            int max = maxRotationSpeed();
            // ⚠️ 需求超过 Create 的上限时**换一句说**：报一个"3000 RPM"毫无意义
            //    （根本到不了），必须明说"加转速也解决不了"。
            tooltip.add(rpmNeeded > max
                    ? Component.translatable("block.create_ballistics.fire_control_node.rpm_short_over_max",
                            rpmText(max), rpmText(Math.abs(getSpeed())))
                    : Component.translatable("block.create_ballistics.fire_control_node.rpm_short",
                            rpmText(rpmNeeded), rpmText(Math.abs(getSpeed()))));
        }
    }

    /** 转速的显示格式 —— 整数 RPM（玩家在 Create 那边看到的就是 RPM）。 */
    private static String rpmText(float rpm) {
        return String.format(Locale.ROOT, "%.0f", rpm);
    }

    /**
     * Create 的转速上限（RPM）—— 取自它的服务端配置（默认 256），**不写死**。
     *
     * <p>用途：给 {@link #isRpmShort()} 那个提示**封顶**。玩家的传动最多只能到 256 RPM
     * （服务端配置可改），报"需要 3000 RPM"等于没报。
     * ⚠️ 读配置失败时退回原版默认值 —— 提示文案绝不能因为一次读配置而崩（本项目的通则）。
     */
    private static int maxRotationSpeed() {
        try {
            return com.simibubi.create.infrastructure.config.AllConfigs.server()
                    .kinetics.maxRotationSpeed.get();
        } catch (Throwable t) {
            return 256;
        }
    }

    /**
     * 弧线说明里的两个数。
     *
     * <p>护目镜跑在<b>客户端</b>，服务端只把数值和语言键同步过来，翻译与格式化都在这一端做
     * （服务端直接翻译只会拿到英文）。多给的参数会被翻译忽略，所以统一给两个。
     */
    private Object[] arcNoteArgs() {
        return new Object[]{
                String.format(Locale.ROOT, "%.0f", arcNoteA),
                String.format(Locale.ROOT, "%.0f", arcNoteB)};
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(Component.literal("    ").append(Component.translatable("block.create_ballistics.fire_control_node")));

        CannonMountBlockEntity mount = cannon();
        if (mount == null) {
            tooltip.add(Component.translatable("block.create_ballistics.fire_control_node.no_cannon"));
            return super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        }
        if (!mount.isRunning() || mount.getContraption() == null) {
            tooltip.add(Component.translatable("block.create_ballistics.fire_control_node.not_assembled"));
            return super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        }

        tooltip.add(Component.literal("  ").append(Component.translatable(ActiveBallistics.activeModelKey())));

        // ---- 有没有接入火控网络 ----
        // 这个关系是协调器扫描时**写回**的，不是"附近恰好有个协调器"的猜测 ——
        // 协调器拆了 / 节点搬远了 / 转速掉了，这里都会如实变成"未接入"。
        if (masterPos != null) {
            // ⚠️ 坐标显示走 PosDisplay：在航空学物理结构上时 masterPos 是**图幅坐标**
            //    （2026-09-25 飞艇实测：护目镜把它显示成 [20481017, 113, 20481031] —— 2896 万格）。
            //    ⚠️ 只转**显示**：masterPos 字段本身保持原坐标，它还要拿去查方块实体。
            BlockPos shownMaster = PosDisplay.of(level, masterPos);
            tooltip.add(Component.literal("  ")
                    .append(Component.translatable("block.create_ballistics.fire_control_node.online"))
                    .append(Component.literal(String.format(Locale.ROOT, " [%d, %d, %d]",
                            shownMaster.getX(), shownMaster.getY(), shownMaster.getZ()))));
        } else {
            tooltip.add(Component.literal("  ")
                    .append(Component.translatable("block.create_ballistics.fire_control_node.offline")));
        }

        // ---- 这门炮是怎么搭的：炮腔 / 炮筒 / 炮闩 ----
        tooltip.add(Component.literal("  ").append(Component.translatable(
                "block.create_ballistics.fire_control_node.build",
                materialText(), probedChambers, barrelOnly(), breechText(), probedBreechStrength)));

        // ---- 装填 / 射界 / 弹种 ----
        tooltip.add(Component.literal("  ").append(Component.translatable(
                "block.create_ballistics.fire_control_node.load",
                probedCharges, chargeCapacity(), propellantText(),
                String.format(Locale.ROOT, "%.1f", probedVelocity),
                // ⚠️ 角度用 Texts.deg —— 一律 %.0f 的话 89.9° 会被显示成「90°」，
                // 而 90° 看着像"炮口垂直朝上、开炮打自己"（2026-09-21 用户指出）
                Texts.deg(probedMinPitch),
                Texts.deg(probedMaxPitch))));
        tooltip.add(Component.literal("  ")
                .append(Component.translatable("block.create_ballistics.fire_control_node.shell"))
                .append(Component.literal("  "))
                .append(shellText()));

        // ---- 能打多远、最少打多近 ----
        if (probedMaxRange > 0.0F) {
            tooltip.add(Component.literal("  ").append(Component.translatable(
                    "block.create_ballistics.fire_control_node.range",
                    String.format(Locale.ROOT, "%.0f", probedMaxRange),
                    String.format(Locale.ROOT, "%.0f", probedMinSafeRange))));
        }

        appendBuildWarnings(tooltip);

        if (!hasTarget) {
            return super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        }
        // 显示的是**实际瞄准高度**（可能已被吸附到地面），让玩家看得见模组给他做了什么
        boolean snapped = Math.abs(aimHeight - targetY) > 0.01;
        Component aimLine = Component.translatable("block.create_ballistics.fire_control_node.aim")
                .append(String.format(Locale.ROOT, " [%.0f, %.0f, %.0f]", targetX, aimHeight, targetZ));
        if (snapped) {
            aimLine = aimLine.copy().append(
                    Component.translatable("block.create_ballistics.fire_control_node.aim_snapped"));
        }
        tooltip.add(aimLine);
        // ⭐ 2026-09-27 用户拍板（原话：「好炮也显示预计落点散布，散布偏大时不仅显示数值，
        //    还要提醒玩家炮管太短、散布会大、要增加炮管才能打得更精准」）：
        //  ① 去掉原来的 `> 0.5 格` 门槛 —— 长管好炮也要看得见那个数，否则玩家以为"没这项功能"；
        //  ② 炮管不够长时补一句**因果**（CBC 的模型决定：散布 = max(装药×2 − 炮管格数×每格减散,
        //     材质下限)，`probedSpreadFree` 就是"压到材质下限所需的炮管格数"，由探针算好）。
        // ⚠️ **两位分量都要报**（2026-09-27 用户拍板）：
        //    横向 = 方位被扰动造成的横移（与弹道形状无关）；纵向 = 射程被扰动（**高抛放大十几倍**）。
        //    只报横向时，81° 吊射会报 ±7 格而实测散到 ±50 格 —— 那个数会骗人。
        // ⚠️ **一位小数**，不是整数：炮管够长时 CBC 的散布会落到材质下限
        //    （钢制 minimum_spread = 0.025 ⇒ 130 格上只有约 0.06 格），
        //    打成整数就是「±0 格」，玩家读起来像"这功能坏了"（2026-09-27 用户实测）。
        tooltip.add(Component.literal(String.format(Locale.ROOT,
                "  预计落点散布 ±%.1f 格（横向）· ±%.1f 格（纵向）",
                probedSpread, probedSpreadInLine)));
        if (probedSpreadFree > 0 && probedBarrelBlocks < probedSpreadFree) {
            tooltip.add(Component.translatable(
                            "block.create_ballistics.fire_control_node.spread_barrel_short",
                            probedBarrelBlocks, probedSpreadFree)
                    .withStyle(ChatFormatting.RED));
        }
        // ---- 当前弹道模式 ----
        // ⚠️ **不要再跟一句「· 用低伸」**。原来写的是「弹道模式：自动 · 用低伸」，
        // 模式本身已经说明了它用哪条，后缀纯属重复啰嗦（2026-09-21 用户明确要求去掉）。
        // 自动模式真的换了弹道时，下面那行说明会讲清楚（「已自动改用高抛」）。
        tooltip.add(Component.literal("  ").append(
                Component.translatable(ARC_MODE_LABEL, Component.translatable(arcMode.langKey()))));

        // ---- 这发要飞多久（**解算完就一直显示**）----
        // ⚠️ 飞行时间必须在「计算完毕」之后就一直挂着，**不能只在倒计时期间才显示** ——
        // 倒计时那个数每 tick 都在变，玩家想截图报数据根本截不到（2026-09-22 用户提出）。
        // 飞行时间本身是定值（装填/目标不变它就不变），截图随时可以。
        // 同时弹着的等待时长作为**后缀**跟在后面，那部分才是会变的。
        if (level != null) {
            final long now = level.getGameTime();
            if (pendingFireTick != 0) {
                // 排期已定、还没轮到我 —— 飞行时间是定值（随时可截图），等待时长才是变的
                Component line = Component.translatable(
                        "block.create_ballistics.fire_control_node.flight_time",
                        String.format(Locale.ROOT, "%.1f", solutionAirTime / 20.0));
                double left = Math.max(0.0, (pendingFireTick - now) / 20.0);
                line = line.copy().append(Component.translatable(
                        "block.create_ballistics.fire_control_node.tot_waiting",
                        String.format(Locale.ROOT, "%.1f", left)));
                tooltip.add(Component.literal("  ").append(line));
            } else if (awaitingSalvoEnd(now)) {
                // ⚠️ 这一门**已经打完、整轮还没结束**。必须单独说一句 ——
                // 不打这句的话它会立刻掉进"未装填"，玩家以为整轮打完了（2026-09-22 用户要求）。
                tooltip.add(Component.literal("  ").append(Component.translatable(
                        fireMode == FireMode.ROLLING
                                ? "block.create_ballistics.fire_control_node.salvo_wait_rolling"
                                : "block.create_ballistics.fire_control_node.salvo_wait_tot",
                        String.format(Locale.ROOT, "%.1f", (salvoEndTick - now) / 20.0))));
            } else if (solvable) {
                // 不在任何排期里，但解出来了 —— 至少把飞行时间给出来
                tooltip.add(Component.literal("  ").append(Component.translatable(
                        "block.create_ballistics.fire_control_node.flight_time",
                        String.format(Locale.ROOT, "%.1f", solutionAirTime / 20.0))));
            }
        }

        if (!solvable) {
            if (arcNoteKey.isEmpty()) {
                tooltip.add(Component.translatable("block.create_ballistics.fire_control_node.no_solution"));
            } else {
                tooltip.add(Component.translatable(arcNoteKey, arcNoteArgs()));
            }
        } else {
            // ⚠️ 这两个数**必须带标签**。原来写成「目标角 33.62° / 90.00°」，被读成了
            // 「仰角 / 俯仰上限」—— 90° 其实是**方位角**（朝 −X 就是 90°），而俯仰上限
            // 是上面那行的「射界 −30°~60°」。写成裸的两个数就一定会被误读。
            tooltip.add(Component.literal(String.format(Locale.ROOT,
                    "  仰角 %.2f° · 方位 %.2f°   %s",
                    targetPitch, targetYaw,
                    aiming ? "转动中" : "已就位")));
            // 能打、但这条弹道有话要说（被挡住 / 地形判断不了 / 自动换过高抛）——
            // 必须说破，否则玩家看到"已就位"却打空了完全无从判断
            if (!arcNoteKey.isEmpty()) {
                tooltip.add(Component.translatable(arcNoteKey, arcNoteArgs()));
            }
        }
        return super.addToGoggleTooltip(tooltip, isPlayerSneaking);
    }
}
