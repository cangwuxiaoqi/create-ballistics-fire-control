package net.createballistics.block;

import com.simibubi.create.content.kinetics.base.IRotate.SpeedLevel;
import java.util.List;
import net.createballistics.block.entity.FireControlComputerBlockEntity;
import net.createballistics.registry.ModBlockEntities;
import net.createballistics.util.Tooltips;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 火控计算机 —— 解算射击诸元：输入炮的参数与目标坐标，输出俯仰/装药/飞行时间。
 *
 * <p>它是动能方块：<b>背面接传动轴</b>，消耗 4096 SU，没转速就算不出诸元。
 */
public class FireControlComputerBlock
        extends AbstractFireControlMachineBlock<FireControlComputerBlockEntity> {

    public FireControlComputerBlock(Properties properties) {
        super(properties);
    }

    /** 方块物品的说明（正文在语言键里，按 {@code \n} 拆行）。 */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        Tooltips.addLines(tooltip, "block.create_ballistics.fire_control_computer.help", ChatFormatting.GRAY);
    }

    @Override
    public Class<FireControlComputerBlockEntity> getBlockEntityClass() {
        return FireControlComputerBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends FireControlComputerBlockEntity> getBlockEntityType() {
        return ModBlockEntities.FIRE_CONTROL_COMPUTER.get();
    }

    /**
     * 转速要求：Create 的 {@code FAST} 档（服务端配置 {@code fastSpeed}，默认 100 RPM）。
     *
     * <p><b>这已经是原版最高的档位</b>（{@code NONE} &lt; {@code SLOW} &lt; {@code MEDIUM} &lt; {@code FAST}），
     * 再往上没有档可升了。要继续加成本只能拧另一个旋钮：方块实体的 {@code STRESS_BASE}。
     *
     * <p>判定逻辑完全走 Create 原生的
     * {@code |转速| >= getMinimumRequiredSpeedLevel().getSpeedValue()}，本 mod 不再自己叠加门槛。
     */
    @Override
    public SpeedLevel getMinimumRequiredSpeedLevel() {
        return SpeedLevel.FAST;
    }

    @Override
    protected InteractionResult openScreen(Level level, BlockPos pos) {
        if (level.isClientSide) {
            net.createballistics.client.ClientHooks.openComputerScreen(pos);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            withBlockEntityDo(level, pos, FireControlComputerBlockEntity::onRemoved);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    // 解算只在目标/参数变化时跑，不需要额外的 tick 逻辑；
    // 但仍由 Create 的 SmartBlockEntityTicker 驱动 KineticBlockEntity.tick() 做动能更新。
}
