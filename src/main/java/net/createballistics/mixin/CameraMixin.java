package net.createballistics.mixin;

import java.util.UUID;
import net.createballistics.entity.DroneEntity;
import net.createballistics.item.MonitorItem;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 手持遥控器进入视角后，把相机<b>顶到无人机上</b>（照卓越前线的 {@code CameraMixin}）。
 *
 * <h2>为什么要 Mixin</h2>
 *
 * 原版相机的机位与朝向是在 {@code Camera.setup} 里一次性算好的，NeoForge 没有提供
 * "换一个相机位置"的事件（它只给了 {@code ViewportEvent.ComputeCameraAngles}，<b>只能改角度</b>）。
 * 所以只能在这里截住。
 *
 * <h2>注入点：{@code setup} 的 {@code @At("RETURN")}，<b>不 cancel</b>（2026-09-27 加固）</h2>
 *
 * 原版 {@code Camera.setup}（NeoForge 21.1.228）的顺序是：
 * <pre>
 *   ① 触发 ComputeCameraAngles 事件 → setRotation(勾选后的 yaw/pitch/roll)
 *   ② setPosition(玩家眼睛位置)
 *   ③ 第三人称才有 move(-getMaxZoom(...))：把相机往后拉一段；睡觉时另有一套
 * </pre>
 *
 * <p>⚠️⚠️ <b>原来注入在 ① 那句 {@code setRotation} 调用上（{@code ordinal = 0}）并 cancel</b>，
 * 等于"提前掐断"。那个做法在**整合包里会静默失效**：大包里
 * <b>越肩 / freecam / iris / acceleratedrendering 四家都在动 {@code Camera}</b>
 * （2026-09-27 静态扫描确认），只要有人在 {@code setup} 里**先插一个同类调用**，
 * `ordinal = 0` 就会打在那个新调用上 ⇒ **不崩、只是"相机不跟无人机"** —— 正是它当年难查的原因。
 * （`defaultRequire = 1` 只保证"找不到就崩"，**不保证"找到的是对的那一个"**。）
 *
 * <p>⇒ 改成 <b>等在 {@code RETURN}</b>：让原版把整条流程跑完（含那个 ComputeCameraAngles
 * 事件 —— 别的模组在用，不该被我们打断），然后**用绝对坐标覆盖掉**机位与朝向。
 * <ul>
 *   <li><b>不依赖 ordinal</b> ⇒ 谁在 setup 里插什么都不影响我们；</li>
 *   <li><b>不 cancel</b> ⇒ 事件照发、别人的链路照走；</li>
 *   <li>③ 那个"往后拉"**不用管** —— 它改的是 position，被我们覆盖掉了；</li>
 *   <li>需要的 {@code rotation} / {@code forwards/up/left} / {@code blockPosition}
 *       由原版的 {@code setRotation} / {@code setPosition} 自己算好，<b>不用我们拼四元数</b>；</li>
 *   <li>本方法天生**幂等**（设的全是绝对值）⇒ 一帧被调多次也无所谓 ✓</li>
 * </ul>
 *
 * <p>⚠️ 用 {@code setRotation(FFF)}（NeoForge 版三参）而不是那个 {@code @Deprecated} 的两参版 ——
 * 两参版只是个转发，将来可能被删。
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    /** 机位（**机体系**，照卓越前线）：机头前 0.18 格、比机身中心高 0.075 格 —— 像机载摄像头。 */
    private static final double CAMERA_FORWARD = 0.18;
    private static final double CAMERA_UP = 0.075;

    @Shadow
    protected abstract void setRotation(float yRot, float xRot, float roll);

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Inject(method = "setup", at = @At("RETURN"))
    private void createballistics$droneCamera(BlockGetter level, Entity entity, boolean detached,
                                              boolean thirdPersonReverse, float partialTick, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        // 照它：第一人称与第三人称背面都算"在视角里"（玩家按 F5 换视角不该把我们踢出去）
        CameraType type = mc.options.getCameraType();
        if (type != CameraType.FIRST_PERSON && type != CameraType.THIRD_PERSON_BACK) {
            return;
        }
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof MonitorItem) || !MonitorItem.isPiloting(stack)) {
            return;
        }
        UUID linked = MonitorItem.linkedDrone(stack);
        if (linked == null) {
            return;
        }
        DroneEntity drone = MonitorItem.findLinked(player.level(), linked);
        if (drone == null) {
            return;
        }

        // 插值到当前帧（否则相机会比无人机晚一 tick、跟着抖）
        // ⚠️⚠️ 偏航必须**按差值**插值：普通 `Mth.lerp(yRotO, yRot)` 在两者跨过 ±180°
        //    （或被归一化过）时会**绕远路转一整圈** ⇒ 高速转向时"一卡一卡"
        //    （2026-09-25 实测：帧耗时平均 4~5 ms、最大十几毫秒 ⇒ 不是性能问题，是这一下）。
        //    `yRotO + wrap(yRot − yRotO) × partialTick` 永远走近路。
        float yaw = drone.yRotO + Mth.wrapDegrees(drone.getYRot() - drone.yRotO) * partialTick;
        // 俯仰不归一化（被夹在 [-10°, 90°]）⇒ 普通 lerp 就够
        float pitch = Mth.lerp(partialTick, drone.xRotO, drone.getXRot());

        double droneX = Mth.lerp((double) partialTick, drone.xo, drone.getX());
        double droneY = Mth.lerp((double) partialTick, drone.yo, drone.getY());
        double droneZ = Mth.lerp((double) partialTick, drone.zo, drone.getZ());

        // 机体系 → 世界：先按无人机位置平移，再绕 Y 转 -yaw
        // （⚠️ 是 **-yaw**：我们的模型正面是 +Z，与它同一个约定。MC 的 yaw 0 = 朝 +Z、90 = 朝 -X，
        //   代进去两处都对：yaw=0 时机位在 +Z、yaw=90 时在 -X。）
        double yawRad = Math.toRadians(yaw);
        double worldX = droneX - CAMERA_FORWARD * Math.sin(yawRad);
        double worldY = droneY + CAMERA_UP;
        double worldZ = droneZ + CAMERA_FORWARD * Math.cos(yawRad);

        // ⚠️ 三参版（roll 归 0）；且**不 cancel** —— 让原版从头跑完，我们只覆盖结果。
        this.setRotation(yaw, pitch, 0.0F);
        this.setPosition(worldX, worldY, worldZ);
    }
}
