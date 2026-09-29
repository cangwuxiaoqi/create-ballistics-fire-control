package net.createballistics.client;

import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import net.createballistics.CreateBallistics;
import net.createballistics.client.sound.DroneEngineSound;
import net.createballistics.entity.DroneEntity;
import net.createballistics.item.MonitorItem;
import net.createballistics.registry.ModBlockEntities;
import net.createballistics.registry.ModEntities;
import net.createballistics.registry.ModItems;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBufferCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterNamedRenderTypesEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * 客户端注册。
 *
 * <h2>三个火控方块<b>只</b>注册 BER 一条路，不注册 Flywheel visual</h2>
 *
 * 包内四个附属模组分成两派，取决于它有没有给方块注册 {@code .visual(...)}：
 *
 * <pre>
 * 【只走 BER】动力加载器、simulated     —— 覆写 renderSafe 时<b>去掉</b>了
 *                                        KineticBlockEntityRenderer 里那句
 *                                        if (!VisualizationManager.supportsVisualization(level)) return;
 * 【双路】Create、火炮、创意传动         —— visual + 带判断的 BER
 * </pre>
 *
 * <p>两派都能跑，但<b>混着来一定出事</b>：注册了 visual 又不给 BER 加判断，就是两根轴
 * 严丝合缝地叠在一起互相 z-fighting；注册了 visual 又给 BER 加了判断，就等于把结果
 * 完全押在 visual 上（本 mod 之前就是这么翻的车）。
 *
 * <p>这里选「只走 BER」：写法最简单，且在本整合包里有动力加载器和 simulated 两个
 * 活代码实例可对照。
 *
 * <p>Create 自己的 {@code SplitShaftRenderer} / {@code GearboxRenderer} 里那段
 * {@code partialFacing(SHAFT_HALF) + RenderType.solid()} 属于双路的兜底分支，在有
 * Flywheel 的客户端上被判断挡掉、从不执行——所以别拿它当范本。
 */
@EventBusSubscriber(modid = CreateBallistics.MODID, value = Dist.CLIENT)
public final class ModClientRegistrations {

    private ModClientRegistrations() {
    }

    @SubscribeEvent
    public static void clientInit(FMLClientSetupEvent event) {
        // ---- SuperByteBuffer 分区 ----
        // Create 在 CreateClient.clientInit 里做了同样的事。不注册的话用不了 CachedBuffers，
        // 症状是形状位置都对、颜色全黑。重复注册是幂等的（内部只是 caches.put）。
        SuperByteBufferCache cache = SuperByteBufferCache.getInstance();
        cache.registerCompartment(CachedBuffers.PARTIAL);
        cache.registerCompartment(CachedBuffers.DIRECTIONAL_PARTIAL);
        cache.registerCompartment(KineticBlockEntityRenderer.KINETIC_BLOCK);

        // ---- 遥控器的「已绑定」图标（黑屏 → 蓝屏）----
        // 照卓越前线：物品模型里挂一条 overrides 判据，值由这里算（它读的是物品 NBT 的 Linked 布尔）。
        // ⚠️ 必须 enqueueWork —— FMLClientSetupEvent 跑在并行加载线程上，注册物品属性要回到主线程。
        event.enqueueWork(() -> ItemProperties.register(ModItems.MONITOR.get(),
                CreateBallistics.id("linked"),
                (stack, level, entity, seed) -> MonitorItem.linkedDrone(stack) != null ? 1.0F : 0.0F));
    }

    // note: this file used to register two things for the drone part models --
    // a custom geometry loader and a named render type. Both belonged to the early
    // convert-Bedrock-to-vanilla pipeline; the drone is now rendered by GeckoLib
    // straight from the Bedrock model (see client/model/DroneModel), and both of those,
    // the 5 hidden part items and the converter script have been deleted.
    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // 火控节点：应力接入面在<b>正面</b>（FACING）。它的 FACING 是六向的 DirectionalKineticBlock.FACING。
        event.registerBlockEntityRenderer(ModBlockEntities.FIRE_CONTROL_NODE.get(),
                ctx -> new FireControlShaftRenderer<>(BlockStateProperties.FACING, true));

        // 火控计算机 / 协调器：应力接入面在<b>背面</b>（FACING 的反面）。FACING 是水平四向的。
        event.registerBlockEntityRenderer(ModBlockEntities.FIRE_CONTROL_COMPUTER.get(),
                ctx -> new FireControlShaftRenderer<>(BlockStateProperties.HORIZONTAL_FACING, false));
        event.registerBlockEntityRenderer(ModBlockEntities.FIRE_CONTROL_COORDINATOR.get(),
                ctx -> new FireControlShaftRenderer<>(BlockStateProperties.HORIZONTAL_FACING, false));

        // 无人机：GeckoLib 渲染（直接读卓越前线那份 Bedrock 模型，见 client/model/DroneModel）
        event.registerEntityRenderer(ModEntities.DRONE.get(), DroneRenderer::new);
    }

    /**
     * 两个迭加层。顺序有意义：先注册的在下 ——
     * 无人机名牌该被举镜时的镜筒盖住（举起镜筒时本来就该盖住别的信息）。
     */
    @SubscribeEvent
    public static void registerGuiLayers(RegisterGuiLayersEvent event) {
        // ⚠️ 顺序有意义（先注册的在下）：图传取景框在最下，然后才是世界空间的名牌与镜筒
        event.registerAboveAll(CreateBallistics.id("drone_hud"), new DroneHudOverlay());
        event.registerAboveAll(CreateBallistics.id("drone_info"), new DroneInfoOverlay());
        event.registerAboveAll(CreateBallistics.id("indicator_spyglass"), new IndicatorOverlay());
    }

    /** 按键。注册过的按键才会出现在「选项 → 控制」里，玩家也才改得动。 */
    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(ModKeyMappings.OPEN_INDICATOR);
        // 操控无人机那几个（照卓越前线：它也是单独注册一套，玩家能单独改）
        event.register(ModKeyMappings.MOVE_FORWARD);
        event.register(ModKeyMappings.MOVE_BACK);
        event.register(ModKeyMappings.MOVE_LEFT);
        event.register(ModKeyMappings.MOVE_RIGHT);
        event.register(ModKeyMappings.MOVE_UP);
        event.register(ModKeyMappings.MOVE_DOWN);
    }

    /**
     * 无人机一进世界（客户端）就起一条循环的「嗡嗡」引擎音 —— 抄卓越前线的做法。
     *
     * <p>⚠️ 用 <b>{@code EntityJoinLevelEvent}</b> 而不是每 tick 扫实体：这个事件在**区块加载把实体交给客户端**
     * 时也会触发，所以"飞远了被卸载、飞回来"会自动重起一条，不用自己维护一张按 id 的表。
     * 音效实例自己在 tick 里发现无人机没了就淡出停掉（见 {@link DroneEngineSound}）。
     */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() && event.getEntity() instanceof DroneEntity drone) {
            // 只**登记**候选；真正建音效由 DroneSoundHandler 每 tick 按"引擎在不在转"决定
            // （照卓越前线那套"按需新建"—— 实例 stop() 后不能复用）
            DroneSoundHandler.register(drone);
        }
    }
}
