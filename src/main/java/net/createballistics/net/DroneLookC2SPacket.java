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
 * <b>无人机操控：鼠标位移</b>（照卓越前线的 {@code MouseMoveMessage}）。
 *
 * <p>载荷是客户端每 tick 算好的两个"已经乘过灵敏度、平滑过"的速度值，
 * 服务端只是把它们加到无人机的 yaw/pitch 上（{@code yaw += 0.5 × speedX} 之类，见
 * {@link DroneEntity#applyLook}）—— <b>不在服务端算灵敏度</b>，与它一致。
 *
 * <p>⚠️ 这个包**每 tick 都发**（20 包/秒）：视角是连续量，只能这样喂。
 * 按键那一路相反（只在变化时发），见 {@link DroneControlC2SPacket}。
 */
public record DroneLookC2SPacket(double speedX, double speedY) implements CustomPacketPayload {

    public static final Type<DroneLookC2SPacket> TYPE =
            new Type<>(CreateBallistics.id("drone_look"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DroneLookC2SPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeDouble(p.speedX);
                        buf.writeDouble(p.speedY);
                    },
                    buf -> new DroneLookC2SPacket(buf.readDouble(), buf.readDouble()));

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(DroneLookC2SPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            DroneEntity drone = MonitorItem.pilotedDrone(player);
            if (drone != null) {
                drone.applyLook(payload.speedX, payload.speedY);
            } else {
            }
        });
    }
}
