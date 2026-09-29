package net.createballistics.client;

import net.createballistics.client.screen.FireControlComputerScreen;
import net.createballistics.client.screen.FireControlCoordinatorScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * 客户端界面入口。
 *
 * <p>两个界面都是纯客户端 {@code Screen}（不走容器菜单）—— 数据由方块实体同步过来，
 * 玩家的修改通过自定义网络包发回服务端。
 */
public final class ClientHooks {

    private ClientHooks() {
    }

    public static void openCoordinatorScreen(BlockPos pos) {
        Minecraft.getInstance().setScreen(new FireControlCoordinatorScreen(pos));
    }

    public static void openComputerScreen(BlockPos pos) {
        Minecraft.getInstance().setScreen(new FireControlComputerScreen(pos));
    }
}
