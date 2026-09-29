package net.createballistics.ballistic;

import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * 弹道求解器。
 *
 * <h2>核心思路：用"两个飞行时间的差"当目标函数</h2>
 *
 * 不用"落点离目标多远"当目标函数（那样必须做带地形的碰撞模拟，而且"打近了"会让
 * 目标函数在边界上突然失效），而是让<b>水平方向与垂直方向各自算出的飞行时间相等</b>：
 *
 * <pre>
 *   水平：等比级数的解析解，走完目标水平距离需要多少 tick
 *   垂直：逐 tick 积分，下降到目标高度需要多少 tick
 *   残差 = |水平时间 − 垂直时间|，为 0 即为真解
 * </pre>
 *
 * 好处：目标函数连续光滑、不需要地形、一次积分同时得到低伸与高抛两解。
 *
 * <h2>求解分两级</h2>
 * <ol>
 *   <li><b>粗扫</b>：从 minPitch 到 maxPitch 逐 1° 采样，找残差曲线的两个局部极小
 *       （第一个 = 低伸，最后一个 = 高抛）</li>
 *   <li><b>细化</b>：围绕种子做 6 轮局部搜索，第 i 轮窗口 ±10⁻ⁱ 度、采 21 个点，
 *       最终精度约 10⁻⁶ 度</li>
 * </ol>
 *
 * <p>每个装药数都跑一遍，最后在低伸组/高抛组里各挑一个残差最小的。
 */
public final class BallisticSolver {

    // ---- 精度优先：本 mod 的调用频率很低（只在目标/参数变化时解算一次），
    //      所以刻意把扫描与细化调得比"够用"更细。要提速就调这三个常量。
    /** 粗扫步长（度）。 */
    public static final double COARSE_STEP_DEG = 0.25;
    /** 细化轮数：第 i 轮窗口 ±10⁻ⁱ。 */
    public static final int REFINE_ITERATIONS = 8;
    /** 每轮采样点数（含两端）。 */
    public static final int REFINE_SAMPLES = 41;
    /**
     * 解算结果的终检门槛：回环模拟的落点与目标的最大允许水平偏差（格）。
     *
     * <p>残差目标函数在"目标超出射程"时仍可能有一个极小值（两条时间曲线靠得最近的那点），
     * 所以必须再独立模拟一遍来确认。
     */
    public static final double ACCEPT_MISS_BLOCKS = 0.1;
    /** 低伸解与高抛解靠得比这个还近时，视为同一个解（射程接近极限时两解会流合）。 */
    public static final double ARC_DEDUPE_DEG = 0.01;
    /**
     * {@link #maxRange} 扫仰角的步长（度）。
     *
     * <p>取 0.5 而不是像解算那样 0.25：最大射程附近射程对仰角的变化很平缓（顶点附近导数为 0），
     * 粗一档完全够，而 {@code maxRange} 要扫上百个角度，省一半时间。
     */
    public static final double MAX_RANGE_STEP_DEG = 0.5;

    private BallisticSolver() {
    }

    /**
     * 解算。
     *
     * @param spec    炮的参数
     * @param cannonX 炮座枢轴坐标（水平方向以它为基准）
     * @param cannonY 炮座枢轴的 Y
     * @param cannonZ 炮座枢轴的 Z
     * @param targetX 目标 X
     * @param targetY 目标 Y
     * @param targetZ 目标 Z
     * @return 0~2 个解；低伸解在前（如果存在）。解不出来时返回空列表，<b>不抛异常</b>
     */
    public static List<FiringSolution> solve(CannonSpec spec,
                                             double cannonX, double cannonY, double cannonZ,
                                             double targetX, double targetY, double targetZ) {

        final double horizontalDist = Math.hypot(targetX - cannonX, targetZ - cannonZ);
        final double yaw = yawDegrees(cannonX, cannonZ, targetX, targetZ);

        // 每个装药数的候选中选出各自弧线的最优
        Candidate bestFlat = null;
        Candidate bestHigh = null;

        for (int charges = spec.minCharges(); charges <= spec.maxCharges(); charges++) {
            final double velocity = spec.velocityFor(charges);

            List<Candidate> coarse = coarseScan(spec, velocity, charges, horizontalDist, cannonY, targetY);
            if (coarse.isEmpty()) {
                continue;
            }

            Candidate flatSeed = firstLocalMinimum(coarse);
            Candidate highSeed = lastLocalMinimum(coarse);

            Candidate flat = refine(flatSeed, spec, velocity, charges, horizontalDist, cannonY, targetY);
            Candidate high = refine(highSeed, spec, velocity, charges, horizontalDist, cannonY, targetY);

            if (flat != null && (bestFlat == null || flat.quality() > bestFlat.quality())) {
                bestFlat = flat;
            }
            if (high != null && (bestHigh == null || high.quality() > bestHigh.quality())) {
                bestHigh = high;
            }
        }

        List<FiringSolution> out = new ArrayList<>(2);
        if (bestFlat != null) {
            out.add(bestFlat.toSolution(yaw, FiringSolution.Arc.FLAT));
        }
        // 残差曲线只有一个局部极小时，低伸与高抛会收敛到同一点 —— 不要重复报两个解。
        if (bestHigh != null && (bestFlat == null
                || Math.abs(bestHigh.pitch() - bestFlat.pitch()) > ARC_DEDUPE_DEG)) {
            out.add(bestHigh.toSolution(yaw, FiringSolution.Arc.HIGH));
        }

        return out;
    }

    /**
     * ⭐ <b>廉价增量重解</b> —— 以上一次的解为起点做几步<b>牛顿迭代</b>，<b>不重扫角度</b>。
     *
     * <h2>为什么需要它</h2>
     *
     * 航空学载具在动时，炮口的世界坐标<b>每 tick 都在变</b>，而诸元只在"目标/参数变了"时才算过 ——
     * 实测：船速 6.73 格/tick、解算间隔 20 tick ⇒ 诸元过期造成 <b>135 格</b>漂移。
     * 唯一的治法是<b>每 tick 重解</b>，但 {@link #solve} 要走
     * {@code coarseScan}（粗扫）+ {@code refine}（8 轮 × 41 点 ≈ 328 次弹道积分）⇒
     * 40 门炮每 tick 全量重解要多花几十毫秒，<b>根本跑不动</b>。
     *
     * <p>增量解算只需要 <b>5~9 次</b>弹道积分（便宜两个数量级）：目标的<b>方位/距离每 tick 只挪几格</b>，
     * 上一 tick 的解就是极好的初值 ⇒ 牛顿两步就收敛。
     *
     * <h2>什么时候会失败（返回 null）</h2>
     *
     * 目标跑出射界、超出射程、被夹到端点后仍不收敛、或终检（独立模拟）判定落点偏差 &gt;
     * {@link #ACCEPT_MISS_BLOCKS} ⇒ <b>返回 {@code null}，由调用方退回 {@link #solve}</b>。
     * 这样做是刻意的：**增量路径只负责"跟得住"，不负责"发现新情况"** ——
     * 弹道被挡/炮口被堵/目标太近这些判断都还留在完整解算那条路上。
     *
     * <p>⚠️ 装药数沿用上一次的解（跟踪期间装填不会变）；换药会走完整解算那条路。
     *
     * @param seed 上一次的解（提供初值仰角、装药数、弧线类型）
     * @return 细化后的解；失败返回 {@code null}
     */
    @Nullable
    public static FiringSolution solveNear(CannonSpec spec, FiringSolution seed,
                                           double cannonX, double cannonY, double cannonZ,
                                           double targetX, double targetY, double targetZ) {
        final int charges = seed.charges();
        final double velocity = spec.velocityFor(charges);
        if (!(velocity > 0.0)) {
            return null;
        }
        final double distance = Math.hypot(targetX - cannonX, targetZ - cannonZ);
        final double yaw = yawDegrees(cannonX, cannonZ, targetX, targetZ);
        final double minPitch = spec.minPitch();
        final double maxPitch = spec.maxPitch();
        double pitch = clamp(seed.pitch(), minPitch, maxPitch);

        for (int step = 0; step < NEAR_STEPS; step++) {
            final double residual = TrajectorySimulator.residual(pitch, velocity, spec.barrelLength(),
                    distance, cannonY, targetY, spec.ballistics());
            if (!Double.isFinite(residual)) {
                return null;
            }
            if (Math.abs(residual) <= NEAR_RESIDUAL_TOL) {
                break;
            }
            // 数值微分：步长不能太小（会被浮点噪声淹），也不能太大（牛顿会走偏）。
            final double probePitch = clamp(pitch + NEAR_DERIV_DEG, minPitch, maxPitch);
            if (probePitch == pitch) {
                break;      // 已经卡在射界端点上，牛顿没有空间可用
            }
            final double probeResidual = TrajectorySimulator.residual(probePitch, velocity,
                    spec.barrelLength(), distance, cannonY, targetY, spec.ballistics());
            if (!Double.isFinite(probeResidual)) {
                return null;
            }
            final double slope = (probeResidual - residual) / (probePitch - pitch);
            if (!Double.isFinite(slope) || Math.abs(slope) < 1.0E-9) {
                break;
            }
            final double next = clamp(pitch - residual / slope, minPitch, maxPitch);
            if (Math.abs(next - pitch) < 1.0E-6) {
                break;
            }
            pitch = next;
        }

        final Candidate candidate = evaluate(pitch, spec, velocity, charges, distance, cannonY, targetY);
        if (candidate == null) {
            return null;
        }
        final Candidate verified = verify(candidate, spec, distance, cannonY, targetY);
        return verified == null ? null : verified.toSolution(yaw, seed.arc());
    }

    /** 牛顿步数上限。每步 1~2 次弹道积分 ⇒ 全程 5~9 次，比 {@link #refine} 的 328 次便宜两个数量级。 */
    private static final int NEAR_STEPS = 4;

    /** 数值微分步长（度）。 */
    private static final double NEAR_DERIV_DEG = 0.05;

    /** 残差已足够小（tick）就提前收手 —— 反正后面还有一次独立模拟的终检。 */
    private static final double NEAR_RESIDUAL_TOL = 1.0E-3;

    /** 本包不依赖 Minecraft ⇒ 自己夹取，不引 {@code Mth}。 */
    private static double clamp(double value, double min, double max) {
        return value < min ? min : (value > max ? max : value);
    }

    /**
     * {@link #maxRange} 的结果：射程，以及<b>达成它的那个仰角</b>。
     *
     * <p>仰角要一起给出来，是因为玩家看到「极限射程 7000 多格」时的第一反应是
     * "这是不是靠超过俯仰上限的角度硬凑出来的"。把最佳仰角摆出来，一眼就能对着射界核对 ——
     * 顺带也能让人看懂为什么打 95% 极限射程只需要三十几度（射程曲线的顶点在四十几度）。
     *
     * @param range    水平射程（格）
     * @param pitchDeg 达成该射程的仰角（度）。射程为 0 时无意义
     */
    public record RangeLimit(double range, double pitchDeg) {

        /** 算不出来时的空结果。 */
        public static final RangeLimit NONE = new RangeLimit(0.0, 0.0);
    }

    /**
     * 这门炮在这套装填下的<b>水平极限射程</b> —— 在射界内扫仰角，取落回发射高度时飞得最远的那一发。
     *
     * <p>用于护目镜显示"这门炮能打多远"。是<b>大致值</b>：扫描步长 {@link #MAX_RANGE_STEP_DEG} 度，
     * 且按「发射高度 → 同高度」定义（真实地面会把炮口架高 2～3 格，实际射程略远一点）。
     *
     * <p><b>扫描范围严格卡在 {@code spec.maxPitch()} 以内</b> —— 最佳角若超过俯仰上限，
     * 报出来的必须是"上限内打得最远的那一发"，不能是"抬到天上才够得着"的角度。
     *
     * <p>⚠️ 只在炮的参数变化时调用，<b>不要放进每 40 tick 的探针刷新里</b> —— 它比单次解算贵得多。
     */
    public static RangeLimit maxRange(CannonSpec spec) {
        double best = 0.0;
        double bestDeg = 0.0;
        for (int charges = spec.minCharges(); charges <= spec.maxCharges(); charges++) {
            final double velocity = spec.velocityFor(charges);
            if (!(velocity > 0.0)) {
                continue;
            }
            // 仰角只扫到 maxPitch；minPitch 侧射程只会更短，不必扫。
            for (double deg = 0.0; deg <= spec.maxPitch() + 1.0E-9; deg += MAX_RANGE_STEP_DEG) {
                TrajectorySimulator.Flight flight = TrajectorySimulator.simulate(
                        deg, velocity, spec.barrelLength(), 0.0, 0.0, spec.ballistics());
                if (flight != null && flight.horizontalRange() > best) {
                    best = flight.horizontalRange();
                    bestDeg = deg;
                }
            }
        }
        return best > 0.0 ? new RangeLimit(best, bestDeg) : RangeLimit.NONE;
    }

    // ------------------------------------------------------------------ 内部

    /** 一个候选解：仰角 + 残差 + 飞行时间。 */
    private record Candidate(double pitch, int charges, double residual, double airTime, double velocity) {

        /** 廉价的质量指标：跨越"够得着/够不着"边界时残差会跳变，用它筛掉坏解。 */
        double quality() {
            if (!(airTime > 0) || !Double.isFinite(residual)) {
                return Double.NEGATIVE_INFINITY;
            }
            return 1.0 - residual / airTime;
        }

        FiringSolution toSolution(double yaw, FiringSolution.Arc arc) {
            return new FiringSolution(pitch, yaw, charges, airTime, residual, arc);
        }
    }

    private static List<Candidate> coarseScan(CannonSpec spec, double velocity, int charges,
                                              double horizontalDist, double cannonY, double targetY) {
        List<Candidate> samples = new ArrayList<>();
        // 按 COARSE_STEP_DEG 扫，而不是整天按整数度扫 —— 局部极小的定位精度直接取决于这一步。
        final double last = spec.maxPitch();
        for (double deg = spec.minPitch(); deg <= last + 1.0E-9; deg += COARSE_STEP_DEG) {
            Candidate c = evaluate(deg, spec, velocity, charges, horizontalDist, cannonY, targetY);
            if (c != null) {
                samples.add(c);
            }
        }
        return samples;
    }

    /** 求一个候选；不可达返回 null。 */
    private static Candidate evaluate(double pitch, CannonSpec spec, double velocity, int charges,
                                      double horizontalDist, double cannonY, double targetY) {
        if (!Double.isFinite(pitch) || pitch < spec.minPitch() || pitch > spec.maxPitch()) {
            return null;
        }
        final double residual = TrajectorySimulator.residual(
                pitch, velocity, spec.barrelLength(), horizontalDist, cannonY, targetY, spec.ballistics());
        if (!Double.isFinite(residual)) {
            return null;
        }
        // ⚠️⚠️ 必须用 airTimeToRange（离目标近的那个穿越点），**不是** timeToHeight（下降段）。
        //    低伸解在目标高于炮口时是从上升段穿过目标的，用下降段会把飞行时间算成好几倍
        //    ⇒ TOT 击发错峰全错（2026-09-27 用户实测：19 发低伸的比值 0.20~0.58）。
        final double airTime = TrajectorySimulator.airTimeToRange(
                pitch, velocity, spec.barrelLength(), cannonY, targetY, horizontalDist,
                spec.ballistics());
        if (!Double.isFinite(airTime)) {
            return null;
        }
        return new Candidate(pitch, charges, residual, airTime, velocity);
    }

    /**
     * 残差曲线的<b>第一个</b>局部极小 = 低伸解。
     *
     * <p>仰角从小往上扫，残差先降后升，第一次由降转升的那个点就是低伸解。
     */
    private static Candidate firstLocalMinimum(List<Candidate> s) {
        for (int i = 1; i < s.size(); i++) {
            if (s.get(i - 1).residual() < s.get(i).residual()) {
                return s.get(i - 1);
            }
        }
        return s.get(0);
    }

    /**
     * 残差曲线的<b>最后一个</b>局部极小 = 高抛解。
     *
     * <p>从高往低扫，最后那个由降转升的点。
     */
    private static Candidate lastLocalMinimum(List<Candidate> s) {
        for (int i = s.size() - 2; i >= 0; i--) {
            if (s.get(i).residual() > s.get(i + 1).residual()) {
                return s.get(i + 1);
            }
        }
        return s.get(s.size() - 1);
    }

    /**
     * 细化：围绕种子逐级收窄窗口，第 i 轮在 ±10⁻ⁱ 度内采 {@link #REFINE_SAMPLES} 个点。
     *
     * <p>每轮窗口缩到上一轮的 1/10，所以 6 轮后步长约 10⁻⁶ 度。
     */
    private static Candidate refine(Candidate seed, CannonSpec spec, double velocity, int charges,
                                    double horizontalDist, double cannonY, double targetY) {
        double pitch = seed.pitch();
        Candidate best = seed;

        for (int i = 0; i < REFINE_ITERATIONS; i++) {
            final double half = Math.pow(10.0, -i);
            final double lo = Math.max(pitch - half, spec.minPitch());
            final double hi = Math.min(pitch + half, spec.maxPitch());
            if (!(hi > lo)) {
                break;
            }
            final double step = (hi - lo) / (REFINE_SAMPLES - 1);

            double bestPitch = best.pitch();
            double bestResidual = best.residual();

            for (int k = 0; k < REFINE_SAMPLES; k++) {
                final double p = lo + step * k;
                final double r = TrajectorySimulator.residual(
                        p, velocity, spec.barrelLength(), horizontalDist, cannonY, targetY, spec.ballistics());
                if (Double.isFinite(r) && r < bestResidual) {
                    bestResidual = r;
                    bestPitch = p;
                }
            }
            pitch = bestPitch;
            Candidate evaluated = evaluate(bestPitch, spec, velocity, charges, horizontalDist, cannonY, targetY);
            if (evaluated == null) {
                break;
            }
            best = evaluated;
        }
        return verify(best, spec, horizontalDist, cannonY, targetY);
    }

    /**
     * 终检：用一次<b>独立</b>的弹道模拟确认这个解真的能落在目标上。
     *
     * <p>残差是一个"两个时间估计之差"的代理指标，它的极小值在目标超出射程时依然存在
     * （两条时间曲线靠得最近的那点，残差可能是几十 tick 而非 0）。只靠阈值筛不够稳，
     * 直接模拟一遍量落点偏差才是可靠判据。
     */
    private static Candidate verify(Candidate c, CannonSpec spec, double targetDistance,
                                    double cannonY, double targetY) {
        TrajectorySimulator.Flight flight = TrajectorySimulator.simulate(
                c.pitch(), c.velocity(), spec.barrelLength(), cannonY, targetY, spec.ballistics());
        if (flight == null) {
            return null;
        }
        // ⚠️⚠️ <b>两条都算命中</b>（2026-09-26 大整合包实测定案）：
        //   ① 下降段落回目标高度时正好在目标距离上 —— 目标在炮口下方/同高时的低伸解；
        //   ② **上升段穿过目标高度时正好在目标距离上** —— 目标比炮口高时的低伸解
        //      （炮弹"穿过"目标点继续飞，落点在更远处，但那正是低伸的定义）。
        //
        // ⚠️ 少了 ② 的后果（实测复现）：目标只要比炮口高约 1.5 格，低伸解就<b>永远过不了终检</b>
        //   ⇒ AUTO 只能退回高抛 ⇒ 在我们自己抬到 89.9° 的射界里它被解成 **89.3°（近乎垂直）**
        //   ⇒ 飞行 1000 tick（50 秒）、顶点 Y≈2300 ⇒ 玩家看到"打了很久炮弹也不落地"。
        //   残差函数里本来就有上升穿越那一条（`min(|tHoriz−tDesc|, |tHoriz−tAsc|)`），
        //   唯独终检漏了 —— 这是补上它，不是改判据。
        boolean hitDescending = Math.abs(flight.horizontalRange() - targetDistance) <= ACCEPT_MISS_BLOCKS;
        boolean hitAscending = Math.abs(flight.ascendRange() - targetDistance) <= ACCEPT_MISS_BLOCKS;
        return hitDescending || hitAscending ? c : null;
    }

    /**
     * 由炮指向目标的方位角，用 MC 的约定：<b>0=+Z、90=−X、180=−Z、270=+X</b>。
     *
     * <p>注意 cbc_ballistics 在这里有个边界 bug：目标正北（dx 恰为 0）时它直接返回 0，
     * 而正确答案是 180。这里用 atan2 一次算清，没有特例分支。
     */
    public static double yawDegrees(double fromX, double fromZ, double toX, double toZ) {
        final double dx = toX - fromX;
        final double dz = toZ - fromZ;
        if (dx == 0.0 && dz == 0.0) {
            return 0.0;
        }
        final double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        return ((yaw % 360.0) + 360.0) % 360.0;
    }
}
