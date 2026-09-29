package net.createballistics.registry;

import net.createballistics.CreateBallistics;
import net.createballistics.item.ArtilleryIndicatorItem;
import net.createballistics.item.DroneItem;
import net.createballistics.item.MonitorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 物品注册。
 *
 * <p>目前只有一件：<b>火炮指示器</b>（观测层 B1）。三个火控方块的物品形式在
 * {@link ModBlocks} 里注册，不在这里。
 */
public class ModItems {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CreateBallistics.MODID);

    /** 火炮指示器：望远镜 / 绑定器 / 之后的配置与开火。一台指示器只绑一台计算机，所以不可堆叠。 */
    public static final DeferredItem<ArtilleryIndicatorItem> ARTILLERY_INDICATOR =
            ITEMS.register("artillery_indicator", () -> new ArtilleryIndicatorItem(
                    new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));

    /** 无人机（物品形态）：潜行右键方块放出来，**消耗一个**（会撞坏，是消耗品）。 */
    // ⚠️ 2026-09-24：**可堆叠到 64**（用户定：只有「火炮指示器」和「无人机遥控器」不可堆叠，
    //    其余一律 64）。原来是 16，没有理由，改掉。
    public static final DeferredItem<DroneItem> DRONE =
            ITEMS.register("drone", () -> new DroneItem(new Item.Properties().stacksTo(64)));

    /** 无人机监视器：遥控 + 图传。一台只绑一台无人机，所以不可堆叠。 */
    public static final DeferredItem<MonitorItem> MONITOR =
            ITEMS.register("monitor", () -> new MonitorItem(
                    new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));

    // 注：这里曾经还有 5 个"渲染专用的隐藏物品"（机身 + 四片桨叶），
    // 那是早期"把 Bedrock 模型转成原版模型、借原版 ItemRenderer 画"方案的产物。
    // 现在无人机由 GeckoLib 直接读 Bedrock 模型渲染（见 client/model/DroneModel），
    // 那 5 个物品连同它们的模型 json、自定义 geometry loader 一起删掉了。

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
