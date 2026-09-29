package net.createballistics.entity;

import java.util.UUID;
import net.createballistics.cbc.ActiveBallistics;
import net.createballistics.cbc.SableCompat;
import net.createballistics.item.MonitorItem;
import net.createballistics.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * 无人机 —— 观测层的「眼睛」。
 *
 * <h2>为什么继承 {@link Entity} 而不是 {@code Mob}</h2>
 * 卓越前线的无人机是**载具**、不是生物：没有血条、没有受伤闪红、也没有 AI 那一套。
 * 用户的诉求相同 —— 它是一件**可消耗的观测器材**（撞坏了就炸掉、再造一台），
 * 所以耐久自己记一个数就够了，**不该为了它去当 LivingEntity 而平白长出一条血条**。
 *
 * <h2>飞行模型（照卓越前线）</h2>
 * <b>没有显式重力</b>：操控端给推力，阻力 {@code (0.965, 0.7, 0.965)} 收住速度 ——
 * 所以松开按键它就停在原地（悬停），不会像别的实体那样往下掉。
 *
 * <h2>撞坏（照卓越前线）</h2>
 * 碰撞按 {@code (速度 − 0.2)² } 扣耐久，耐久见底就**炸一下然后消失**，
 * 而且**不掉落无人机物品** —— 撞坏了就是没了，想再用得再造一台。
 * 爆炸刻意<b>不破坏地形</b>（观测器材炸了不该顺手拆掉玩家的墙）。
 *
 * <h2>⚠️ 绑定关系不存在它身上</h2>
 * 「哪台监视器绑着它」记在<b>监视器物品</b>上（{@code ModDataComponents#LINKED_DRONE}）。
 * 服务端发指令时拿"发包人手上那台监视器"校验 ⇒ 无人机这边不存 UUID，
 * 也就没有"两边各记一份、对不上"这类问题。
 */
public class DroneEntity extends Entity implements GeoAnimatable {

    /**
     * GeckoLib 的动画实例缓存 —— 实现 {@link GeoAnimatable} 的样板。
     *
     * <p>⚠️ 无人机实体**实现 GeckoLib 的接口**，所以 GeckoLib 是**硬依赖**（见 {@code DroneModel} 的注释）：
     * 换来的好处是渲染用的就是卓越前线那套（同模型、同渲染器），外观必然一致。
     */
    private final AnimatableInstanceCache animatableCache = GeckoLibUtil.createInstanceCache(this);

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // 刻意留空：这份模型没有可用的动画（它动画文件里的骨骼名和模型对不上），
        // **桨叶自转是在 DroneModel#setCustomAnimations 里每帧手写**的（照卓越前线）。
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return animatableCache;
    }

    @Override
    public double getTick(Object entity) {
        return tickCount;
    }

    /**
     * 满耐久（点数，不叫"生命值"——它不是生物）。
     *
     * <p>⚠️ 用户改过两次：<b>5 → 20</b>（2026-09-26）→ <b>20 → 30</b>（2026-09-26 晚）。
     * 注意它只是**上限**：撞击伤害是 {@code (超出速度)² × CRASH_DAMAGE}，两次都**没跟着改**
     * ⇒ 同一撞掉的血量不变 ⇒ **实际效果是"耐撞 N 倍"**（用户要的就是这个）：
     * 相对最初的 5 是 6 倍、相对 20 是 1.5 倍。
     *
     * <p>⚠️ 这是**唯一**一处写死耐久的地方：HUD 的「耐久 X / Y」行、护目镜旁那根耐久条
     * （{@code DroneInfoOverlay} 拿它当分母）、同步初值全从它推导 ⇒ **只改这里就够**，
     * 别去别处另写一个数。
     */
    public static final double MAX_DURABILITY = 30.0;
    /** 撞击的速度门槛：低于它算蹭一下，不扣。 */
    private static final double CRASH_SPEED = 0.2;
    /** 撞击伤害系数：{@code 伤害 = (超出速度)² × 它}。 */
    private static final double CRASH_DAMAGE = 8.0;
    /** 摧毁时的爆炸威力（不破坏地形）。 */
    private static final float EXPLOSION_POWER = 1.2F;
    /** 多久复核一次"主人是不是还绑着我"（照协调器/节点 verifyMaster 的节奏，它们也是几十 tick 一次）。 */
    private static final int VERIFY_CONTROLLER_INTERVAL = 40;

    /** 每 tick 的速度衰减 —— 水平 0.965、垂直 0.7（照卓越前线）。 */
    private static final double DRAG_HORIZONTAL = 0.965;
    private static final double DRAG_VERTICAL = 0.7;

    // ------------------------------------------------------------------
    // 飞行模型常量 —— 逐条照搬卓越前线 DroneEntity（2026-09-23 用户拍板"直接照搬全抄"）
    // ------------------------------------------------------------------

    /** 升力上限（照它 0.2）、怠速（0.02）、地面上下降时的下限（0.06）。 */
    private static final float MAX_POWER = 0.2F;
    private static final float IDLE_POWER = 0.02F;
    private static final float MIN_HOVER_POWER = 0.06F;
    /** 升力系数：{@code deltaMovement += power × 0.6}（照它）。 */
    private static final double LIFT_PER_POWER = 0.6;
    /** **倾角 → 水平加速度**的系数（照它：{@code 0.017}）。水平速度就是这么来的。 */
    private static final double TILT_TO_ACCEL = 0.017;

    /**
     * 自身重力（格/tick²）—— 照卓越前线载具数据的默认值 {@code Gravity = 0.06}
     * （它的无人机数据没覆盖这一项 ⇒ 就用这个数）。
     *
     * <p>⚠️⚠️ <b>这一个是整组数字的前提</b>：怠速升力只有 {@code 0.02 × 0.6 = 0.012}，
     * <b>托不住 0.06 的重力</b> ⇒ 无人机放下来就是**真的往下沉**（用户 2026-09-23 实测它原版的行为）；
     * 按住空格把 power 顶上去（0.2 × 0.6 = 0.12 &gt; 0.06）才开始爬升。
     *
     * <p>我一开始漏抄了它（并且记忆里那条"它没有显式重力项"是**错的**），
     * 于是无人机变成"没有任何重力、那点升力变成净上升"——正是用户报的"静止时慢慢往上升"。
     */
    private static final double GRAVITY = 0.06;

    // ------------------------------------------------------------------
    // 客户端插值（照 LivingEntity 那一套）
    // ------------------------------------------------------------------

    /**
     * ⚠️⚠️ <b>1.21 的 {@code Entity.lerpTo} 根本不做插值</b> —— 它是一句直接的 {@code setPos}：
     * <pre>
     *   public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
     *       this.setPos(x, y, z);          // ← 直接落位
     *       this.setRot(yRot, xRot);
     *   }
     *   public double lerpTargetX() { return this.getX(); }   // ← 刚设完 ⇒ "目标就是自己"
     * </pre>
     * 而<b>真正做插值的那一步 {@code lerpPositionAndRotationStep}，在 {@code Entity.java} 里
     * 一个调用者都没有</b> —— 它是留给 {@code LivingEntity} 的：生物的 {@code aiStep} 会调它，
     * 配合 {@code LivingEntity} 自己那份 {@code lerpTo}（只记目标、不落位）。
     *
     * <p>⇒ 也就是说：<b>原版实体的平滑主要来自"客户端自己也跑一遍物理"</b>
     * （同步过来的速度 + 本地 {@code travel}），位置包只是纠正。
     * 我们的无人机是<b>裸 {@code Entity}</b>、客户端也没有物理 ⇒ <b>纯听包</b>：
     * 单人游戏里服务端与客户端是两个独立的 20Hz 时钟，必然互相错拍 —— 有的客户端 tick
     * 落了两个包、有的一个都没有 ⇒ 位置"一大一小"地跳 ⇒ <b>机身一卡一卡、连音效都炸音</b>
     * （2026-09-23 用户实测；诊断日志里 {@code 机位移avg=1.8 max=4.0} 正是这个"一大一小"的图案）。
     *
     * <p>⇒ 修法：把 {@code LivingEntity} 那套搬过来（<b>只记目标 + 每 tick 走 1/steps</b>），
     * 位置与朝向都变成指数收敛 ⇒ 平滑，且实测图案会变成 avg≈max。
     */
    private double lerpX;
    private double lerpY;
    private double lerpZ;
    private int lerpSteps;

    /**
     * ⚠️⚠️ <b>位置插值，但朝向<b>直接落位</b></b> —— 这是 2026-09-23 用探针量出来的结论。
     *
     * <h2>朝向为什么不能走"分步插值"</h2>
     * 我先前让朝向也按 {@code 1/steps} 慢慢逼近（还"折中"把窗口从 3 加到 10）⇒
     * **窗口越大滞后越大**：稳态时朝向会落后 {@code (窗口−1) × 每 tick 转角}，
     * 转圈时就是"视角落后一大截、还在抖"（用户实测"比之前更严重" ✗）。
     *
     * <p>卓越前线的朝向**不是这么同步的**：它每 tick 把真实朝向写进<b>同步数据</b>
     * （{@code SERVER_YAW}/{@code SERVER_PITCH}，服务端 {@code handleClientSync} 里
     * {@code setServerYaw(getYRot())}），客户端拿到的就是**最新值**，
     * 再由原版渲染的 {@code Mth.lerp(partialTick, yRotO, getYRot())} 抹平成亚 tick 的平滑
     * ⇒ <b>既不滞后、也不抖</b>。
     *
     * <p>⇒ 我们照这个效果做：<b>朝向在 {@code lerpTo} 里直接 set</b>（每 tick 一次，
     * 原版渲染自己会插值），只让**位置**走分步 —— 位置那一路才是"明显变顺"的来源。
     */
    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        // 朝向：直接落位（照它的效果，见上面的说明）。
        // ⚠️ 例外：**本地玩家正在操控这一台时不要套用包里的朝向** —— 那一路由本地鼠标驱动
        //    （见 predictLookLocal），套用只会把它往回拽一 tick ⇒ 变成"抖"。
        //    只在偏差大到明显是掉包/失步时才拉回来（防永久漂移）。
        // ⚠️⚠️ **偏航要按"差值"套用，不能直接赋值**（2026-09-25 实测定案）。
        //
        // 包里的偏航是**被包成 ±180° 的**（网络字节量），而实体自己那份 `yRot` 是**连续的**
        // （`setYRot` 不归一化，会一直加减下去）。直接 `setYRot(包里的值)` 会让它**每次跨过
        // ±180° 就猛跳 360°** ⇒ 渲染那一句 `Mth.lerp(partialTick, yRotO, getYRot())`
        // 于是**绕远路转一整圈** ⇒ 看起来就是"高速转向时一卡一卡" ✗
        // （而帧耗时统计显示平均 4~5 ms、最大十几毫秒 ⇒ **根本不是性能问题**，是这一下画错了）。
        // ⇒ 用 `当前值 + wrap(差值)`：连续、绕近路，跨 ±180° 也平滑。
        float yawDeviation = Mth.wrapDegrees(yRot - getYRot());
        // ⚠️⚠️ 本地操控时的"猛拉"阈值必须**远大于**一个网络往返的转动量（2026-09-25 实测定案）。
        //
        // 原来的 30° 太小：本地鼠标预测**领先服务端一个网络往返**，高速 whip 时偏差轻易超过 30°
        // ⇒ 于是被"直接落位"猛拉回去 ⇒ **卡一下**，下一 tick 继续转、偏差又回到 30° 内 ⇒ 立刻恢复。
        // 日志指纹：被猛拉的那两个窗口正是偏航跳最大（19°、32°）的窗口 ✓✓
        // ⇒ 现在只在**明显是掉包/失步**（>120°）时才拉回；正常的高速转向交给本地预测 ✓
        if (!pilotedLocally() || Math.abs(yawDeviation) > 120.0F) {
            setYRot(getYRot() + yawDeviation);
        }
        if (!pilotedLocally() || Math.abs(xRot - getXRot()) > 30.0F) {
            setXRot(xRot);
        }
        // 位置：记目标，由 stepInterpolation() 分步逼近；窗口用包里给的（vanilla 是 3）
        this.lerpX = x;
        this.lerpY = y;
        this.lerpZ = z;
        this.lerpSteps = steps;
    }

    // 位置目标读数：原版包里"只给朝向不给位置"的那种包会拿它们当前置，
    // 所以没有待插值目标时返回当前值（照原版语义）。

    @Override
    public double lerpTargetX() {
        return this.lerpSteps > 0 ? this.lerpX : this.getX();
    }

    @Override
    public double lerpTargetY() {
        return this.lerpSteps > 0 ? this.lerpY : this.getY();
    }

    @Override
    public double lerpTargetZ() {
        return this.lerpSteps > 0 ? this.lerpZ : this.getZ();
    }

    /**
     * 位置的分步逼近（**只管位置**；朝向在 {@link #lerpTo} 里就落位了）。
     *
     * @param carry 这一 tick 由**客户端预测**补上的甲板位移（{@link Vec3#ZERO} = 没被甲板带着走）
     */
    private void stepInterpolation(Vec3 carry) {
        if (lerpSteps <= 0) {
            return;
        }
        // ⚠️⚠️ 被甲板带着走时：**只纠正"真的错位"，不纠正"预测领先"**（2026-09-26 定案）。
        //
        // 客户端预测**天生**与包差着一点点：包给的是服务端在它那一 tick 末尾的位置，
        // 而客户端已经把甲板又往前推了一 tick（实测量到客户端**领先** 0.35~0.45 格，
        // 正好是"一 tick 的甲板位移"）。按原版 1/3 去抹它 ⇒ 每来一个包就把速度砍掉三成、
        // 连抹三 tick ⇒ 用户看到的**"每隔一会小幅度抖动一下"**
        // （实测指纹：实走 −0.095 而甲板补位移 −0.19，正好一半）。
        //
        // ⭐ 而真正要保的东西是"无人机**贴在画出来的甲板上**"——那由客户端预测保证；
        //    去追那个落后的包目标，反而把它从甲板上拽下来。
        // ⇒ 残差**小于两 tick 的甲板位移**时整个忽略（那是领先，不是错位）；
        //    只有真的错位（丢包久了、被传送、被人推开）才照原版那样拉回。
        // ⚠️ 阈值随**当前船速**缩放：船越快，一 tick 的领先量越大，固定阈值会误判。
        double residual = Math.sqrt(Mth.square(lerpX - getX()) + Mth.square(lerpY - getY())
                + Mth.square(lerpZ - getZ()));
        boolean carried = carry.lengthSqr() > 1.0E-12;
        if (carried && residual < CARRY_LEAD_TICKS * carry.length() + CARRY_LEAD_SLACK) {
            lerpSteps = 0;   // 丢弃这个包的目标：位置由甲板说了算（下次来包重新评估）
            return;
        }
        double amount = 1.0 / lerpSteps;
        setPos(Mth.lerp(amount, getX(), lerpX),
                Mth.lerp(amount, getY(), lerpY),
                Mth.lerp(amount, getZ(), lerpZ));
        --lerpSteps;
    }

    /**
     * "预测领先"的容忍倍数（单位：**这一 tick 的甲板位移**）——
     * 残差小于它就算"领先"，不去抹；见 {@link #stepInterpolation} 里那段说明。
     */
    private static final double CARRY_LEAD_TICKS = 2.0;
    /** 上面那个阈值的固定余量（格），避免船停着时阈值退化成 0。 */
    private static final double CARRY_LEAD_SLACK = 0.05;


    // ------------------------------------------------------------------
    // 操控输入（服务端；由 DroneControlC2SPacket / DroneLookC2SPacket 写进来）
    // ------------------------------------------------------------------

    /**
     * 按键位掩码 —— **照卓越前线的位号，一个都不改**：{@code 1左 2右 4前 8后 16上 32下}。
     *
     * <p>它后面还排着 {@code 64}(诱饵) / {@code 128}(开火) / {@code 256}(疾跑)，
     * 我们的无人机用不到，但**位号刻意不重排** —— 将来要加就顺着往后排。
     */
    public static final int KEY_LEFT = 1;
    public static final int KEY_RIGHT = 2;
    public static final int KEY_FORWARD = 4;
    public static final int KEY_BACK = 8;
    public static final int KEY_UP = 16;
    public static final int KEY_DOWN = 32;

    /** 俯仰的上下限（照它：低头最多 10°、抬头到 90°）。 */
    private static final float MIN_LOOK_PITCH = -10.0F;
    private static final float MAX_LOOK_PITCH = 90.0F;

    /**
     * 操控端此刻按着哪些键（0 = 没按）。
     *
     * <p>⚠️ 刻意**不做同步字段**（它与卓越前线的区别）：位移和姿态都在服务端算，
     * 客户端不需要知道遥控端按着什么。真要做 HUD 再同步不迟。
     */
    private int controlKeys;

    /**
     * 操控端这一 tick 给的鼠标速度。
     *
     * <p>⚠️ **用完就清零**（见 {@link #applyPilotInput}）：它靠客户端每 tick 灌一包维持，
     * 万一客户端卡住 / 掉线 / 退出视角，不清零就会"以最后一帧的速度一直转下去"。
     */
    private double lookSpeedX;
    private double lookSpeedY;

    /** 机身横滚倾角（照它的 {@code deltaRot}）。服务端算；将来要画"压机身"也用它。 */
    private float deltaRot;
    /** 机身俯仰倾角（照它的同步字段 {@code DELTA_X_ROT}）。 */
    private float tiltPitch;
    /** 方向键按住的 tick 数（照它的 {@code holdTickX/Y/Z}）：倾角与升力都随按住时长加大，最多算 5。 */
    private int holdTickX;
    private int holdTickY;
    private int holdTickZ;

    /**
     * 上一次收到操控包的 tick —— 用来兜"客户端不再说话了"。
     *
     * <p>⚠️ 这一条是**我们比卓越前线多做的**：它那边操控端一退出视角，最后那个位掩码就永远留着，
     * 于是"按着 W 退出视角"会让无人机一直飞下去。我们改成"操控端不说话超过
     * {@link #PILOT_TIMEOUT_TICKS} tick，输入一律当没按" —— 客户端在操控期间**每 tick**
     * 都会发鼠标包（见 {@code DroneLookC2SPacket}），所以正常操作下这条判据永远是满足的，
     * 只在退出视角 / 卡住 / 掉线时才生效。松手就是松手。
     */
    private int lastPilotPacketTick = -1000;

    /** 多久没收到操控包就当"操控端不在了"（5 tick = 0.25 秒，比鼠标包的一 tick 节奏宽松得多）。 */
    private static final int PILOT_TIMEOUT_TICKS = 5;

    /** 已经走完销毁流程（防"爆炸打到自己 ⇒ 再销毁"的递归，见 {@link #destroy}）。 */
    private boolean destroyed;

    /** 绑定着它的玩家 UUID（空 = 没人绑）。**它就是"归属"这个显示量的判据**。 */
    private static final EntityDataAccessor<String> CONTROLLER =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.STRING);
    /**
     * 归属者的显示名 —— 刻意**跟 UUID 一起记下来**，而不是每次去查。
     *
     * <p>看名牌的人常常离那台遥控器很远（无人机就是拿来远程看的），
     * 那时客户端上根本找不到那个玩家实体、也就拿不到名字。绑定的那一刻顺手记下最省事，
     * 而且是**唯一权威**的一份（改绑/解绑都会一起改）。
     */
    private static final EntityDataAccessor<String> CONTROLLER_NAME =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.STRING);
    /** 剩余耐久 —— 放同步数据里，护目镜/HUD 才能显示"这台还能撑几下"。 */
    private static final EntityDataAccessor<Float> DURABILITY =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.FLOAT);
    /**
     * 升力（照卓越前线的 {@code power}，0.02~0.2）—— **必须同步**：
     * 音效的音量与音调按它算（客户端要读），"引擎在不在转"也看它（见 {@link #engineRunning}）。
     */
    private static final EntityDataAccessor<Float> POWER =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.FLOAT);

    public DroneEntity(EntityType<? extends DroneEntity> type, Level level) {
        super(type, level);
        // ⚠️ **不设 noGravity**（照卓越前线）：它的无人机是有重力的，
        // 靠"升力 power"去对抗它 —— 见 {@link #GRAVITY}。
        // 我原来写了 setNoGravity(true)，于是静止时会自己慢慢往上升（用户实测）。
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(CONTROLLER, "");
        builder.define(CONTROLLER_NAME, "");
        builder.define(DURABILITY, (float) MAX_DURABILITY);
        builder.define(POWER, IDLE_POWER);
    }

    /** 升力（0.02~0.2）。音效与"引擎在不在转"都读它。 */
    public float getPower() {
        return entityData.get(POWER);
    }

    private void setPower(float power) {
        entityData.set(POWER, Mth.clamp(power, 0.0F, MAX_POWER));
    }

    /**
     * 引擎在不在转 —— 照卓越前线：{@code |power| > 0.05}。
     *
     * <p>⭐ 这就是"无人机不飞的时候是安静的"那条判据：怠速 power 只有 0.02
     * ⇒ 音效淡出停掉。我们原来把它写死成 {@code return true}，于是**每时每刻都在嗡嗡嗡**
     * （用户 2026-09-23 实测："原版离远了会小声、不飞时不吵"）。
     */
    public boolean engineRunning() {
        return Math.abs(getPower()) > 0.05F;
    }

    // ------------------------------------------------------------------
    // 飞行
    // ------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            // 客户端：只做插值（照卓越前线 —— 它客户端**不**跑物理，位置完全跟着包走、由插值抹平）。
            // ⚠️ 我一度在这里加了"本地推进 + hasImpulse"，那是**错的**：既不是它的做法，
            //    而且"本地推进"与"包纠正"会互相打架、产生**有节奏的**抖动（2026-09-23 实测）。
            //
            // ⭐⭐⭐ 2026-09-26 新增：**本地把"甲板这一 tick 的位移"补上**（客户端预测）。
            //
            // ⚠️⚠️ 为什么必须补（本轮定案的完整因果链，前面四种做法全是**改错地方**了）：
            //   ① **服务端那边一直是对的** —— 逐 tick 日志核实过：无人机每 tick 的世界位移
            //      **逐位等于**甲板位移（`Δ实际` == `甲板速度`），本地坐标恒定 ⇒ 它确实被带着走 ✓；
            //   ② 而**客户端**只认包：位置包每 tick 一条、原版一律 `lerpTo(..., steps=3)`
            //      ⇒ 客户端位置**永远落在包目标后面 ≈ 3 tick**（稳态残差 = 3 × 船速；
            //      船速 0.34 格/tick 时就是**一格**，船更快就是好几格）；
            //   ③ 于是画面读起来就是"无人机落在甲板后面一截、被后面的包拉回去"；船速一变、
            //      或"着地标志翻转"触发那条**绝对传送包**（`ServerEntity` 里那句
            //      `wasOnGround != onGround()`）时，这个落后量会**一次跳掉** ⇒
            //      **"往后移一段又被强行拉回、反复循环"**（用户 2026-09-26 原话）。
            //   ④ 前四种做法（加载具速度 / 刚性粘附 / 位移法 / 交接速度）**全在改服务端**
            //      ⇒ 一个也没治到它 —— 服务端本来就对。
            //
            // ⭐ 做法：与**服务端那一句逐字同一个公式**（`SableCompat.structureDisplacementAt`），
            //    只是这里吃的是**客户端的姿态**：`ClientSubLevel.tick()` 每客户端 tick 会把
            //    `logicalPose` 从插值器更新一次、`lastPose` 记的是上一 tick ⇒ 差值正是
            //    "客户端这一 tick 的甲板位移"。而且渲染那张 `renderPose(partialTick)` 插的就是
            //    **同一对姿态** ⇒ 预测出来的位置与**画出来的甲板**天然对齐，不会各走一套。
            // ⚠️ 与 `stepInterpolation()` **不打架**：本地补的是"该走的量"，插值补的是
            //    "与服务端之间的残差"（收敛项）⇒ 两者相加，稳定（正是原版生物"本地物理 + 插值纠正"
            //    那一套）。⚠️ 判据与服务器那边**逐字一致**：**本地玩家正在操控这一台时整段跳过**
            //    （那时听操纵杆的，见 `applyPilotInput`）。
            Vec3 carry = !pilotedLocally() && ActiveBallistics.sableLoaded()
                    ? SableCompat.structureDisplacementAt(level(), position())
                    : Vec3.ZERO;
            if (carry.lengthSqr() > 1.0E-12) {
                setPos(getX() + carry.x, getY() + carry.y, getZ() + carry.z);
            }
            stepInterpolation(carry);
            return;
        }
        if (tickCount % VERIFY_CONTROLLER_INTERVAL == 0) {
            verifyController();
        }
        // ⚠️ 不要在这里加"区块保活票据"。2026-09-23 我照它抄过一版（半径 3 的 START 区域票据），
        //    结果**飞行时沿途一片片强行加载/生成区块** ⇒ 探针量到**单帧 1.5~3.7 秒**的地形段停顿
        //    （比不加更糟）。它的作用只是"让无人机自己那格别卸载"，要重做必须先想清楚
        //    半径/票据类型/与"远距离观测"的关系，别再直接照搬这一句。
        // 姿态与速度：阻力**在它里面**已经施加过了（照它的 travel）—— 这里别再乘一次
        applyPilotInput();
        // 重力：照它放在 travel 之后、move 之前（{@code VehicleEntity.tick} 就是这一句）
        setDeltaMovement(getDeltaMovement().add(0.0, -GRAVITY, 0.0));

        // ---- ⭐ 站在会动的物理结构（飞艇甲板）上时：**跟着它一起走**（2026-09-25 用户拍板）----
        //
        // ⚠️ 为什么不靠 Sable 自己那条：Sable 的 `EntityMixin#moveInject`(TAIL) 会给非生物实体
        //    补一个 `setPos(position + inheritedMotion)`，**但那个位移要过一遍碰撞解**
        //    （`sable$vanillaCollide`）⇒ 卡在甲板上时水平那一截会被吃掉 ⇒ **实测 Δ多出 恒为 0**
        //    ⇒ 从飞行中的飞艇上放出来的无人机**不跟着走、原地不动** ✗（用户实测）。
        //
        // ⚠️ 做法分两半，缺一不可：
        //   ① **吸附期间**：把载具这一 tick 的位移直接加在**位置**上（就是 Sable 本该做的那件事，
        //      只是不过碰撞解）—— 甲板带着它走 ✓ 而且"每个 tick 加一次位移"不会累加 ✓；
        //   ② **离开那一刻**：把载具速度**交接成自己的速度** ⇒ 之后靠自身的阻力自然衰减
        //      —— 这正是"从船上抛出的东西"该有的样子 ✓
        // ⚠️⚠️ 判据**不能用 `onGround()`**：日志实测，无人机停在飞艇甲板上时 `着地` **一直是 false**
        //     （Sable 对"站在子层上的世界系实体"不报着地）⇒ 用 onGround 当闸门 ⇒ **这条代码从来
        //     没跑过** ⇒ 无人机压根没被带着走 ⇒ 飞艇一飞它就像被落在后面/飘出去 ✗✗（2026-09-25 反复踩）。
        // ⚠️ 判据也**不加 `被操控`**：玩家开着它时就该听操纵杆的，别和操纵打架 ✓ 所以只在
        //     "没人操控" 且 "落在某条结构的包围盒里且有速度" 时才带着走。
        boolean controlled = tickCount - lastPilotPacketTick <= PILOT_TIMEOUT_TICKS;

        // ⭐⭐ 越线 ⇒ **当 tick 立即**自爆（用户 2026-09-26 二次定稿）。
        //
        // 无人机身上有**两堵墙**，谁先撞上就由这一句判 —— 判据与 HUD 那条告警**同源**：
        //   ① **客户端渲染距离**：客户端只 tick「它已加载的区块里」的实体
        //      （{@code ClientLevel#unload} → {@code entityStorage.stopTicking}）⇒
        //      那块地一卸载，**画面与操控当场停住**，而服务端其实还在飞它 —— 你在盲飞；
        //   ② **服务端模拟距离**：区块不再 tick ⇒ 无人机**真的**不动了（按什么都没反应）。
        // ⇒ 取**两堵墙里较近的那个**：客户端那侧读 {@code requestedViewDistance}
        //    （= 客户端的「渲染距离」选项，登录时随 {@code ClientInformation} 上来）；
        //    服务端那侧读 {@code PlayerList#getSimulationDistance}。
        //
        // ⚠️⚠️ 度量是**切比雪夫区块距离**（水平两轴取大者），**不是**三维欧氏距离 ——
        //    因为"墙"本身就是**区块**：区块的加载与模拟都按方形半径判、**与 Y 无关**。
        //    这也正是 {@code DroneHudOverlay} 那条告警用的同一把尺子 ⇒ **"提醒说会炸"与
        //    "真的会炸"必然落在同一格**（且提醒还留了 {@code WARN_MARGIN_CHUNKS} 的提前量）。
        //    ⚠️ 曾经用 {@code distanceToSqr}（三维欧氏）—— 那样"**垂直往上飞**"会毫无提醒地炸掉：
        //    飞机在头顶 300 格、水平 0 格、区块其实一直好好加载着，却因为三维距离超限而死。
        //
        // ⚠️ 用户 2026-09-26 改掉的两件事，别改回去：
        //   ① 原来是"连续 3 秒超出才炸"⇒ 现在是**越线当 tick 就炸**（没有宽限期）；
        //   ② 原来只算模拟距离 ⇒ 现在是**两堵墙都算、谁近算谁**。
        //
        // ⚠️ 只在**有人操控**时判定 —— 没人管的无人机停在哪里都不该自己炸
        //    （停在自己机器旁、玩家自己走开，都不该炸）。这顺带排掉了"操控者换了维度"：
        //    那种情况 5 tick 内 {@code controlled} 就变 false 了。同维度那条判断是兜底。
        // ⚠️ {@code pilot.isAlive()} 是"取消宽限期"之后**才需要**的一条：操控者**死亡/重生**
        //    那一瞬会被搬回出生点 ⇒ 区块距离突变，不加就会把一台好端端飞在前线的无人机当场炸掉。
        // 爆炸走现有 {@link #destroy()}：**不破坏地形**的那种。
        if (controlled && level() instanceof ServerLevel serverLevel) {
            PlayerList playerList = serverLevel.getServer().getPlayerList();
            ServerPlayer pilot = playerList.getPlayer(controllerId());
            if (pilot != null && pilot.isAlive() && pilot.level() == level()) {
                int renderChunks = pilot.requestedViewDistance();
                int simChunks = playerList.getSimulationDistance();
                // 与 HUD 同一条 tie-break —— 只决定"提示里报哪堵墙"，不决定炸不炸。
                boolean renderEdgeIsNearer = renderChunks <= simChunks;
                int limitChunks = Math.min(renderChunks, simChunks);
                int dxChunks = Math.abs((getBlockX() >> 4) - (pilot.getBlockX() >> 4));
                int dzChunks = Math.abs((getBlockZ() >> 4) - (pilot.getBlockZ() >> 4));
                if (Math.max(dxChunks, dzChunks) >= limitChunks) {
                    pilot.displayClientMessage(Component.translatable(renderEdgeIsNearer
                            ? "entity.create_ballistics.drone.link_lost_view"
                            : "entity.create_ballistics.drone.link_lost_sim"), true);
                    destroy();
                    return;
                }
            }
        }

        // ⭐⭐ 跟着平台走：**只把"平台这一 tick 的位移"加在位置上**（不硬拉、不设定、不算速度）。
        //
        // ⚠️⚠️ 三种做法我都试过，前两种都翻车，别再走回头路：
        //   ① "每 tick 加一遍载具**速度**"（`velocityAt`）⇒ 含 ω×r 那一项，坐标系一错就**超量** ⇒
        //      冲出去 ⇒ 出盒不再带 ⇒ 落后 ⇒ 又回盒 ⇒ **一前一后快速反复** ✗（2026-09-25 实测）；
        //   ② "刚性粘附"（记住局部坐标、每 tick 按当前姿态 `setPos` 回去）⇒ 变成**硬拉**：
        //      船姿先动、无人机后跟 ⇒ 每 tick 差 1 格船的位移 ⇒ **往后飘一段又被强行拉回原位置** ✗
        //      （2026-09-26 用户实测，原话"反复拉长"）；
        //   ③ **位移法（现在这个）**：取结构 `logicalPose` 与 `lastPose` 在**同一局部点**上的
        //      世界坐标差 = **刚体位移本身**，不多不少 ⇒ 加在位置上即可 ✓
        //      ⇒ 不超量、不振荡、不硬拉 ✓ 而且**无人机的重力/操纵完全保留** ✓
        // ⚠️ 只加"这一 tick 的位移"（不加速度、不累加）✓
        // ⚠️ 被玩家操控时整段跳过：让操纵杆说了算，不与操纵打架 ✓
        Vec3 carry = !controlled && ActiveBallistics.sableLoaded()
                ? SableCompat.structureDisplacementAt(level(), position())
                : Vec3.ZERO;
        if (carry.lengthSqr() > 1.0E-12) {
            setPos(getX() + carry.x, getY() + carry.y, getZ() + carry.z);
        }
        // ⚠️⚠️ 这里**刻意不做**"离开甲板时把载具速度交接成自己的速度"。
        //
        // 我做过两版，都闯了祸：
        //  ① 无闸门 ⇒ `onGround` 在甲板上会**抖**（脚下方块跟着船动）⇒ 反复交接 ⇒ 速度累加 ⇒
        //     "直接高速飘走" ✗；
        //  ② 加了闸门（只有自己的水平速度还小于载具速度时才交接）⇒ **仍然叠加** ——
        //     交接后再有一次"着地"就又走上面那条**位移**路 ⇒ 等效速度变**两倍** ⇒
        //     无人机**冲到飞艇前方** ✗（2026-09-25 用户实测）。
        // ⇒ 只保留"甲板带着走"这一半：站在甲板上就跟着走 ✓；被操控飞走就交给操纵 ✓；
        //    没人管时它停在原地（对观测器材来说这是合理行为）。

        Vec3 motion = getDeltaMovement();

        // ⚠️⚠️ 停在地面且没有水平意图时：**照样要 move()，只是把"碰撞解的上爬"丢掉**。
        //
        // 历史（两次都踩在这上面，别再走回头路）：
        // ① 原来直接 move()：碰撞解每 tick 把它微微往上挤 ~0.002 格 ⇒ 服务端位置**慢慢上爬**，
        //    而客户端按包节流 ⇒ 每隔几 tick 猛跳 0.1 格 ⇒ 地上"来回抽搐" ✗。
        // ② 我改成"停稳就整个跳过 move()" ⇒ **更糟**：`onGround` 只在 move() 里更新 ⇒
        //    它永远停在 true，而 move 再也不跑 ⇒ **无人机吊在半空、飞艇飞走了它还留在原地** ✗✗
        //    （2026-09-25 实机：飞艇往前飞，无人机悬浮原地 ✗）。
        // ⇒ 正解：move() 照跑（`onGround` 保持新鲜、下面没东西时会自然下落），
        //    只在"停稳"那一刻把**位置退回原处** —— 上爬被掐掉，物理一点没少。
        if (motion.lengthSqr() > 1.0E-6) {
            // ⚠️ `Entity.move` 返回 void（只有 LivingEntity 那一版才返回"实际位移"）——
            //    所以碰撞要自己比："想走的"和"实际走成的"差太多，就是撞上东西了。
            Vec3 before = position();
            move(MoverType.SELF, motion);
            Vec3 actual = position().subtract(before);

            // ⚠️ 停稳的那一刻把位置**退回原处** —— 上爬被掐掉，但 move() 已经跑过
            //    （`onGround` 是真值、下面没东西时它会自然下落，见上面那段历史）。
            if (onGround() && motion.y <= 0.0
                    && motion.x * motion.x + motion.z * motion.z < 1.0E-6) {
                setPos(before);
                setDeltaMovement(Vec3.ZERO);
            }

            if (actual.lengthSqr() < motion.lengthSqr() * 0.7) {
                crash(motion.length());
            }
        }
    }

    /** 操控端按下的键（位掩码，见 {@link #KEY_LEFT} 那一组）。由 {@code DroneControlC2SPacket} 写进来。 */
    public void setControlKeys(int keys) {
        this.controlKeys = keys;
        this.lastPilotPacketTick = tickCount;
    }

    /**
     * <b>本地玩家正在操控这一台</b>吗（只在客户端有真值）。
     *
     * <p>⚠️⚠️ <b>专用服务器安全</b>：真正的判断在 {@code client/DroneClientAccess}（客户端专属的类，
     * 服务端永远不会加载它）。这里先过 {@code level().isClientSide} 这道闸 —— <b>它不能省</b>：
     * 服务端的分发包里没有 {@code net/minecraft/client/**}，公共类只要在服务端的执行路径上碰到
     * 那些类就会 {@code NoClassDefFoundError}（本模组发布前专门扫过一遍，见
     * {@code tools/scan_client_refs.js}）。
     */
    private boolean pilotedLocally() {
        if (level() == null || !level().isClientSide) {
            return false;
        }
        return net.createballistics.client.DroneClientAccess.isPilotingLocally(getUUID());
    }

    /**
     * <b>本地鼠标直接改朝向</b> —— ⚠️⚠️ 这是"转向丝滑"的关键一步。
     *
     * <p>公式与服务端 {@link #applyPilotInput} 里那条**完全一样**
     * （{@code yaw += 0.5 × speedX}、pitch 夹在 [-10°, 90°]），区别只是**立刻在本地生效**。
     * 不这么做，朝向就要等"一个网络往返 + 插值窗口"⇒ 高速转圈时必然又滞后又抖
     * （2026-09-23 反复实测：`机转avg=25°/tick` 而 max 到 36，帧率却是 200+ ⇒ 不是掉帧，是同步）。
     *
     * <p>服务端那份到达时**不再覆盖**它（见 {@link #lerpTo} 里的例外），只在偏差过大时拉回（防掉包漂移）。
     * 只应在客户端调。
     */
    public void predictLookLocal(double speedX, double speedY) {
        setYRot(getYRot() + 0.5F * (float) speedX);
        setXRot(Mth.clamp(getXRot() + 0.5F * (float) speedY, MIN_LOOK_PITCH, MAX_LOOK_PITCH));
    }

    /** 操控端这一 tick 的鼠标速度。由 {@code DroneLookC2SPacket} 写进来，**下一 tick 用掉即清**。 */
    public void applyLook(double speedX, double speedY) {
        this.lookSpeedX = speedX;
        this.lookSpeedY = speedY;
        this.lastPilotPacketTick = tickCount;
    }

    /**
     * ⚠️ 无人机会在**没有玩家靠近**时也继续 tick（照卓越前线 {@code VehicleEntity.isAlwaysTicking}）。
     *
     * <p>默认规则下，实体只有在"玩家模拟距离内"的区块里才 tick ⇒ 飞远了就冻在半空、
     * 操控指令也失去意义。它把载具一律标成"总是 tick"，我们照做。
     */
    @Override
    public boolean isAlwaysTicking() {
        return true;
    }

    /**
     * 把操控端这一 tick 的输入变成飞行姿态与速度 ——
     * <b>整段照搬卓越前线 {@code DroneEntity.travel()}</b>（2026-09-23 用户拍板："直接照搬全抄"）。
     *
     * <h2>它的模型（与我们早先自己写的那套完全不同）</h2>
     * <ul>
     *   <li><b>W/S = 机身俯仰倾斜、A/D = 机身横滚倾斜</b>：按住越久倾得越多
     *       （每 tick 加 {@code 0.3 × min(holdTick,5)}，同时自己 {@code ×0.7} 回复，
     *       稳态约 ±3.5 单位）；</li>
     *   <li><b>水平移动是倾斜"压"出来的</b>：{@code 加速度 = 方向 × 倾角 × 0.017}，
     *       而方向是<b>只含 yaw 的水平向量</b> ⇒ 抬头低头不改变前进方向；</li>
     *   <li><b>空格 / 左Shift = 升降（{@code power}）</b>：缓升缓降（0.02~0.2），
     *       升力 {@code += power × 0.6}，另外按 holdTick 直接给一个竖直速度
     *       （{@code ±0.05 × holdTick}）；松开后 power 自己往 0.02 漂（引擎怠速）；</li>
     *   <li>阻力每 tick 乘 {@code (0.965, 0.7, 0.965)}（在地面上换成 {@code (0.8, 1, 0.8)}）。</li>
     * </ul>
     *
     * <p>⚠️ 我先前把它读成"按方向直接给推力"，于是速度和手感都完全不对
     * （算下来是它真实速度的好几倍）。现在逐行照它，**改动它之前先回去看 {@code travel()}**。
     */
    private void applyPilotInput() {
        // ⭐ 这一条是我们加的（它没有）：操控端超过几 tick 不说话 ⇒ 输入全部作废、等于松手。
        //    见 lastPilotPacketTick 的注释。
        if (tickCount - lastPilotPacketTick > PILOT_TIMEOUT_TICKS) {
            controlKeys = 0;
            lookSpeedX = 0.0;
            lookSpeedY = 0.0;
        }
        // 照它 baseTick 的判据：**操控者手上没拿着"指着我的、正在操控的"监视器 ⇒ 输入全清**。
        // （它是每 tick 直接查主人的手；和上面那条超时兜底互补 —— 那条管"客户端不说话"，
        //   这条管"主人已经放开/换手了"。）
        if (!onGround() && !controllerIsPiloting()) {
            controlKeys = 0;
            lookSpeedX = 0.0;
            lookSpeedY = 0.0;
        }
        boolean left = (controlKeys & KEY_LEFT) != 0;
        boolean right = (controlKeys & KEY_RIGHT) != 0;
        boolean forward = (controlKeys & KEY_FORWARD) != 0;
        boolean back = (controlKeys & KEY_BACK) != 0;
        boolean up = (controlKeys & KEY_UP) != 0;
        boolean down = (controlKeys & KEY_DOWN) != 0;

        // ---- 倾斜（照它：在地面上不生效）----
        if (!onGround()) {
            if (right) {
                holdTickX++;
                deltaRot -= 0.3F * Math.min(holdTickX, 5);
            } else if (left) {
                holdTickX++;
                deltaRot += 0.3F * Math.min(holdTickX, 5);
            } else {
                holdTickX = 0;
            }
            if (forward) {
                holdTickZ++;
                tiltPitch -= 0.3F * Math.min(holdTickZ, 5);
            } else if (back) {
                holdTickZ++;
                tiltPitch += 0.3F * Math.min(holdTickZ, 5);
            } else {
                holdTickZ = 0;
            }
            setDeltaMovement(getDeltaMovement().multiply(DRAG_HORIZONTAL, DRAG_VERTICAL, DRAG_HORIZONTAL));
        } else {
            // 在地面上（照它）：阻力更大、倾角回正，**而且把视线俯仰也乘 0.7 收回去**
            setDeltaMovement(getDeltaMovement().multiply(0.8, 1.0, 0.8));
            setXRot(getXRot() * 0.7F);
            deltaRot *= 0.7F;
            tiltPitch *= 0.7F;
        }

        // 在水里：每 4 tick 减速并受伤（照它 —— 它的无人机掉进水里是会坏的）
        if (isInWater() && tickCount % 4 == 0) {
            setDeltaMovement(getDeltaMovement().multiply(0.6, 0.6, 0.6));
            hurt(damageSources().flyIntoWall(), (float) (0.25 + 2.0 * getDeltaMovement().length()));
        }

        // ---- 升降（power）----
        Vec3 motion = getDeltaMovement();
        if (up) {
            holdTickY++;
            setPower(Math.min(getPower() + 0.01F * Math.min(holdTickY, 5), MAX_POWER));
            setDeltaMovement(motion.x, 0.05 * holdTickY, motion.z);
        } else if (down) {
            holdTickY++;
            setPower(Math.max(getPower() - 0.02F * Math.min(holdTickY, 5),
                    onGround() ? 0.0F : MIN_HOVER_POWER));
            setDeltaMovement(motion.x, -0.05 * holdTickY, motion.z);
        } else {
            holdTickY = 0;
        }
        // 不按升降时：在下沉就补升力、在上升就慢慢收回（照它的 0.005 / 0.0005 漂移）
        if (!up && !down) {
            setPower(motion.y < 0.0
                    ? Math.min(getPower() + 0.005F, MAX_POWER)
                    : Math.max(getPower() - (onGround() ? 5.0E-4F : 0.005F), IDLE_POWER));
        }

        // ---- 倾角回复 ----
        deltaRot *= 0.7F;
        tiltPitch *= 0.7F;

        // ---- 升力 + 倾斜产生的水平加速度（照它：0.017 是"倾角→加速度"的系数）----
        // 方向**只含 yaw**（照它 getForwardDirection/getRightDirection 的写法）
        Vec3 horizontalForward = new Vec3(
                Mth.sin(-getYRot() * Mth.DEG_TO_RAD), 0.0, Mth.cos(getYRot() * Mth.DEG_TO_RAD)).normalize();
        Vec3 horizontalRight = new Vec3(
                Mth.cos(-getYRot() * Mth.DEG_TO_RAD), 0.0, Mth.sin(getYRot() * Mth.DEG_TO_RAD)).normalize();
        setDeltaMovement(getDeltaMovement()
                .add(0.0, getPower() * LIFT_PER_POWER, 0.0)
                .add(horizontalRight.scale(deltaRot * TILT_TO_ACCEL))
                .add(horizontalForward.scale(-tiltPitch * TILT_TO_ACCEL)));


        // ---- 鼠标改朝向（照它：也在这里做）----
        if (lookSpeedX != 0.0 || lookSpeedY != 0.0) {
            setYRot(getYRot() + 0.5F * (float) lookSpeedX);
            setXRot(Mth.clamp(getXRot() + 0.5F * (float) lookSpeedY, MIN_LOOK_PITCH, MAX_LOOK_PITCH));
            // ⚠️ 用完就清：它是"客户端每 tick 灌一包"的量，不清零的话客户端一卡就会一直转
            lookSpeedX = 0.0;
            lookSpeedY = 0.0;
        }
    }

    // ------------------------------------------------------------------
    // 耐久 / 撞坏
    // ------------------------------------------------------------------

    private void crash(double speed) {
        double over = speed - CRASH_SPEED;
        if (over <= 0.0) {
            return;
        }
        hurt(damageSources().flyIntoWall(), (float) (over * over * CRASH_DAMAGE));
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level().isClientSide || isRemoved()) {
            return false;
        }
        double left = getDurability() - amount;
        if (left <= 0.0) {
            destroy();
            return true;
        }
        setDurability(left);
        return true;
    }

    /** 耐久见底：炸一下然后消失。**刻意不掉落无人机物品** —— 撞坏了就是没了。 */
    private void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        forgetMe();
        // ⚠️⚠️ **必须"先消失、再爆炸"**：原来反过来写，爆炸的实体伤害段会打到自己
        // （这时自己还在世界里）⇒ hurt ⇒ destroy ⇒ 再炸 ⇒ **无限递归 ⇒ StackOverflowError 崩游戏**
        // （2026-09-23 用户实测崩了两次，堆栈就是 destroy/hurt 交替 44 层）。
        discard();
        if (level() instanceof ServerLevel server) {
            server.explode(this, getX(), getY(), getZ(), EXPLOSION_POWER,
                    Level.ExplosionInteraction.NONE);
        }
    }

    /**
     * 通知主人身上那台遥控器「我已不在」。
     *
     * <p>⚠️ 绑定关系只存在遥控器物品上，所以<b>无人机这一侧不主动说一声，那台遥控器就永远回不来</b>：
     * 它指着一个消失的 UUID，右键新无人机被拒、潜行右键解绑也被拒 —— 见
     * {@link MonitorItem#unlinkAll}。
     */
    private void forgetMe() {
        MonitorItem.unlinkAll(level(), getUUID(), controllerId());
    }

    /**
     * 复核归属：主人身上已经没有指着我的遥控器了 ⇒ 归属作废。
     *
     * <p>与协调器/节点的 {@code verifyMaster()} 同一个路子 —— <b>只有一边记着的关系总有对不上的时候</b>。
     * 这里要解的是两种情况：① 玩家在别处潜行右键放开了这台遥控器（那台遥控器不会来通知我）；
     * ② 上一任主人走了、却没人能把归属清掉 —— 不清的话<b>别的玩家会永远绑不上这台无人机</b>。
     *
     * <p>⚠️ <b>主人不在线时什么都不做</b>：那只是他下线了，不是他放开了我。
     *
     * <p>⚠️ 已知且<b>刻意接受</b>的后果：判据是"<b>身上</b>有没有那台遥控器"，
     * 所以把遥控器放进箱子 / 丢在地上超过两秒，这台无人机也会当作"没人要了" ——
     * 归属名消失、别人可以来绑。这与"潜行右键 = 放开"是同一句话的两种说法，是<b>想要</b>的简化；
     * 反过来说，如果只看"世界上还在不在"，就没法区分"他把遥控器收起来了"和"他永远不回来了"。
     */
    private void verifyController() {
        UUID ownerId = controllerId();
        if (ownerId == null || !(level() instanceof ServerLevel server)) {
            return;
        }
        ServerPlayer owner = server.getServer().getPlayerList().getPlayer(ownerId);
        if (owner == null) {
            return;
        }
        if (!MonitorItem.holdsMonitorLinkedTo(owner, getUUID())) {
            clearController();
        }
    }

    /**
     * 操控者此刻是否正拿着"指着我的、且处于操控状态的"监视器 —— 照卓越前线 {@code baseTick} 里那条判据。
     *
     * <p>⚠️ 主人**不在线**时返回 {@code false} 会被误解为"放开了" ⇒ 这里刻意返回 {@code true}
     * （与 {@link #verifyController} 同一个谨慎：下线不等于放开）。
     */
    private boolean controllerIsPiloting() {
        UUID ownerId = controllerId();
        if (ownerId == null || !(level() instanceof ServerLevel server)) {
            return true;
        }
        Player owner = server.getServer().getPlayerList().getPlayer(ownerId);
        if (owner == null) {
            return true;
        }
        ItemStack held = owner.getMainHandItem();
        return MonitorItem.isPiloting(held) && getUUID().equals(MonitorItem.linkedDrone(held));
    }

    @Nullable
    private UUID controllerId() {
        String id = getController();
        if (id == null) {
            return null;
        }
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 能被玩家和弹丸打到（不然没法把它打下来）。 */
    @Override
    public boolean isPickable() {
        return true;
    }

    public double getDurability() {
        return entityData.get(DURABILITY);
    }

    private void setDurability(double value) {
        entityData.set(DURABILITY, (float) Math.max(0.0, value));
    }

    @Nullable
    public String getController() {
        String id = entityData.get(CONTROLLER);
        return id.isEmpty() ? null : id;
    }

    /** 归属者的显示名（没有归属时 {@code null}）。 */
    @Nullable
    public String getControllerName() {
        String name = entityData.get(CONTROLLER_NAME);
        return name.isEmpty() ? null : name;
    }

    /**
     * 绑定 / 改绑：记下是谁在用它。
     *
     * <p>⚠️ 这一步是<b>必须</b>的 —— 绑定关系本身只存在遥控器物品上，
     * 无人机这一侧只有它知道"该在消失时通知谁"（见 {@link #forgetMe()}）。
     * 少了这一步，无人机被摧毁时就谁也通知不到，那台遥控器会永远卡在"已绑定"上
     * （2026-09-23 用户撞到的那个死结）。
     */
    public void setController(Player player) {
        entityData.set(CONTROLLER, player.getStringUUID());
        entityData.set(CONTROLLER_NAME, player.getDisplayName().getString());
    }

    /** 解绑：归属一起放开。**留着旧名字会让名牌继续说假话**。 */
    public void clearController() {
        entityData.set(CONTROLLER, "");
        entityData.set(CONTROLLER_NAME, "");
    }

    // ------------------------------------------------------------------
    // 用监视器右键：绑定 / 解绑；空手潜行右键：收起
    // ------------------------------------------------------------------

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (stack.getItem() instanceof MonitorItem monitor) {
            if (level().isClientSide) {
                return InteractionResult.SUCCESS;   // 真正的改动全在服务端
            }
            return monitor.toggleLink(player, stack, this)
                    ? InteractionResult.CONSUME
                    : InteractionResult.FAIL;
        }
        if (player.isSecondaryUseActive() && stack.isEmpty()) {
            if (!level().isClientSide) {
                player.displayClientMessage(Component.translatable("entity.create_ballistics.drone.recovered")
                        .withStyle(ChatFormatting.GRAY), true);
                // ⭐ 回收成**掉落物**（照卓越前线：潜行空手右键把无人机收回来，东西不丢）。
                //    此前这里只有一句提示 + discard()，无人机是**凭空消失**的。
                spawnAtLocation(new ItemStack(ModItems.DRONE.get()));
                // 收起来了同样要放开遥控器（照卓越前线：回收时把所有绑它的遥控器解绑）
                forgetMe();
                discard();
            }
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        return super.interact(player, hand);
    }

    // ------------------------------------------------------------------
    // 存档
    // ------------------------------------------------------------------

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("Durability")) {
            setDurability(tag.getDouble("Durability"));
        }
        entityData.set(CONTROLLER, tag.getString("Controller"));
        entityData.set(CONTROLLER_NAME, tag.getString("ControllerName"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putDouble("Durability", getDurability());
        String controller = getController();
        if (controller != null) {
            tag.putString("Controller", controller);
        }
        String controllerName = getControllerName();
        if (controllerName != null) {
            tag.putString("ControllerName", controllerName);
        }
    }
}
