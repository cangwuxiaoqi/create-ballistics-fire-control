package net.createballistics.block.entity;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.createballistics.ballistic.ArcMode;
import net.createballistics.ballistic.FireMode;
import net.createballistics.ballistic.FiringSolution;
import net.createballistics.registry.ModBlockEntities;
import net.createballistics.util.PosDisplay;
import net.createballistics.util.RangeDisplayState;
import net.createballistics.util.StageText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 火控协调器 —— 火控网络的中枢。
 *
 * <p>它登记半径内所有<b>可用</b>的火控节点（背面贴着炮座的那些），统一下达：
 * <ul>
 *   <li><b>目标</b>：把世界坐标发给每个节点，由节点自己解算/瞄准；</li>
 *   <li><b>开火</b>：让所有节点击发。</li>
 * </ul>
 *
 * <p>设计上刻意<b>只下发目标、不下发角度</b> —— 每门炮的弹道参数不同，
 * 各自解算才能各自适配。
 *
 * <p><b>它靠应力干活</b>：背面接传动轴，固定消耗 {@link #STRESS_SU} SU。
 * 没有转速就既不扫描炮位、也不下发目标/开火。
 */
public class FireControlCoordinatorBlockEntity extends KineticBlockEntity
        implements IHaveGoggleInformation, RangeDisplayToggle {

    /**
     * 应力<b>基准值</b>：每 1 RPM 消耗多少 SU。
     *
     * <p>实际消耗 SU = 本值 × |转速|，乘转速那一步由 Create 的 {@code KineticNetwork} 做
     * （{@code getActualStressOf}），方块这边<b>只能返回基准、绝不能自己乘</b>。详见
     * {@link FireControlNodeBlockEntity#STRESS_BASE} 的注释。
     */
    public static final float STRESS_BASE = 32.0F;

    /**
     * 登记节点的搜索半径（格）。
     *
     * <p>2026-09-19 由 16 提到 32：16 格摆不下几门炮。32 是跟着计算机的 64 格算出来的 ——
     * 4 台半径 32 的协调器放在 (±32, ±32) 处，并集<b>正好</b>铺满计算机的 ±64（见 {@link #MAX_NODES} 旁注）。
     */
    public static final int SCAN_RADIUS = 32;
    /**
     * 最多能带几门炮。超出的按距离取最近的。
     *
     * <p>2026-09-19 定为 10 —— 与计算机的 4 台上限一起构成「最多 40 门炮」的规模。
     *
     * <p>⚠️ 面板放不下这么多：炮位列表每门占两行，单页只画得下约 5 门（面板画不下会静默截断）。
     * GUI 重做时要一并解决。
     */
    public static final int MAX_NODES = 10;
    /** 扫描节流（tick）。 */
    private static final int SCAN_INTERVAL = 20;

    /** 登记进来的火控节点坐标。顺序 = 按到本协调器的距离从近到远（见 {@link #scanNodes()}）。 */
    private final List<BlockPos> nodes = new ArrayList<>();

    /**
     * 扫描半径内、<b>本机有机会登记</b>的节点数（含不可用的）。
     * 诊断用，见 {@link #scanNodes()}。
     *
     * <p>⚠️ 2026-09-24 起把"已经被别的协调器认领的节点"**排除在外**（另计
     * {@link #nearbyTakenCount}）—— 否则界面会把它报成"有节点但都不可用"，
     * 而那门炮其实好得很，只是归了别人。
     */
    private int nearbyNodeCount;

    /** 扫描半径内**已被别的协调器认领**的节点数。诊断用（界面要说清这种"扫不到"）。 */
    private int nearbyTakenCount;

    /**
     * 弹道模式（自动 / 低伸 / 高抛）。玩家在<b>火控计算机</b>上切换，计算机下发到这里、再下发给各节点。
     *
     * <p>⚠️ 2026-09-20 之前这里是一个只在护目镜上显示、<b>没有任何消费者</b>的死枚举
     * （照抄 {@code cbc_ballistics} 那个"只用于 UI"的 Mode 时留下的）。现在换成了共用的
     * {@link ArcMode} —— 弹道模式是<b>逐炮各自判断</b>的（每门炮位置不同、遮挡不同），
     * 所以真正的决策发生在节点里，这里存的只是"本机收到的模式"。
     */
    private ArcMode mode = ArcMode.AUTO;

    /**
     * 上线到哪一台火控计算机 —— 由计算机扫描时<b>写回</b>（见 {@code FireControlComputerBlockEntity.scanCoordinators}）。
     * {@code null} 表示这台协调器没接入任何计算机，界面上要如实说。
     */
    @Nullable
    private BlockPos masterPos;

    /**
     * 范围显示开关 —— <b>扳手右键</b>切换（见 {@code AbstractFireControlMachineBlock.onWrenched}）。
     *
     * <p>为什么要做成开关而不是"看着方块才显示"：范围有 64 格那么大，玩家得转头才能看全，
     * 而一转头脑门上的准星就离开方块了、框立刻消失 —— 根本看不了。所以改成手动开、手动关。
     */
    private boolean rangeVisible;

    private boolean hasTarget;
    private double targetX;
    private double targetY;
    private double targetZ;

    private int tickCounter;

    public FireControlCoordinatorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public FireControlCoordinatorBlockEntity(BlockPos pos, BlockState state) {
        this(ModBlockEntities.FIRE_CONTROL_COORDINATOR.get(), pos, state);
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

    /** 当前要求的最低转速（RPM）。取自 Create 的档位，会随服务端配置变。界面要用。 */
    public float requiredRpm() {
        return getBlockState().getBlock() instanceof com.simibubi.create.content.kinetics.base.IRotate rotate
                ? rotate.getMinimumRequiredSpeedLevel().getSpeedValue() : 0.0F;
    }


    // ------------------------------------------------------------------ tick

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide) {
            return;
        }
        if (++tickCounter % SCAN_INTERVAL == 0) {
            // 上线关系的复核放在供电判断<b>之前</b>：计算机被拆时自己就该变回「未绑定」，
            // 不该因为本机恰好没转速就一直挂着一个已经不存在的上级。
            verifyMaster();
            if (hasRotationPower()) {
                scanNodes();   // 没接应力就不扫描（设计如此）
            }
        }
    }

    /**
     * 周期性重新登记半径内<b>可用</b>的火控节点（挨着炮座、火炮已组装、且接了转速）。
     *
     * <p>超出 {@link #MAX_NODES} 时按距离取最近的 —— 用距离排序而不是"先扫到谁算谁"，
     * 这样结果与区块遍历顺序无关、可复现。
     */
    private void scanNodes() {
        List<BlockPos> found = new ArrayList<>();
        // 顺便数一下<b>范围内一共有几个节点（不管可不可用）</b> —— 界面靠它区分两种失败：
        // 「范围内压根没有节点」是距离/摆放问题，「有节点但不可用」是转速或组装问题。
        // 2026-09-17 加：炸膛重建后协调器识别不到节点，光看「0/12」分不清是哪一种。
        int nearby = 0;
        int taken = 0;
        for (BlockPos p : BlockPos.betweenClosed(
                worldPosition.offset(-SCAN_RADIUS, -SCAN_RADIUS, -SCAN_RADIUS),
                worldPosition.offset(SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS))) {
            if (level.getBlockEntity(p) instanceof FireControlNodeBlockEntity node) {
                // ⭐ **一台炮只能归一台协调器**：已经归了别人的，本机不登记、也不去改它的归属。
                //    见 claimedByOther() 的注释（这就是"4 门报成 7 门"的另一半）。
                if (claimedByOther(node)) {
                    taken++;
                    continue;
                }
                nearby++;
                if (node.isOperational()) {
                    found.add(p.immutable());
                }
            }
        }
        // ⚠️ 这两个计数**也进同步数据**（见 write/read），所以它们变了同样要 sendData() ——
        //    否则"炮都归别人了"这种提示要等名单本身变化才刷得出来。
        boolean diagChanged = nearby != nearbyNodeCount || taken != nearbyTakenCount;
        nearbyNodeCount = nearby;
        nearbyTakenCount = taken;
        found.sort((a, b) -> Double.compare(a.distSqr(worldPosition), b.distSqr(worldPosition)));
        if (found.size() > MAX_NODES) {
            found.subList(MAX_NODES, found.size()).clear();
        }
        // 把「登记关系」反向写回给每个节点。
        //
        // 火控网络的登记是**本协调器单向扫描**的，节点自己并不知道谁登记了它。要让它显示
        // 「是否已接入火控网络」，就必须由我们明确告诉它 —— 不能靠"附近恰好有个协调器"去猜，
        // 那样协调器拆了、或节点搬远了，节点还会一直显示已接入。
        List<BlockPos> previous = new ArrayList<>(nodes);
        nodes.clear();
        nodes.addAll(found);
        for (BlockPos p : nodes) {
            if (level.getBlockEntity(p) instanceof FireControlNodeBlockEntity node) {
                node.setMaster(worldPosition);
            }
        }
        // 被除名的节点：**只有它的上级还是本协调器时才清**，避免抢了别台协调器的下线关系。
        for (BlockPos p : previous) {
            if (nodes.contains(p)) {
                continue;
            }
            if (level.getBlockEntity(p) instanceof FireControlNodeBlockEntity node
                    && worldPosition.equals(node.getMasterPos())) {
                node.setMaster(null);
            }
        }

        if (!found.equals(previous) || diagChanged) {
            setChanged();
            // ⚠️ 必须显式同步 —— 界面读的是客户端那份副本（`getNodeCount()` → 这里的 nodes）。
            // 只 setChanged() 不 sendData() 的话，服务端扫到了、界面却一直显示 0 个炮位。
            sendData();
        }
    }

    /** 扫描半径内、<b>本机有机会登记</b>的节点数（<b>不管可用与否</b>）。界面用它定位"扫不到"的原因。 */
    public int getNearbyNodeCount() {
        return nearbyNodeCount;
    }

    /** 扫描半径内**已经归别的协调器**的节点数。界面用它区分"周围真没炮"与"炮都不是我的"。 */
    public int getNearbyTakenCount() {
        return nearbyTakenCount;
    }

    /**
     * 这个节点是不是已经归<b>别的</b>协调器了。
     *
     * <p>⚠️ 2026-09-24 加（用户实测报的）：一台协调器的识别半径是 <b>32 格</b>，
     * <b>多台挨着放就会同时看到同一门炮</b>。此前两边都会把它登记进自己的名单、
     * 还互相覆盖节点的 master ⇒ 一门炮被两台下命令 —— 计算机那边表现为"炮数翻倍"
     * （用户实测 <b>4 门报成 7 门</b>），而轮射 / 同时弹着是**排期**下达的，
     * 同一门炮可能因此收到两份排期而打两发。
     *
     * <p>判据是<b>双向确认</b>：对方记着这门炮、<b>并且</b>对方还活着。
     * 上级被拆掉 / 搬远了 ⇒ 这门炮就"没人管"了、本机可以接管 ——
     * 不然拆掉一台协调器会让那一带的炮全饿死。
     */
    private boolean claimedByOther(FireControlNodeBlockEntity node) {
        BlockPos master = node.getMasterPos();
        if (master == null || master.equals(worldPosition)) {
            return false;   // 没人要，或者本来就是我的
        }
        return level.getBlockEntity(master) instanceof FireControlCoordinatorBlockEntity other
                && other.getNodes().contains(node.getBlockPos());
    }

    public List<BlockPos> getNodes() {
        return nodes;
    }

    public int getNodeCount() {
        return nodes.size();
    }

    // ------------------------------------------------------------------ 上线关系

    /** 由火控计算机在扫描时写回：告诉这台协调器「你被谁管辖」。{@code null} = 被除名。 */
    public void setMaster(@Nullable BlockPos master) {
        if (master == null ? masterPos == null : master.equals(masterPos)) {
            return;
        }
        this.masterPos = master == null ? null : master.immutable();
        setChanged();
        sendData();
    }

    /** 上线到哪台火控计算机；{@code null} = 没接入计算机。 */
    @Nullable
    public BlockPos getMasterPos() {
        return masterPos;
    }

    /** 有没有接入火控计算机。界面用它显示「已绑定 / 未绑定」。 */
    public boolean isOnline() {
        return masterPos != null;
    }

    // ------------------------------------------------------------------ 范围显示

    public boolean isRangeVisible() {
        return rangeVisible;
    }

    /** 「开启 / 关闭」。玩家看一眼就知道现在是什么状态，不需要再解释扳手能干什么。 */
    private Component rangeText() {
        return Component.translatable(rangeVisible
                ? "gui.create_ballistics.range_on" : "gui.create_ballistics.range_off");
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

    // ------------------------------------------------------------------ 指令

    public void setTarget(double x, double y, double z) {
        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
        this.hasTarget = true;
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

    public ArcMode getMode() {
        return mode;
    }

    /**
     * 设置弹道模式，并把新模式<b>推给所有已登记的炮</b>。
     *
     * <p>光改自己这个字段是没用的 —— 决策在节点里。而且推完必须让它们<b>重新解算</b>：
     * 节点一旦转到位就撒手（{@code aiming = false}），不会自己重算，
     * 不推的话玩家切了模式、炮还按旧弹道瞄着（这正是「换装药后不重解」那个坑的同一类）。
     */
    public void setMode(ArcMode mode) {
        this.mode = mode;
        if (level != null && !level.isClientSide()) {
            for (BlockPos p : nodes) {
                if (level.getBlockEntity(p) instanceof FireControlNodeBlockEntity node) {
                    node.setArcMode(mode);
                }
            }
        }
        setChanged();
        sendData();
    }

    public void cycleMode() {
        setMode(mode.next());
    }

    /**
     * 把<b>目标坐标</b>下发给网络里的每一门炮，由各炮自己解算各自的诸元。
     *
     * <p><b>协调器刻意只下发目标、不下发角度</b>：每门炮的装填、弹种、管长、初速都不同，
     * 只有各自解算才能各自适配。节点收到坐标后用 {@code CannonProbe} 探自己那门炮再解算。
     *
     * @return 有没有成功下发（得先有目标、且本机接了应力）
     */
    public boolean layAllTarget() {
        if (!hasRotationPower()) {
            return false;   // 没应力不干活
        }
        if (!hasTarget) {
            return false;
        }
        for (BlockPos p : nodes) {
            if (level.getBlockEntity(p) instanceof FireControlNodeBlockEntity node) {
                // 弹道模式一起下发 —— 决策在节点里（逐炮各自判断遮挡），这里只是把"玩家的选择"带过去
                node.aimAt(targetX, targetY, targetZ, mode);
            }
        }
        return true;
    }

    /**
     * 清除目标，并让各节点把炮座交还给 CBC。
     *
     * <p>用 {@code node.clearTarget()} 而<b>不是</b> {@code ceaseFire()} —— 后者会取消已排队的
     * 开火，而计算机点完「开火！」会紧接着清目标，那一炮就打不出去了。
     */
    public void clearTarget() {
        this.hasTarget = false;
        if (level != null && !level.isClientSide()) {
            for (BlockPos p : nodes) {
                if (level.getBlockEntity(p) instanceof FireControlNodeBlockEntity node) {
                    node.clearTarget();
                }
            }
        }
        setChanged();
    }

    /** 半径内最近的火控计算机。 */
    @Nullable
    public FireControlComputerBlockEntity findComputer() {
        if (level == null) {
            return null;
        }
        FireControlComputerBlockEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(
                worldPosition.offset(-SCAN_RADIUS, -SCAN_RADIUS, -SCAN_RADIUS),
                worldPosition.offset(SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS))) {
            if (level.getBlockEntity(p) instanceof FireControlComputerBlockEntity computer) {
                double d = p.distSqr(worldPosition);
                if (d < bestDist) {
                    bestDist = d;
                    best = computer;
                }
            }
        }
        return best;
    }

    /**
     * 全网齐射。没应力不干活。
     *
     * @return <b>真正接受开火的炮数</b> —— 未就位的炮会被节点自己挡下来（见
     *         {@link FireControlNodeBlockEntity#fire()}），所以这个数可能小于登记的炮数
     */
    public int fireAll() {
        if (!hasRotationPower()) {
            return 0;
        }
        int fired = 0;
        for (BlockPos p : nodes) {
            if (level.getBlockEntity(p) instanceof FireControlNodeBlockEntity node && node.fire()) {
                fired++;
            }
        }
        return fired;
    }

    /**
     * 本级炮位里<b>飞得最久</b>的那一发（tick）—— 同时弹着要靠它定基准时刻。
     *
     * <p>只看<b>已就位</b>的炮：没就位的排了也打不准，不参与这一轮。
     */
    public double getMaxAirTime() {
        double best = 0.0;
        for (BlockPos p : nodes) {
            if (level != null && level.getBlockEntity(p) instanceof FireControlNodeBlockEntity node
                    && node.isAimed()) {
                best = Math.max(best, node.getSolutionAirTime());
            }
        }
        return best;
    }

    /**
     * <b>同时弹着（TOT）</b>：按各自飞行时间<b>错开击发</b>，让所有炮弹同一瞬间落地。
     *
     * <p>飞得久的先开火 —— 每门炮在 {@code baseTick − 自己的飞行时间} 击发。
     * 于是所有炮弹都在 {@code baseTick} 附近落地。
     *
     * @param baseTick 基准时刻（= 预计的<b>落点</b>时刻，{@code level.getGameTime()} 的口径）
     * @return 真正排上的炮数
     */
    public int scheduleSalvo(long baseTick, FireMode mode) {
        if (!hasRotationPower()) {
            return 0;
        }
        int scheduled = 0;
        for (BlockPos p : nodes) {
            if (level.getBlockEntity(p) instanceof FireControlNodeBlockEntity node && node.isAimed()) {
                // 飞行时间向上取整 —— 宁可早半 tick 也不能晚，晚了那发就落在别人后面
                long at = baseTick - (long) Math.ceil(node.getSolutionAirTime());
                // 整轮落地时刻与模式一起下发 —— 节点要拿它显示"已开火，等整轮结束"
                if (node.scheduleFire(Math.max(at, 1L), baseTick, mode)) {
                    scheduled++;
                }
            }
        }
        return scheduled;
    }

    /**
     * 有多少门炮<b>收到了目标但解不出诸元</b>（典型是目标超出射程）。
     *
     * <p>界面靠它区分「还在计算」和「根本算不出来」—— 否则超出射程时界面会一直卡在
     * 「正在计算…」，玩家只能干等（2026-09-18 实机踩到，4000 格时计算机就是这么卡住的）。
     */
    public int getUnsolvableCount() {
        int n = 0;
        long now = level == null ? 0L : level.getGameTime();
        for (BlockPos p : nodes) {
            if (level != null && level.getBlockEntity(p) instanceof FireControlNodeBlockEntity node
                    && node.hasTarget() && !node.isSolvable()
                    // ⚠️ 正在参加本轮射击的**不算** —— 它们膛里空了所以解不出来，
                    //    但那是"已经打完在等整轮结束"，不是"打不着"（2026-09-22 用户实测报的）
                    && !node.inSalvo(now)) {
                n++;
            }
        }
        return n;
    }

    /** 有多少门炮已就位。 */
    public int getReadyCount() {
        int ready = 0;
        for (BlockPos p : nodes) {
            if (level.getBlockEntity(p) instanceof FireControlNodeBlockEntity node && node.isAimed()) {
                ready++;
            }
        }
        return ready;
    }

    // ------------------------------------------------------------------ 生命周期

    public void onRemoved() {
        nodes.clear();
        masterPos = null;
    }

    /**
     * 复核「上线到哪台计算机」是否还有效 —— 计算机被拆掉后自己变回「未绑定」。
     *
     * <p>只在下级方块<b>已加载</b>时才判定：区块没加载不等于被拆了，那种情况保持原样。
     * 这样就不需要计算机在拆除时挨个通知谁。
     */
    private void verifyMaster() {
        if (masterPos == null || level == null || !level.isLoaded(masterPos)) {
            return;
        }
        if (!(level.getBlockEntity(masterPos) instanceof FireControlComputerBlockEntity)) {
            setMaster(null);
        }
    }

    // 注意：不能覆盖 saveAdditional/loadAdditional —— SmartBlockEntity 把它们标了 final。
    // Create 的钩子是下面这对 read/write(..., clientPacket)。
    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putString("Mode", mode.name());
        tag.putBoolean("HasTarget", hasTarget);
        tag.putDouble("TargetX", targetX);
        tag.putDouble("TargetY", targetY);
        tag.putDouble("TargetZ", targetZ);

        ListTag list = new ListTag();
        for (BlockPos p : nodes) {
            CompoundTag e = new CompoundTag();
            e.putInt("X", p.getX());
            e.putInt("Y", p.getY());
            e.putInt("Z", p.getZ());
            list.add(e);
        }
        tag.put("Nodes", list);
        tag.putInt("NearbyNodes", nearbyNodeCount);
        tag.putInt("NearbyTaken", nearbyTakenCount);
        tag.putBoolean("RangeVisible", rangeVisible);

        tag.putBoolean("HasMaster", masterPos != null);
        if (masterPos != null) {
            tag.putInt("MasterX", masterPos.getX());
            tag.putInt("MasterY", masterPos.getY());
            tag.putInt("MasterZ", masterPos.getZ());
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        mode = ArcMode.byName(tag.getString("Mode"));
        hasTarget = tag.getBoolean("HasTarget");
        targetX = tag.getDouble("TargetX");
        targetY = tag.getDouble("TargetY");
        targetZ = tag.getDouble("TargetZ");

        nodes.clear();
        ListTag list = tag.getList("Nodes", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            nodes.add(new BlockPos(e.getInt("X"), e.getInt("Y"), e.getInt("Z")));
        }
        nearbyNodeCount = tag.getInt("NearbyNodes");
        nearbyTakenCount = tag.getInt("NearbyTaken");
        rangeVisible = tag.getBoolean("RangeVisible");

        masterPos = tag.getBoolean("HasMaster")
                ? new BlockPos(tag.getInt("MasterX"), tag.getInt("MasterY"), tag.getInt("MasterZ"))
                : null;

        // ⚠️ 客户端登记**必须也在这里做一次**。
        // onLoad() 只在区块加载时跑，而扳手切换走的是 toggleRangeVisible() → sendData() → 本方法。
        // 少了这一句，服务端开关切了、客户端的渲染名单却没更新 —— 症状就是"右键完全没反应"。
        if (level != null && level.isClientSide) {
            RangeDisplayState.set(worldPosition, rangeVisible);
        }
    }

    // ------------------------------------------------------------------ 护目镜

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(Component.literal("    ").append(Component.translatable("block.create_ballistics.fire_control_coordinator")));

        // ---- 有没有接入火控计算机 ----
        // 由计算机扫描时写回（见 FireControlComputerBlockEntity.scanCoordinators），
        // 不是"64 格内恰好有台计算机"的猜测 —— 计算机被拆了这里会如实变回"未接入"。
        if (masterPos != null) {
            // ⚠️ 坐标显示走 PosDisplay（航空学物理结构上是图幅坐标）——
            //    只转**显示**，masterPos 字段本身保持原坐标（还要查方块实体）。
            BlockPos shownMaster = PosDisplay.of(level, masterPos);
            tooltip.add(Component.literal("  ")
                    .append(Component.translatable("gui.create_ballistics.master"))
                    .append(Component.literal(String.format(Locale.ROOT, " [%d, %d, %d]",
                            shownMaster.getX(), shownMaster.getY(), shownMaster.getZ()))));

            // ---- 全网的「计算过程」—— 和计算机界面 / 计算机护目镜**逐字一致** ----
            // 用户 2026-09-22 定的规矩：计算机界面上那行「计算过程」必须同步到护目镜上。
            // 协调器自己不知道全网在干什么（它只管自己登记的几门炮），所以这里**去问上级计算机** ——
            // 这正是"同步"的含义：三处显示同一句话，而不是各说各的。
            if (level != null
                    && level.getBlockEntity(masterPos) instanceof FireControlComputerBlockEntity computer) {
                tooltip.add(Component.literal("  ").append(StageText.of(computer, level)));
            }
        } else {
            tooltip.add(Component.literal("  ")
                    .append(Component.translatable("block.create_ballistics.fire_control_coordinator.offline")));
        }

        // ---- 生效半径 ----
        // 键名直接用按键自己那条语言键，玩家改了键位提示也跟着变，不会说谎。
        tooltip.add(Component.translatable("gui.create_ballistics.range_hint", SCAN_RADIUS, rangeText()));

        // 转速/过载的提醒交给 Create 原生（KineticBlockEntity 实现了 IHaveHoveringInformation，
        // 它的 addToTooltip 会显示原版那两句），不用我们自己写。
        tooltip.add(Component.translatable("block.create_ballistics.fire_control_coordinator.nodes")
                .append(": " + nodes.size()));
        tooltip.add(Component.translatable("block.create_ballistics.fire_control_coordinator.mode")
                .append(": ").append(Component.translatable(mode.langKey())));

        // ---- 火力模式（齐射 / 同时弹着 / 轮射）----
        // 它和弹道模式一起决定"这一轮到底怎么打"，玩家在**炮位上**（而不是计算机面前）时也该看得到。
        // ⚠️ 协调器**自己没有这个字段** —— 火力模式归计算机管，所以这里**直接问上级计算机**。
        //    和上面那句「计算过程」是同一个路子：不复制状态，只读源头，就永远不会两边不一致。
        if (masterPos != null && level != null
                && level.getBlockEntity(masterPos) instanceof FireControlComputerBlockEntity computer) {
            tooltip.add(Component.translatable("block.create_ballistics.fire_control_coordinator.fire_mode")
                    .append(": ").append(Component.translatable(computer.getFireMode().langKey())));
        }

        if (hasTarget) {
            tooltip.add(Component.translatable("block.create_ballistics.fire_control_coordinator.target")
                    .append(String.format(" [%.0f, %.0f, %.0f]", targetX, targetY, targetZ)));
        } else {
            tooltip.add(Component.translatable("block.create_ballistics.fire_control_coordinator.no_target"));
        }
        return super.addToGoggleTooltip(tooltip, isPlayerSneaking);
    }

}
