package net.createballistics.block.entity;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.createballistics.CreateBallistics;
import net.createballistics.cbc.ActiveBallistics;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.createballistics.ballistic.ArcMode;
import net.createballistics.ballistic.BallisticSolver;
import net.createballistics.ballistic.FireMode;
import net.createballistics.ballistic.ExternalBallistics;
import net.createballistics.ballistic.CannonSpec;
import net.createballistics.ballistic.FiringSolution;
import net.createballistics.registry.ModBlockEntities;
import net.createballistics.util.RangeDisplayState;
import net.createballistics.util.StageText;
import net.createballistics.util.Texts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 火控计算机 —— 解算射击诸元。
 *
 * <p>输入一门炮的参数与目标坐标，输出低伸/高抛两条弹道（仰角、装药、飞行时间）。
 * 解算本身跑在 {@link BallisticSolver} 里 —— 那部分不依赖 Minecraft，可以单独自检。
 *
 * <p>现阶段参数由界面录入；后续会接「自动读取火炮真实参数」，见 {@code CannonProbe}。
 *
 * <p><b>它靠应力干活</b>：背面接传动轴，固定消耗 {@link #STRESS_SU} SU。
 * 没有转速就解不出诸元。
 */
public class FireControlComputerBlockEntity extends KineticBlockEntity
        implements IHaveGoggleInformation, RangeDisplayToggle {

    /**
     * 应力<b>基准值</b>：每 1 RPM 消耗多少 SU。
     *
     * <p>实际消耗 SU = 本值 × |转速|，乘转速那一步由 Create 的 {@code KineticNetwork} 做
     * （{@code getActualStressOf}），方块这边<b>只能返回基准、绝不能自己乘</b>。详见
     * {@link FireControlNodeBlockEntity#STRESS_BASE} 的注释。
     */
    public static final float STRESS_BASE = 64.0F;

    /** 登记协调器的搜索半径（格）。 */
    public static final int SCAN_RADIUS = 64;
    /**
     * 最多带几个协调器。
     *
     * <p>2026-09-19 由 3 提到 <b>4</b>：协调器半径是 32、计算机是 64，
     * 4 台放在计算机中心的 (±32, ±32) 处正好把 ±64 铺满（并集精确相等，中缝各有 1 格重叠）——
     * 3 台的话永远差一个象限。
     *
     * <p>规模：4 台 × 每台 10 门 = <b>最多 40 门炮</b>。
     */
    public static final int MAX_COORDINATORS = 4;
    /** 扫描节流（tick）。 */
    private static final int SCAN_INTERVAL = 20;

    /** 登记进来的协调器坐标。超出上限时按距离丢掉最远的。 */
    private final List<BlockPos> coordinators = new ArrayList<>();
    private int scanCooldown;

    // ---- 炮的参数 ----
    private double minPitch = -30.0;
    private double maxPitch = 60.0;
    private double barrelLength = 4.0;
    private double velocityPerCharge = 2.0;
    private int minCharges = 1;
    private int maxCharges = 1;

    // ---- 目标 ----
    private boolean hasTarget;
    private double targetX;
    private double targetY;
    private double targetZ;

    /**
     * 弹道模式（自动 / 低伸 / 高抛）—— 玩家在这里切，然后随目标一起下发给各炮。
     *
     * <p><b>它只是个"玩家的选择"，不是"全网的结论"</b>：真正决定用哪条弹道的是各节点
     * （每门炮位置不同、遮挡不同）。见 {@code FireControlNodeBlockEntity.chooseArc}。
     */
    private ArcMode arcMode = ArcMode.AUTO;

    /**
     * 火力模式（齐射 / 同时弹着）—— <b>按下「开火！」之后全网怎么打</b>。
     *
     * <p>和 {@link ArcMode}（每门炮用哪条弹道）正交：弹道是逐炮各自判断的，火力模式是全网节奏。
     */
    private FireMode fireMode = FireMode.SALVO;

    /**
     * 这次排期的<b>最后一发落地时刻</b>（{@code 0} = 没在打）。<b>同时弹着 / 轮射共用。</b>
     *
     * <p>两个用途：① 界面上显示倒计时（从按下开火到最后一发落地可能隔几十秒，
     * 不说清楚玩家一定以为卡住了）；② 到点之后把目标清掉、让界面回到"待输入打击坐标"。
     */
    private long salvoEndTick;

    /** 轮射的落点间隔档位（tick）：<b>0.5 / 1 / 2 / 5 秒</b>。 */
    public static final int[] ROLLING_INTERVALS = {10, 20, 40, 100};

    /** 轮射的落点间隔（tick）。用户 2026-09-20 定的走档位、默认 1 秒。 */
    private int rollingIntervalTicks = 20;

    /** 覆盖打击的半径档位（格）：<b>10 / 20 / 30 / 40 / 50</b>（用户 2026-09-20 定）。 */
    public static final int[] COVERAGE_RADII = {10, 20, 30, 40, 50};

    /** 指示器报到多久还算"新鲜"（指示器每 10 tick 报一次，这里留 4 倍余量）。 */
    private static final int REPORT_FRESH_TICKS = 40;

    /**
     * 本机绑定的<b>指示器身份</b>（那个物品身上的 {@code INDICATOR_ID}）；{@code null} = 还没被绑过。
     *
     * <p>⭐ <b>这是一对一的永久配对</b>（用户 2026-09-22 拍板）：一台计算机只认一台指示器、
     * 一台指示器只认一台计算机，而且<b>不会自己过期</b>。
     * 唯一的重置手段是<b>把计算机挖掉重放</b>（方块实体数据没了，配对自然解除）——
     * 也就是说玩家把指示器弄丢了就得拆一次计算机。这是刻意选的简单规则：
     * 「谁在用」这种会自己变的判据，玩家很难预期，不如"绑了就绑着、想换就拆"。
     *
     * <p>⚠️ 绑定关系存在<b>物品</b>上（在玩家身上），计算机这边枚举不到 ——
     * 所以是<b>指示器主动来报到</b>（见 {@code ArtilleryIndicatorItem#inventoryTick}），
     * 但报到<b>只更新"主人离多远"这个显示量</b>，<b>不决定归属</b>。
     */
    @Nullable
    private UUID ownerIndicator;

    /** 主人最后一次报到（tick + 距离）。<b>只用来显示"最近多远"</b>。 */
    @Nullable
    private IndicatorPresence lastReport;

    /** 同步给客户端的距离；−1 = 主人没在报到（收起来了 / 走远了 / 它的区块没加载）。 */
    private double indicatorDistance = -1.0;

    /** 一台指示器的一次报到：它哪个 tick 来的、当时离本机多远。 */
    private record IndicatorPresence(long tick, double distance) {
    }

    /** 覆盖打击的半径（格）。默认最小档。 */
    private int coverageRadius = 10;

    /**
     * 范围显示开关 —— <b>扳手右键</b>切换。理由见
     * {@link FireControlCoordinatorBlockEntity#isRangeVisible()}：范围有 64 格，
     * "看着才显示"根本看不了（一转头准星就离开方块了）。
     */
    private boolean rangeVisible;

    public FireControlComputerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public FireControlComputerBlockEntity(BlockPos pos, BlockState state) {
        this(ModBlockEntities.FIRE_CONTROL_COMPUTER.get(), pos, state);
    }

    // ------------------------------------------------------------------ 应力

    /** 只返回<b>基准值</b>；乘转速那一步由 Create 的 KineticNetwork 做。 */
    @Override
    public float calculateStressApplied() {
        return STRESS_BASE;
    }

    /**
     * 有没有达到最低转速要求。判定<b>完全交给 Create</b>（档位在方块的
     * {@code getMinimumRequiredSpeedLevel()} 里声明），我们不再自己叠加门槛。
     */
    public boolean hasRotationPower() {
        return isSpeedRequirementFulfilled();
    }


    // ------------------------------------------------------------------ 组网

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide) {
            return;
        }

        // ⚠️ 炮数掉到 1 门时**自动切回齐射** —— 2026-09-25 用户要求：
        //    "当火控节点从 2 个手动挖掉 1 个的那一刻，自动就切换好齐射模式了"。
        //    同时弹着 / 轮射 / 覆盖在 1 门炮下和齐射**没有任何区别**，留着那个模式只会让人困惑
        //    （界面写着"同时弹着"，打出来却是齐射）。
        //    ⚠️ 这是"自动切回"，**不是**"把按钮禁掉" —— 后者会把玩家**锁死**
        //       （见 FireControlComputerScreen#refreshFireModeButton 里记的那次教训）。
        if (needsTwoCannons(fireMode) && !hasEnoughCannonsForFireModes()) {
            setFireMode(FireMode.SALVO);
        }

        // ---- 同时弹着：到点了收尾 ----
        // 目标在等待期间必须一直留着（各炮靠它保持瞄准），所以清目标的时机只能放在这里 ——
        // 落点时刻之后再清，界面才会干净地回到「待输入打击坐标」。
        if (salvoEndTick != 0 && level.getGameTime() > salvoEndTick) {
            salvoEndTick = 0;
            clearTarget();
            return;
        }

        // 指示器的"主人离多远"每 tick 都要刷新 —— 而且必须在转速门槛之前：
        // 没应力时也要能把它刷成「不在场」，否则那行数字会一直停在最后一次的值
        refreshIndicatorDisplay();

        if (!hasRotationPower()) {
            return;   // 没应力不干活
        }
        if (--scanCooldown <= 0) {
            scanCooldown = SCAN_INTERVAL;
            scanCoordinators();
        }
    }

    /**
     * 周期性地把半径内的火控协调器登记进来。
     *
     * <p>超出 {@link #MAX_COORDINATORS} 时按距离取最近的 —— 排序保证结果可复现，
     * 不受区块遍历顺序影响。
     */
    private void scanCoordinators() {
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(
                worldPosition.offset(-SCAN_RADIUS, -SCAN_RADIUS, -SCAN_RADIUS),
                worldPosition.offset(SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS))) {
            if (level.getBlockEntity(p) instanceof FireControlCoordinatorBlockEntity) {
                found.add(p.immutable());
            }
        }
        found.sort((a, b) -> Double.compare(a.distSqr(worldPosition), b.distSqr(worldPosition)));
        if (found.size() > MAX_COORDINATORS) {
            found.subList(MAX_COORDINATORS, found.size()).clear();
        }
        // 把「被谁管辖」反向写回给每台协调器 —— 协调器自己不知道上线到了谁，
        // 「已接入火控计算机」这条信息必须由我们告诉它。被剔除的也要清掉。
        List<BlockPos> previous = new ArrayList<>(coordinators);
        coordinators.clear();
        coordinators.addAll(found);
        for (BlockPos p : coordinators) {
            if (level.getBlockEntity(p) instanceof FireControlCoordinatorBlockEntity c) {
                c.setMaster(worldPosition);
            }
        }
        // 被除名的：只有它的上级还是本计算机时才清，避免抢了别台计算机的下线关系。
        for (BlockPos p : previous) {
            if (coordinators.contains(p)) {
                continue;
            }
            if (level.getBlockEntity(p) instanceof FireControlCoordinatorBlockEntity c
                    && worldPosition.equals(c.getMasterPos())) {
                c.setMaster(null);
            }
        }

        if (!found.equals(previous)) {
            setChanged();
            sendData();
        }
    }

    public int getCoordinatorCount() {
        return coordinators.size();
    }

    /** 已登记的协调器坐标（界面列表要显示「在哪」）。顺序就是界面上的 #1 / #2 编号顺序。 */
    public List<BlockPos> getCoordinators() {
        return coordinators;
    }

    // ------------------------------------------------------------------ 范围显示

    public boolean isRangeVisible() {
        return rangeVisible;
    }

    /** 扳手右键：切换范围显示。 */
    public void toggleRangeVisible() {
        this.rangeVisible = !rangeVisible;
        setChanged();
        sendData();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        // 客户端登记 —— 渲染器靠这份名单找该画哪个框，见 {@link RangeDisplayState}。
        if (level != null && level.isClientSide) {
            RangeDisplayState.set(worldPosition, rangeVisible);
        }
    }

    /**
     * 一台指示器报到（由 {@code ArtilleryIndicatorItem#inventoryTick} 每 10 tick 调一次）。
     *
     * <p>⚠️ <b>报到只更新"主人离多远"这个显示量，不决定归属</b> —— 归属是
     * {@link #ownerIndicator} 里那条永久配对。不是主人的报到一律忽略：
     * 指示器身上的绑定记录不会因为被拒绝而消失，放它进来就会两家互相覆盖目标。
     */
    public void reportIndicator(Player player, @Nullable UUID indicatorId) {
        if (level == null || level.isClientSide || !isOwner(indicatorId)) {
            return;
        }
        lastReport = new IndicatorPresence(level.getGameTime(), Math.sqrt(player.distanceToSqr(
                worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5)));
        refreshIndicatorDisplay();
    }

    /**
     * 刷新"主人离多远"这个同步量。
     *
     * <p>⚠️ 必须<b>每 tick 都跑</b>，而且要跑在转速门槛之前 —— 主人的报到一停，
     * 就<b>没有任何事件会来刷新它</b>，界面上那个距离会一直停在最后一次的值。
     */
    private void refreshIndicatorDisplay() {
        if (level == null || level.isClientSide) {
            return;
        }
        boolean fresh = lastReport != null && level.getGameTime() - lastReport.tick() <= REPORT_FRESH_TICKS;
        double distance = fresh ? lastReport.distance() : -1.0;
        // 只在"看得见的量"真的变了才发包 —— 否则每 tick 白灌
        if (Math.abs(distance - indicatorDistance) >= 0.5) {
            indicatorDistance = distance;
            sendData();
        }
    }

    /**
     * 有几门炮<b>最近一发没打出去</b>（CBC 判定卡弹 / 炸膛）。
     *
     * <p>这种失败在 CBC 那边是静默的：整发装填被消耗掉，世界上却没多出一颗弹丸。
     * 不把它统计出来的话，玩家看到的就是"膛空了、什么也没飞"，只能猜是模组坏了。
     */
    public int getFailedFireCount() {
        int failed = 0;
        for (FireControlNodeBlockEntity node : allNodes()) {
            if (node.isLastFireFailed()) {
                failed++;
            }
        }
        return failed;
    }

    /** 本机有没有被绑走。 */
    public boolean hasIndicator() {
        return ownerIndicator != null;
    }

    /** 指定的这台指示器是不是本机的主人。 */
    public boolean isOwner(@Nullable UUID indicatorId) {
        return ownerIndicator != null && ownerIndicator.equals(indicatorId);
    }

    /** 把本机绑给这台指示器（调用方负责先确认本机空着）。 */
    public void claimIndicator(UUID indicatorId) {
        ownerIndicator = indicatorId;
        lastReport = null;
        indicatorDistance = -1.0;
        setChanged();
        sendData();
    }

    /** 解绑 —— 只有主人自己能解（改绑时旧的那台由指示器负责放开）。 */
    public void releaseIndicator(@Nullable UUID indicatorId) {
        if (!isOwner(indicatorId)) {
            return;
        }
        ownerIndicator = null;
        lastReport = null;
        indicatorDistance = -1.0;
        setChanged();
        sendData();
    }

    /** 主人没在报到时返回 −1。 */
    public double getIndicatorDistance() {
        return indicatorDistance;
    }

    /**
     * 「已绑定火炮指示器」那一行的<b>后半截</b> —— 界面与护目镜共用这一份，别再各写一套。
     *
     * <p>⚠️ <b>刻意不提数量</b>：一台计算机只绑一台指示器（永久配对），数量恒为 0 或 1，
     * 写「1 台」是画蛇添足（2026-09-22 用户指出）。主人没在报到时改说「不在场」。
     */
    public Component indicatorSummary() {
        if (ownerIndicator == null) {
            return Component.translatable("gui.create_ballistics.indicators_none");
        }
        return indicatorDistance >= 0
                ? Component.translatable("gui.create_ballistics.indicators_one",
                        Texts.num("%.0f", indicatorDistance))
                : Component.translatable("gui.create_ballistics.indicators_away");
    }

    /**
     * 全网**去重后**的炮位。
     *
     * <p>⚠️ <b>为什么必须去重</b>：协调器的识别半径是 32 格 —— <b>多台协调器挨着放时会同时
     * 看到同一门炮</b>。2026-09-24 用户实测：实际只有 4 门炮，计算机界面却报 <b>7</b> 门；
     * 逐条对下来正是「#1 报 4 门、#2/#3/#4 各报 1 门」，而后面那 3 门就是 #1 已经报过的。
     *
     * <p>⇒ <b>所有"全网有多少门 / 多少就位 / 多少打不着"都必须走这里</b>，别再去累加各协调器
     * 自己的计数 —— 那个加法默认了"各协调器的识别范围互不重叠"，而那个假设只在
     * "4 台按 ±32 摆开"这一种摆法下才成立。
     */
    public Set<BlockPos> distinctNodes() {
        Set<BlockPos> out = new HashSet<>();
        if (level == null) {
            return out;
        }
        for (BlockPos p : coordinators) {
            if (level.getBlockEntity(p) instanceof FireControlCoordinatorBlockEntity c) {
                out.addAll(c.getNodes());
            }
        }
        return out;
    }

    /** 全网有多少门炮已经解算完、转到位了（<b>去重</b>；判定与协调器那边逐字一致）。 */
    public int getReadyCount() {
        if (level == null) {
            return 0;
        }
        int ready = 0;
        for (BlockPos p : distinctNodes()) {
            if (level.getBlockEntity(p) instanceof FireControlNodeBlockEntity node && node.isAimed()) {
                ready++;
            }
        }
        return ready;
    }

    /**
     * 全网有没有**任意一门炮**正骑在会动的物理结构（航空学载具）上 —— 「实时计算」状态的依据。
     *
     * <p>"任意"就够了：这行字描述的是**全网**，只要有一门在实时跟踪，这条线就该说实时，
     * 而不是「计算完毕」。
     */
    public boolean isAnyNodeMoving() {
        if (level == null) {
            return false;
        }
        for (BlockPos p : distinctNodes()) {
            if (level.getBlockEntity(p) instanceof FireControlNodeBlockEntity node
                    && node.isPlatformMoving()) {
                return true;
            }
        }
        return false;
    }

    /** 当前要求的最低转速（RPM）。界面要用。 */
    public float requiredRpm() {
        return getBlockState().getBlock() instanceof com.simibubi.create.content.kinetics.base.IRotate rotate
                ? rotate.getMinimumRequiredSpeedLevel().getSpeedValue() : 0.0F;
    }

    /**
     * <b>本轮不参与的炮</b>，按协调器汇总：协调器序号（1 起）→ 有几门。
     *
     * <p>界面就靠它画那几行「无法射击：#3 协调器有 2 门炮」——
     * <b>一台协调器一行、按序号从小到大</b>排（`LinkedHashMap` + 序号递增遍历，天然有序）。
     *
     * <p>⚠️⚠️ <b>文案不变、口径在 2026-09-26 变宽了</b>（用户拍板）：
     * 两种炮都算「不参与本轮射击」，因为**表现完全一样** —— 都是 `isAimed()` 永不成立、
     * 于是永远不进 `readyNodes()`：
     * <ol>
     *   <li>解不出来的（超射程 / 被挡 / 炮口被堵 / 没装填）；</li>
     *   <li>⭐ <b>炮塔转速跟不上目标的</b>（`isRpmShort()`，P3）—— 能打，但要给炮座加转速。</li>
     * </ol>
     * 玩家在计算机前面只能看到「正在计算 6 / 10」，不知道那 4 门怎么了 ⇒ 点名到协调器，
     * **逐门的原因**在协调器界面那一列状态里（`ArcFail` 或「转速不够」）。
     *
     * <p>⚠️ 与 {@link #getCannonCount()} 一样<b>必须按炮位去重</b>：
     * 多台协调器挨着放时会同时看到同一门炮，一律归给**第一台**登记它的协调器
     * ⇒ 一门炮只会被点名一次、也只会挂在某一个编号下。
     */
    public Map<Integer, Integer> notParticipatingByCoordinator() {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        if (level == null) {
            return out;
        }
        final long now = level.getGameTime();
        Set<BlockPos> seen = new HashSet<>();
        int coordinatorIndex = 1;
        for (BlockPos cp : coordinators) {
            if (level.getBlockEntity(cp) instanceof FireControlCoordinatorBlockEntity c) {
                int bad = 0;
                for (BlockPos np : c.getNodes()) {
                    if (!seen.add(np)) {
                        continue;   // 已经被前面的协调器登记过 ⇒ 这一门不算它的
                    }
                    // ⚠️ 正在参加本轮射击的炮**不算"打不着"** —— 它们打完膛里空了、于是解不出来，
                    //    但那是"已开火、在等整轮结束"。不排掉的话，同时弹着刚打出去，
                    //    界面上就会把刚开火的那几门点名成"打不着的炮"（2026-09-22 用户实测报的）。
                    if (level.getBlockEntity(np) instanceof FireControlNodeBlockEntity n
                            && n.hasTarget() && !n.inSalvo(now)
                            && (!n.isSolvable() || n.isRpmShort())) {
                        bad++;
                    }
                }
                if (bad > 0) {
                    out.put(coordinatorIndex, bad);
                }
            }
            coordinatorIndex++;
        }
        return out;
    }

    /**
     * 第一门打不到的炮的<b>原因</b>。
     *
     * <p>为什么要它：界面上原来无论什么原因都只说「目标超出射程」，而真实原因常常是
     * <b>被山挡住</b>或<b>仰角不够</b>。那句错话只有炮位的护目镜上才纠正得过来，
     * 而玩家在计算机面前看不到护目镜 —— 2026-09-21 用户实测报的。
     *
     * <p>读的是节点上<b>已经同步到客户端</b>的东西，所以客户端直接能算，不需要新增同步。
     *
     * @param fail  分类 —— <b>界面</b>用它显示一行简短结论（面板上只能放一行）
     * @param key   完整说明的语言键；空串表示"真够不着、没别的话可说"
     * @param a     完整说明的第一个参数（撞到的距离 / 需要多少度）
     * @param b     完整说明的第二个参数
     */
    public record UnsolvableNote(FireControlNodeBlockEntity.ArcFail fail, String key, double a, double b) {
    }

    /** @return 没有打不到的炮时返回 {@code null} */
    @Nullable
    public UnsolvableNote findUnsolvableNote() {
        if (level == null) {
            return null;
        }
        // 同 findUnreachable()：正在参加本轮射击的炮不算"打不着"，它们只是打完了在等整轮结束
        final long now = level.getGameTime();
        for (BlockPos cp : coordinators) {
            if (level.getBlockEntity(cp) instanceof FireControlCoordinatorBlockEntity c) {
                for (BlockPos np : c.getNodes()) {
                    if (level.getBlockEntity(np) instanceof FireControlNodeBlockEntity n
                            && n.hasTarget() && !n.isSolvable() && !n.inSalvo(now)) {
                        return new UnsolvableNote(n.getArcFail(), n.getArcNoteKey(),
                                n.getArcNoteA(), n.getArcNoteB());
                    }
                }
            }
        }
        return null;
    }

    /**
     * 全网有多少门炮解不出诸元（比如目标超出射程）。界面用它区分「还在算」和「算不出来」。
     *
     * <p>⚠️ <b>去重</b>，见 {@link #distinctNodes()}。
     */
    public int getUnsolvableCount() {
        if (level == null) {
            return 0;
        }
        int n = 0;
        long now = level.getGameTime();
        for (BlockPos p : distinctNodes()) {
            if (level.getBlockEntity(p) instanceof FireControlNodeBlockEntity node
                    && node.hasTarget() && !node.isSolvable() && !node.inSalvo(now)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 全网登记到多少门炮。
     *
     * <p>⚠️ <b>去重</b>，见 {@link #distinctNodes()} —— 这一条正是用户看到"4 门报成 7 门"的那处。
     */
    public int getCannonCount() {
        return distinctNodes().size();
    }

    /**
     * 把<b>目标坐标</b>下发给所有协调器，再由协调器下发给各自登记的炮 —— 各炮自己解算。
     *
     * <p>计算机刻意<b>不下发角度</b>：每门炮的装填、弹种、管长、初速都不同，
     * 一组诸元只对恰好相同的那门炮有效。
     *
     * @return 有没有至少下发到一个协调器
     */
    public boolean layTarget() {
        if (!hasRotationPower() || !hasTarget || level == null || level.isClientSide()) {
            return false;
        }
        if (fireMode == FireMode.COVERAGE) {
            return layCoverage();
        }
        boolean any = false;
        for (BlockPos p : coordinators) {
            if (level.getBlockEntity(p) instanceof FireControlCoordinatorBlockEntity c) {
                // ⚠️ 必须先把自己的坐标告诉协调器 —— layAllTarget() 一上来就检查它自己的 hasTarget，
                // 不设的话它直接 return false，节点一辈子收不到军令（2026-09-17 实机踩到：
                // 计算机界面显示"正在计算… 已就位 0/1"永远不动，炮纹丝不动，就是漏了这一句）。
                c.setTarget(targetX, targetY, targetZ);
                // 弹道模式一起带上 —— 协调器那份可能还停在默认值（刚放下的、或存档后刚加载的），
                // 不推的话它会把 AUTO 下发给一门本来该打高抛的炮。
                c.setMode(arcMode);
                if (c.layAllTarget()) {
                    any = true;
                }
            }
        }
        return any;
    }

    /**
     * <b>覆盖打击</b>：给每门炮分配一个<b>自己的目标点</b>（绕目标铺开成同心圆环），各自解算。
     *
     * <p>⚠️ 这条**绕开了协调器的 {@code layAllTarget()}** —— 那个方法给所有节点发同一个坐标，
     * 而覆盖打击的要点恰恰是"每门炮打不同的点"。所以这里由计算机**直接逐炮点名**
     * （横向收集全网节点，和 {@code scheduleRolling} 一个路子）。
     *
     * <p>⚠️ 目标高度统一传主目标的 Y，<b>不需要在这里逐点算地形高度</b>：
     * 每个节点解算前会自己按<b>它那个点</b>的坐标去问地形高度（见 {@code resolveAimHeight}）。
     * "斜坡上各点高度不同"这件事，架构上天然就对了。
     */
    private boolean layCoverage() {
        List<FireControlNodeBlockEntity> nodes = allNodes();
        if (nodes.isEmpty()) {
            return false;
        }
        final double[][] offsets = coverageOffsets(nodes.size(), coverageRadius);
        for (int i = 0; i < nodes.size(); i++) {
            FireControlNodeBlockEntity node = nodes.get(i);
            // ⚠️ 最后一参数 = **强制吸附地面高度**：每个点的高度统一传主目标的 Y，
            //    而陡坡上相邻点（半径可达 50 格）地面高度差很容易超过那条 ±10 格容差，
            //    不强制的话半个圆的炮弹会按错误的基准高度解算、整片打偏。
            node.aimAt(targetX + offsets[i][0], targetY, targetZ + offsets[i][1], arcMode, true);
        }
        // 协调器那边也同步一下目标坐标（它的面板要显示"在打哪"），但**不下发坐标** ——
        // 各炮的点位由上面直接点名，不走协调器
        for (BlockPos p : coordinators) {
            if (level.getBlockEntity(p) instanceof FireControlCoordinatorBlockEntity c) {
                c.setTarget(targetX, targetY, targetZ);
                c.setMode(arcMode);
            }
        }
        return true;
    }

    /**
     * 重新下发一次目标坐标。
     *
     * <p><b>计算机刻意不自己解算</b> —— 每门炮的装填、弹种、管长、初速都不同，一组诸元只对
     * 恰好相同的那门炮有效。所以诸元一律由各节点探自己那门炮后各自解算，
     * 见 {@code FireControlNodeBlockEntity#solveForTarget}。这里只是"确认坐标"那个入口。
     */
    public void calculate() {
        layTarget();
    }

    /**
     * 全网开火，<b>随即清掉目标</b> —— 一轮打完界面就回到「待输入打击坐标 / 未设定目标」，
     * 免得玩家以为还能对着同一个目标接着按。
     *
     * <p>清目标不会打掉这一炮：节点那边的开火条件是 {@code fireQueued && !aiming}，
     * {@code clearTarget()} 只清目标、保留 {@code fireQueued}，反而让它在当 tick 就击发。
     */
    @Nullable
    public String fireAll() {
        if (!hasRotationPower()) {
            return "gui.create_ballistics.fire_nopower";
        }
        if (level == null || level.isClientSide()) {
            return null;   // 走不到：包处理器已经确认过"方块实体存在且是服务端"
        }
        if (!hasTarget) {
            return "gui.create_ballistics.fire_notarget";
        }
        if (fireMode == FireMode.TOT) {
            return scheduleTot();
        }
        if (fireMode == FireMode.ROLLING) {
            return scheduleRolling();
        }
        int fired = 0;
        for (BlockPos p : coordinators) {
            if (level.getBlockEntity(p) instanceof FireControlCoordinatorBlockEntity c) {
                fired += c.fireAll();
            }
        }
        // ⚠️ 一门都没打出去时<b>不清目标</b>。清了的话玩家会看到目标莫名其妙消失、
        // 还得重新输入一遍坐标，却不知道刚才那一炮为什么没响。典型情形就是炮还在转
        // （未就位），或者目标超出射程导致所有炮都解不出诸元。
        if (fired == 0) {
            return "gui.create_ballistics.fire_nothing_ready";
        }
        clearTarget();
        return null;
    }

    /**
     * <b>同时弹着（TOT）</b>：算出最远那一门的飞行时间，各炮按飞行时间倒推击发时刻，
     * 让所有炮弹<b>同一瞬间落地</b>。
     *
     * <p>⚠️ <b>这里刻意不清目标</b>（齐射那条路才清）。目标是各炮保持瞄准的唯一依据 ——
     * 清了节点就撒手，等待期间炮位一漂，到点点火时打的就不是原来那个角度了。
     * 清目标的时机改由 {@code tick()} 在<b>落点时刻之后</b>做。
     */
    private String scheduleTot() {
        double maxAir = 0.0;
        for (BlockPos p : coordinators) {
            if (level.getBlockEntity(p) instanceof FireControlCoordinatorBlockEntity c) {
                maxAir = Math.max(maxAir, c.getMaxAirTime());
            }
        }
        // 一门"已就位"的炮都没有 —— 跟齐射一样什么都不做，也不清目标
        if (maxAir <= 0.0) {
            return "gui.create_ballistics.fire_nothing_ready";
        }
        // 落点时刻 = 现在 + 最远那门的飞行时间。于是最远那门立刻开火，其余依次延后。
        long baseTick = level.getGameTime() + (long) Math.ceil(maxAir);
        int scheduled = 0;
        for (BlockPos p : coordinators) {
            if (level.getBlockEntity(p) instanceof FireControlCoordinatorBlockEntity c) {
                scheduled += c.scheduleSalvo(baseTick, FireMode.TOT);
            }
        }
        if (scheduled == 0) {
            return "gui.create_ballistics.fire_nothing_ready";
        }
        salvoEndTick = baseTick;
        setChanged();
        sendUpdate();
        return null;
    }

    /**
     * <b>轮射</b>：各炮按飞行时间倒推击发时刻，让<b>落点等间隔</b>排开。
     *
     * <p>和 {@link #scheduleTot()} 是<b>同一个式子的两端</b>：都是「落点时刻 − 自己的飞行时间」，
     * 只是 TOT 让所有落点时刻相同，轮射让它们依次差 {@link #rollingIntervalTicks}。
     *
     * <p>顺序按飞行时间<b>从长到短</b>：飞得最久的那门落第一个、也最先开火。
     * 这样排出来的击发时刻<b>天然递增</b>，可以从算上证明不会出现负延时：
     * <pre>
     *   击发_i = (now + ⌈t₀⌉) + i·d − ⌈tᵢ⌉ ，而 t₀ ≥ tᵢ
     *   ⇒ 击发_i ≥ now + i·d ≥ now　（永远在未来）
     * </pre>
     * <p>用户 2026-09-20 明确取消了"按协调器编号排序"的要求 ——
     * 玩家看的是打击目标那一头，远炮近炮谁先开火都无所谓。
     */
    private String scheduleRolling() {
        List<FireControlNodeBlockEntity> ready = readyNodes();
        if (ready.isEmpty()) {
            // 一门就位的都没有 → 跟别的模式一样什么都不做、也不清目标
            CreateBallistics.LOGGER.info("[火控] 轮射排期失败：全网 {} 个节点，没有一个 isAimed（已就位={}）",
                    allNodes().size(), getReadyCount());
            return "gui.create_ballistics.fire_nothing_ready";
        }
        ready.sort(Comparator.comparingDouble(FireControlNodeBlockEntity::getSolutionAirTime).reversed());

        final long now = level.getGameTime();
        // 第 0 发（飞得最久的那门）在"现在 + 最长飞行时间"落地，之后每 d tick 一发
        final long firstImpact = now + (long) Math.ceil(ready.get(0).getSolutionAirTime());

        int scheduled = 0;
        final long end = firstImpact + (long) (ready.size() - 1) * rollingIntervalTicks;
        for (int i = 0; i < ready.size(); i++) {
            FireControlNodeBlockEntity node = ready.get(i);
            long at = firstImpact + (long) i * rollingIntervalTicks
                    - (long) Math.ceil(node.getSolutionAirTime());
            // 整轮落地时刻与模式一起下发 —— 节点要拿它显示"已开火，等整轮结束"
            if (node.scheduleFire(Math.max(at, 1L), end, FireMode.ROLLING)) {
                scheduled++;
            }
        }
        if (scheduled == 0) {
            return "gui.create_ballistics.fire_nothing_ready";
        }
        salvoEndTick = end;
        setChanged();
        sendUpdate();
        return null;
    }

    /**
     * 全网<b>已就位</b>的炮（按协调器 → 炮位顺序收集）。
     *
     * <p>轮射要<b>全网统一排号</b>（第 i 发落在什么时候），所以只能在这里横向收集，
     * 没法像 TOT 那样让各协调器各自算 —— TOT 的延时只取决于每门炮自己，轮射的却取决于它的名次。
     */
    private List<FireControlNodeBlockEntity> readyNodes() {
        // ⚠️⚠️ 必须返回**可变**列表：调用方（轮射）要按飞行时间排序，
        //   而 `Stream.toList()` 给的是**不可变列表** —— `sort` 会抛 UnsupportedOperationException，
        //   这个异常又被网络层吞进日志 ⇒ 表现就是"点了开火什么都没发生"
        //   （2026-09-23 实测：**只有轮射开不了炮**，因为只有它排序；查了两轮才在日志里看到堆栈）。
        return new ArrayList<>(allNodes().stream().filter(FireControlNodeBlockEntity::isAimed).toList());
    }

    /**
     * 全网登记到的所有节点（按协调器 → 炮位顺序）。
     *
     * <p>⚠️ **顺序必须稳定可复现** —— 覆盖打击要给每门炮分配一个自己的点位，
     * 顺序一乱、各炮就会互相换点（而且是在玩家眼皮底下换，看着像随机的）。
     * 协调器列表和炮位列表都是扫完按距离排好序的，所以这里是确定的。
     */
    private List<FireControlNodeBlockEntity> allNodes() {
        List<FireControlNodeBlockEntity> out = new ArrayList<>();
        if (level == null) {
            return out;
        }
        for (BlockPos cp : coordinators) {
            if (level.getBlockEntity(cp) instanceof FireControlCoordinatorBlockEntity c) {
                for (BlockPos np : c.getNodes()) {
                    if (level.getBlockEntity(np) instanceof FireControlNodeBlockEntity n) {
                        out.add(n);
                    }
                }
            }
        }
        return out;
    }

    /**
     * <b>把 n 门炮铺在半径 {@code radius} 的圆面上</b> —— 「同心圆环」铺法。
     *
     * <p>正中心 1 点（保证圆心一定被打到），其余由内到外一圈圈铺；
     * 每圈大约 6 个点（再多就看不出一圈了）。用户 2026-09-20 选的就是这个图案
     * （"像真实炮击覆盖一样"）。
     *
     * <p>⚠️ <b>点数 = 炮数</b>，所以门炮少的时候点必然稀 —— 那不是 bug，
     * 是"一次铺开"的必然结果。界面要把平均点间距算给玩家看，让他自己决定半径。
     *
     * @return 每个点的 {@code (dx, dz)} 偏移；下标即炮的序号
     */
    public static double[][] coverageOffsets(int n, double radius) {
        final double[][] out = new double[Math.max(n, 0)][];
        if (n <= 0) {
            return out;
        }
        out[0] = new double[]{0.0, 0.0};   // 圆心
        final int rest = n - 1;
        if (rest == 0) {
            return out;
        }

        final int rings = Math.max(1, (int) Math.ceil(rest / 6.0));
        int placed = 0;
        for (int k = 1; k <= rings && placed < rest; k++) {
            final double r = radius * k / rings;
            // 剩下的点均摊到还剩的圈数上 —— 别全堆在最外圈，内圈也得有
            final int left = rest - placed;
            final int leftRings = rings - k + 1;
            final int count = (int) Math.ceil((double) left / leftRings);
            for (int j = 0; j < count && placed < rest; j++) {
                // ⚠️ 相邻两圈错开半个间隔 —— 否则各圈的点会沿同一批半径对齐，
                //    看上去像放射状的辐条，而不是"铺开的一片"
                final double angle = 2.0 * Math.PI * (j + 0.5 * (k % 2)) / count;
                out[++placed] = new double[]{r * Math.cos(angle), r * Math.sin(angle)};
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 参数读写

    public double getMinPitch() {
        return minPitch;
    }

    public void setMinPitch(double v) {
        this.minPitch = v;
        setChanged();
    }

    public double getMaxPitch() {
        return maxPitch;
    }

    public void setMaxPitch(double v) {
        this.maxPitch = v;
        setChanged();
    }

    public double getBarrelLength() {
        return barrelLength;
    }

    public void setBarrelLength(double v) {
        this.barrelLength = v;
        setChanged();
    }

    public double getVelocityPerCharge() {
        return velocityPerCharge;
    }

    public void setVelocityPerCharge(double v) {
        this.velocityPerCharge = v;
        setChanged();
    }

    public int getMinCharges() {
        return minCharges;
    }

    public void setMinCharges(int v) {
        this.minCharges = Math.max(1, v);
        setChanged();
    }

    public int getMaxCharges() {
        return maxCharges;
    }

    public void setMaxCharges(int v) {
        this.maxCharges = Math.max(1, v);
        setChanged();
    }

    public boolean hasTarget() {
        return hasTarget;
    }

    public double getTargetX() {
        return targetX;
    }

    public double getTargetY() {
        return targetY;
    }

    public double getTargetZ() {
        return targetZ;
    }

    public void setTarget(double x, double y, double z) {
        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
        this.hasTarget = true;
        setChanged();
        // ⚠️ 必须自己同步 —— 界面读的是客户端那份副本。以前这句是靠 calculate() 末尾顺带做的，
        // 改成"只转发不下发"之后那条路径就断了：服务端炮照常瞄准，界面却永远卡在「未设定目标」
        // （2026-09-17 实机踩到，症状是「目标: 未设定目标」和「已就位: 1 / 1」同时出现）。
        sendUpdate();
        calculate();
    }

    public ArcMode getArcMode() {
        return arcMode;
    }

    public FireMode getFireMode() {
        return fireMode;
    }

    /**
     * 这个火力模式<b>是不是要有 2 门炮才有意义</b>。
     *
     * <p>同时弹着 / 轮射 / 范围覆盖都是"在<b>多门炮之间</b>做文章"的 —— 只有 1 门炮时它们
     * 和齐射<b>没有任何区别</b>，玩家会以为"选了没生效"（2026-09-25 用户实测报的）。
     *
     * <p>⚠️ 齐射不受限制：它就是"开火"，1 门炮照样要能用。
     */
    public static boolean needsTwoCannons(FireMode mode) {
        return mode == FireMode.TOT || mode == FireMode.ROLLING || mode == FireMode.COVERAGE;
    }

    /** 现在炮够不够换到"需要多门炮"的模式。 */
    public boolean hasEnoughCannonsForFireModes() {
        return getCannonCount() >= 2;
    }

    /**
     * 切到下一个火力模式（界面按钮就是点它）。
     *
     * <p>⚠️ <b>会跳过"炮不够"的那些模式</b>：只有 1 门炮时，从齐射切一圈会转回齐射
     * （也就是"按了没变化"）。**"没变化"这件事由调用方去提示** ——
     * {@code ComputerActionC2SPacket} 的处理器比较切换前后就知道该不该说话，
     * 而包处理器手里才有 {@code player}（方块实体没有）。
     */
    public void cycleFireMode() {
        FireMode next = fireMode;
        for (int i = 0; i < FireMode.values().length; i++) {
            next = next.next();
            if (!needsTwoCannons(next) || hasEnoughCannonsForFireModes()) {
                break;
            }
        }
        setFireMode(next);
    }

    public void setFireMode(FireMode mode) {
        this.fireMode = mode;
        setChanged();
        sendUpdate();
        // ⚠️ 换模式必须**重新下发目标**：覆盖打击是"逐炮各自的点位"，
        // 切进切出都会换一套坐标；其它模式则是同一点。不重发的话各炮还瞄着上一次的点。
        if (hasTarget) {
            layTarget();
        }
    }

    /** 这次排期的最后一发落地时刻（{@code 0} = 没在打）。界面拿它显示倒计时。 */
    public long getSalvoEndTick() {
        return salvoEndTick;
    }

    public int getRollingIntervalTicks() {
        return rollingIntervalTicks;
    }

    public int getCoverageRadius() {
        return coverageRadius;
    }

    /** 轮到下一个半径档位（10 → 20 → 30 → 40 → 50 → 10）。界面按钮就是点它。 */
    public void cycleCoverageRadius() {
        int idx = 0;
        for (int i = 0; i < COVERAGE_RADII.length; i++) {
            if (COVERAGE_RADII[i] == coverageRadius) {
                idx = i;
                break;
            }
        }
        coverageRadius = COVERAGE_RADII[(idx + 1) % COVERAGE_RADII.length];
        setChanged();
        sendUpdate();
        // 半径变了 = 每个炮位分到的点变了 → 重新下发一遍，各炮会各自重解
        if (hasTarget) {
            layTarget();
        }
    }

    /**
     * 覆盖打击下<b>平均</b>每点间隔多少格（估算，给玩家判断"炮太少 / 半径太大"用）。
     *
     * <p>按"把圆面积均分给每门炮"算：{@code 间距 ≈ √(π·R² / n)}。
     * 这只是个量级参考 —— 实际是同心圆环铺设，内圈会密一点、外圈稀一点。
     */
    public double coverageSpacing() {
        int n = getCannonCount();
        if (n <= 0) {
            return 0.0;
        }
        return Math.sqrt(Math.PI * coverageRadius * coverageRadius / n);
    }

    /** 轮到下一个间隔档位（0.5 → 1 → 2 → 5 秒 → 0.5）。界面按钮就是点它。 */
    public void cycleRollingInterval() {
        int idx = 0;
        for (int i = 0; i < ROLLING_INTERVALS.length; i++) {
            if (ROLLING_INTERVALS[i] == rollingIntervalTicks) {
                idx = i;
                break;
            }
        }
        rollingIntervalTicks = ROLLING_INTERVALS[(idx + 1) % ROLLING_INTERVALS.length];
        setChanged();
        sendUpdate();
    }

    /** 切到下一个弹道模式（界面按钮就是点它）。 */
    public void cycleArcMode() {
        setArcMode(arcMode.next());
    }

    /**
     * 设置弹道模式，<b>并立刻推给全网</b>。
     *
     * <p>光改自己这个字段是没用的 —— 决策在各节点里。而节点一旦转到位就撒手、
     * 不会自己重算，所以推过去的同时必须逼它们重解，否则玩家切了模式、
     * 界面显示新模式，炮却还按旧弹道瞄着（和"换装药后不重解"是同一个坑）。
     */
    public void setArcMode(ArcMode mode) {
        this.arcMode = mode;
        setChanged();
        sendUpdate();
        if (level == null || level.isClientSide()) {
            return;
        }
        for (BlockPos p : coordinators) {
            if (level.getBlockEntity(p) instanceof FireControlCoordinatorBlockEntity c) {
                // 协调器会继续往下推给各节点，并让它们重解
                c.setMode(mode);
            }
        }
    }

    /** 清除打击坐标：本机与所有协调器一起清，各节点随即交还炮座。 */
    public void clearTarget() {
        this.hasTarget = false;
        // 玩家中途清目标 = 取消这一轮，倒计时也跟着作废
        this.salvoEndTick = 0;
        if (level != null && !level.isClientSide()) {
            for (BlockPos p : coordinators) {
                if (level.getBlockEntity(p) instanceof FireControlCoordinatorBlockEntity c) {
                    c.clearTarget();
                }
            }
        }
        setChanged();
        sendUpdate();
    }

    // ------------------------------------------------------------------ 生命周期

    public void onRemoved() {
        coordinators.clear();
    }

    private void sendUpdate() {
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    // 注意：不能覆盖 saveAdditional/loadAdditional —— SmartBlockEntity 把它们标了 final。
    // Create 的钩子是下面这对 read/write(..., clientPacket)。
    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putDouble("MinPitch", minPitch);
        tag.putDouble("MaxPitch", maxPitch);
        tag.putDouble("BarrelLength", barrelLength);
        tag.putDouble("VelocityPerCharge", velocityPerCharge);
        tag.putInt("MinCharges", minCharges);
        tag.putInt("MaxCharges", maxCharges);
        tag.putBoolean("HasTarget", hasTarget);
        if (hasTarget) {
            tag.putDouble("TargetX", targetX);
            tag.putDouble("TargetY", targetY);
            tag.putDouble("TargetZ", targetZ);
        }

        // 协调器列表也要同步 —— 护目镜跑在客户端，不同步的话那边永远是 0
        ListTag coords = new ListTag();
        for (BlockPos p : coordinators) {
            CompoundTag e = new CompoundTag();
            e.putInt("X", p.getX());
            e.putInt("Y", p.getY());
            e.putInt("Z", p.getZ());
            coords.add(e);
        }
        tag.put("Coordinators", coords);
        tag.putBoolean("RangeVisible", rangeVisible);
        tag.putString("ArcMode", arcMode.name());
        tag.putString("FireMode", fireMode.name());
        tag.putLong("SalvoEndTick", salvoEndTick);
        tag.putInt("RollingInterval", rollingIntervalTicks);
        tag.putInt("CoverageRadius", coverageRadius);
        // 指示器账本不进存档，但这两个显示量要同步给客户端（界面与护目镜都读它）
        // ⚠️ 配对的指示器身份<b>必须进存档</b> —— 它是一对一永久配对，
        //    而"重置"的唯一手段就是拆掉这台计算机（拆了数据就没了，配对自然解除）。
        if (ownerIndicator != null) {
            tag.putUUID("OwnerIndicator", ownerIndicator);
        }
        tag.putDouble("IndicatorDistance", indicatorDistance);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        minPitch = tag.getDouble("MinPitch");
        maxPitch = tag.getDouble("MaxPitch");
        barrelLength = tag.getDouble("BarrelLength");
        velocityPerCharge = tag.getDouble("VelocityPerCharge");
        minCharges = Math.max(1, tag.getInt("MinCharges"));
        maxCharges = Math.max(1, tag.getInt("MaxCharges"));
        hasTarget = tag.getBoolean("HasTarget");
        targetX = tag.getDouble("TargetX");
        targetY = tag.getDouble("TargetY");
        targetZ = tag.getDouble("TargetZ");

        ownerIndicator = tag.hasUUID("OwnerIndicator") ? tag.getUUID("OwnerIndicator") : null;
        indicatorDistance = tag.contains("IndicatorDistance") ? tag.getDouble("IndicatorDistance") : -1.0;

        rangeVisible = tag.getBoolean("RangeVisible");
        arcMode = ArcMode.byName(tag.getString("ArcMode"));
        fireMode = FireMode.byName(tag.getString("FireMode"));
        salvoEndTick = tag.getLong("SalvoEndTick");
        // 认不出来的档位就退回 1 秒（默认值），别让一个脏数据把间隔变成 0
        int storedInterval = tag.getInt("RollingInterval");
        rollingIntervalTicks = 0;
        for (int v : ROLLING_INTERVALS) {
            if (v == storedInterval) {
                rollingIntervalTicks = v;
                break;
            }
        }
        if (rollingIntervalTicks == 0) {
            rollingIntervalTicks = 20;
        }
        // 同理：半径也必须是合法档位，认不出来就退回最小档
        int storedRadius = tag.getInt("CoverageRadius");
        coverageRadius = 0;
        for (int v : COVERAGE_RADII) {
            if (v == storedRadius) {
                coverageRadius = v;
                break;
            }
        }
        if (coverageRadius == 0) {
            coverageRadius = COVERAGE_RADII[0];
        }

        // ⚠️ 客户端登记**必须也在这里做一次**。
        // onLoad() 只在区块加载时跑，而扳手切换走的是 toggleRangeVisible() → sendData() → 本方法。
        // 少了这一句，服务端开关切了、客户端的渲染名单却没更新 —— 症状就是"右键完全没反应"。
        if (level != null && level.isClientSide) {
            RangeDisplayState.set(worldPosition, rangeVisible);
        }

        coordinators.clear();
        ListTag coords = tag.getList("Coordinators", Tag.TAG_COMPOUND);
        for (int i = 0; i < coords.size(); i++) {
            CompoundTag e = coords.getCompound(i);
            coordinators.add(new BlockPos(e.getInt("X"), e.getInt("Y"), e.getInt("Z")));
        }

        if (hasTarget) {
            calculate();
        }
    }

    // ------------------------------------------------------------------ 护目镜

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(Component.literal("    ").append(Component.translatable("block.create_ballistics.fire_control_computer")));
        tooltip.add(Component.literal("    ").append(Component.translatable(ActiveBallistics.activeModelKey())));
        // 生效半径 + 怎么看它。键名直接引用按键那条语言键，玩家改键位后提示会跟着变。
        tooltip.add(Component.translatable("gui.create_ballistics.range_hint", SCAN_RADIUS,
                Component.translatable(rangeVisible
                        ? "gui.create_ballistics.range_on" : "gui.create_ballistics.range_off")));

        // 正绑着本机的火炮指示器（数量 / 最近距离）—— 与界面上那一行同源同文案
        tooltip.add(Component.literal("    ").append(Component.translatable("gui.create_ballistics.indicators"))
                .append(": ")
                .append(indicatorSummary()));
        tooltip.add(Component.literal(String.format(Locale.ROOT,
                "    协调器 %d / 上限 %d    登记火炮 %d 门",
                getCoordinatorCount(), MAX_COORDINATORS, getCannonCount())));

        // 覆盖打击：把"点有多稀"直接算给玩家看 —— 门炮少的时候点必然稀，
        // 那不是 bug 而是"一次铺开、点数=炮数"的必然结果，让他自己决定要不要把半径调小。
        if (fireMode == FireMode.COVERAGE) {
            tooltip.add(Component.translatable("gui.create_ballistics.coverage_hint",
                    coverageRadius, getCannonCount(),
                    String.format(Locale.ROOT, "%.0f", coverageSpacing())));
        }

        // 转速/过载的提醒交给 Create 原生（KineticBlockEntity 实现了 IHaveHoveringInformation，
        // 它的 addToTooltip 会显示原版那两句）。这里没转速就直接不列解算结果。
        if (!hasRotationPower()) {
            return super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        }

        if (!hasTarget) {
            tooltip.add(Component.translatable("block.create_ballistics.fire_control_computer.no_target"));
            return super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        }

        // 诸元由各节点探自己那门炮后各自解算（每门炮的装填/初速都不同），计算机这边
        // 只该报"有多少门炮已经就位/在干什么"，不再自己解算 —— 2026-09-17 之前它拿一组废弃的
        // 手填参数硬算，恒无解，护目镜上挂着一句误导人的"无解"。
        int total = getCannonCount();
        int ready = getReadyCount();
        tooltip.add(Component.literal(String.format(Locale.ROOT,
                "  目标 [%.0f, %.0f, %.0f] · 已就位 %d / %d",
                targetX, targetY, targetZ, ready, total)));

        // ⚠️⚠️ 这一行**必须和界面上的「计算过程」逐字一致**（用户 2026-09-22 明确定的规矩）。
        // 所以它不再自己拼 —— 界面和护目镜共用 StageText 那一份实现。
        // 这里曾经是**第三份平行实现**，为此栽过两次（界面改了护目镜没改、两处说法不一样）。
        tooltip.add(Component.literal("  ").append(StageText.of(this, level)));

        if (total == 0) {
            tooltip.add(Component.translatable("gui.create_ballistics.no_nodes",
                    FireControlCoordinatorBlockEntity.SCAN_RADIUS));
        }
        // 护目镜地方够，所以"为什么打不到"在这里**写全**（在 94 格处被挡住 / 需要多少度），
        // 界面上那一行只放得下简短结论。
        if (getUnsolvableCount() > 0 && ready == 0) {
            UnsolvableNote note = findUnsolvableNote();
            if (note != null && !note.key().isEmpty()) {
                tooltip.add(Component.translatable(note.key(),
                        String.format(Locale.ROOT, "%.0f", note.a()),
                        String.format(Locale.ROOT, "%.0f", note.b())));
            }
        }
        return super.addToGoggleTooltip(tooltip, isPlayerSneaking);
    }
}
