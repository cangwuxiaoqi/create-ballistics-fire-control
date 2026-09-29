package net.createballistics.net;

import net.createballistics.CreateBallistics;
import net.createballistics.entity.DroneEntity;
import net.createballistics.item.MonitorItem;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * <b>无人机操控：按键位掩码</b>（照卓越前线的 {@code VehicleMovementMessage}）。
 *
 * <p>位号与它完全一致：{@code 1左 2右 4前 8后 16上 32下}（它后面还有 {@code 64/128/256}
 * 给诱饵 / 开火 / 疾跑用，我们的无人机用不到，但**位号刻意不重排**，将来要加顺着往后排即可）。
 *
 * <p>⚠️ <b>只在掩码变化时发</b>（照它）—— 按着 W 不放不会每 tick 灌包。
 * 鼠标那一路相反（每 tick 都要发），见 {@link DroneLookC2SPacket}。
 */
public record DroneControlC2SPacket(short keys) implements CustomPacketPayload {

    public static final Type<DroneControlC2SPacket> TYPE =
            new Type<>(CreateBallistics.id("drone_control"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DroneControlC2SPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> buf.writeShort(p.keys),
                    buf -> new DroneControlC2SPacket(buf.readShort()));

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(DroneControlC2SPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            // 三个闸门都在 MonitorItem#pilotedDrone 里：主手是那台遥控器 + 正在操控 + 查得到那台无人机。
            // ⚠️ 刻意**不额外**要求"无人机上的 CONTROLLER 是我" —— 绑定时就已经要求过，
            //    而多加一条"必须对上"的前置条件，正是本项目在"解绑死锁"上栽过的那个坑。
            DroneEntity drone = MonitorItem.pilotedDrone(player);
            if (drone != null) {
                drone.setControlKeys(payload.keys);
            } else {
            }
        });
    }
}
