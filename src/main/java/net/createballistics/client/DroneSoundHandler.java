package net.createballistics.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.createballistics.CreateBallistics;
import net.createballistics.client.sound.DroneEngineSound;
import net.createballistics.entity.DroneEntity;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 无人机引擎音效的<b>创建 / 回收</b> —— 照卓越前线的
 * {@code ClientSyncedEntityHandler#managePhantomEngineSound}：
 * <b>按需新建</b>（引擎转起来才 {@code new} 一条并 {@code play()}），实例停了就从表里去掉，下次再建。
 *
 * <p>⚠️ <b>为什么必须"按需新建"</b>：{@code SoundInstance} 一旦 {@code stop()} 就<b>不能复用</b>。
 * 我们早先是"无人机一进世界就建一条、永不重建"，于是引擎第一次熄火之后<b>再也听不到</b>
 * （用户 2026-09-23 实测："嗡嗡声彻底没了"）。
 *
 * <p>候选实体靠 <b>{@code EntityJoinLevelEvent}</b> 登记（客户端没有"按 UUID 查实体"的公开接口）——
 * 那个事件在"区块加载把实体交给客户端"时也会触发，所以飞远卸载、飞回来会自动重新登记。
 */
@EventBusSubscriber(modid = CreateBallistics.MODID, value = Dist.CLIENT)
public final class DroneSoundHandler {

    /** 登记过的无人机（UUID → 实体）。 */
    private static final Map<UUID, DroneEntity> DRONES = new HashMap<>();
    /** 此刻正在响的那几条（UUID → 音效实例）。 */
    private static final Map<UUID, DroneEngineSound> SOUNDS = new HashMap<>();

    private DroneSoundHandler() {
    }

    /** 无人机被交给客户端时登记（见类注释：区块加载/重新进入视距都会触发）。 */
    public static void register(DroneEntity drone) {
        DRONES.put(drone.getUUID(), drone);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            DRONES.clear();
            SOUNDS.clear();
            return;
        }
        // 实体没了 / 音效已经停了（引擎熄火后它自己 stop 的）⇒ 清掉登记
        DRONES.entrySet().removeIf(entry -> entry.getValue().isRemoved());
        SOUNDS.entrySet().removeIf(entry -> !mc.getSoundManager().isActive(entry.getValue()));

        for (DroneEntity drone : DRONES.values()) {
            if (!drone.engineRunning() || SOUNDS.containsKey(drone.getUUID())) {
                continue;
            }
            // 引擎转起来了、而这台还没有音效 ⇒ 照它：新建一条并播放
            DroneEngineSound sound = new DroneEngineSound(drone);
            mc.getSoundManager().play(sound);
            SOUNDS.put(drone.getUUID(), sound);
        }
    }
}
