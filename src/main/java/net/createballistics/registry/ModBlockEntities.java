package net.createballistics.registry;

import java.util.Set;
import net.createballistics.CreateBallistics;
import net.createballistics.block.entity.FireControlComputerBlockEntity;
import net.createballistics.block.entity.FireControlCoordinatorBlockEntity;
import net.createballistics.block.entity.FireControlNodeBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CreateBallistics.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FireControlNodeBlockEntity>> FIRE_CONTROL_NODE =
            BLOCK_ENTITIES.register("fire_control_node",
                    () -> BlockEntityType.Builder.of(FireControlNodeBlockEntity::new,
                            new Block[]{ModBlocks.FIRE_CONTROL_NODE.get()}).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FireControlCoordinatorBlockEntity>> FIRE_CONTROL_COORDINATOR =
            BLOCK_ENTITIES.register("fire_control_coordinator",
                    () -> BlockEntityType.Builder.of(FireControlCoordinatorBlockEntity::new,
                            new Block[]{ModBlocks.FIRE_CONTROL_COORDINATOR.get()}).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FireControlComputerBlockEntity>> FIRE_CONTROL_COMPUTER =
            BLOCK_ENTITIES.register("fire_control_computer",
                    () -> BlockEntityType.Builder.of(FireControlComputerBlockEntity::new,
                            new Block[]{ModBlocks.FIRE_CONTROL_COMPUTER.get()}).build(null));

    public static void register(IEventBus eventBus) {
        BLOCK_ENTITIES.register(eventBus);
    }

    /** 供便利使用的类型集合。 */
    public static Set<BlockEntityType<?>> allTypes() {
        return Set.of(FIRE_CONTROL_NODE.get(), FIRE_CONTROL_COORDINATOR.get(), FIRE_CONTROL_COMPUTER.get());
    }
}
