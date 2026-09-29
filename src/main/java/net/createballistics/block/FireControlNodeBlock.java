package net.createballistics.block;

import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.base.IRotate.SpeedLevel;
import com.simibubi.create.foundation.block.IBE;
import java.util.List;
import net.createballistics.block.entity.FireControlNodeBlockEntity;
import net.createballistics.registry.ModBlockEntities;
import net.createballistics.util.Tooltips;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 火控节点 —— 挨着 CBC 炮座摆放，负责驱动那一门炮的俯仰/回旋与击发。
 *
 * <h2>摆位规则（两条，2026-09-25 起都是<b>六向</b>的）</h2>
 * <ul>
 *   <li><b>正面接应力</b>：{@code FACING} 指向玩家（放下时正对你的那一面，六向皆可），
 *       传动轴只在这一面接得上 —— {@link #hasShaftTowards} 只认 {@code FACING}。</li>
 *   <li><b>挨着炮座任意一面即可</b>：六个邻居里只要有一个是 CBC 炮座
 *       （{@code CannonMountBlockEntity}）就行，<b>不要求特定的面</b> ——
 *       包括把节点放在炮座的<b>上面 / 下面</b>。
 *       判定在 {@code FireControlNodeBlockEntity.refreshCannon()} 里扫六个方向。</li>
 * </ul>
 *
 * <p>它是 Create 的动能方块：转速决定炮塔回转快慢（也决定消耗多少应力），无转速不工作。
 */
public class FireControlNodeBlock extends DirectionalKineticBlock implements IBE<FireControlNodeBlockEntity> {

    public FireControlNodeBlock(Properties properties) {
        super(properties);
    }

    /** 方块物品的说明（正文在语言键里，按 {@code \n} 拆行）。 */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        Tooltips.addLines(tooltip, "block.create_ballistics.fire_control_node.help", ChatFormatting.GRAY);
    }

    /** 传动轴沿 FACING 轴。 */
    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(FACING).getAxis();
    }

    /** 只有正面（FACING）能接传动轴。 */
    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face == state.getValue(FACING);
    }

    /**
     * 转速要求：Create 的 {@code MEDIUM} 档（服务端配置 {@code mediumSpeed}，默认 30 RPM）。
     *
     * <p>反编译 {@code IRotate.SpeedLevel.getSpeedValue()} 确认，原版三档的实际取值是：
     * {@code NONE}=0、{@code SLOW}=<b>1</b>（硬编码，语义是"只要有在转"）、
     * {@code MEDIUM}=服务端配置 {@code mediumSpeed}（默认 30）、
     * {@code FAST}=服务端配置 {@code fastSpeed}（默认 100）。
     *
     * <p>判定逻辑完全走 Create 原生的
     * {@code |转速| >= getMinimumRequiredSpeedLevel().getSpeedValue()}，本 mod 不再自己叠加门槛。
     */
    @Override
    public SpeedLevel getMinimumRequiredSpeedLevel() {
        return SpeedLevel.MEDIUM;
    }

    /**
     * 放下时让正面朝向玩家 —— 玩家看到的这一面就是接应力那一面。
     *
     * <h2>⚠️ 2026-09-25 起支持<b>上 / 下</b>（此前只允许水平四向）</h2>
     *
     * 用 {@code getClickedFace()}：它就是"玩家点的那一面"、也就是<b>朝向玩家的那一面</b>。
     * 水平摆放时，它与原来的 {@code getHorizontalDirection().getOpposite()} <b>逐字等价</b>
     * （玩家在南边点炮座朝南的那一面：点面=getClickedFace()=SOUTH；旧写法=玩家朝向 NORTH 取反=SOUTH）；
     * 而它天然支持"贴着炮座的<b>顶面 / 底面</b>放"这种竖摆（航空学载具的观感需要）。
     *
     * <p>为什么不用 {@code getNearestLookingDirection()}：那个按<b>视线</b>取最近轴向 ——
     * 玩家斜着往下看时，在<b>侧面</b>摆放也会被判成 UP/DOWN，摆位不可预测。
     * {@code getClickedFace()} 只看"点的是哪一面"，稳定。
     * （对着空气凭空放时它给 UP，可接受；摆错用扳手转一下即可，Create 的 {@code rotate} 是继承来的。）
     *
     * <p>⚠️ {@code FACING} 本来就是六向的 {@code BlockStateProperties.FACING}
     * （Create 的 {@code DirectionalKineticBlock} 定的），所以这次改动<b>不动物块状态结构 ⇒
     * 老存档不受影响</b>；{@code blockstates} 里的 {@code facing=up/down} 变体与渲染器也都早就是通用的。
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getClickedFace());
    }

    @Override
    public Class<FireControlNodeBlockEntity> getBlockEntityClass() {
        return FireControlNodeBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends FireControlNodeBlockEntity> getBlockEntityType() {
        return ModBlockEntities.FIRE_CONTROL_NODE.get();
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide) {
            withBlockEntityDo(level, pos, FireControlNodeBlockEntity::refreshCannon);
        }
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (!level.isClientSide) {
            // 炮座可能刚被放上/拆掉，重新找一次
            withBlockEntityDo(level, pos, FireControlNodeBlockEntity::refreshCannon);
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            withBlockEntityDo(level, pos, FireControlNodeBlockEntity::onRemoved);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    // 注意：这是 Create 的动能方块（BlockEntity 实现 ITickableBlockEntity），
    // tick 由 Create 自动安装，这里**不能**再写 getTicker —— DirectionalKineticBlock
    // 也不继承 BaseEntityBlock，没有 createTickerHelper 可用。
}
