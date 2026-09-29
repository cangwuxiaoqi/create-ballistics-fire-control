package net.createballistics.client;

import net.createballistics.CreateBallistics;
import net.createballistics.entity.DroneEntity;
import net.createballistics.item.ArtilleryIndicatorItem;
import net.createballistics.item.MonitorItem;
import net.createballistics.client.screen.ArtilleryIndicatorScreen;
import net.createballistics.net.ComputerActionC2SPacket;
import net.createballistics.registry.ModItems;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 火炮指示器的客户端行为：<b>开镜变焦</b>、<b>滚轮调焦</b>、<b>左键蓄力开火</b>。
 *
 * <h2>为什么自己改 FOV，而不是用 {@code UseAnim.SPYGLASS}</h2>
 *
 * {@code UseAnim.SPYGLASS} 只负责"举着的姿势"。NeoForge 把三件事都挂在
 * <b>物品能力 {@code ItemAbilities.SPYGLASS_SCOPE}</b> 上（判据是
 * {@code stack.canPerformAction(SPYGLASS_SCOPE)}，不再是 {@code is(Items.SPYGLASS)}）：
 * 原版那 10 倍变焦、开镜时的灵敏度削减、第三人称的举镜手臂姿势。
 *
 * <p><b>我们故意不给这个能力</b>：给了就会连着拿到原版<b>固定 10 倍</b>的变焦
 * 和那层圆形遮罩，得反过来跟它们打架。所以变焦自己算（{@link #onComputeFov}）、
 * 灵敏度自己削（{@code MouseHandlerMixin}）、姿势自己接（{@code PlayerItemInHandLayerMixin}）。
 * 卓越前线正是同样的取舍 —— 它也因此自己写了那两个 Mixin。
 * 详见 {@link IndicatorOverlay} 的类注释。
 */
@EventBusSubscriber(modid = CreateBallistics.MODID, value = Dist.CLIENT)
public final class IndicatorClientEvents {

    /** 举起时的基础倍率，与卓越前线一致。 */
    private static final double BASE_ZOOM = 4.0;
    /** 滚轮能把它推动的范围与每格步长，同样照卓越前线。实际倍率范围 2.0 ~ 10.0。 */
    private static final double MIN_CUSTOM_ZOOM = -2.0;
    private static final double MAX_CUSTOM_ZOOM = 6.0;
    private static final double SCROLL_STEP = 0.4;

    /**
     * 蓄力满多少 tick 就开火、蓄力上限、以及开火后的冷却。
     *
     * <p>照卓越前线：{@code holdArtilleryIndicator} 每 tick +1、夹在 0~20，
     * <b>≥19 就开火</b>（约 1 秒），打完设 10 tick 冷却 —— 否则按住不放会每 tick 灌一个包。
     * 圆环的进度就是「当前蓄力 ÷ 20」。
     */
    private static final int FIRE_AT_TICKS = 19;
    private static final int CHARGE_CAP = 20;
    private static final int FIRE_COOLDOWN_TICKS = 10;

    /** 平滑后的当前倍率；1.0 = 没在开镜。 */
    private static double zoom = 1.0;
    /**
     * 滚轮推动的那一档 —— 实际倍率 = {@link #BASE_ZOOM} + 本值，范围 2.0 ~ 10.0。
     *
     * <p>⚠️⚠️ <b>初值必须是 {@link #MIN_CUSTOM_ZOOM}</b>（2026-09-27 用户实测报的）：
     * 原来没写初值 ⇒ Java 给 0.0 ⇒ <b>首次使用是 4.0 倍（区间正中）</b>，
     * 玩家得自己往回滚一大堆才到最小倍率。用户的原话：
     * 「每次重启进游戏、进入存档，右键使用指示器，初始默认就是有一定倍数的放大，
     * 不是最小的放大倍数」⇒ **默认就该是最小倍率**。
     *
     * <p>⚠️ 同一个意图还要覆盖"**不重启、只退出存档再进**"那种情形 ⇒
     * 见 {@code onLoggingIn}：进入世界时也把它复位一次。
     */
    private static double customZoom = MIN_CUSTOM_ZOOM;

    /**
     * <b>倍率平滑的时间常数（秒）</b> —— 用"<b>按时间</b>逼近"，而不是"每被调用一次推一步"。
     *
     * <h2>⚠️⚠️ 为什么必须按时间（2026-09-27 定案 · 用户逐帧录像 + 探针实证）</h2>
     *
     * 原来是 {@code zoom = Mth.lerp(min(0.3 * partialTick, 1), zoom, target)} ——
     * <b>每被事件调用一次就推进一步</b>。而<b>大包里 FOV 事件每帧的调用次数是变化的</b>
     * （iris / acceleratedrendering 会多跑几个 pass；我们自己早就记着"一帧触发两次"）
     * ⇒ 某些帧会多推进几步 ⇒ <b>那一帧画面突然"跳宽"一下</b>。
     *
     * <p>实证（用户逐帧截图定案）：松手后的 15 帧里**只有第 3 帧**异常（画面比前后宽约 2.2 倍），
     * 去掉它之后 1→2→4→…→15 是一条**平滑**的恢复曲线。
     * 探针日志也印证：相邻两次调用的步长在 **0.023 ~ 0.151** 之间摆动（7 倍差），
     * 还出现过<b>连续三次完全不动</b>（那几次的 partialTick 为 0）。
     *
     * <p>⇒ 改成 {@code zoom += (target − zoom) × (1 − e^(−dt/τ))}：
     * <b>与"一帧调用几次"无关、与刷新率也无关</b>（原来高刷新率下平滑更快，本身也是个隐藏问题）。
     *
     * <p>τ = 0.07 秒 —— 这个值是**从原行为反推的**（松手后从 2.0 降到 1.06 用了约 10 帧
     * ⇒ 每帧残留 ≈ 0.24 ⇒ τ ≈ 4 帧 ≈ 0.067 秒）⇒ **手感与从前基本一致**，
     * 变的只是"不再依赖调用次数"。
     */
    private static final double ZOOM_TAU_SECONDS = 0.07;
    /** 上一次推进平滑的时刻（纳秒）；0 = 还没初始化。 */
    private static long zoomLastNanos;

    /** 左键按住攒了多少 tick；松手归零。 */
    private static int charge;
    private static int fireCooldown;

    private IndicatorClientEvents() {
    }

    // ------------------------------------------------------------------
    // 中键取点
    // ------------------------------------------------------------------

    /**
     * <b>中键 = 把准星所指的方块报给绑定的计算机</b>（原版那颗"选取方块"）。两个姿势都认：
     *
     * <ul>
     *   <li><b>手持指示器</b>：从<b>玩家的眼睛</b>沿视线取点（B1 第四轮）；</li>
     *   <li><b>无人机视角</b>（主手遥控器 + 副手指示器）：从<b>无人机的眼睛</b>沿机头取点 ——
     *       照卓越前线的 {@code ClickEventHandler#droneLeftClick}。
     *       ⚠️ 这时取点的是无人机，不是玩家：玩家本人可能在几百格之外，
     *       拿玩家的眼睛去取点会报到完全不相干的地方。</li>
     * </ul>
     *
     * <p>必须先把事件取消掉，否则原版"选取方块"会同时生效 —— 把方块塞进快捷栏、
     * 甚至把手上拿着的指示器顶掉。
     *
     * <p>⚠️ 与它的一处差别：它只要"主手监视器 + 副手指示器"就<b>无条件</b>吞掉中键，
     * 哪怕那一刻并没有进图传 ⇒ 那一下中键白按。我们只在<b>真的进着图传</b>时才吞。
     */
    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || event.getButton() != 2 /* 鼠标中键 */ || event.getAction() != 1 /* 按下 */) {
            return;
        }
        if (holdingIndicator(player)) {
            event.setCanceled(true);
            markTarget(player, player.getMainHandItem(), player,
                    player.getEyePosition(), player.getViewVector(1.0F));
            return;
        }
        if (MonitorItem.isPiloting(player.getMainHandItem())
                && player.getOffhandItem().is(ModItems.ARTILLERY_INDICATOR.get())) {
            DroneEntity drone = MonitorItem.pilotedDrone(player);
            if (drone != null) {
                event.setCanceled(true);
                markTarget(player, player.getOffhandItem(), drone,
                        drone.getEyePosition(), drone.getLookAngle());
            }
        }
    }

    /**
     * 从给定的眼睛沿给定方向取一个打击坐标，报给绑定的计算机。
     *
     * <p>取哪一点由 {@link CrosshairPick#targetPoint} 决定（**唯一实现**）：
     * 优先锁准星上的<b>生物</b>、否则取方块中心，<b>潜行则强制只取方块</b> —— 全照卓越前线。
     * 图传的测距用的是同一个 {@link CrosshairPick}，所以"测距说有实体"与"这里锁得到它"永远一致。
     *
     * <p>⚠️ 用户 2026-09-22 定的那条边界仍然成立：**对着天空（既没方块也没实体）就什么都不做**。
     * 卓越前线会把 {@code clip} 的 MISS 结果当成坐标（"512 格外的空中某处"）报出去，
     * 那样等于凭空设了个打不着的目标。宁可"按了没反应"，也不要报一个假坐标。
     */
    private static void markTarget(LocalPlayer player, ItemStack indicator, Entity shooter, Vec3 eye, Vec3 look) {
        BlockPos computer = ArtilleryIndicatorItem.boundComputer(indicator);
        if (computer == null) {
            return;
        }
        Vec3 target = CrosshairPick.targetPoint(player, shooter, eye, look);
        if (target == null) {
            return;
        }
        PacketDistributor.sendToServer(new ComputerActionC2SPacket(computer,
                ComputerActionC2SPacket.FIELD_INDICATOR_TARGET, 0, target.x, target.y, target.z));
    }

    // ------------------------------------------------------------------
    // 变焦
    // ------------------------------------------------------------------

    /**
     * <b>进入世界时把倍率复位到最小</b>（2026-09-27 用户要求）。
     *
     * <p>用户原话：「每次重启进游戏、进入存档，右键使用指示器，**默认就该是最小的放大倍数**」。
     * 静态初值（见 {@link #customZoom}）能覆盖"重启"那一种；这一条覆盖"不重启、
     * 只是退出存档再进"——两种情形下"首次使用"都从最小倍率开始 ✓
     *
     * <p>⚠️ 只在**进世界**时复位，**不在每次收起指示器时复位** ——
     * 同一次游戏里反复举镜不该把玩家刚调好的倍率吃掉。
     */
    @SubscribeEvent
    public static void onLoggingIn(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingIn event) {
        customZoom = MIN_CUSTOM_ZOOM;
    }

    /**
     * 变焦本体。<b>两处防连乘/防叠加，都是 2026-09-26 大整合包首测换来的</b>。
     *
     * <h2>① 幂等：不能拿"入参"当基准反复除</h2>
     *
     * 原写法 {@code event.setFOV(event.getFOV() / zoom)} 只有在"入参与基准一致"时才等价于
     * "基准 ÷ 倍率"。而大包里这个事件一帧能被触发多次（iris 的多个渲染阶段、
     * acceleratedrendering 重写的管线都会再取一次 FOV）—— 只要其中任何一次拿到的是
     * <b>我们改过的值</b>，倍率就会<b>连乘</b>：4× → 16× → 64×（用户实测"倍速放大了很大"）。
     * ⇒ 先认一下"入参是不是已经是我们这一档的输出"，是就原样放行。
     *
     * <h2>② 原版望远镜那一档：抵消它的 10 倍</h2>
     *
     * 我们**故意不给**指示器申请 {@code SPYGLASS_SCOPE} 能力（见类注释）。但大整合包里
     * 若有模组给"用着 {@code UseAnim.SPYGLASS} 的物品"补上这个能力，原版就会在
     * <b>本事件之后</b>再乘 0.1（固定 10 倍）并画那层圆形遮罩 ⇒ 10 × 我们的倍率，
     * 正是用户截图里那种"原版望远镜视野 + 放大得离谱"。
     * ⇒ 发现 {@code isScoping()} 为真时把 10 倍乘回来（净效果 = 只有我们的倍率），并把这件事记一笔。
     *
     * <p>⚠️ 圆形遮罩是原版 GUI 画的，我们这层抵消不了它 —— 它到底有没有出现，看
     * {@code [诊断:render]} 里那条"原版望远镜能力也生效"。
     */
    @SubscribeEvent
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        boolean scoping = player != null && isScoping(player);
        double target = scoping ? BASE_ZOOM + customZoom : 1.0;
        // ⭐⭐ **按时间逼近**（见 ZOOM_TAU_SECONDS 的说明）：与"一帧被调用几次"无关。
        //    ⚠️ dt 夹在 0.25 秒以内：卡顿一下（一次 GC / 区块生成）不该让画面跳一大步。
        long nowNanos = System.nanoTime();
        if (zoomLastNanos == 0L) {
            zoomLastNanos = nowNanos;
        }
        double dtSeconds = Math.min((nowNanos - zoomLastNanos) / 1.0E9, 0.25);
        zoomLastNanos = nowNanos;
        double blend = 1.0 - Math.exp(-dtSeconds / ZOOM_TAU_SECONDS);
        zoom = zoom + (target - zoom) * blend;

        // ⚠️⚠️ **没在变焦时本来是一个字都不改**（别人的速度感 / 镜头模组 / 着色器保持原样），
        //     ——但**举着指示器时是例外**：那时必须继续接管，哪怕倍率已经回到 1。
        //
        // 为什么（2026-09-26 用户实测：**松开右键回收视野时，有一帧突然放大**）：
        //    大整合包里 `isScoping()` 被第三方模组劫持成 true（见类注释），于是**原版会给我们
        //    的基准 FOV 乘 0.1**（77 → 7.7）。而"松开右键"那一瞬 zoom 刚好落回 1.0001 以下
        //    ⇒ 我们放手 ⇒ **那一帧用的就是 7.7**（放大 10 倍）⇒ 闪一下 ✗。
        // ⇒ 判据改成「**还在举着指示器**就继续接管」：倍率 1 时算出来正好是 `选项FOV`，
        //    与我们不接管时**同值** ⇒ 对普通整合包逐字无差别，而劫持场景下也不会漏那一帧 ✓。
        boolean holdingScope = scoping;

        // ⚠️⚠️ **绝对基准，不用入参**（2026-09-26 大包第二次实测换来的）。
        //
        // 实测日志：一帧里事件会被触发两次，其中一次的入参是 **7.70 = 77 × 0.1**
        // —— 也就是说原版那个"望远镜 ×0.1"在**我们之前**就已经乘进基准里了。
        // 那一刻若还按「入参 ÷ 倍率」，再叠上我们这层的补偿，最后会得到 ≈1.9° 的 FOV
        // ⇒ **40 倍**（用户截图里那种"放大得离谱"）。而另一路的入参是 70/77。
        // ⇒ 干脆**只认选项里的 FOV 当基准**：不管别人怎么改过入参，我们算出的最终 FOV 只有一个值，
        //    幂等、与事件触发次数无关、与谁先谁后无关。
        final double optionFov = mc.options.fov().get();
        boolean takeOver = !(zoom <= 1.0001 && !holdingScope);

        if (!takeOver) {
            return;
        }
        double out = optionFov / zoom;

        // ⚠️⚠️ **绝对不要再乘 10**（2026-09-26 大包第三次实测的教训）。
        //
        // 我上一版以为"原版那 10 倍是在事件之后乘的"，于是乘 10 抵消 —— 结果实测出参 192.5°
        // 就是最终值（>180° ⇒ tan 变号 ⇒ **画面上下翻转 + 鱼眼畸变**，用户截图铁证）。
        // 真相：哨戒动力臂那个 `SpyglassZoomMixin` 把"任何用望远镜姿势的物品"都判成
        // `isScoping()=true`，而那 0.1 是在**事件之前**就乘进基准里了（日志里那个 7.70 = 77×0.1）
        // ⇒ 我们**忽略入参、从选项值重算**，本来就已经绕开了它 ⇒ 再乘 10 就是纯多余。
        event.setFOV(out);
    }

    // ⚠️ 这里**曾经有一个** `@SubscribeEvent(priority = LOWEST)` 的探针（2026-09-27 加、同日删）：
    //    用来验证"我们设的 FOV 会不会被别的模组覆盖" —— `ComputeFov` 是**多方监听、后写的人赢**，
    //    而大包里 iris / acceleratedrendering / 相机模组都会取 FOV，怀疑它们在我们之后又写一次。
    //
    //    **实测结论：不会被覆盖** —— 松手那一段 20 帧里，每一帧的最终值都**等于**我们设的值 ✓
    //    ⇒ 那条分支关闭。真正的原因是 `zoom` 的平滑按"**事件调用次数**"推进（见 ZOOM_TAU_SECONDS），
    //      大包里一帧的调用次数会变 ⇒ 某些帧多推进几步 ⇒ 那一帧跳宽。**已修** ✓
    //
    //    ⚠️ 留这个注释是因为：**下次再遇到"视野类"的怪现象，别急着怀疑"被抢"** ——
    //      先用 LOWEST 探针证伪（成本很低），再往我们自己这边查。

    /**
     * 开镜时滚轮 = 调焦。
     *
     * <p>必须把事件取消掉，否则滚轮会<b>同时</b>切换快捷栏 ——
     * 那一格正好是玩家拿着指示器的那一格，切走就等于把镜子收了。
     */
    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !isScoping(player)) {
            return;
        }
        customZoom = Mth.clamp(customZoom + SCROLL_STEP * event.getScrollDeltaY(),
                MIN_CUSTOM_ZOOM, MAX_CUSTOM_ZOOM);
        event.setCanceled(true);
    }

    /** 当前平滑后的开镜倍率（1.0 = 没开镜）。灵敏度削减要用它。 */
    public static double currentZoom() {
        return zoom;
    }

    /**
     * 开镜时把视角灵敏度按倍率压下去 —— 照卓越前线：
     * {@code 原值 / max(1 + 0.2 × 倍率, 0.1)}。
     *
     * <p>放大 4~10 倍之后，同样的鼠标位移在画面上被放大同样的倍数，
     * 不压一下就会"甩得又快又急"、根本对不准。
     *
     * <p>⚠️ <b>判据是「当前倍率 &gt; 1」而不是「正在开镜」</b>：这样没开镜时天然是个
     * 空操作（倍率恒为 1.0），不会把没在用指示器的玩家的灵敏度平白压低，
     * 而且松手后能跟着倍率的平滑回落一起慢慢恢复，不会"啪"地弹回去。
     */
    public static double scopedSensitivity(double original) {
        // ⚠️ 操控无人机时，玩家自己的视角**一点都不转**（照卓越前线：它在这一步直接 return 0）。
        //    鼠标此刻是拿来转无人机的，玩家本体的头不该跟着动 ——
        //    否则你退出视角时会发现自己已经原地转了半圈。
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && MonitorItem.isPiloting(mc.player.getMainHandItem())) {
            return 0.0;
        }
        if (zoom <= 1.0001) {
            return original;
        }
        // ⭐ 与卓越前线逐字对齐（2026-09-27 对着它反编译的源码核过）：
        //    `ClientMouseHandler.changeSensitivity` 里那一支**多一个「第一人称」条件** ——
        //    也就是说**第三人称下它不降灵敏**。我们原来没这条 ⇒ 补上，行为才一模一样。
        //    （公式本身已经逐字一致：`原值 ÷ max(1 + 0.2 × 倍率, 0.1)`。）
        if (mc.options.getCameraType() != net.minecraft.client.CameraType.FIRST_PERSON) {
            return original;
        }
        double reduced = original / Math.max(1.0 + 0.2 * zoom, 0.1);
        // ⚠️⚠️ 大整合包里 `isScoping()` 被**第三方模组**（哨戒动力臂 SpyglassZoomMixin）对我们的
        //    指示器判成 true ⇒ 原版 `turnPlayer` 会走"开镜"那条分支，而那一条**用的量比正常少 8 倍**
        //    ⇒ 净效果就是"同一套鼠标位移，视角转得只有原来的八分之一"（用户实测："灵敏度又太低了"）。
        //
        // ⚠️⚠️ **这一档是给"望远镜能力被第三方模组劫持"的整合包兜底的**（2026-09-26 实测定案）：
        //
        // 「哨戒动力臂」的 `SpyglassZoomMixin` 把 `Player.isScoping()` 改成了
        // 「拿着任何用 {@code UseAnim.SPYGLASS} 的物品」就返回 true —— 而我们的指示器
        // **故意**用那个姿势（为了举到眼前的手臂动作）⇒ 原版 `turnPlayer` 里"开镜"那条
        // 分支（**少乘 8 倍**）也跟着生效 ⇒ 与我们的降灵敏叠乘 ⇒ 手感又重又黏。
        //
        // <p>⚠️ 这个数**得靠实测定**（原版那条分支到底怎么算的，光读代码推不准）：
        // 我试过不乘（用户实测"太低"）与乘 8（"异常的高"），最后用可调旋钮让用户在
        // 3.0 上定了下来 ⇒ 现在固化成常量（不再提供运行时旋钮）。
        // 只在 {@code isScoping()} 真为真时才乘 ⇒ **没有这类劫持的整合包里行为与从前逐字一致**。
        if (mc.player != null && mc.player.isScoping()) {
            reduced *= VANILLA_SCOPED_SENSITIVITY_COMPENSATION;
        }
        return reduced;
    }

    /**
     * 抵消"原版开镜分支少乘的那几倍"的系数 —— 来历见 {@link #scopedSensitivity} 里的说明。
     * 3.0 是用户实机拨出来的手感值。
     */
    private static final double VANILLA_SCOPED_SENSITIVITY_COMPENSATION = 3.0;

    // ------------------------------------------------------------------
    // 左键蓄力 / 开火
    // ------------------------------------------------------------------

    /**
     * ⚠️ 这里<b>故意不去取消鼠标按下事件</b>。
     *
     * <p>卓越前线是取消原始按下、再靠按下/抬起两个事件自己记状态。那样一来
     * {@code keyAttack} 永远不会被按下去（{@code MouseHandler.onPress} 被取消后直接 return，
     * 后面那行 {@code KeyMapping.set(...)} 根本不执行），于是只能靠事件自己维护状态。
     *
     * <p>我们改成：<b>放行按下</b>（这样每 tick 读 {@code keyAttack.isDown()} 就是准的），
     * 而在 {@link #onInteractionKeyMapping} 里取消"攻击"这个动作本身。
     * 效果一样（不挖方块、不打人），但状态来源只有一个。
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        // H：打开配置界面（键位可在「选项 → 控制」里改）。
        // ⚠️ 按键队列要**无条件排空**：没拿指示器时按的 H 会攒在队列里，
        //    一拿起指示器就"自己"弹出界面。
        boolean openRequested = false;
        while (ModKeyMappings.OPEN_INDICATOR.consumeClick()) {
            openRequested = true;
        }
        if (openRequested && player != null && mc.screen == null && holdingIndicator(player)) {
            if (ArtilleryIndicatorItem.boundComputer(player.getMainHandItem()) == null) {
                player.displayClientMessage(Component.translatable(
                        "item.create_ballistics.artillery_indicator.unbound"), true);
            }
            mc.setScreen(new ArtilleryIndicatorScreen());
        }

        if (fireCooldown > 0) {
            fireCooldown--;
        }
        // 「正在用指示器」= 主手拿着，**或操控无人机时副手拿着**（照卓越前线：图传里也能长按左键开炮）
        if (player == null || ArtilleryIndicatorItem.actingIndicator(player).isEmpty()
                || !mc.options.keyAttack.isDown()) {
            charge = 0;
            return;
        }

        charge = Math.min(charge + 1, CHARGE_CAP);
        if (charge >= FIRE_AT_TICKS && fireCooldown == 0) {
            fire(player);
            fireCooldown = FIRE_COOLDOWN_TICKS;
        }
    }

    /**
     * 左键按住不放时，<b>别把方块挖了、也别打人</b>。
     *
     * <p>取消的是 {@code InteractionKeyMappingTriggered} —— 也就是"攻击键真的触发了"这件事。
     * 它在 {@code Minecraft.startAttack()}（点下去那一下）和
     * {@code Minecraft.continueAttack()}（按住持续挖）<b>两处</b>都会发出来，
     * 所以堵这一处就够，不用碰原始按键。
     */
    @SubscribeEvent
    public static void onInteractionKeyMapping(InputEvent.InteractionKeyMappingTriggered event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (event.isAttack() && player != null && (holdingIndicator(player) || piloting(player))) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    /**
     * 正在操控无人机（主手遥控器 + 已进视角）。
     *
     * <p>⚠️ 这个姿势下也<b>必须</b>把左键的攻击动作取消掉：镜头虽然跟着无人机走了，
     * 但原版挖方块用的是<b>玩家自己眼睛</b>的射线 —— 不堵这一处，在无人机视角里点左键
     * 会去挖<b>玩家肉身脚下</b>那一片方块。
     *
     * <p>（卓越前线这一手是"开火"：副手有观测物品时驱动绑定的炮，没有就让无人机自爆。
     * 我们<b>暂不给</b>无人机视角的左键开火 —— 要不要，等用户定。）
     */
    private static boolean piloting(LocalPlayer player) {
        return MonitorItem.isPiloting(player.getMainHandItem());
    }

    /** 圆环进度：0 = 没蓄力，1 = 满了（会开火）。 */
    public static float chargeProgress() {
        return charge / (float) CHARGE_CAP;
    }

    private static void fire(LocalPlayer player) {
        BlockPos computer = ArtilleryIndicatorItem.boundComputer(ArtilleryIndicatorItem.actingIndicator(player));
        if (computer == null) {
            return;
        }
        // ⚠️ 用**指示器专用**的开火字段，不用界面的 FIELD_FIRE_ALL ——
        //    归属检查（"这台计算机是不是归你这台指示器"）只能加在专用字段上，
        //    加在公用字段上会把"站在计算机前按界面"的人也拦掉（2026-09-22 踩过）。
        PacketDistributor.sendToServer(new ComputerActionC2SPacket(
                computer, ComputerActionC2SPacket.FIELD_INDICATOR_FIRE, 0, 0, 0, 0));
    }

    // ------------------------------------------------------------------

    private static boolean holdingIndicator(LocalPlayer player) {
        return player.getMainHandItem().is(ModItems.ARTILLERY_INDICATOR.get());
    }

    private static boolean isScoping(LocalPlayer player) {
        return player.isUsingItem()
                && player.getUseItem().is(ModItems.ARTILLERY_INDICATOR.get())
                && Minecraft.getInstance().options.getCameraType() == CameraType.FIRST_PERSON;
    }
}
