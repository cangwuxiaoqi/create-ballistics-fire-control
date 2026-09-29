package net.createballistics.registry;

import com.mojang.serialization.Codec;
import net.createballistics.CreateBallistics;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 本模组的数据组件。
 *
 * <h2>为什么用数据组件，而不是裸 NBT</h2>
 *
 * 1.21 起物品的附加数据走<b>组件</b>系统：每个组件自带一份存档 codec 和一份网络 codec，
 * 声明式写法不用手拼 {@code CompoundTag}，也不会踩「改了 NBT 但没同步给客户端」那个坑
 * （{@code getOrCreateTag()} 改出来的东西要等物品栏同步才走到客户端）。
 */
public class ModDataComponents {

    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, CreateBallistics.MODID);

    /**
     * 火炮指示器绑定的<b>火控计算机坐标</b>。组件缺席 = 未绑定。
     *
     * <p>存的是<b>单个</b>坐标、不是列表 —— 一台指示器只绑一台计算机
     * （2026-09-22 用户定）。理由见
     * {@link net.createballistics.item.ArtilleryIndicatorItem} 的类注释。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BlockPos>> BOUND_COMPUTER =
            COMPONENTS.register("bound_computer", () -> DataComponentType.<BlockPos>builder()
                    .persistent(BlockPos.CODEC)
                    .networkSynchronized(BlockPos.STREAM_CODEC)
                    .build());

    /**
     * 这台指示器<b>最近一次报出去的打击坐标</b> —— 只用来在屏幕上画那个目标标记。
     *
     * <p>⚠️ 它<b>不是</b>权威数据，权威坐标始终在火控计算机上（一台指示器报什么、
     * 40 门炮打什么是两件事）。这里存一份纯粹是因为<b>指示器的意义就是"离计算机很远"时用</b>：
     * 那时客户端根本读不到计算机的方块实体，没法从源头取。
     *
     * <p>每次通过指示器报点（中键）时由服务端刷新；解绑时清掉。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BlockPos>> LAST_TARGET =
            COMPONENTS.register("last_target", () -> DataComponentType.<BlockPos>builder()
                    .persistent(BlockPos.CODEC)
                    .networkSynchronized(BlockPos.STREAM_CODEC)
                    .build());

    /**
     * 这一台指示器的<b>身份</b>（UUID）—— 第一次绑定时生成，之后不变。
     *
     * <p><b>为什么物品也需要身份</b>：要做到「一台计算机只绑一台指示器」，计算机就得能区分
     * "来的是不是同一台指示器"。而物品本身没有 id —— 同名的两把剑在数据上毫无区别 ——
     * 所以自己发一个。
     *
     * <p>⚠️ 它是<b>永久配对</b>的一部分：计算机记的就是这个 UUID，配对不会自己过期，
     * 唯一的重置手段是<b>把计算机挖掉重放</b>（方块实体数据没了，配对自然解除）。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> INDICATOR_ID =
            COMPONENTS.register("indicator_id", () -> DataComponentType.<UUID>builder()
                    .persistent(UUIDUtil.CODEC)
                    .networkSynchronized(UUIDUtil.STREAM_CODEC)
                    .build());

    /**
     * 监视器绑定的<b>无人机 UUID</b>（照卓越前线的 {@code LinkedDrone}）。
     *
     * <p>⚠️ 绑定关系记在<b>物品</b>上、不记在无人机上：服务端每次收到操控指令都拿
     * "发包人手上这台监视器"来校验，于是无人机那边不需要存 UUID ——
     * <b>一份数据只有一个来源</b>，不会有"两边记的不一样"这种问题。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> LINKED_DRONE =
            COMPONENTS.register("linked_drone", () -> DataComponentType.<UUID>builder()
                    .persistent(UUIDUtil.CODEC)
                    .networkSynchronized(UUIDUtil.STREAM_CODEC)
                    .build());

    /**
     * 这台遥控器是不是<b>正在操控无人机</b>（= 卓越前线物品 NBT 里那个 {@code Using}）。
     *
     * <p>它管两件事：<b>客户端</b>决定"相机跟不跟着无人机"（{@code CameraMixin}）、
     * <b>服务端</b>决定"收不收这条操控指令"（B3-b-②）。
     * 组件缺席 = 没在操控。
     *
     * <p>⚠️ 与 {@link #LINKED_DRONE} 是**两件事**：绑上了不等于正看着它 ——
     * 绑着是"这台遥控器认哪台无人机"，在看着是"我此刻在这个视角里"。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> MONITOR_USING =
            COMPONENTS.register("monitor_using", () -> DataComponentType.<Boolean>builder()
                    .persistent(Codec.BOOL)
                    .networkSynchronized(ByteBufCodecs.BOOL)
                    .build());

    /**
     * 计算机那三个设置的<b>镜像</b>（弹道模式 / 火力模式 / 参数），供 H 键界面显示。
     *
     * <p>和 {@link #LAST_TARGET} 是同一个理由：<b>指示器常常离计算机很远</b>，
     * 那时客户端读不到计算机的方块实体，界面就没法显示"现在是什么模式" ——
     * 而服务端不受客户端视距限制，由它每 10 tick 抄一份给物品即可。
     *
     * <p>⚠️ 同样是<b>只读镜像</b>：权威设置永远是计算机上那一份，
     * 这三个量只用来把按钮标签画对；改设置走 {@code ComputerActionC2SPacket} 发给计算机。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> MIRROR_ARC_MODE =
            COMPONENTS.register("mirror_arc_mode", () -> DataComponentType.<String>builder()
                    .persistent(Codec.STRING)
                    .networkSynchronized(ByteBufCodecs.STRING_UTF8)
                    .build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> MIRROR_FIRE_MODE =
            COMPONENTS.register("mirror_fire_mode", () -> DataComponentType.<String>builder()
                    .persistent(Codec.STRING)
                    .networkSynchronized(ByteBufCodecs.STRING_UTF8)
                    .build());

    /**
     * 计算机下<b>登记到多少门炮</b>的镜像。
     *
     * <p>用来在<b>镜筒迭加层</b>上显示「火力模式至少要 2 门炮」那句提示（{@code IndicatorOverlay}）——
     * 举镜时聊天栏被镜筒贴着、字是糊的，所以提示得自己画 ✓（2026-09-25 用户实测报的）。
     *
     * <p>⚠️ **它的用途仅限"显示提示"，绝不用来禁用任何按钮** ——
     * 2026-09-25 我一度拿它判"火力模式按钮能不能点"并置灰，结果**把玩家锁死**
     * （炮不够 2 门时连切回齐射都点不动）。见 {@code FireControlComputerScreen#refreshFireModeButton}。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> MIRROR_CANNON_COUNT =
            COMPONENTS.register("mirror_cannon_count", () -> DataComponentType.<Integer>builder()
                    .persistent(Codec.INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT)
                    .build());

    /** 当前火力模式对应的那个参数：轮射是间隔 tick、范围覆盖是半径。其它模式无意义。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> MIRROR_PARAM =
            COMPONENTS.register("mirror_param", () -> DataComponentType.<Integer>builder()
                    .persistent(Codec.INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT)
                    .build());

    /**
     * 计算机「计算过程」那一行的<b>原料快照</b>（字段见 {@code StageText.Snapshot}）。
     *
     * <p>举镜观测时右下角要显示这一行，而指示器常常离计算机很远、客户端读不到方块实体 ——
     * 所以和设置一样镜像一份过来，由客户端调用<b>同一个</b> {@code StageText} 渲染。
     *
     * <p>⚠️ <b>镜像的是原料，不是成品文字</b>：倒计时那一段要用当前 tick 现算，
     * 镜像一段写死的"还有 28.9 秒"会永远停在那一刻。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<CompoundTag>> STAGE =
            COMPONENTS.register("stage", () -> DataComponentType.<CompoundTag>builder()
                    .persistent(CompoundTag.CODEC)
                    .networkSynchronized(ByteBufCodecs.COMPOUND_TAG)
                    .build());

    /**
     * 绑定的计算机<b>此刻的协调器列表</b>的镜像 —— 只用来画屏幕上的 <b>IFF 友军标记</b>。
     *
     * <p>它和上面几份是同一个路子（{@link #LAST_TARGET} / {@link #STAGE}：指示器常在很远的地方、
     * 客户端读不到方块实体），但这里多一层更硬的约束：
     * <b>协调器是"方块"，不是"实体"。</b>
     * 客户端要读它，就必须<b>自己加载着那块区块</b>；而玩家为了远程观测给炮阵放的区块加载器
     * <b>只对服务端生效</b> —— 强加载的区块<b>不会</b>因此被发给远处的客户端。
     * ⇒ 玩家带着指示器走远之后，客户端<b>永远</b>读不到那台计算机的方块实体。
     *
     * <p>而这会造出一个很难解释的现象：同一块屏幕上，<b>计算机自己那个标记一直在</b>
     * （它来自物品上的 {@link #BOUND_COMPUTER}，永远都在），<b>协调器的标记却消失了</b>。
     * 由服务端抄一份过来，两者才在同一个条件下工作。
     *
     * <p>⚠️ 仍是<b>只读镜像</b>：权威列表永远在计算机上（上限 {@code MAX_COORDINATORS} = 4，
     * 所以这份很小）。读不到计算机时会被<b>清空</b> —— 见
     * {@code ArtilleryIndicatorItem#inventoryTick}。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<List<BlockPos>>> MIRROR_COORDINATORS =
            COMPONENTS.register("mirror_coordinators", () -> DataComponentType.<List<BlockPos>>builder()
                    .persistent(BlockPos.CODEC.listOf())
                    .networkSynchronized(BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list()))
                    .build());

    public static void register(IEventBus eventBus) {
        COMPONENTS.register(eventBus);
    }
}
