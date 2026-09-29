package net.createballistics.ballistic;

import java.util.ArrayList;
import java.util.List;

/**
 * 数值弹道积分器（<b>不考虑地形</b>）。
 *
 * <h2>⚠️ 积分必须与 CBC 逐字一致，否则解算会被系统性带偏</h2>
 *
 * CBC 的 {@code AbstractCannonProjectile} 是这么走的：
 * <pre>
 * protected Vec3 getForces(Vec3 position, Vec3 velocity) {
 *     return velocity.normalize().scale(-this.getDragForce()).add(0, this.getGravity(), 0);
 * }
 * // tick 里：
 * Vec3 accel = getForces(oldPos, oldVel);
 * Vec3 newPos = oldPos.add(oldVel).add(accel.scale(0.5));   // ← 中点法
 * setDeltaMovement(oldVel.add(accel));
 * </pre>
 *
 * 两个要点：
 * <ol>
 *   <li><b>中点法，不是欧拉法。</b>位置要用 {@code pos += v + a·0.5}。用欧拉法
 *       （{@code pos += v} 再更新速度）会留下一个固定半步滞后，3 公里的弹道上约 <b>7 格</b>
 *       的系统偏差 —— 正好和实测拟合残差同量级。</li>
 *   <li><b>阻力沿速度反方向施加</b>，不是每个轴各衰减一次。线性阻力下两者碰巧等价，
 *       但二次阻力下不等价，而 going_ballistic 用的正是二次阻力。</li>
 * </ol>
 *
 * <h2>为什么要对穿越时刻做插值</h2>
 *
 * 如果直接用"第几个整数 tick 上 y ≤ 目标高度"当落地判据，落点距离对仰角会呈<b>阶梯状</b>：
 * 在一个 tick 的带宽内平滑上升，然后突然跳升约一个 tick 的水平位移（十几格）。后果是求根会
 * 找到"跳变沿"而不是真解。所以要在跨过目标高度<b>那一段之间做线性插值</b>，得到连续的落点。
 * CBC 真实世界里炮弹是连续运动的，插值反而比取整更贴近实际。
 *
 * <h2>求解用的目标函数为什么是「两个飞行时间的差」</h2>
 *
 * 不用"落点离目标多远"（那样必须带地形做碰撞模拟，而且"打近了"会让目标函数在边界上突然失效），
 * 而是让<b>水平走完目标距离的时刻</b>与<b>垂直下降到目标高度的时刻</b>相等 —— 二者相等即为真解。
 * 好处：目标函数连续光滑、不需要地形、一次积分同时得到低伸与高抛两解。
 *
 * <p>注意这个"水平时刻"<b>必须是数值积分出来的</b>，不能像线性阻力那样用等比级数的闭式解 ——
 * 二次阻力下根本没有闭式解。
 */
public final class TrajectorySimulator {

    /** 单次弹道积分的结果。 */
    public record Flight(
            double horizontalRange,   // 从炮座枢轴算起的水平落点距离（格）
            double airTime,           // 从炮口到落点的 tick 数（已插值，非整数）
            double peakHeight,        // 弹道最高点的绝对 Y
            double ascendingTime,     // 上升段穿过目标高度的时间；起点已在目标高度之上时为 NaN
            /**
             * ⭐ <b>上升段穿过目标高度时所在的水平距离</b>（格，从炮座枢轴算起）；
             * 起点已在目标高度之上、或压根升不到那个高度时为 {@link Double#NaN}。
             *
             * <p>为什么必须有它：<b>目标比炮口高时，低伸解就是"上升途中穿过目标点"那一条</b>
             * —— 它压根不会在目标距离处"落地"（落点在更远处）。终检只比"落点距离"的话，
             * 那种解<b>永远过不了</b>，于是 AUTO 只能退回近乎垂直的高抛
             * （2026-09-26 大包实测：高差 +1.5 格就复现，飞 1000 tick 的炮弹就是这么来的）。
             */
            double ascendRange
    ) {
        public double airTimeSeconds() {
            return airTime / 20.0;
        }
    }

    /** 迭代上限，防止病态输入把 CPU 卡死。CBC 自己是 100000。 */
    public static final int MAX_TICKS = 100_000;

    private TrajectorySimulator() {
    }

    /** 炮口相对炮座枢轴的水平前伸量。 */
    public static double muzzleForwardOffset(double pitchDeg, double barrelLength) {
        return barrelLength * Math.cos(Math.toRadians(pitchDeg));
    }

    /** 炮口的绝对 Y 坐标。 */
    public static double muzzleHeight(double pitchDeg, double barrelLength, double cannonY) {
        return cannonY + barrelLength * Math.sin(Math.toRadians(pitchDeg));
    }

    /** 在 {@code [0,1]} 内取比例，退化情形返回 0，避免 0/0。 */
    private static double fraction(double numerator, double denominator) {
        if (denominator == 0.0) {
            return 0.0;
        }
        double f = numerator / denominator;
        return f < 0.0 ? 0.0 : (f > 1.0 ? 1.0 : f);
    }

    /**
     * 给定仰角与初速，从炮口逐 tick 积分，直到弹丸下降到 {@code targetHeight}。
     *
     * <p>{@code horizontalRange} 是<b>从炮座枢轴算起</b>的水平距离（已把炮口前伸量加回去），
     * 可以直接跟"炮到目标的水平距离"比较。
     *
     * @return 弹丸升不到目标高度时返回 {@code null}
     */
    public static Flight simulate(double pitchDeg, double velocity, double barrelLength,
                                  double cannonY, double targetHeight, ExternalBallistics ballistics) {

        final double rad = Math.toRadians(pitchDeg);
        final double muzzleForward = muzzleForwardOffset(pitchDeg, barrelLength);
        final double muzzleY = muzzleHeight(pitchDeg, barrelLength, cannonY);

        double x = muzzleForward;   // 从炮座枢轴开始记
        double y = muzzleY;
        double vx = velocity * Math.cos(rad);
        double vy = velocity * Math.sin(rad);
        double peak = muzzleY;

        final boolean startedBelow = muzzleY <= targetHeight;
        double ascendingTime = Double.NaN;
        double ascendRange = Double.NaN;

        for (int t = 1; t <= MAX_TICKS; t++) {
            final double speed = Math.hypot(vx, vy);
            if (!(speed > 1.0E-12)) {
                return null;
            }
            final double drag = ballistics.dragAcceleration(speed);
            final double ax = -(vx / speed) * drag;
            final double ay = -(vy / speed) * drag - ballistics.gravity();

            final double xPrev = x;
            final double yPrev = y;

            // 与 CBC 逐字一致的中点法
            x += vx + ax * 0.5;
            y += vy + ay * 0.5;
            vx += ax;
            vy += ay;

            if (y > peak) {
                peak = y;
            }
            if (startedBelow && Double.isNaN(ascendingTime) && y > targetHeight) {
                final double f = fraction(targetHeight - yPrev, y - yPrev);
                ascendingTime = (t - 1) + f;
                // 同一个插值点上的水平位置 —— 终检要靠它判"上升途中是否正好穿过目标点"
                ascendRange = xPrev + f * (x - xPrev);
            }
            // ⚠️ 只有「在下降」且低于目标高度才算落地。
            //
            // 少了 vy < 0 这个条件就会坏事：当目标高度比炮口还高时（比如玩家把 Y 填在
            // 自己站的地面上，而炮轴支点更低），炮弹要爬升一段才能碰到目标高度，
            // 可上升途中 y 一直 ≤ targetHeight —— 于是第一步就被判成「已落地」，
            // 而此时水平距离才十几格，直接返回「无解」。
            // 2026-09-17 实机踩到：目标 (0,173,-300)、炮轴支点 171.5 → 300 格永远算不出来，
            // 而仰角-落点曲线也呈现非单调的怪象（0.5°→199, 1.0°→92, 4.0°→511）。
            if (vy < 0.0 && y <= targetHeight) {
                if (startedBelow && Double.isNaN(ascendingTime)) {
                    return null;
                }
                final double f = fraction(yPrev - targetHeight, yPrev - y);
                return new Flight(xPrev + f * (x - xPrev), (t - 1) + f, peak, ascendingTime, ascendRange);
            }
        }
        return null;
    }

    /**
     * 弹道折线上的一个采样点（<b>二维</b>）。
     *
     * @param horizontal 从<b>炮座枢轴</b>算起的水平距离（格），和 {@link Flight#horizontalRange()} 同一套记法
     * @param height     绝对高度（Y）
     */
    public record PathPoint(double horizontal, double height) {
    }

    /**
     * 把弹道采样成一条二维折线，供<b>地形校验</b>使用。
     *
     * <p>⚠️ <b>这里的积分必须和 {@link #simulate} 逐字一致</b> —— 两条轨迹只要有一点不一样，
     * 就会出现"解算说打得到、地形校验说被挡住"这种自相矛盾。{@link SolverSelfCheck} 里
     * 有一条断言守着这件事：本方法最后一个点必须落在 {@code simulate()} 报的落点上。
     *
     * <p>之所以只给二维折线、不带世界坐标：这样 {@code ballistic} 包就能保持<b>不依赖
     * Minecraft</b>，世界坐标的换算交给 {@code cbc.TerrainCheck} 去做。
     *
     * @param stepBlocks 采样步长（水平格）。段与段之间靠射线检测兜底，所以不用取太小
     * @return 从炮口到落点的折线；弹道不可达（升不到目标高度 / 速度归零）时返回<b>空列表</b>
     */
    public static List<PathPoint> samplePath(double pitchDeg, double velocity, double barrelLength,
                                             double cannonY, double targetHeight,
                                             ExternalBallistics ballistics, double stepBlocks) {

        final double rad = Math.toRadians(pitchDeg);
        final double muzzleForward = muzzleForwardOffset(pitchDeg, barrelLength);
        final double muzzleY = muzzleHeight(pitchDeg, barrelLength, cannonY);

        double x = muzzleForward;
        double y = muzzleY;
        double vx = velocity * Math.cos(rad);
        double vy = velocity * Math.sin(rad);

        final boolean startedBelow = muzzleY <= targetHeight;
        boolean roseAbove = !startedBelow;

        final List<PathPoint> out = new ArrayList<>();
        out.add(new PathPoint(x, y));
        double lastSampleX = x;

        for (int t = 1; t <= MAX_TICKS; t++) {
            final double speed = Math.hypot(vx, vy);
            if (!(speed > 1.0E-12)) {
                return List.of();
            }
            final double drag = ballistics.dragAcceleration(speed);
            final double ax = -(vx / speed) * drag;
            final double ay = -(vy / speed) * drag - ballistics.gravity();

            final double xPrev = x;
            final double yPrev = y;

            x += vx + ax * 0.5;
            y += vy + ay * 0.5;
            vx += ax;
            vy += ay;

            if (!roseAbove && y > targetHeight) {
                roseAbove = true;
            }
            // ⚠️ 与 simulate() 同一条落地判据：必须「在下降」且低于目标高度。
            if (vy < 0.0 && y <= targetHeight) {
                if (!roseAbove) {
                    return List.of();   // 升不到目标高度
                }
                final double f = fraction(yPrev - targetHeight, yPrev - y);
                out.add(new PathPoint(xPrev + f * (x - xPrev), yPrev + f * (y - yPrev)));
                return out;
            }
            if (x - lastSampleX >= stepBlocks) {
                out.add(new PathPoint(x, y));
                lastSampleX = x;
            }
        }
        return List.of();
    }

    /**
     * 求解器用的目标函数：让弹丸在同一时刻既走完水平距离、又到达目标高度，两者必须自洽。
     *
     * <p>整条弹道<b>只积分一次</b>，同时读出两个穿越时刻：
     * <ul>
     *   <li>{@code tHoriz} —— 水平方向走完目标距离的时刻（插值）</li>
     *   <li>{@code tDesc} / {@code tAsc} —— 垂直方向穿过目标高度的时刻（插值）</li>
     * </ul>
     * 这样对线性阻力和二次阻力<b>同样成立</b>，不必为阻力模型分叉。
     *
     * @return 两个时刻之差（tick），<b>越小越接近真解</b>；
     *         {@link Double#NaN} 表示该仰角不可达
     */
    public static double residual(double pitchDeg, double velocity, double barrelLength,
                                  double targetHorizontalDistance, double cannonY, double targetHeight,
                                  ExternalBallistics ballistics) {

        final double rad = Math.toRadians(pitchDeg);
        final double muzzleForward = muzzleForwardOffset(pitchDeg, barrelLength);
        final double muzzleY = muzzleHeight(pitchDeg, barrelLength, cannonY);

        // 目标比炮口还近，谈不上"走完水平距离"
        if (!(targetHorizontalDistance > muzzleForward) || !(velocity > 0.0)) {
            return Double.NaN;
        }

        double x = muzzleForward;
        double y = muzzleY;
        double vx = velocity * Math.cos(rad);
        double vy = velocity * Math.sin(rad);

        final boolean startedBelow = muzzleY <= targetHeight;
        double tHoriz = Double.NaN;
        double tAsc = Double.NaN;

        for (int t = 1; t <= MAX_TICKS; t++) {
            final double speed = Math.hypot(vx, vy);
            if (!(speed > 1.0E-12)) {
                return Double.NaN;
            }
            final double drag = ballistics.dragAcceleration(speed);
            final double ax = -(vx / speed) * drag;
            final double ay = -(vy / speed) * drag - ballistics.gravity();

            final double xPrev = x;
            final double yPrev = y;

            x += vx + ax * 0.5;
            y += vy + ay * 0.5;
            vx += ax;
            vy += ay;

            if (Double.isNaN(tHoriz) && xPrev < targetHorizontalDistance && x >= targetHorizontalDistance) {
                tHoriz = (t - 1) + fraction(targetHorizontalDistance - xPrev, x - xPrev);
            }
            if (startedBelow && Double.isNaN(tAsc) && y > targetHeight) {
                tAsc = (t - 1) + fraction(targetHeight - yPrev, y - yPrev);
            }
            // ⚠️ 同 simulate()：必须等它开始下降才算到达目标高度，否则「目标比炮口高」时
            // 上升段会被误判成落地，直接返回 NaN（解不出诸元）。
            if (vy < 0.0 && y <= targetHeight) {
                if (startedBelow && Double.isNaN(tAsc)) {
                    return Double.NaN;      // 升不到目标高度
                }
                if (Double.isNaN(tHoriz)) {
                    return Double.NaN;      // 还没走完水平距离就落地了 → 这一炮够不着
                }
                final double tDesc = (t - 1) + fraction(yPrev - targetHeight, yPrev - y);
                final double descending = Math.abs(tHoriz - tDesc);
                if (Double.isNaN(tAsc)) {
                    return descending;
                }
                return Math.min(descending, Math.abs(tHoriz - tAsc));
            }
        }
        return Double.NaN;
    }

    /**
     * 到达目标高度的实际飞行时间（下降段，已插值）。
     *
     * <p>这才是"炮弹什么时候落地"的物理答案，用于同时弹着 (TOT) 的击发时刻错开。
     *
     * <p><b>必须整条弹道二维积分</b>，不能只积垂直分量：二次阻力是沿<b>速度方向</b>施加的，
     * 水平分量和垂直分量通过"速度大小"耦合在一起 —— 只积垂直会算出偏大的下落时间。
     *
     * @return 落不到目标高度时返回 {@link Double#NaN}
     */
    public static double timeToHeight(double pitchDeg, double velocity,
                                      double cannonY, double barrelLength,
                                      double targetHeight, ExternalBallistics ballistics) {
        Flight flight = simulate(pitchDeg, velocity, barrelLength, cannonY, targetHeight, ballistics);
        return flight == null ? Double.NaN : flight.airTime();
    }

    /**
     * <b>到达目标的飞行时间</b>（tick）—— 两个穿越点里挑<b>离目标近的那一个</b>。
     *
     * <p>⚠️⚠️ 为什么不能直接用 {@link #timeToHeight}（= {@link Flight#airTime()}，下降段/远处落点）：
     * <b>目标比炮口高时，低伸解是从上升段穿过目标的</b>（{@link Flight#ascendRange} 存在的理由），
     * 那条弹道此后会继续飞很远才"落地" ⇒ 用下降段的时刻会把低伸的飞行时间算成**好几倍**。
     *
     * <p>实测（2026-09-27，用户 40 发日志）：103 格 / 11° / 初速 8 的真实飞行 ≈ 13 tick，
     * 而解算报 47 tick（3.6 倍）；比值 0.20~0.58 连续 19 发都是这样，高抛那批则全是 1.00。
     * 这个数<b>要拿去做同时弹着 (TOT) 的击发错峰</b>（{@code getSolutionAirTime}）⇒ 错的是真的"打不齐"。
     *
     * @param targetRange 到目标的水平距离（格）—— 用来判"哪个穿越点才是目标"
     */
    public static double airTimeToRange(double pitchDeg, double velocity, double barrelLength,
                                        double cannonY, double targetHeight, double targetRange,
                                        ExternalBallistics ballistics) {
        Flight flight = simulate(pitchDeg, velocity, barrelLength, cannonY, targetHeight, ballistics);
        if (flight == null) {
            return Double.NaN;
        }
        final double ascending = flight.ascendingTime();
        if (Double.isNaN(ascending)) {
            return flight.airTime();   // 起点已在目标高度之上 ⇒ 只有下降段
        }
        return Math.abs(flight.ascendRange() - targetRange)
                <= Math.abs(flight.horizontalRange() - targetRange)
                ? ascending : flight.airTime();
    }
}
