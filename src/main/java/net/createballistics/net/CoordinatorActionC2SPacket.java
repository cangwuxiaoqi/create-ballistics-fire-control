package net.createballistics.net;

import net.createballistics.CreateBallistics;
import net.createballistics.block.entity.FireControlCoordinatorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * 火控协调器界面的操作。
 *
 * <p>界面是纯客户端的，所有操作都要发回服务端执行。
 */
public record CoordinatorActionC2SPacket(BlockPos pos, byte action, double x, double y, double z)
        implements CustomPacketPayload {

    public static final byte ACTION_SET_TARGET = 0;
    public static final byte ACTION_CYCLE_MODE = 1;
    public static final byte ACTION_LAY_ALL = 2;
    public static final byte ACTION_FIRE_ALL = 3;

    public static final Type<CoordinatorActionC2SPacket> TYPE =
            new Type<>(CreateBallistics.id("coordinator_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CoordinatorActionC2SPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeBlockPos(p.pos);
                        buf.writeByte(p.action);
                        buf.writeDouble(p.x);
                        buf.writeDouble(p.y);
                        buf.writeDouble(p.z);
                    },
                    buf -> new CoordinatorActionC2SPacket(
                            buf.readBlockPos(), buf.readByte(),
                            buf.readDouble(), buf.readDouble(), buf.readDouble()));

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CoordinatorActionC2SPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player().level().getBlockEntity(payload.pos)
                    instanceof FireControlCoordinatorBlockEntity coordinator)) {
                return;
            }
            switch (payload.action) {
                case ACTION_SET_TARGET -> coordinator.setTarget(payload.x, payload.y, payload.z);
                case ACTION_CYCLE_MODE -> coordinator.cycleMode();
                case ACTION_LAY_ALL -> coordinator.layAllTarget();
                case ACTION_FIRE_ALL -> coordinator.fireAll();
                default -> {
                }
            }
        });
    }
}
