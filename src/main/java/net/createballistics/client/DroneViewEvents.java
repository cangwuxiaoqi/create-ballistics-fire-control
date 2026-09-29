package net.createballistics.client;

import net.createballistics.CreateBallistics;
import net.createballistics.item.MonitorItem;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * 无人机图传的<b>变焦</b> —— 照卓越前线的 {@code droneFov} / {@code droneFovLerp} 那一套：
 * <ul>
 *   <li><b>滚轮</b>改变焦目标：{@code clamp(目标 + 0.4 × 滚轮, 1.0, 6.0)}（它的原句）；</li>
 *   <li>实际倍率是**平滑**过去的：{@code lerp(0.1 × 帧时间, 当前, 目标)}；</li>
 *   <li>应用方式是 <b>FOV 除以倍率</b>（{@code event.setFOV(fov / lerp)}）；</li>
 *   <li>⚠️ 鼠标灵敏度也要**除以同一个倍率**（它 `ClientMouseHandler` 里就是
 *       {@code 灵敏度 / droneFovLerp}）—— 不然放大 6 倍后一甩就过头。</li>
 * </ul>
 *
 * <p>⚠️ 滚轮必须取消事件，否则会同时切换快捷栏 —— 那一格正是玩家拿着遥控器的那一格
 * （与举镜调焦是同一个坑，2026-09-22 踩过）。
 */
@EventBusSubscriber(modid = CreateBallistics.MODID, value = Dist.CLIENT)
public final class DroneViewEvents {

    private static final double MIN_ZOOM = 1.0;
    private static final double MAX_ZOOM = 6.0;
    private static final double SCROLL_STEP = 0.4;

    /** 变焦目标（滚轮改它）。 */
    private static double zoom = 1.0;
    /** 平滑后的实际倍率（用于 FOV、灵敏度、HUD 上的读数）。 */
    private static double zoomLerp = 1.0;

    private DroneViewEvents() {
    }

    /** 平滑后的倍率（1.0 = 没变焦）。{@code DroneHudOverlay} 与灵敏度都用它。 */
    public static double zoomLerp() {
        return zoomLerp;
    }

    @SubscribeEvent
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        if (!piloting()) {
            // 退出视角后平滑回到 1.0（这样"收镜"是渐进的，不会啪一下）
            zoom = 1.0;
            zoomLerp = Mth.lerp(Math.min(0.1F * (float) event.getPartialTick(), 1.0F), zoomLerp, 1.0);
            return;
        }
        zoomLerp = Mth.lerp(Math.min(0.1F * (float) event.getPartialTick(), 1.0F), zoomLerp, zoom);
        // ⚠️ 没变焦时一个字都不改（别人的 FOV 改动保持原样），只在真变焦时接管。
        if (zoomLerp <= 1.0001) {
            return;
        }
        // ⚠️⚠️ **绝对基准，不用入参** —— 与 IndicatorClientEvents 完全同一个理由（2026-09-26 大包实测）：
        //    一帧内这个事件会被触发多次、别人的改动也会混进入参，用「入参 ÷ 倍率」会**连乘**。
        Minecraft mc = Minecraft.getInstance();
        // ⚠️ **不要乘 10**（同 IndicatorClientEvents 的注释）：那 0.1 是乘在**基准**上的，
        //    我们既然从选项值重算，就已经绕开了它。
        event.setFOV(mc.options.fov().get() / zoomLerp);
    }

    /** 滚轮 = 调焦（照它：{@code droneFov = clamp(droneFov + 0.4 × scroll, 1, 6)}）。 */
    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (!piloting()) {
            return;
        }
        zoom = Mth.clamp(zoom + SCROLL_STEP * event.getScrollDeltaY(), MIN_ZOOM, MAX_ZOOM);
        event.setCanceled(true);
    }

    private static boolean piloting() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && MonitorItem.isPiloting(mc.player.getMainHandItem());
    }
}
