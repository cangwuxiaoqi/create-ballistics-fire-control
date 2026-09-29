package net.createballistics;

import net.createballistics.registry.ModBlockEntities;
import net.createballistics.registry.ModBlocks;
import net.createballistics.registry.ModCreativeTabs;
import net.createballistics.registry.ModDataComponents;
import net.createballistics.registry.ModEntities;
import net.createballistics.registry.ModItems;
import net.createballistics.registry.ModSounds;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 机械动力火炮：火控计算弹道学
 *
 * <p>为 Create Big Cannons 提供火控：火控计算机解算诸元 → 火控协调器调度整个炮群 →
 * 火控节点逐 tick 驱动每一门炮的俯仰/回旋并击发。
 *
 * <p>解算核心在 {@link net.createballistics.ballistic} 包里，刻意做成<b>不依赖 Minecraft</b>，
 * 因此可以用 {@code gradlew verifySolver} 在普通 JVM 里跑数值自检。
 */
@Mod(CreateBallistics.MODID)
public class CreateBallistics {

    public static final String MODID = "create_ballistics";
    public static final Logger LOGGER = LoggerFactory.getLogger("CreateBallistics");

    public CreateBallistics(IEventBus modEventBus, ModContainer modContainer) {
        // ⭐ 给火控射出的那一发"保活"（区块票控制器，见 ShellChunkKeeper 的类注释：
        //    大整合包里远射的炮弹会被"卡顿 ⇒ RPL 票过期 ⇒ 停 tick ⇒ 死锁"吞掉）。
        modEventBus.addListener(net.createballistics.cbc.ShellChunkKeeper::registerController);
        ModBlocks.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModItems.register(modEventBus);
        ModDataComponents.register(modEventBus);
        ModSounds.register(modEventBus);
        ModEntities.register(modEventBus);
        ModCreativeTabs.register(modEventBus);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
