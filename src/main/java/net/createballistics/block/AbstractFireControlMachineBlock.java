package net.createballistics.block;

import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.equipment.wrench.WrenchItem;
import com.simibubi.create.content.kinetics.base.IRotate.SpeedLevel;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.block.IBE;
import net.createballistics.block.entity.RangeDisplayToggle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 火控计算机 / 火控协调器的共同底座 —— <b>背面接应力</b>的动能方块。
 *
 * <h2>为什么用 {@link HorizontalKineticBlock} 而不是 {@code DirectionalKineticBlock}</h2>
 *
 * 它用的 {@code HORIZONTAL_FACING} 与这两个方块原来用的
 * {@code HorizontalDirectionalBlock.FACING} <b>是同一个属性对象</b>
 * （都是 {@code BlockStateProperties.HORIZONTAL_FACING}），所以：
 * <ul>
 *   <li>已有的 blockstate（{@code facing=north/south/east/west}）一个字都不用改；</li>
 *   <li>已经放在存档里的方块不会因为状态对不上而被替换掉；</li>
 *   <li>{@code getStateForPlacement} 仍是"正面朝玩家"，摆放手感不变。</li>
 * </ul>
 *
 * <p>它只是把面限制在水平四个方向（不会出现朝上朝下的机箱），并自带
 * {@code getPreferredHorizontalFacing} —— 那个"扫一圈邻居、问它肯不肯接轴"的辅助方法
 * （创意传动和火炮延长器里都有同一套逻辑）。
 *
 * <h2>轴接在<b>背面</b></h2>
 *
 * {@code hasShaftTowards} 只认 {@code FACING.getOpposite()}：正面朝向玩家 / 朝向操作员，
 * 背面朝里接传动轴。这与动力加载器区块加载器、航空学螺旋桨、offroad 轮架是同一套语义。
 *
 * <p>转速决定这些机器能不能干活（见各自的 BlockEntity），
 * 而应力消耗由 {@code calculateStressApplied()} 给出。
 */
public abstract class AbstractFireControlMachineBlock<T extends KineticBlockEntity>
        extends HorizontalKineticBlock implements IBE<T>, IWrenchable {

    protected AbstractFireControlMachineBlock(Properties properties) {
        super(properties);
    }

    /**
     * <b>扳手右键 = 开关范围显示。</b>
     *
     * <p>这两个方块本来没有扳手功能，而这个交互位置正合适：范围有 16 / 64 格那么大，
     * 玩家得转头才看得全，可一转头脑门上的准星就离开方块了 —— 所以"看着才显示"是行不通的，
     * 必须是<b>手动开、手动关</b>。
     *
     * <p>返回 {@code SUCCESS} 会挡掉 Create 扳手的默认动作（旋转/拆解），这正是我们要的 ——
     * 这两个方块没有朝向可转，右键就只干这一件事。
     */
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        if (level.getBlockEntity(context.getClickedPos()) instanceof RangeDisplayToggle toggle) {
            if (!level.isClientSide) {
                toggle.toggleRangeVisible();
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return InteractionResult.PASS;
    }

    /** 空手右键打开的界面。两个方块各开各的（见各自的 Screen）。 */
    protected abstract InteractionResult openScreen(Level level, BlockPos pos);

    /**
     * ⚠️ 必须在<b>方块这一层</b>就把扳手分派掉，不能只靠 {@link #onWrenched}。
     *
     * <p>原因：{@code useWithoutItem} 会打开界面并返回 {@code SUCCESS}，右键<b>当场就被消费掉了</b>，
     * Create 扳手那条路（{@code WrenchItem.useOn} → {@code IWrenchable.onWrenched}）
     * 根本轮不到执行。症状是"拿着扳手右键，界面照开，范围纹丝不动"（2026-09-19 用户实测）。
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (holdingWrench(player)) {
            toggleRange(level, pos);
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hit);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (holdingWrench(player)) {
            toggleRange(level, pos);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return openScreen(level, pos);
    }

    /** 手上拿着扳手吗（Create 的扳手或它的子类）。副手也算 —— 意图同样是"用扳手点它"。 */
    private static boolean holdingWrench(Player player) {
        return player.getMainHandItem().getItem() instanceof WrenchItem
                || player.getOffhandItem().getItem() instanceof WrenchItem;
    }

    /** 切换范围显示，<b>并且不打开界面</b>。 */
    private static void toggleRange(Level level, BlockPos pos) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof RangeDisplayToggle toggle) {
            toggle.toggleRangeVisible();
        }
    }

    /** 传动轴沿水平朝向那条线。 */
    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(HORIZONTAL_FACING).getAxis();
    }

    /** <b>只有背面</b>能接传动轴。 */
    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face == state.getValue(HORIZONTAL_FACING).getOpposite();
    }

    /**
     * 转速要求档位 —— <b>唯一的门槛声明处</b>，判定完全由 Create 原生完成。
     *
     * <p>Create 的 {@code KineticBlockEntity.isSpeedRequirementFulfilled()} 就是
     * {@code |转速| >= getMinimumRequiredSpeedLevel().getSpeedValue()}，本 mod 不再自己叠加任何门槛。
     * 这样护目镜里原生的「转速不足」提示和真正的功能门槛<b>永远是同一件事</b>。
     *
     * <p>反汇编 {@code IRotate.SpeedLevel.getSpeedValue()} 得到的真实取值：
     * {@code NONE}=0、{@code SLOW}=<b>1</b>（硬编码）、{@code MEDIUM}=配置 {@code mediumSpeed}（默认 30）、
     * {@code FAST}=配置 {@code fastSpeed}（默认 100）。后两档服务端可改。
     */
    @Override
    public abstract SpeedLevel getMinimumRequiredSpeedLevel();
}
