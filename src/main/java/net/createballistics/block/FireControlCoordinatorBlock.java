package net.createballistics.block;

import com.simibubi.create.content.kinetics.base.IRotate.SpeedLevel;
import java.util.List;
import net.createballistics.block.entity.FireControlCoordinatorBlockEntity;
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
 * 火控协调器 —— 火控网络的中枢：登记炮位、下发目标、统一下达开火。
 *
 * <p>它是动能方块：<b>背面接传动轴</b>，消耗 1024 SU，没转速就不干活。
 */
public class FireControlCoordinatorBlock
        extends AbstractFireControlMachineBlock<FireControlCoordinatorBlockEntity> {

    public FireControlCoordinatorBlock(Properties properties) {
        super(properties);
    }

    /** 方块物品的说明（正文在语言键里，按 {@code \n} 拆行）。 */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        Tooltips.addLines(tooltip, "block.create_ballistics.fire_control_coordinator.help", ChatFormatting.GRAY);
    }

    @Override
    public Class<FireControlCoordinatorBlockEntity> getBlockEntityClass() {
        return FireControlCoordinatorBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends FireControlCoordinatorBlockEntity> getBlockEntityType() {
        return ModBlockEntities.FIRE_CONTROL_COORDINATOR.get();
    }

    /**
     * 转速要求：Create 的 {@code FAST} 档（服务端配置 {@code fastSpeed}，默认 100 RPM）。
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
            net.createballistics.client.ClientHooks.openCoordinatorScreen(pos);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            withBlockEntityDo(level, pos, FireControlCoordinatorBlockEntity::onRemoved);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    // 注意：不要在这里写 getTicker —— KineticBlockEntity 是 SmartBlockEntity，
    // Create 的 IBE.getTicker 会自动给它装 SmartBlockEntityTicker。
    // 手写一个反而会把 Create 的动能 ticker 顶掉，方块就不再参与动能网络了。
}
