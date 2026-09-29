package net.createballistics.registry;

import net.createballistics.CreateBallistics;
import net.createballistics.block.FireControlComputerBlock;
import net.createballistics.block.FireControlCoordinatorBlock;
import net.createballistics.block.FireControlNodeBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 三个核心方块的注册。
 *
 * <ul>
 *   <li><b>火控节点</b> fire_control_node —— 挨着 CBC 炮座放，正面接应力</li>
 *   <li><b>火控协调器</b> fire_control_coordinator —— 组网与调度</li>
 *   <li><b>火控计算机</b> fire_control_computer —— 解算射击诸元</li>
 * </ul>
 */
public class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CreateBallistics.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CreateBallistics.MODID);

    /** 与 CBC 的机匣同风格的金属方块属性。 */
    private static BlockBehaviour.Properties metal() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .sound(SoundType.METAL)
                .strength(1.5F)
                .requiresCorrectToolForDrops();
    }

    public static final DeferredBlock<FireControlNodeBlock> FIRE_CONTROL_NODE =
            BLOCKS.register("fire_control_node", () -> new FireControlNodeBlock(metal()));
    public static final DeferredItem<BlockItem> FIRE_CONTROL_NODE_ITEM =
            ITEMS.registerSimpleBlockItem("fire_control_node", FIRE_CONTROL_NODE, new Item.Properties().stacksTo(64));

    public static final DeferredBlock<FireControlCoordinatorBlock> FIRE_CONTROL_COORDINATOR =
            BLOCKS.register("fire_control_coordinator", () -> new FireControlCoordinatorBlock(metal()));
    public static final DeferredItem<BlockItem> FIRE_CONTROL_COORDINATOR_ITEM =
            ITEMS.registerSimpleBlockItem("fire_control_coordinator", FIRE_CONTROL_COORDINATOR, new Item.Properties().stacksTo(64));

    public static final DeferredBlock<FireControlComputerBlock> FIRE_CONTROL_COMPUTER =
            BLOCKS.register("fire_control_computer", () -> new FireControlComputerBlock(metal()));
    public static final DeferredItem<BlockItem> FIRE_CONTROL_COMPUTER_ITEM =
            ITEMS.registerSimpleBlockItem("fire_control_computer", FIRE_CONTROL_COMPUTER, new Item.Properties().stacksTo(64));

    public static void register(IEventBus eventBus) {
        BLOCKS.register(eventBus);
        ITEMS.register(eventBus);
    }
}
