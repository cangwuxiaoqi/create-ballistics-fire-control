package net.createballistics.registry;

import net.createballistics.CreateBallistics;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 创造模式物品栏。<b>图标用火控计算机</b>。
 */
public class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CreateBallistics.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.create_ballistics"))
                    .icon(() -> new ItemStack(ModBlocks.FIRE_CONTROL_COMPUTER_ITEM.get()))
                    .displayItems((params, output) -> {
                        output.accept(ModBlocks.FIRE_CONTROL_COMPUTER_ITEM.get());
                        output.accept(ModBlocks.FIRE_CONTROL_COORDINATOR_ITEM.get());
                        output.accept(ModBlocks.FIRE_CONTROL_NODE_ITEM.get());
                        output.accept(ModItems.ARTILLERY_INDICATOR.get());
                        output.accept(ModItems.MONITOR.get());
                        output.accept(ModItems.DRONE.get());
                    })
                    .build());

    public static void register(IEventBus eventBus) {
        TABS.register(eventBus);
    }
}
