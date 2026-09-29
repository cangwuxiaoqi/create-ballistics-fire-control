package net.createballistics.cbc;

import java.util.Locale;
import java.util.UUID;
import net.createballistics.CreateBallistics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * <b>实弹标定（自适应）：从每一发的落点反推"解算器的射程比例误差"，并把它学到手。</b>
 *
 * <p>⚠️ 这<b>不是诊断代码</b> —— 它是产品功能（用户 2026-09-26 拍板"让它自己学"）。
 *
 * <h2>它补的是什么</h2>
 *
 * 飞艇上打静止目标时，落点会**沿行进方向偏前**，量级 ∝ 射程：
 * 130 格偏 7.3、180 格偏 10.4 ⇒ 比例稳定在 **≈5%**。逐项排查（都写在交接文件里）已排除：
 * 位置时序差、炮塔伺服滞后、`aimDrift` 前馈、炮弹初速 —— 剩下的唯一解释是
 * **解算器的弹道模型把射程算短了约 5%**（模型的"某个角度打多远"比实际短）。
 *
 * <p>本类把那个比例记成 {@link #k}（"解算射程的相对误差"），每打一发就用落点修正一点：
 * <pre>
 *   落点沿行进方向的偏移 = (k_真 − k_当前) × 解算射程          ← 实测关系（四轮实弹）
 *   还差多少 deltaK      = 偏移 ÷ 解算射程
 *   k                    = k + deltaK × LEARNING_RATE         // 约十发收敛到 k_真
 * </pre>
 * ⚠️⚠️ <b>注意 `deltaK` 是"还差多少"，不是"应该是多少"</b> —— 落点上量到的是
 * **我们当前修正之后剩下的残差**，所以必须**累加**（`k + deltaK`）。
 * 我第一版写成 `k += (k_观测 − k) × 速率`（把它当成了目标值）—— 那个式子会把 k 拖向 0，
 * 也就是"越学越把提前量学没了" ✗（落地就露馅，但没露馅之前很难看出来）。
 *
 * <p>上层拿它算提前量：{@code 提前量(tick) = k × 解算射程 ÷ 载具水平速度}
 * —— 因为"模型少算了 {@code k × 射程} 格"就得把枢轴前移同样多格。
 *
 * <h2>为什么学 k 而不是直接学提前量</h2>
 *
 * {@code k} 是**弹道模型自己的属性** ⇒ 与弹种、装药、平台速度、射程**都无关** ⇒
 * 换炮换弹乃至换弹道环境（GB ↔ 原版 CBC）之后它照样有意义；
 * 而"提前量（tick）"是 k 与"射程 ÷ 速度"的合成量，直接学它就会把平台速度揉进去。
 *
 * <h2>三道防呆（实弹里见过各种离谱样本）</h2>
 * <ol>
 *   <li><b>速度太低不学</b>：提前量本身没意义，k 根本不可观测（静止平台就是对照组）；</li>
 *   <li><b>实际飞行时间与解算的差太多不学</b>：多半是打到山/没炸/被挡住 ⇒ 落点不是"弹道落点"；</li>
 *   <li><b>k_观测 超出 {@link #K_CLAMP} 不学</b>（再夹一次）：那种样本一定是别的问题；
 *       而且 {@link #LEARNING_RATE} 只取四分之一 ⇒ 单发异常最多把 k 带偏一点点，几发就拉回来。</li>
 * </ol>
 * 每发都打一行 {@code [射程标定]} 日志（学了什么、k 从多少到多少）—— <b>方便验收</b>。
 */
public final class ShotCalibration {

    /** 日志前缀（grep 它）。 */
    public static final String PREFIX = "[射程标定]";

    /**
     * k 的初值 —— 四轮实弹标定出来的 ≈0.05（飞艇巡航 6.3~7.3 格/tick，静止目标）。
     * 没打过之前先用它，打了之后它会被学到的值取代。
     */
    private static final double DEFAULT_K = 0.050;
    /** k 的硬夹取范围（超出就是异常样本，不学）。 */
    private static final double K_CLAMP = 0.15;
    /** 学习率：每次取观测值的这个比例（0.25 ⇒ 约十发收敛）。 */
    private static final double LEARNING_RATE = 0.25;
    /** 载具水平速度低于它就不学（k 不可观测）。 */
    private static final double MIN_SPEED = 0.05;
    /** 实际飞行时间与解算飞行时间的相对差超过它就不学。 */
    private static final double AIRTIME_TOLERANCE = 0.4;
    /**
     * 前几发**每一发都打日志**（标定期，方便验收）；之后只在"真的学到东西"时才打。
     *
     * <p>⚠️ 为什么要有这个降噪：这是一台炮**每一发**都会跑到的路径，几十门炮同开时
     * 每发一行会把日志刷爆；而标定稳定之后（修正量 < {@link #QUIET_DELTA}）那些行
     * 全是"k 没变"的废话。**但"这一发为什么不学"永远打**（那是异常，值得看见）。
     */
    private static final int VERBOSE_SHOTS = 10;
    /** 修正量小于它就不再逐发打日志（标定已经稳定）。 */
    private static final double QUIET_DELTA = 0.002;
    /** 炮弹"停住"多少 tick 判定落地。 */
    private static final int LANDED_STILL_TICKS = 3;

    /**
     * 判定"落地"时允许的<b>目标高度差</b>（格）。见 {@code tick} 里那段说明：
     * 光看"停住 + 在下降"会把**近乎垂直的高抛弹的顶点**误判成落点。
     */
    private static final double LANDED_HEIGHT_TOLERANCE = 3.0;
    /** 跟踪超时（tick）—— 炮弹不消失就永远不结账。 */
    private static final int TRACK_TIMEOUT_TICKS = 20 * 60;

    /** 学到的射程比例误差（= 解算射程的相对误差）。 */
    private double k = DEFAULT_K;
    /** 已经学过多少发（日志与"信不信它"都用得上）。 */
    private int learnedShots;

    // ---- 正在跟的那一发 ----

    @Nullable
    private UUID shellId;
    private Vec3 lastPos = Vec3.ZERO;
    private long fireTick = -1L;
    /**
     * <b>最后一次真正看到弹丸</b>的游戏刻。
     *
     * <p>⚠️⚠️ 结账时的飞行时间必须用它，**不能用"现在"** —— "查不到弹丸"那条路要先宽限
     * {@link #MISSING_GRACE_TICKS}(100) tick 才结账，而炮弹撞地后实体被移除正是**最常见**的结局
     * ⇒ 拿"现在"当落点时刻会把**每一发**都算长 100 tick。
     * <p>⚠️ 2026-09-27 我自己就栽在这上面：当时按"现在"结账，算出的飞行时间**每一发都长 100 tick**
     * （差 103 ≈ {@code MISSING_GRACE_TICKS}）⇒ 一度误判成"实测比解算长 45%"、还怀疑过阻力模型。
     * ⇒ **这个数只能取"最后一次看到它的那一刻"**，那条守卫（{@code guardReason} 的飞行时间比对）
     * 才不会被这个宽限期污染。
     */
    private long lastSeenTick = -1L;
    private int stillTicks;
    private Vec3 target = Vec3.ZERO;
    /** 解算用的射程（枢轴 → 目标，水平）—— 用来把"偏移多少格"换算成 k。 */
    private double solvedRange;
    /** 击发那一刻的载具**水平**速度矢量（沿它投影才是"行进方向的偏移"）。 */
    private Vec3 shipVelH = Vec3.ZERO;
    /** 解算的飞行时间（tick），用来做"这一发靠不靠谱"的守卫。 */
    private double solvedAirTime;

    /** 学到的 k（上层算提前量用）。 */
    public double k() {
        return k;
    }

    /**
     * "查不到弹丸"的宽限 tick 数 —— 见 {@code tick()} 里那段说明。
     *
     * <p>取 100（5 秒）：弹丸的区块被卸载/被搬走时 {@code level.getEntity()} 会查不到它，
     * 而它随时可能回来。宽限一下就不会把"暂时看不到"当成"这一发结束了"，
     * 从而避免拿一个假的落点去喂 k 的学习。
     */
    private static final int MISSING_GRACE_TICKS = 100;

    /** 连续查不到弹丸的 tick 数。 */
    private int missingTicks;

    /**
     * ⭐⭐ <b>给这一发"保活"</b> —— 见 {@link ShellChunkKeeper}：卡顿会让 RPL 的区块票过期、
     * 弹丸停止 tick，而 RPL 续票恰恰要靠弹丸自己 tick ⇒ 死锁。
     * 这里在飞行期间显式挂一张**不过期的、实体 tick 级**的票，卡顿多久都能接着飞。
     */
    private final ShellChunkKeeper keeper = new ShellChunkKeeper();

    /**
     * 记下发出去的那一发（在击发那一 tick 调）。
     *
     * @param pivot      **未前移**的枢轴（世界）—— 这才是炮弹真实出生点附近的位置
     * @param shipVel    击发那一刻的载具速度（世界；本类只用它的水平分量）
     * @param solvedAirTime 解算给出的飞行时间（tick）
     */
    public void recordShot(ServerLevel level, Vec3 pivot, Vec3 target, Vec3 shipVel,
                           double solvedAirTime, Entity shell) {
        this.shellId = shell.getUUID();
        this.lastPos = shell.position();
        this.fireTick = level.getGameTime();
        this.lastSeenTick = this.fireTick;
        this.stillTicks = 0;
        this.target = target;
        this.shipVelH = new Vec3(shipVel.x, 0.0, shipVel.z);
        this.solvedAirTime = solvedAirTime;
        this.solvedRange = Math.hypot(target.x - pivot.x, target.z - pivot.z);
        this.missingTicks = 0;
    }

    /** 每 tick 推一下跟踪；炮弹落地/消失/超时 ⇒ 结账、按上面的守卫决定学不学。 */
    public void tick(ServerLevel level) {
        if (shellId == null) {
            return;
        }
        long now = level.getGameTime();
        Entity e = level.getEntity(shellId);
        boolean gone = e == null || e.isRemoved() || !e.isAlive();
        double flightTicks = now - fireTick;
        if (gone) {
            // ⚠️⚠️ **"查不到"不等于"这一发结束了"**：区块被卸载、弹丸被搬去别的维度时
            //    `level.getEntity()` 都会查不到它，而它随时可能回来 —— 弹丸是被存档保存着的。
            //    （2026-09-26 大整合包实测：①卡顿把区块冲掉、②弹丸飞出大气层被"北极星"
            //      搬进太空维度，两种都会表现为"查不到"，而修法完全不同。）
            // ⇒ 先宽限 {@link #MISSING_GRACE_TICKS} tick 再结账，免得拿一个假落点去喂 k 的学习。
            missingTicks++;
            if (missingTicks <= MISSING_GRACE_TICKS && flightTicks < TRACK_TIMEOUT_TICKS) {
                return;
            }
            shellId = null;
            keeper.release(level);   // 这一发到此为止 ⇒ 把保活票放掉
            // ⚠️⚠️ 飞行时间用**最后一次真正看到它**的时刻，不是 `now` ——
            //    这条路前面宽限了 MISSING_GRACE_TICKS(100) tick，用 now 会把每一发都算长 100 tick
            //    （见 lastSeenTick 的说明：2026-09-27 我自己栽过）。
            learn(level, lastPos, Math.max(1L, lastSeenTick - fireTick), true);
            return;
        }
        missingTicks = 0;
        lastSeenTick = now;   // ⭐ 结账时的飞行时间以它为基准（见 lastSeenTick 的说明）
        keeper.keep(level, e);                  // ⭐ 保活：让这颗弹丸一直待在"实体 tick"级的区块里
        {
            lastPos = e.position();
            // ⚠️⚠️ <b>"落地"的判据必须是"贴到目标高度附近"</b>，不能只看"停住了"。
            //
            // 两次实测的教训（2026-09-26）：
            //   ① 只判"速度≈0" ⇒ 近乎垂直的高抛弹在**顶点**速度也≈0 ⇒ 顶点被当成落点；
            //   ② 加上"在下降"也没用（我试过）⇒ 顶点刚过、vy 刚变负的那几 tick 同样满足
            //      —— 而那颗弹接下来还要从 2300 格高往下掉二十秒。
            // ⇒ 只有"贴近目标高度"（打进地面/目标附近）或"弹丸消失"才算结账。
            //   其余的落点都不是"弹道落点"，拿来学 k 只会被守卫剔掉、还误导读日志的人。
            boolean nearTargetHeight = Math.abs(e.getY() - target.y) <= LANDED_HEIGHT_TOLERANCE;
            if (e.getDeltaMovement().lengthSqr() < 1.0E-4 && nearTargetHeight) {
                stillTicks++;
            } else {
                stillTicks = 0;
            }
            if (stillTicks < LANDED_STILL_TICKS && flightTicks < TRACK_TIMEOUT_TICKS) {
                return;
            }
        }
        shellId = null;
        keeper.release(level);   // 落地/结账 ⇒ 放掉保活票（不在世界里留永久加载区）
        learn(level, lastPos, flightTicks, gone);
    }

    /** 落点结账 —— 三道守卫都过了才更新 {@link #k}。 */
    private void learn(ServerLevel level, Vec3 land, double flightTicks, boolean gone) {
        String why = guardReason(flightTicks);
        if (why != null) {
            line("这一发不学（%s）：落点=%s 飞行 %.0f tick（解算 %.0f）解算射程 %.1f 格 | k 仍是 %.4f（已学 %d 发）",
                    why, vec(land), flightTicks, solvedAirTime, solvedRange, k, learnedShots);
            return;
        }
        Vec3 vHat = shipVelH.normalize();
        double along = land.subtract(target).dot(vHat);      // 沿行进方向的落点偏移（正 = 打过了头仍然偏前）
        // ⚠️ 这是"**还差多少**"（当前修正之后的残差），不是"应该是多少" ⇒ 下面要**累加**
        double deltaK = along / solvedRange;
        if (Math.abs(deltaK) > K_CLAMP) {
            line("这一发不学（残差 %.4f 超出 ±%.2f，多半打到别的东西）：落点=%s 偏移 %+.2f 格 | k 仍是 %.4f",
                    deltaK, K_CLAMP, vec(land), along, k);
            return;
        }
        double before = k;
        k += deltaK * LEARNING_RATE;
        k = Math.max(-K_CLAMP, Math.min(K_CLAMP, k));
        learnedShots++;
        // 标定期逐发打；稳定之后只在"这次真的动了 k"时才打（见 VERBOSE_SHOTS / QUIET_DELTA）
        if (learnedShots > VERBOSE_SHOTS && Math.abs(k - before) < QUIET_DELTA) {
            return;
        }
        line("学到一发：落点偏移 %+.2f 格 ÷ 射程 %.1f 格 ⇒ **还差 %.4f，k: %.4f → %.4f**"
                        + "（第 %d 发，%s）| 下一发枢轴前移 = %.2f 格（k × 射程）",
                along, solvedRange, deltaK, before, k, learnedShots,
                gone ? "消失" : "停住", k * solvedRange);
    }

    /** 三道守卫；返回 null 表示"可以学"，否则返回不学的理由。 */
    @Nullable
    private String guardReason(double flightTicks) {
        if (shipVelH.length() < MIN_SPEED) {
            return "载具几乎没动，k 不可观测";
        }
        if (solvedRange <= 1.0) {
            return "射程太短（解算射程 " + String.format(Locale.ROOT, "%.1f", solvedRange) + " 格）";
        }
        if (solvedAirTime <= 0.0) {
            return "没有解算飞行时间";
        }
        if (Math.abs(flightTicks - solvedAirTime) > solvedAirTime * AIRTIME_TOLERANCE) {
            return String.format(Locale.ROOT, "飞行时间对不上（实际 %.0f / 解算 %.0f tick）",
                    flightTicks, solvedAirTime);
        }
        return null;
    }

    // ------------------------------------------------------------------ 存档

    /** 存进节点 NBT —— 学到的值要跨存档、跨重启留下来。 */
    public void save(CompoundTag tag) {
        tag.putDouble("RangeScaleK", k);
        tag.putInt("RangeScaleShots", learnedShots);
    }

    /** 从节点 NBT 读回；没有（老存档 / 新节点）就用默认值。 */
    public void load(CompoundTag tag) {
        if (tag.contains("RangeScaleK")) {
            double v = tag.getDouble("RangeScaleK");
            if (Double.isFinite(v) && Math.abs(v) <= K_CLAMP) {
                k = v;
            }
        }
        learnedShots = tag.getInt("RangeScaleShots");
    }

    private static String vec(Vec3 v) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }

    private static void line(String fmt, Object... args) {
        try {
            CreateBallistics.LOGGER.info(PREFIX + " " + String.format(Locale.ROOT, fmt, args));
        } catch (Throwable t) {
            // ⚠️ 格式化失败不能静默（否则"没输出"会被误读成"这件事没发生"）
            CreateBallistics.LOGGER.warn(PREFIX + " ⚠️ 日志格式化失败: [{}] -> {}", fmt, t.toString());
        }
    }
}
