package net.createballistics.cbc;

import java.util.List;
import net.createballistics.ballistic.TrajectorySimulator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * 地形遮挡校验 —— 这条弹道会不会<b>撞在山上</b>。
 *
 * <p>它回答的是"低伸到底打不打得到"，供「自动」弹道模式决定要不要改用高抛。
 *
 * <h2>为什么在 {@code cbc} 包而不是 {@code ballistic} 包</h2>
 *
 * 它要查世界里的方块，必须依赖 Minecraft；而 {@code ballistic} 包刻意保持纯净（能在普通 JVM 里自检）。
 * 所以分工是：{@link TrajectorySimulator#samplePath} 给出<b>二维</b>折线（水平距离 / 高度），
 * 这里再按方位角换算成世界坐标、逐段做方块射线检测。
 *
 * <h2>⚠️ 判据是「真的穿过方块」，<b>没有</b>任何余量</h2>
 *
 * 一度打算按"弹道与地形间隙 &lt; 2 格也算挡"来做（防抖）。<b>那是错的</b>：
 * 一门架在地面上的炮，平射时炮口本来就只有约 2~3 格高 —— 加任何余量都会让
 * <b>所有平射</b>都被判成"挡住"，低伸直接用不了。
 *
 * 而且"抖动"这个担心不成立：地形不会在两次解算之间变，同一组输入必然给出同一个结论，
 * 不会出现"这次挡、下次不挡"的反复横跳。所以零余量既是正确的（真撞上就是真撞上），也是稳定的。
 *
 * <h2>⚠️ 区块没加载 = 查不了，必须如实说</h2>
 *
 * 射线检测只在<b>已加载</b>的区块里有意义。没加载时既不能报"畅通"（那是撒谎）也不能报"挡住"
 * —— 只能报 {@link Kind#UNKNOWN}，由上层文案告诉玩家"判断不了，按低伸算"。
 * 好处是这条限制顺带把开销也限制住了：走到未加载区块就停，超远距离的射击几乎不花时间。
 */
public final class TerrainCheck {

    /** 校验结果。 */
    public enum Kind {
        /** 一路畅通。 */
        CLEAR,
        /** 撞到方块了。 */
        BLOCKED,
        /** 有需要校验的区段落在未加载的区块里 —— <b>判断不了</b>，不是"畅通"。 */
        UNKNOWN
    }

    /**
     * @param kind          结论
     * @param distance      撞上时的水平距离（格，<b>从炮座枢轴算起</b>）—— 给"半路上有山"那条文案用，
     *                      玩家拿它对着自己的位置量就行
     * @param beyondMuzzle  撞上时距离<b>炮口</b>多远（格）—— 给"炮口被堵住"那条文案用。
     *
     *                      <p>⚠️ <b>这两个必须分开存</b>：炮口本身就在枢轴前约 20 格
     *                      （{@code barrelLength}），只留枢轴距离的话，
     *                      "炮口前 3 格"会被报成"20 格"，判不出是不是堵在炮口
     *                      （2026-09-21 用户实测：方块就在炮口前，却显示成低伸半路被挡）。
     */
    public record Result(Kind kind, double distance, double beyondMuzzle) {

        private static final Result CLEAR = new Result(Kind.CLEAR, 0.0, 0.0);

        public boolean blocked() {
            return kind == Kind.BLOCKED;
        }

        public boolean unknown() {
            return kind == Kind.UNKNOWN;
        }

        /** 障碍是不是就堵在<b>炮口跟前</b>（而不是半路上有山）。 */
        public boolean atMuzzle() {
            return kind == Kind.BLOCKED && beyondMuzzle < MUZZLE_ZONE;
        }
    }

    /**
     * 炮口前面这一丁点距离<b>不检查</b>。
     *
     * <p>取 1 格，只是为了不把"炮弹生成点正踩着的那一格"算成障碍。
     *
     * <p>⚠️ 这里原本是 <b>4 格</b>，理由是"避开炮管自身和 CBC 的生成点外推量"。但那个顾虑不成立：
     * <b>火炮本体是 Create 的 contraption，它的方块不在世界里</b>（我们自己的 {@code CannonProbe}
     * 也是从 {@code contraption.presentBlockEntities} 读炮身、而不是查世界方块），
     * 而模型里的 {@code barrelLength} 本来就已经是"含 CBC 外推 2~3 格"的前伸量。
     * 留着 4 格的实际后果是<b>把"炮口被方块堵住"整段漏掉了</b> ——
     * 玩家在炮口前垒一堵墙，我们什么都报不出来（2026-09-21 用户提出）。
     */
    public static final double LEAD_IN = 1.0;

    /**
     * 障碍落在这个距离以内（<b>从炮口算起</b>），就算<b>炮口被堵住</b>，而不是"半路上有山"。
     *
     * <p>两者对玩家是两件事：半路被山挡要<b>换弹道</b>，炮口被堵要<b>去把那个方块挖掉</b>。
     * 取 6 格 —— 大致就是"炮管口那一圈"的范围。
     */
    public static final double MUZZLE_ZONE = 6.0;

    /**
     * 弹道末段<b>不检查</b>的最小水平距离（格）。
     *
     * <p>炮弹最后是<b>扎进目标所在的地面</b>的 —— 那是命中，不是"被挡住"。
     * 实际取值再取"<b>走到目标那一段</b>的 2%"里的大者：远射时末段下降得又长又陡，固定 6 格不够。
     *
     * <p>⚠️⚠️ 2026-09-27 订正：基准从"这条弹道自己的落点"改成了"**到达目标的距离**"
     * （见 {@link #check} 的 {@code targetRange}）。原来是拿落点算的，而**低伸解在目标比炮口高时
     * 是从上升段穿过目标的** —— 那种弹道的落点在目标之外老远（实测 80.3 格的目标、落点 313.9 格），
     * 于是保护带被放到了 307~314 那一段，**目标脚下那块地反而在检查范围内**
     * ⇒ 每一条低伸解都被判"撞地形" ⇒ 自动模式只能退回高抛。
     * 实测：48 次决策里 34 次的"撞到距离"与目标距离相差不到 5 格。
     */
    public static final double MIN_TAIL_MARGIN = 6.0;

    /** 采样步长（水平格）。段与段之间靠射线检测兜底，所以不用取太小。 */
    public static final double STEP_BLOCKS = 4.0;

    /**
     * 炮口那一小段的采样密度（格）。
     *
     * <p>取 0.25 —— 要密到不会从"1 格厚的方块"两边跨过去（方块本身就是 1 格厚）。
     */
    public static final double MUZZLE_SAMPLE = 0.25;

    private TerrainCheck() {
    }

    /**
     * 校验一整条弹道。
     *
     * @param originX 炮座枢轴 X
     * @param originY 炮座枢轴 Y（未用到，留着是为了和别处的调用形状一致）
     * @param originZ 炮座枢轴 Z
     * @param yawDeg      方位角（度，MC 约定：0=+Z、90=−X、180=−Z、270=+X），取自 {@code FiringSolution.yaw()}
     * @param targetRange <b>炮座枢轴到目标的水平距离</b>（格）—— <b>校验到它为止</b>，见下面那段。
     * @param path        {@link TrajectorySimulator#samplePath} 给出的折线
     *
     * <h2>⚠️⚠️ 为什么需要 {@code targetRange}（2026-09-27 定案）</h2>
     *
     * 折线的最后一个点是<b>这条弹道自己落到 {@code targetHeight} 的位置</b>，
     * 它<b>不一定等于</b>目标距离：目标比炮口高时，低伸解是从<b>上升段</b>穿过目标的
     * （这正是 2026-09-26 `ascendRange` 那次修复要支持的情形），弹道随后继续飞、落点在目标之外老远。
     *
     * <p>实测（用户日志，48 次决策）：目标 80.3 格、低伸解的落点 <b>313.9 格</b>。
     * 拿落点算保护带 ⇒ 保护带落在 307~314 那一段 ⇒ <b>目标脚下那块地在检查范围内</b>
     * ⇒ 每一条低伸都被判"撞地形" ⇒ 自动模式只能退回高抛（34/48 的"撞到距离"与目标距离差 &lt; 5 格）。
     *
     * <p>⇒ 校验范围取 {@code min(落点, 目标距离)}：**弹道到目标就为止了**，
     * 目标之外它撞到什么都已经无所谓（那时炮弹早该命中了）。
     */
    public static Result check(Level level, double originX, double originY, double originZ,
                               double yawDeg, double targetRange,
                               List<TrajectorySimulator.PathPoint> path) {

        if (level == null || path.size() < 2) {
            return new Result(Kind.UNKNOWN, 0.0, 0.0);
        }

        final double yawRad = Math.toRadians(yawDeg);
        final double dirX = -Math.sin(yawRad);
        final double dirZ = Math.cos(yawRad);

        // 折线的第一个点就是炮口 —— 它在枢轴前方约 20 格（= barrelLength）。
        // 把它减掉才能得到"距炮口多远"，否则判不出障碍是不是堵在炮口
        // （2026-09-21 用户实测：方块就在炮口前，却因为报的是枢轴距离而落到"半路被挡"那条文案）。
        final double muzzleAt = path.get(0).horizontal();
        final double landing = path.get(path.size() - 1).horizontal();
        // ⚠️⚠️ 校验到**目标**为止，不是"到这条弹道自己落到哪儿为止" ——
        //    低伸解在目标比炮口高时是从上升段穿过目标的，落点在目标之外老远（见方法注释）。
        final double reach = Math.min(landing, targetRange);
        final double tailMargin = Math.max(MIN_TAIL_MARGIN, reach * 0.02);

        // ---- 炮口那一小段：单独打一条精确射线 ----
        //
        // ⚠️ 折线的采样步长是 STEP_BLOCKS(4 格)，而**第一个点就在炮口上** ——
        // 拿折线的点判"炮口前几格"必然跳格（下一个点已经在 4 格开外了）。
        // 所以炮口这一小段单独精确测一次，距离才报得准（"炮口前 3 格"就得是 3）。
        final TrajectorySimulator.PathPoint p0 = path.get(0);
        final TrajectorySimulator.PathPoint p1 = path.get(1);
        final Vec3 muzzlePos = new Vec3(originX + dirX * p0.horizontal(), p0.height(),
                originZ + dirZ * p0.horizontal());
        final Vec3 nextPos = new Vec3(originX + dirX * p1.horizontal(), p1.height(),
                originZ + dirZ * p1.horizontal());
        final Vec3 axis3 = nextPos.subtract(muzzlePos);

        // 折线的校验起点。⚠️ 炮口射线没打到东西时，**必须把它止步的位置接上去** ——
        // 折线的采样点是 0 / 4 / 8…（相对炮口），而射线覆盖到 MUZZLE_ZONE(6)，
        // 于是 6~8 那两格**两段都覆盖不到**：摆在炮口前 7 格的方块会被整个漏掉，
        // 而且会被误报成"畅通"。把 prev 设成射线终点就把缝补上了。
        Vec3 prev = null;

        if (axis3.lengthSqr() > 1.0E-9 && level.isLoaded(BlockPos.containing(muzzlePos))) {
            final Vec3 unit = axis3.normalize();

            // ⚠️⚠️ 这里**刻意不用 level.clip()**，而是一格一格问"这儿是不是实心的"。
            //
            // 原因是 clip 只认「从外面**进入**某个碰撞面」——
            // **射线起点落在方块内部时它返回"没打中"**。而方块紧贴炮口（炮口前 1 格）恰恰是
            // 起点在它内部的情形，于是最该抓到的那种情况反而漏检，
            // 并且还会在"打中/没打中"之间随几何微抖动来回翻面 ⇒ AUTO 疯了似地切弹道、炮上下抽
            // （2026-09-21 用户实测：目标 1500 格、炮口前一格有方块，报的却是"地形没加载"）。
            //
            // 逐点采样既不怕起点在方块里，报出来的距离也更准（就是采样到的那个位置）。
            for (double t = LEAD_IN; t <= MUZZLE_ZONE + 1.0E-6; t += MUZZLE_SAMPLE) {
                final Vec3 at = muzzlePos.add(unit.scale(t));
                if (!level.isLoaded(BlockPos.containing(at))) {
                    break;
                }
                if (isSolidAt(level, at)) {
                    return new Result(Kind.BLOCKED, p0.horizontal() + t, t);
                }
            }
            prev = muzzlePos.add(unit.scale(MUZZLE_ZONE));
        }

        // 待校验区段：跳过领入段与末段（见两个常量的注释）。
        // ⚠️ 注意 LEAD_IN 是**相对炮口**的，而 p.horizontal() 是相对枢轴的
        final double from = muzzleAt + MUZZLE_ZONE;
        final double to = reach - tailMargin;
        for (TrajectorySimulator.PathPoint p : path) {
            if (p.horizontal() < from) {
                continue;
            }
            if (p.horizontal() > to) {
                break;
            }
            final Vec3 cur = new Vec3(
                    originX + dirX * p.horizontal(),
                    p.height(),
                    originZ + dirZ * p.horizontal());

            if (!level.isLoaded(BlockPos.containing(cur))) {
                return new Result(Kind.UNKNOWN, p.horizontal(), p.horizontal() - muzzleAt);
            }
            if (prev == null) {
                // 第一个校验点本身也要查 —— 否则贴着炮口的那堵墙会被漏掉
                // （它正好落在 prev→cur 这条线段的**起点**上，后面的 clip 覆盖不到）
                if (isSolidAt(level, cur)) {
                    return new Result(Kind.BLOCKED, p.horizontal(), p.horizontal() - muzzleAt);
                }
            } else if (clips(level, prev, cur)) {
                return new Result(Kind.BLOCKED, p.horizontal(), p.horizontal() - muzzleAt);
            }
            prev = cur;
        }
        return Result.CLEAR;
    }

    private static BlockHitResult clip(Level level, Vec3 from, Vec3 to) {
        // ⚠️ 最后一个参数要写 CollisionContext.empty() 而不是 null ——
        // ClipContext 既有 (Vec3,Vec3,Block,Fluid,Entity) 又有 (...,CollisionContext) 两个构造器，
        // 传 null 编译器无法取舍。
        return level.clip(new ClipContext(
                from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                CollisionContext.empty()));
    }

    private static boolean clips(Level level, Vec3 from, Vec3 to) {
        BlockHitResult hit = clip(level, from, to);
        return hit.getType() != HitResult.Type.MISS;
    }

    private static boolean isSolidAt(Level level, Vec3 at) {
        BlockPos pos = BlockPos.containing(at);
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }
}
