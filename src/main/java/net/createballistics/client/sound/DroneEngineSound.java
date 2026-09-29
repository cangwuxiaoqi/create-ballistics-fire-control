package net.createballistics.client.sound;

import net.createballistics.entity.DroneEntity;
import net.createballistics.item.MonitorItem;
import net.createballistics.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * 无人机飞行时那条「嗡嗡」的循环音 —— <b>逐条抄卓越前线的 {@code VehicleSoundInstance}（其 {@code EngineSound} 子类）</b>。
 *
 * <ol>
 *   <li><b>循环 + {@link SoundSource#AMBIENT}</b>，每 tick 在自己的位置更新音量/音调；</li>
 *   <li><b>淡入淡出</b>（{@code fade} 0~3）—— 直接切音量会"啪"一下；</li>
 *   <li>开局还有一层很慢的淡入：{@code clamp(0.01 × tickCount, 0, 1)}；</li>
 *   <li><b>多普勒</b>：{@code pitch += 0.16 × atan(lastDistance − distance)}（距离先平滑，理由见 tick）；</li>
 *   <li>镜头几乎贴在机身上时音调锁 1.0（它的判据用距离；我们用"正在操控这一台"，理由见 {@link #cameraOnThisDrone}）；</li>
 *   <li><b>音量 = |power| × engineSoundVolume(0.4)</b>、<b>音调按 power 分档</b>，
 *       而 <b>"引擎在不在转 = |power| &gt; 0.05"</b> 决定它响不响（怠速 0.02 ⇒ 安静）。</li>
 * </ol>
 *
 * <p>⚠️ <b>实例是"按需新建"的</b>（见 {@link net.createballistics.client.DroneSoundHandler}）：
 * 一旦引擎停下就 {@link #stop()}，下次引擎转起来由那个 handler 再建一条 —— 照它的做法
 * （{@code ClientSyncedEntityHandler#managePhantomEngineSound}）。
 */
public class DroneEngineSound extends AbstractTickableSoundInstance {

    /**
     * 引擎音量系数 —— 照卓越前线的 {@code engineSoundVolume}（它的数据里默认 {@code 0.4}）。
     *
     * <p>⚠️ 它乘的是 **{@code power}（≤0.2）**：所以无人机的实际音量上限只有 0.08 —— 很轻的一层底噪。
     */
    private static final float ENGINE_SOUND_VOLUME = 0.4F;

    /** 淡入淡出的步长上限（照原版 0~3）。 */
    private static final int MAX_FADE = 3;

    private final DroneEntity drone;

    /** 0 = 静音，MAX_FADE = 满音量。 */
    private int fade;
    /** 该收尾了（引擎停了 / 无人机没了），正在淡出。 */
    private boolean die;
    /** 上一 tick 与镜头的距离 —— 算多普勒用；负数表示"还没采过"。 */
    private double lastDistance = -1.0;

    public DroneEngineSound(DroneEntity drone) {
        super(ModSounds.DRONE_ENGINE.get(), SoundSource.AMBIENT, drone.getRandom());
        this.drone = drone;
        this.looping = true;
        this.delay = 0;
    }

    @Override
    public void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (drone.isRemoved() || player == null) {
            stop();
            return;
        }

        // 照它：引擎不转 ⇒ 标记要收尾 ⇒ 淡到 0 就 stop()（实例不能复用；再响由 handler 新建）
        if (!drone.engineRunning()) {
            die = true;
        }
        if (die) {
            if (fade > 0) {
                fade--;
            } else {
                stop();
                return;
            }
        } else if (fade < MAX_FADE) {
            fade++;
        }

        // 位置：跟着无人机走（位置音的音源就在它身上）
        this.x = drone.getX();
        this.y = drone.getY();
        this.z = drone.getZ();

        // ---- 音量与音调：照它的 EngineSound（**完全不看速度**）----
        float power = drone.getPower();
        float enginePitch;
        if (power < 0.5F) {
            enginePitch = 0.6F + power * 0.4F;
        } else if (power <= 1.0F) {
            enginePitch = 0.8F + (power - 0.5F) * 0.4F;
        } else {
            enginePitch = Math.min(power, 1.5F);
        }
        this.pitch = Math.abs(enginePitch);
        this.volume = Math.abs(power) * ENGINE_SOUND_VOLUME
                * (float) fade * Mth.clamp(0.01F * player.tickCount, 0.0F, 1.0F);

        // 多普勒：离近了压一点、离远了抬一点（照它）。
        // ⚠️ 距离**平滑后**再喂给它：一边是"每帧算的相机位置"、另一边是"每 tick 的实体位置"，
        //    两者能差上好几格 ⇒ 原始值每 tick 都在抖 ⇒ 多普勒跟着抖 ⇒ 炸音。
        Vec3 cameraPos = minecraft.gameRenderer.getMainCamera().getPosition();
        double distance = drone.position().distanceTo(cameraPos);
        double smoothed = lastDistance < 0.0 ? distance : Mth.lerp(0.35, lastDistance, distance);
        this.pitch += (float) (0.16 * Math.atan(lastDistance < 0.0 ? 0.0 : lastDistance - smoothed));
        lastDistance = smoothed;

        // 镜头贴在机身上时把音调锁回 1.0（它为"坐在载具里"准备的）
        if (cameraOnThisDrone()) {
            this.pitch = 1.0F;
        }
    }

    /**
     * 镜头是不是就架在**这一台**无人机上（= 本地玩家正在操控它）。
     *
     * <p>⚠️ 照它的**意图**（"镜头贴在机身上就锁音调"），但判据换成这个**稳定**的：
     * 它原来用"相机与无人机的距离 &lt; 0.25"，而相机是**每帧**算的插值位置、`drone.position()`
     * 是**每 tick** 的位置，两者差上几格 ⇒ 判据每 tick 翻来覆去 ⇒ 音调反复横跳 = 炸音。
     * 比 UUID 就没有这个问题（玩家可能正在操控**另一台**，那这台不该锁）。
     */
    private boolean cameraOnThisDrone() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        ItemStack held = player.getMainHandItem();
        return MonitorItem.isPiloting(held) && drone.getUUID().equals(MonitorItem.linkedDrone(held));
    }
}
