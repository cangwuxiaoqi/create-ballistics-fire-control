package net.createballistics.net;

import net.createballistics.CreateBallistics;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 网络包注册。
 *
 * <p>只有两个方向：客户端 → 服务端（界面操作）。方块实体 → 客户端的数据同步走
 * 原版的 {@code getUpdateTag / sendBlockUpdated}，不用自定义包。
 */
@EventBusSubscriber(modid = CreateBallistics.MODID)
public final class ModPayloads {

    private ModPayloads() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(CoordinatorActionC2SPacket.TYPE, CoordinatorActionC2SPacket.STREAM_CODEC,
                CoordinatorActionC2SPacket::handle);
        registrar.playToServer(ComputerActionC2SPacket.TYPE, ComputerActionC2SPacket.STREAM_CODEC,
                ComputerActionC2SPacket::handle);
        // 无人机操控（B3-b-②）：按键位掩码（变化时才发）+ 鼠标位移（每 tick 发），照卓越前线那两个包。
        registrar.playToServer(DroneControlC2SPacket.TYPE, DroneControlC2SPacket.STREAM_CODEC,
                DroneControlC2SPacket::handle);
        registrar.playToServer(DroneLookC2SPacket.TYPE, DroneLookC2SPacket.STREAM_CODEC,
                DroneLookC2SPacket::handle);
    }
}
