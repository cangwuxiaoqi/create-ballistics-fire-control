package net.createballistics.client;

import net.createballistics.CreateBallistics;
import net.createballistics.entity.DroneEntity;
import net.createballistics.item.MonitorItem;
import net.createballistics.net.DroneControlC2SPacket;
import net.createballistics.net.DroneLookC2SPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 操控无人机的<b>客户端</b>那一半：采集输入、发给服务端，并按住玩家本体
 * （照卓越前线的 {@code ClientMouseHandler} + {@code ClientEventHandler.handleControlVehicle}
 * + {@code KeyboardInputMixin}）。
 *
 * <h2>两个包，两种节奏（都照它）</h2>
 * <ul>
 *   <li><b>按键位掩码</b>：{@link DroneControlC2SPacket}，<b>只在掩码变化时</b>发 —— 按着 W 不放不会每 tick 灌包。</li>
 *   <li><b>鼠标位移</b>：{@link DroneLookC2SPacket}，<b>每 tick 都发</b> —— 视角是连续量，只能这样喂。</li>
 * </ul>
 *
 * <h2>鼠标位移怎么采：直接读 {@code MouseHandler} 的累积位置（不用 Mixin）</h2>
 *
 * 鼠标被抓进窗口时，{@code MouseHandler.xpos()/ypos()} 就是它一直在累加的原始位置，
 * 相邻两 tick 的差就是这一 tick 的位移 —— 卓越前线也是这么读的（{@code MouseMovementHandler.getMousePos}）。
 * 所以这一块**不需要新的 Mixin**，只要自己记住上一次的位置。
 *
 * <h2>玩家本体：转不了头（本类 + {@code IndicatorClientEvents}）+ 走不了路（本类）</h2>
 *
 * 照它的两处做法，但我们**都用事件实现、不加 Mixin**：
 * <ul>
 *   <li><b>不转头</b>：它把 {@code turnPlayer} 的灵敏度置 0（在 {@code MouseHandlerMixin} 里）。
 *       我们已经有同一个注入点（开镜降灵敏那个），所以顺手在那儿多判一句即可。</li>
 *   <li><b>不走路</b>：它用 {@code KeyboardInputMixin} 把 {@code Input} 清零。
 *       我们用 NeoForge 的 {@link MovementInputUpdateEvent}（{@code LocalPlayer} 里紧跟
 *       {@code input.tick} 之后触发）—— 效果一样，少一个 Mixin。</li>
 * </ul>
 */
@EventBusSubscriber(modid = CreateBallistics.MODID, value = Dist.CLIENT)
public final class DroneControlClient {

    /** 鼠标灵敏度 —— 照卓越前线的载具数据默认值 {@code mouseSensitivity = 0.4}。 */
    private static final double SENSITIVITY = 0.4;
    /** 单个 tick 的鼠标位移夹取上限（照它）。猛甩一下鼠标不至于让无人机翻过去。 */
    private static final double MOUSE_DELTA_LIMIT = 256.0;
    /** 位移的平滑系数（照它：{@code lerp(0.3)}）。 */
    private static final double SMOOTHING = 0.3;

    /** 上一次发出去的位掩码；{@code -1} = 还没发过（进视角时复位，逼它发一次）。 */
    private static short lastKeys = -1;
    /** 上一刻是不是在操控 —— 用来分辨"刚进视角"（要把鼠标基准点重新采一次）。 */
    private static boolean wasPiloting;
    private static double lastMouseX;
    private static double lastMouseY;
    /** 平滑后的速度（照它：发的是这个平滑值，不是原始位移）。 */
    private static double lerpSpeedX;
    private static double lerpSpeedY;

    private DroneControlClient() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        boolean piloting = player != null && MonitorItem.isPiloting(player.getMainHandItem());

        // ⭐⭐ 相机对账（2026-09-26 修「无人机炸了、画面回到玩家身上，人却卡在第三人称」）。
        //
        // ⚠️⚠️ 位置很关键：必须在下面那条 `if (!piloting) return;` **之前**，
        //    而且判据**不能**只看 `MONITOR_USING` 组件 —— 因为服务端也在跑同一条
        //    `MonitorItem#inventoryTick`，而且**服务端会先一步**把它清掉并同步过来。
        //    那一刻客户端看到的 `isPiloting` 已经是 false ⇒ `MonitorItem` 那条兜底**直接早退**
        //    ⇒ `PilotCamera.exit()` 永远调不到 ⇒ 相机停在 THIRD_PERSON_BACK，
        //    玩家只看到自己站在那儿（第三人称）出不来。
        //    ⚠️ 这就是"偶尔"的来源：服务端与客户端谁先跑到那一步是个**竞态**。
        //    日志实证：有几次只有 `[Server thread] 遥控器自动退出视角`、没有对应的客户端那一行。
        //
        // ⭐ 判据改成"**这一帧到底该不该在无人机上**"：正在操控 **且** 客户端真的找得到那台无人机。
        //   · 不在了 ⇒ `exit()`（幂等：没进过视角时什么都不做 ⇒ 每 tick 调安全）；
        //   · 又在了（典型：飞出了客户端视距又飞回来）⇒ 补一次 `enter()`，
        //     ⚠️ 用 `isActive()` 当闸门 ⇒ **只在真的"没在视角里"时**才切，
        //     玩家自己按 F5 切的第一/第三人称不会被每 tick 覆盖掉。
        if (piloting && MonitorItem.pilotedDrone(player) != null) {
            if (!PilotCamera.isActive()) {
                PilotCamera.enter();
            }
        } else {
            PilotCamera.exit();
        }

        if (!piloting) {
            if (wasPiloting) {
                wasPiloting = false;
                lastKeys = -1;
                lerpSpeedX = 0.0;
                lerpSpeedY = 0.0;
            }
            return;
        }
        if (!wasPiloting) {
            // ⚠️ 刚进视角：把鼠标基准点重新采一次。不然第一帧的位移会是"进视角前的累积量"，
            //    无人机会当场被甩一下。
            wasPiloting = true;
            lastKeys = -1;
            lastMouseX = mc.mouseHandler.xpos();
            lastMouseY = mc.mouseHandler.ypos();
        }

        // ---- 按键：只在变化时发 ----
        short keys = currentKeys();
        if (keys != lastKeys) {
            lastKeys = keys;
            PacketDistributor.sendToServer(new DroneControlC2SPacket(keys));
        }

        // ---- 鼠标：每 tick 都发 ----
        double mouseX = mc.mouseHandler.xpos();
        double mouseY = mc.mouseHandler.ypos();
        double deltaX = 0.0;
        double deltaY = 0.0;
        // ⚠️ 开着界面时鼠标是自由光标，位移没有意义（而且会是一个巨大的跳变）——
        //    那时发 0，并且把基准点跟住，关界面的那一帧才不会跳。
        if (mc.screen == null) {
            deltaX = Mth.clamp(mouseX - lastMouseX, -MOUSE_DELTA_LIMIT, MOUSE_DELTA_LIMIT);
            deltaY = Mth.clamp(mouseY - lastMouseY, -MOUSE_DELTA_LIMIT, MOUSE_DELTA_LIMIT);
        }
        lastMouseX = mouseX;
        lastMouseY = mouseY;

        // 灵敏度照它：drone.getMouseSensitivity() / **变焦倍率** × 位移
        // （它 `ClientMouseHandler` 里就是除以 `droneFovLerp`）—— 放大到 6 倍后不除就是一甩就过头。
        double sensitivity = SENSITIVITY / DroneViewEvents.zoomLerp();
        lerpSpeedX = Mth.lerp(SMOOTHING, lerpSpeedX, deltaX * sensitivity);
        lerpSpeedY = Mth.lerp(SMOOTHING, lerpSpeedY, deltaY * sensitivity);
        // ⚠️ 本地立刻转一下头（与服务端同一个公式，见 DroneEntity#predictLookLocal）：
        //    不这样的话朝向要等一个网络往返 + 插值窗口，高速转圈时又滞后又抖。
        DroneEntity localDrone = MonitorItem.pilotedDrone(player);
        if (localDrone != null) {
            localDrone.predictLookLocal(lerpSpeedX, lerpSpeedY);
        }

        PacketDistributor.sendToServer(new DroneLookC2SPacket(lerpSpeedX, lerpSpeedY));
    }

    /** 此刻按着哪些键 —— 位号见 {@link DroneEntity#KEY_LEFT} 那一组（与卓越前线一致）。 */
    private static short currentKeys() {
        int keys = 0;
        if (ModKeyMappings.MOVE_LEFT.isDown()) {
            keys |= DroneEntity.KEY_LEFT;
        }
        if (ModKeyMappings.MOVE_RIGHT.isDown()) {
            keys |= DroneEntity.KEY_RIGHT;
        }
        if (ModKeyMappings.MOVE_FORWARD.isDown()) {
            keys |= DroneEntity.KEY_FORWARD;
        }
        if (ModKeyMappings.MOVE_BACK.isDown()) {
            keys |= DroneEntity.KEY_BACK;
        }
        if (ModKeyMappings.MOVE_UP.isDown()) {
            keys |= DroneEntity.KEY_UP;
        }
        if (ModKeyMappings.MOVE_DOWN.isDown()) {
            keys |= DroneEntity.KEY_DOWN;
        }
        return (short) keys;
    }

    /**
     * 操控期间<b>玩家本体不跟着走</b>（照它的 {@code KeyboardInputMixin}）。
     *
     * <p>⚠️ 必须有这一步：无人机的键默认就是 WASD，不清零的话你会一边看着无人机一边
     * 自己走下悬崖 —— 而且操控时你根本看不见自己在往哪走。
     *
     * <p>清零放在这个事件里（{@code LocalPlayer} 里紧跟 {@code input.tick} 之后触发），
     * 比 Mixin 更靠后、也更稳：无论版本怎么改 {@code KeyboardInput} 的字段，这里改的
     * 都是它交给移动逻辑的那份结果。
     */
    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (!(event.getEntity() instanceof LocalPlayer player)
                || !MonitorItem.isPiloting(player.getMainHandItem())) {
            return;
        }
        Input input = event.getInput();
        input.up = false;
        input.down = false;
        input.left = false;
        input.right = false;
        input.jumping = false;
        input.shiftKeyDown = false;
        input.forwardImpulse = 0.0F;
        input.leftImpulse = 0.0F;
    }
}
