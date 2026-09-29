package net.createballistics.ballistic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 解算器自检 —— <b>不启动 Minecraft</b>，纯数值验证。
 *
 * <p>验证手段刻意<b>不复用求解器的代码路径</b>，否则只是自我确认：
 * <ol>
 *   <li><b>暴力扫描对照</b>：以 0.005° 步长扫遍整个射界，直接模拟每条弹道，
 *       找 {@code 落点距离 − 目标距离} 的变号点 —— 这才是"真解"。</li>
 *   <li><b>回环验证</b>：把求解器给出的仰角独立重放一遍，量落点与目标的实际偏差。</li>
 *   <li><b>弧线一致性</b>：低伸解的仰角必须小于高抛解。</li>
 * </ol>
 *
 * <p>运行：{@code java net.createballistics.ballistic.SolverSelfCheck}
 * 全部通过时退出码 0，有失败项时退出码 1（便于接到构建脚本里）。
 */
public final class SolverSelfCheck {

    /** 暴力扫描步长（度）。 */
    private static final double BRUTE_STEP_DEG = 0.005;
    /** 回环验证允许的最大水平偏差（格）。 */
    private static final double MAX_MISS_BLOCKS = 0.05;

    private static int checks = 0;
    private static int failures = 0;

    public static void main(String[] args) {
        System.out.println("================ 弹道解算器自检 ================");
        System.out.println("CBC 默认物理: formDrag=" + ExternalBallistics.Linear.CBC_DEFAULT.formDrag()
                + " (数据包里的 drag)  gravity=" + ExternalBallistics.Linear.CBC_DEFAULT.gravity());
        System.out.println();

        checkYaw();
        checkAgainstRealMeasurements();
        checkPathSampling();
        checkBallisticMatrix();
        checkOutOfRange();
        checkPitchLimits();
        checkFlatArcAboveMuzzle();
        checkTerrainRangeClamp();
        checkAirTimeAboveMuzzle();

        System.out.println();
        System.out.println("================ 结果 ================");
        System.out.println("检查项 " + checks + " 个，失败 " + failures + " 个");
        if (failures > 0) {
            System.out.println("SELF-CHECK FAILED");
            System.exit(1);
        }
        System.out.println("ALL CHECKS PASSED");
    }

    // ---------------------------------------------------------------- 方位角

    private static void checkYaw() {
        System.out.println("--- 方位角（MC 约定：0=+Z 90=−X 180=−Z 270=+X）---");
        // (目标相对炮的 dx, dz, 期望方位角)
        double[][] cases = {
                {0, 10, 0},      // 正南 +Z
                {10, 0, 270},    // 正东 +X
                {0, -10, 180},   // 正北 −Z  ← cbc_ballistics 在这里会错答 0
                {-10, 0, 90},    // 正西 −X
                {10, 10, 315},   // 东南
                {-10, 10, 45},   // 西南
                {10, -10, 225},  // 东北
                {-10, -10, 135}, // 西北
        };
        for (double[] c : cases) {
            double got = BallisticSolver.yawDegrees(0, 0, c[0], c[1]);
            expect(Math.abs(wrap(got - c[2])) < 1e-9,
                    String.format("方位角 dx=%.0f dz=%.0f → %.1f°", c[0], c[1], c[2]),
                    String.format("得到 %.4f°", got));
        }
    }

    // ------------------------------------------------------------ 实弹回归

    /**
     * going_ballistic 的二次阻力系数，按其 {@code ProjectilePhysicsMath} 的定义复算：
     * {@code k = 0.5 · 空气密度 · Cd · π·r² / 弹重}。
     */
    private static final double GB_GRAVITY = 0.024516625;
    private static final double GB_K =
            0.5 * 1.225 * 0.47 * Math.PI * Math.pow(0.754441738242 / 2.0, 2) / 3159.9;

    /**
     * <b>实弹回归</b> —— 用 2026-09-17 在整合包里实打的三个弹坑坐标校验整个物理模型。
     *
     * <p>这是本自检里<b>唯一一条拿真实世界数据当基准</b>的用例，价值最高：它同时验证了
     * 中点法积分、二次阻力、真实地球重力、以及初速注入，任何一环写错都会被它抓出来。
     *
     * <p>测试炮（全部原版 CBC）：青铜材质 / 青铜楔式炮闩 / 4 格炮腔 / 12 格炮筒 /
     * 4 个发射药 / 一发穿甲榴弹。炮座在 (0, 173, 4)，固定仰角实射。
     * 初速 9.55 格/tick 是三发联合拟合出来的（Robins 公式复算给 13.97，高 1.46 倍，
     * 出处未明 —— 所以实战里初速必须调 going_ballistic 自己的函数拿，不能自己复算）。
     *
     * <p>容差取 1%：炮口相对炮座的前伸量有十几格的不确定度（约占 0.5%），
     * 再加上青铜炮本身的散布。物理模型若写错，偏差会是 40% 以上，绝不会被这条放过。
     */
    private static void checkAgainstRealMeasurements() {
        System.out.println();
        System.out.println("--- 实弹回归（青铜炮 4 药包 / 12 格炮筒 / 穿甲榴弹 @ going_ballistic 物理）---");

        ExternalBallistics gb = new ExternalBallistics.Quadratic(GB_GRAVITY, GB_K);
        final double velocity = 9.53;    // 三发联合拟合值
        // 炮闩 1 + 炮腔 4 + 炮筒 12 = 17 格，再按 CBC 的生成点往前推 2.5 格。
        // 见TrajectorySimulator 里关于"两个不同管长"的说明。
        final double barrel = 19.5;
        final double cannonY = 173.0;
        final double targetY = cannonY - 4.5;   // 实测落点比炮口低 4~5 格

        // {仰角, 实测水平射程(格)}
        double[][] shots = {
                {45.08, 3351.0},
                {30.04, 2972.1},
                {60.00, 2892.2},
        };

        for (double[] s : shots) {
            TrajectorySimulator.Flight f =
                    TrajectorySimulator.simulate(s[0], velocity, barrel, cannonY, targetY, gb);
            double errPct = f == null ? 1.0E9
                    : Math.abs(f.horizontalRange() - s[1]) / s[1] * 100.0;
            expect(errPct < 1.0,
                    String.format(Locale.ROOT, "实弹 %.2f° → %.0f 格", s[0], s[1]),
                    f == null ? "模拟打不到"
                            : String.format(Locale.ROOT, "模拟 %.0f 格，偏差 %.2f%%（飞行 %.1f 秒）",
                                    f.horizontalRange(), errPct, f.airTimeSeconds()));
        }

        // ---- 极限射程 ----
        // 同一门炮、同一套物理：maxRange 的口径是「发射高度 → 同高度」，模型给出 3321 格 @ 44°。
        // 这条守的是 maxRange 本身 —— 它要扫上百个仰角，写错（扫错区间、漏掉某个装药档）
        // 很难从别的地方看出来，而它是要显示给玩家看的"这门炮能打多远"。
        CannonSpec maxRangeSpec = new CannonSpec(velocity, barrel, -30.0, 60.0, 1, 1, gb);
        BallisticSolver.RangeLimit maxRange = BallisticSolver.maxRange(maxRangeSpec);
        expect(Math.abs(maxRange.range() - 3321.0) < 3321.0 * 0.03,
                "极限射程 maxRange",
                String.format(Locale.ROOT, "算出 %.0f 格 @ %.2f°（基准 3321；实弹 45.08° 打到 3351）",
                        maxRange.range(), maxRange.pitchDeg()));

        // 最佳仰角必须落在射界内 —— 否则报出来的就是"抬到天上才够得着"的角度，
        // 玩家照着摆炮会发现压根转不到位。
        expect(maxRange.pitchDeg() <= 60.0 + 1.0E-9,
                "极限射程的最佳仰角不超出俯仰上限",
                String.format(Locale.ROOT, "最佳仰角 %.2f°，上限 60°", maxRange.pitchDeg()));
    }

    // ------------------------------------------------------------ 弹道采样

    /**
     * <b>地形校验用的弹道采样</b>（{@link TrajectorySimulator#samplePath}）自检。
     *
     * <p>这一层最容易出的错是"采样用的积分和 {@link TrajectorySimulator#simulate} 悄悄走了样" ——
     * 那样就会出现「解算说打得到、地形校验说被挡住」这种自相矛盾，而且极难排查。
     * 所以这里<b>最重要的就是第一条</b>：折线的终点必须精确落在 {@code simulate()} 报的落点上。
     */
    private static void checkPathSampling() {
        System.out.println();
        System.out.println("--- 弹道采样（地形校验用，终点必须与 simulate 完全一致）---");

        ExternalBallistics gb = new ExternalBallistics.Quadratic(GB_GRAVITY, GB_K);
        final double velocity = 9.53;
        final double barrel = 19.5;
        final double cannonY = 173.0;
        final double targetY = cannonY - 4.5;

        double[][] shots = {
                {45.08, 3351.0},
                {30.04, 2972.1},
                {60.00, 2892.2},
        };

        for (double[] s : shots) {
            TrajectorySimulator.Flight flight =
                    TrajectorySimulator.simulate(s[0], velocity, barrel, cannonY, targetY, gb);
            List<TrajectorySimulator.PathPoint> path =
                    TrajectorySimulator.samplePath(s[0], velocity, barrel, cannonY, targetY, gb, 4.0);

            if (path.isEmpty() || flight == null) {
                expect(false, String.format(Locale.ROOT, "采样 %.2f°", s[0]),
                        "折线或模拟为空");
                continue;
            }

            TrajectorySimulator.PathPoint last = path.get(path.size() - 1);
            double dx = Math.abs(last.horizontal() - flight.horizontalRange());
            double dy = Math.abs(last.height() - targetY);
            expect(dx < 1.0E-6 && dy < 1.0E-6,
                    String.format(Locale.ROOT, "采样 %.2f° 的终点精确落在 simulate 的落点上", s[0]),
                    String.format(Locale.ROOT, "折线终点 (%.6f, %.6f)，模拟落点 (%.6f, %.6f)",
                            last.horizontal(), last.height(),
                            flight.horizontalRange(), targetY));

            // 水平距离必须严格递增 —— TerrainCheck 是逐段推进的，乱序会让它漏检
            boolean increasing = true;
            for (int i = 1; i < path.size(); i++) {
                if (!(path.get(i).horizontal() > path.get(i - 1).horizontal())) {
                    increasing = false;
                    break;
                }
            }
            expect(increasing,
                    String.format(Locale.ROOT, "采样 %.2f° 的水平距离严格递增（%d 个点）", s[0], path.size()),
                    "出现非递增的相邻点");
        }

        // 步长只影响点密度，不该影响落点
        List<TrajectorySimulator.PathPoint> coarse =
                TrajectorySimulator.samplePath(30.04, velocity, barrel, cannonY, targetY, gb, 16.0);
        List<TrajectorySimulator.PathPoint> fine =
                TrajectorySimulator.samplePath(30.04, velocity, barrel, cannonY, targetY, gb, 1.0);
        expect(!coarse.isEmpty() && !fine.isEmpty() && fine.size() > coarse.size(),
                "步长越小点越密",
                String.format(Locale.ROOT, "步长 16 → %d 点，步长 1 → %d 点", coarse.size(), fine.size()));
        if (!coarse.isEmpty() && !fine.isEmpty()) {
            double d = Math.abs(coarse.get(coarse.size() - 1).horizontal()
                    - fine.get(fine.size() - 1).horizontal());
            expect(d < 1.0E-6, "步长不影响落点",
                    String.format(Locale.ROOT, "两个步长的终点差 %.9f 格", d));
        }

        // 高抛弹道的折线必须真的拱起来 —— 否则地形校验用的是"平的一条线"，越障判断全废
        List<TrajectorySimulator.PathPoint> high =
                TrajectorySimulator.samplePath(45.08, velocity, barrel, cannonY, targetY, gb, 4.0);
        double peak = Double.NEGATIVE_INFINITY;
        for (TrajectorySimulator.PathPoint p : high) {
            peak = Math.max(peak, p.height());
        }
        expect(peak > cannonY + 100.0, "45° 弹道的折线确实拱起来了",
                String.format(Locale.ROOT, "折线最高 %.1f 格（炮口 %.1f）", peak, cannonY));

        // 打不到时要返回空列表，而不是一条半截的折线 —— 半截的会被地形校验当成"畅通"
        List<TrajectorySimulator.PathPoint> unreachable =
                TrajectorySimulator.samplePath(-30.0, velocity, barrel, cannonY, cannonY + 100.0, gb, 4.0);
        expect(unreachable.isEmpty(), "升不到目标高度时采样返回空列表",
                String.format(Locale.ROOT, "得到 %d 个点", unreachable.size()));

        // 采样必须覆盖整条弹道（从炮口一直到落点）
        List<TrajectorySimulator.PathPoint> p30 =
                TrajectorySimulator.samplePath(30.04, velocity, barrel, cannonY, targetY, gb, 4.0);
        if (!p30.isEmpty()) {
            double start = p30.get(0).horizontal();
            double expectedStart = TrajectorySimulator.muzzleForwardOffset(30.04, barrel);
            expect(Math.abs(start - expectedStart) < 1.0E-9,
                    "折线的第一个点在炮口",
                    String.format(Locale.ROOT, "起点 %.4f 格，炮口前伸量 %.4f 格", start, expectedStart));
        }
    }

    // ------------------------------------------------------- 解算 vs 暴力扫描

    private static void checkBallisticMatrix() {
        System.out.println();
        System.out.println("--- 解算结果 vs 暴力扫描真值 ---");

        // (每份装药初速, 装药数, 最低仰角, 最高仰角, 炮管长度, 目标水平距离)
        double[][] cases = {
                {2.0, 4, -30, 60, 4, 100},
                {2.0, 4, -30, 60, 4, 300},
                {2.0, 4, -30, 60, 4, 600},
                {2.0, 4, -30, 60, 4, 1000},
                {2.0, 8, -30, 60, 8, 300},
                {2.0, 8, -30, 60, 8, 900},
                {2.0, 8, -30, 60, 8, 1400},
                {2.0, 8, -15, 85, 8, 400},   // 迫击炮尾：高抛才够得着的近距离
                {2.0, 8, -15, 85, 8, 1200},
                {2.0, 2, -30, 60, 12, 150},
                {2.0, 12, -30, 60, 16, 2500},
        };

        for (double[] c : cases) {
            double perCharge = c[0];
            int charges = (int) c[1];
            double minPitch = c[2], maxPitch = c[3], barrel = c[4], targetDist = c[5];

            CannonSpec spec = new CannonSpec(perCharge, barrel, minPitch, maxPitch,
                    charges, charges, ExternalBallistics.Linear.CBC_DEFAULT);
            double velocity = spec.velocityFor(charges);

            double cannonY = 64.0, targetY = 64.0;   // 平坦地面，排除高差干扰

            List<FiringSolution> solutions = BallisticSolver.solve(
                    spec, 0, cannonY, 0, targetDist, targetY, 0);

            List<Double> brute = bruteForceRoots(minPitch, maxPitch, velocity, barrel,
                    cannonY, targetY, targetDist);

            String label = String.format(Locale.ROOT,
                    "初速%.0f 管%2.0f 仰角[%.0f,%.0f] 目标%5.0f格",
                    velocity, barrel, minPitch, maxPitch, targetDist);
            System.out.printf("%n%s%n   暴力扫描真解: %s%n", label, fmt(brute));

            // 真解个数应当与求解器给出的解个数一致
            expect(solutions.size() == brute.size(),
                    label + " 解的数量与真值一致",
                    "求解器给 " + solutions.size() + " 个，真值 " + brute.size() + " 个");

            if (solutions.size() != brute.size()) {
                for (FiringSolution s : solutions) {
                    System.out.println("     求解器: " + s);
                }
                continue;
            }

            for (int i = 0; i < solutions.size(); i++) {
                FiringSolution s = solutions.get(i);
                double truth = brute.get(i);

                System.out.printf("     %s%n", s);
                System.out.printf("        真值仰角 %.5f°   差 %.6f°   飞行 %.2fs%n",
                        truth, Math.abs(s.pitch() - truth), s.airTimeSeconds());

                expect(Math.abs(s.pitch() - truth) < 0.01,
                        label + " 第" + (i + 1) + "解仰角匹配暴力扫描",
                        String.format(Locale.ROOT, "求解器 %.6f° vs 真值 %.6f°", s.pitch(), truth));

                // 回环：独立重放这条弹道，量实际落点偏差
                TrajectorySimulator.Flight f = TrajectorySimulator.simulate(
                        s.pitch(), velocity, barrel, cannonY, targetY, ExternalBallistics.Linear.CBC_DEFAULT);
                double miss = f == null ? Double.MAX_VALUE
                        : Math.abs(f.horizontalRange() - targetDist);
                System.out.printf("        回环复算落点偏差 %.5f 格%n", miss);
                expect(miss < MAX_MISS_BLOCKS,
                        label + " 第" + (i + 1) + "解回环落点精度",
                        String.format(Locale.ROOT, "偏差 %.5f 格", miss));

                // 飞行时间必须与独立模拟一致 —— 这个数字要拿去做同时弹着 (TOT) 的击发错峰，
                // 错一 tick 就是 50 毫秒的弹着偏差，所以单独验。
                if (f != null) {
                    // ⚠️ 用 airTimeToRange（离目标近的那个穿越点）**同一个定义**去比：
                    //    低伸解在目标高于炮口时走的是上升段，拿 f.airTime()（下降段）比会判"不一致"，
                    //    而那是**定义不同**、不是算错（2026-09-27 定案）。
                    final double independent = TrajectorySimulator.airTimeToRange(
                            s.pitch(), velocity, barrel, cannonY, targetY, targetDist,
                            ExternalBallistics.Linear.CBC_DEFAULT);
                    expect(Math.abs(s.airTime() - independent) < 1e-3,
                            label + " 第" + (i + 1) + "解飞行时间与回环模拟一致",
                            String.format(Locale.ROOT, "解算 %.6f vs 模拟 %.6f tick",
                                    s.airTime(), independent));
                }
            }

            // 两解时：低伸仰角必须小于高抛
            if (solutions.size() == 2) {
                expect(solutions.get(0).pitch() < solutions.get(1).pitch(),
                        label + " 低伸仰角 < 高抛仰角",
                        String.format(Locale.ROOT, "%.4f vs %.4f",
                                solutions.get(0).pitch(), solutions.get(1).pitch()));
                expect(solutions.get(0).airTime() < solutions.get(1).airTime(),
                        label + " 低伸飞行时间 < 高抛飞行时间",
                        String.format(Locale.ROOT, "%.1f vs %.1f tick",
                                solutions.get(0).airTime(), solutions.get(1).airTime()));
            }
        }
    }

    // -------------------------------------------------------------- 边界情况

    private static void checkOutOfRange() {
        System.out.println();
        System.out.println("--- 超出射程：应当安静地返回空，而不是抛异常 ---");
        CannonSpec weak = new CannonSpec(2.0, 4, -30, 60, 1, 1, ExternalBallistics.Linear.CBC_DEFAULT);
        double maxReach = weak.velocityFor(1) / ExternalBallistics.Linear.CBC_DEFAULT.formDrag();
        double far = maxReach * 3.0;

        List<FiringSolution> none = BallisticSolver.solve(weak, 0, 64, 0, far, 64, 0);
        expect(none.isEmpty(), "超出射程返回空列表", "得到 " + none.size() + " 个解");
    }

    /**
     * <b>目标比炮口略高时，低伸解必须存在</b> —— 2026-09-26 大整合包实测定案后补的回归网。
     *
     * <h2>为什么原有的自检抓不到它</h2>
     *
     * 这个 bug 活了很久，因为<b>自检的暴力扫描与解算器的终检犯了同一个错</b>：
     * 两边都拿「弹道<b>下降段</b>落回目标高度时的水平距离」去跟目标距离比
     * （{@link #bruteForceRoots} 里的 {@code f.horizontalRange() - targetDist}、
     * {@code BallisticSolver.verify} 里的同一条判据）。
     *
     * <p>而<b>目标比炮口高时，低伸解是"上升途中穿过目标点"那一条</b> ——
     * 它的落点天然在更远处 ⇒ 两边都判它"不命中" ⇒ 解算器丢掉低伸、
     * AUTO 只能退回高抛，而抬到 89.9° 的射界把高抛解成 <b>89.3°（近乎垂直）</b>：
     * 实测飞行 <b>1000 tick（50 秒）</b>、顶点 Y≈2300 ⇒ 玩家报"打了很久炮弹也不落地"。
     *
     * <p>⇒ 这条检查**不碰落点**，只问一句"低伸解在不在"。
     */
    private static void checkFlatArcAboveMuzzle() {
        System.out.println();
        System.out.println("--- 目标比炮口高：低伸解必须在（2026-09-26 实测复现用例）---");

        // 实测那门小炮：管长 2 格（含 CBC 生成点外推 ⇒ 4.5）· 1 包药 · 解算初速 12.9852
        // 目标：水平 129.55 格，高差 +1.50 格（当时的日志）。
        ExternalBallistics gb = new ExternalBallistics.Quadratic(GB_GRAVITY, GB_K);
        CannonSpec small = new CannonSpec(12.9852, 4.5, -30.0, 89.9, 1, 1, gb);

        for (double dy : new double[]{-0.5, 1.5, 5.0, 20.0}) {
            List<FiringSolution> sols = BallisticSolver.solve(small, 0.0, 69.5, 0.0, 129.55, 69.5 + dy, 0.0);
            boolean hasFlat = false;
            for (FiringSolution s : sols) {
                if (s.arc() == FiringSolution.Arc.FLAT) {
                    hasFlat = true;
                }
            }
            expect(hasFlat,
                    String.format(Locale.ROOT, "高差 %+.1f 格：低伸解存在", dy),
                    "得到 " + sols.size() + " 个解：" + sols);
        }

        // 反面对照：低伸解存在时，它的仰角必须**显著低于**高抛解（别把高抛错标成低伸）。
        List<FiringSolution> sols = BallisticSolver.solve(small, 0.0, 69.5, 0.0, 129.55, 71.0, 0.0);
        if (sols.size() >= 2) {
            expect(sols.get(0).pitch() < sols.get(1).pitch(),
                    "低伸解在前且仰角更小",
                    String.format(Locale.ROOT, "低伸 %.3f° · 高抛 %.3f°", sols.get(0).pitch(), sols.get(1).pitch()));
            // ⚠️ 只报不断言：短距离目标的"高抛解"在数学上就该是 ~89°（两个解关于 45° 对称），
            //    于是它飞行 ≈1000 tick。**实战上这种解等于打不出去**（炮弹飞近一分钟、
            //    顶点冲上 2000+ 格），但"要不要给高抛设一个陡度上限、超过就报打不着"
    //    是**玩法取舍**（要用户拍板），所以这里只把数摆出来提醒，不写成硬断言。
            System.out.println(String.format(Locale.ROOT,
                    "   [note] 该用例的高抛解：%.3f° 飞行 %.0f tick（约 %.0f 秒）—— "
                            + "近乎垂直，实战不可用；AUTO 有低伸可选时不会挑它",
                    sols.get(1).pitch(), sols.get(1).airTime(), sols.get(1).airTime() / 20.0));
        } else {
            expect(false, "该用例应当同时给出低伸与高抛两个解", "实际 " + sols.size() + " 个");
        }
    }

    /**
     * <b>地形校验的"校验范围"必须截到目标</b>（2026-09-27 实机定案）。
     *
     * <p>背景：目标比炮口高时，<b>低伸解是从上升段穿过目标的</b>
     * （那正是 {@code checkFlatArcAboveMuzzle} 那次修复要支持的情形），
     * 弹道随后继续飞，<b>落点在目标之外老远</b>——实测：水平距离 80.3 格的目标，
     * 低伸解的折线落点在 <b>313.9 格</b>。
     *
     * <p>而 {@code TerrainCheck} 原来拿<b>落点</b>算"末段保护带"（射程的 2%），
     * 于是保护带落到了 307~314 那一段，<b>目标脚下那块地反而在检查范围内</b>
     * ⇒ 炮弹砸在目标上那一下被判成"撞地形" ⇒ <b>每一条低伸解都被挡住 ⇒ 自动模式全退高抛</b>。
     * 实测：48 次决策里 <b>34 次</b>的"撞到距离"与目标距离相差不到 5 格。
     *
     * <p>修法是校验范围取 {@code min(落点, 目标距离)}。这条断言守住那个前提 ——
     * <b>"目标在炮口上方时，低伸解的落点必然不早于目标"</b>；一旦它不成立，
     * 说明求解器或采样器变了，那处 clamp 的语义要重新想。
     */
    private static void checkTerrainRangeClamp() {
        System.out.println();
        System.out.println("--- 地形校验的校验范围：低伸解从上升段穿过目标 ⇒ 落点在目标之外 ---");

        ExternalBallistics gb = new ExternalBallistics.Quadratic(GB_GRAVITY, GB_K);
        CannonSpec small = new CannonSpec(12.9852, 4.5, -30.0, 89.9, 1, 1, gb);
        final double targetRange = 129.55;
        final double cannonY = 69.5;
        final double velocity = 12.9852;
        final double barrel = 4.5;

        double worstRatio = 1.0;
        int cases = 0;
        for (double dy : new double[]{0.5, 1.5, 5.0, 20.0}) {
            List<FiringSolution> sols = BallisticSolver.solve(small, 0.0, cannonY, 0.0,
                    targetRange, cannonY + dy, 0.0);
            FiringSolution flat = null;
            for (FiringSolution s : sols) {
                if (s.arc() == FiringSolution.Arc.FLAT) {
                    flat = s;
                }
            }
            if (flat == null) {
                continue;   // 没有低伸解就不成立（上面那条已经在管"解必须在"）
            }
            List<TrajectorySimulator.PathPoint> path = TrajectorySimulator.samplePath(
                    flat.pitch(), velocity, barrel, cannonY, cannonY + dy, gb, 4.0);
            if (path.size() < 2) {
                expect(false, String.format(Locale.ROOT, "高差 %+.1f：折线不该为空", dy), "空折线");
                continue;
            }
            final double landing = path.get(path.size() - 1).horizontal();
            cases++;
            worstRatio = Math.max(worstRatio, landing / targetRange);
            // ⚠️ 容差 0.05 格：目标只在炮口上方一丁点时，**上升穿越点与下降落点相切**
            //    （高差 +0.5 实测就是 129.5499999 / 129.55）—— 那是合法的边界情形，不是反例。
            expect(landing >= targetRange - 0.05,
                    String.format(Locale.ROOT, "高差 %+.1f：低伸解的落点不早于目标", dy),
                    String.format(Locale.ROOT, "目标 %.2f · 落点 %.2f（%.2f×）",
                            targetRange, landing, landing / targetRange));
            if (dy >= 5.0) {
                // 真正要守的那一档：目标明显高于炮口 ⇒ 落点**显著**在目标之外
                // ⇒ TerrainCheck 的保护带会整段错过目标，必须靠 min(落点, 目标距离) 截住。
                expect(landing > targetRange * 1.5,
                        String.format(Locale.ROOT, "高差 %+.1f：落点显著在目标之外（保护带会错过目标）", dy),
                        String.format(Locale.ROOT, "落点 %.2f = %.2f× 目标距离",
                                landing, landing / targetRange));
            }
        }
        expect(cases >= 3, "至少覆盖 3 个高差用例", "实际 " + cases + " 个");
        System.out.println(String.format(Locale.ROOT,
                "   [note] 覆盖 %d 个高差，落点最远是目标的 %.2f 倍 ⇒ "
                        + "TerrainCheck 的校验范围**必须**取 min(落点, 目标距离)，"
                        + "否则保护带会落到目标之外、把目标脚下那块地判成障碍",
                cases, worstRatio));
    }

    /**
     * <b>目标比炮口高时，低伸解的飞行时间必须是「到达目标」的时间</b>（2026-09-27 实机定案）。
     *
     * <p>背景：那种低伸解是从<b>上升段</b>穿过目标的（{@link TrajectorySimulator.Flight#ascendRange}
     * 存在的理由），之后还会继续飞很远才落地。解算原来取的是<b>下降段</b>（远处落点）的时刻
     * ⇒ 低伸的飞行时间被算成好几倍：实测 103 格 / 11° / 初速 8，真实 ≈13 tick、解算报 47（3.6×）。
     * 而这个数<b>要拿去做同时弹着 (TOT) 的击发错峰</b> ⇒ 错的是真的"打不齐"。
     *
     * <p>守两条：① 低伸解的上升穿越点就是目标；② 报出来的飞行时间等于<b>上升段</b>的时刻。
     */
    private static void checkAirTimeAboveMuzzle() {
        System.out.println();
        System.out.println("--- 目标比炮口高：低伸解的飞行时间必须是「到达目标」的时间 ---");

        ExternalBallistics gb = new ExternalBallistics.Quadratic(GB_GRAVITY, GB_K);
        CannonSpec small = new CannonSpec(12.9852, 4.5, -30.0, 89.9, 1, 1, gb);
        final double targetRange = 129.55;
        final double cannonY = 69.5;

        int covered = 0;
        for (double dy : new double[]{1.5, 5.0, 20.0}) {
            List<FiringSolution> sols = BallisticSolver.solve(small, 0.0, cannonY, 0.0,
                    targetRange, cannonY + dy, 0.0);
            FiringSolution flat = null;
            for (FiringSolution s : sols) {
                if (s.arc() == FiringSolution.Arc.FLAT) {
                    flat = s;
                }
            }
            if (flat == null) {
                continue;
            }
            covered++;
            TrajectorySimulator.Flight f = TrajectorySimulator.simulate(
                    flat.pitch(), 12.9852, 4.5, cannonY, cannonY + dy, gb);
            if (f == null) {
                expect(false, "回环模拟不该为空", "空");
                continue;
            }
            expect(Math.abs(f.ascendRange() - targetRange) < 1.0,
                    String.format(Locale.ROOT, "高差 %+.1f：低伸解的上升穿越点就是目标", dy),
                    String.format(Locale.ROOT, "上升 %.2f · 目标 %.2f · 下降(远落点) %.2f",
                            f.ascendRange(), targetRange, f.horizontalRange()));
            expect(Math.abs(flat.airTime() - f.ascendingTime()) < 1e-3,
                    String.format(Locale.ROOT, "高差 %+.1f：报出的飞行时间 = 到达目标的时间", dy),
                    String.format(Locale.ROOT, "解算 %.2f tick · 上升段 %.2f · 下降段(旧算法取它) %.2f",
                            flat.airTime(), f.ascendingTime(), f.airTime()));
        }
        expect(covered >= 3, "至少覆盖 3 个高差用例", "实际 " + covered + " 个");
    }

    private static void checkPitchLimits() {
        System.out.println();
        System.out.println("--- 射界约束：解必须落在给定仰角范围内，超出的不给 ---");
        CannonSpec mortar = new CannonSpec(2.0, 8, -15, 85, 4, 4, ExternalBallistics.Linear.CBC_DEFAULT);
        List<FiringSolution> full = BallisticSolver.solve(mortar, 0, 64, 0, 500, 64, 0);
        for (FiringSolution s : full) {
            expect(s.withinPitchRange(-15, 85), "解在射界 [-15, 85] 内",
                    String.format(Locale.ROOT, "仰角 %.4f°", s.pitch()));
        }

        // 分界点从解本身推导，不手算 —— 手算很容易把"最大射程角"估计错。
        // 这一档两个解挨得很近（500 格已是该炮最大射程的 96%），所以必须精确地卡在中间。
        if (full.size() != 2) {
            expect(false, "该档应当有两个解以便测试射界裁剪",
                    "实际 " + full.size() + " 个");
            return;
        }
        double flatPitch = full.get(0).pitch();
        double highPitch = full.get(1).pitch();

        CannonSpec cutBetween = mortar.withPitchRange(-15, (flatPitch + highPitch) / 2.0);
        List<FiringSolution> betweenSols = BallisticSolver.solve(cutBetween, 0, 64, 0, 500, 64, 0);
        expect(betweenSols.size() == 1
                        && betweenSols.get(0).arc() == FiringSolution.Arc.FLAT,
                String.format(Locale.ROOT, "上限卡在两解之间(%.2f°)后只剩低伸解",
                        (flatPitch + highPitch) / 2.0),
                "得到 " + betweenSols.size() + " 个解: " + betweenSols);

        CannonSpec cutBelow = mortar.withPitchRange(-15, flatPitch - 1.0);
        List<FiringSolution> noneSols = BallisticSolver.solve(cutBelow, 0, 64, 0, 500, 64, 0);
        expect(noneSols.isEmpty(),
                String.format(Locale.ROOT, "上限压到 %.2f°（低伸解之下）后无解",
                        flatPitch - 1.0),
                "得到 " + noneSols.size() + " 个解");
    }

    // ------------------------------------------------------------ 暴力扫描

    /**
     * 以极小的步长扫遍射界，找 {@code 落点距离 − 目标距离} 的变号点。
     *
     * <p>这是与 {@link BallisticSolver} <b>完全独立</b>的一条路径：它直接模拟弹道、
     * 看落点在目标前还是目标后，完全不碰"两个飞行时间之差"那套目标函数。
     *
     * @return 按仰角升序排列的真解仰角
     */
    private static List<Double> bruteForceRoots(double minPitch, double maxPitch, double velocity,
                                                double barrelLength, double cannonY, double targetY,
                                                double targetDist) {
        List<Double> roots = new ArrayList<>();
        double prevPitch = Double.NaN;
        double prevValue = Double.NaN;

        for (double pitch = minPitch; pitch <= maxPitch + 1e-9; pitch += BRUTE_STEP_DEG) {
            TrajectorySimulator.Flight f = TrajectorySimulator.simulate(
                    pitch, velocity, barrelLength, cannonY, targetY, ExternalBallistics.Linear.CBC_DEFAULT);
            double value = (f == null) ? Double.NaN : f.horizontalRange() - targetDist;

            if (Double.isFinite(value) && Double.isFinite(prevValue)
                    && Math.signum(value) != Math.signum(prevValue) && value != 0.0) {
                // 线性插值定位零点
                double t = -prevValue / (value - prevValue);
                roots.add(prevPitch + t * (pitch - prevPitch));
            }
            prevPitch = pitch;
            prevValue = value;
        }
        return roots;
    }

    // ---------------------------------------------------------------- 工具

    private static void expect(boolean ok, String label, String detail) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("   [FAIL] " + label + "  —— " + detail);
        } else {
            System.out.println("   [ok]   " + label);
        }
    }

    private static String fmt(List<Double> values) {
        if (values.isEmpty()) {
            return "(无解)";
        }
        StringBuilder sb = new StringBuilder();
        for (double v : values) {
            sb.append(String.format(Locale.ROOT, "%.5f°  ", v));
        }
        return sb.toString().trim();
    }

    private static double wrap(double deg) {
        double d = deg % 360.0;
        if (d > 180.0) d -= 360.0;
        if (d < -180.0) d += 360.0;
        return d;
    }
}
