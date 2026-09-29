package net.createballistics.item;

import java.util.List;
import net.createballistics.cbc.ActiveBallistics;
import net.createballistics.cbc.SableCompat;
import net.createballistics.entity.DroneEntity;
import net.createballistics.registry.ModEntities;
import net.createballistics.util.Tooltips;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.Shapes;

/**
 * 无人机（物品形态）—— <b>右键</b>把它放出来，<b>消耗一个</b>。
 *
 * <h2>⚠️ 刻意<b>不要</b>潜行</h2>
 *
 * 卓越前线的部署类物品（{@code AbstractDeployerItem}）是<b>直接右键</b>放出的，
 * 玩家对"潜行才放"没有预期 —— 用户 2026-09-23 明确要求照原版改回来。
 *
 * <p>不潜行的代价与所有原版物品一样：<b>右键一个"自己要用这个右键"的方块</b>
 * （箱子、我们的火控方块…）时，方块层先跑、右键被它吃掉，无人机就不会被放出来 ——
 * 这正是我们要的（免得对着机器放飞机）。
 *
 * <p>放出来之后它是一件**可消耗的观测器材**：撞墙 / 撞地按速度掉耐久，耐久见底就炸掉、
 * **不掉落** —— 想再用就再造一台，拿遥控器重新绑一次。
 */
public class DroneItem extends Item {

    public DroneItem(Properties properties) {
        super(properties);
    }

    /** 右键方块：把无人机放在这个方块的上方 / 侧面。 */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.CONSUME;
        }

        // 照卓越前线（也就是原版实体放置那套）：**没有碰撞箱的方块**（草、雪、空气）就放在它那一格，
        // 否则放到被点击面的外侧；然后**用碰撞箱把实体压到方块表面上**（见 getYOffset）。
        // ⚠️ 我先前只写了个固定的 `pos.getY() + 1`，于是无人机**悬在半空中**再慢慢沉下去，
        //    而原版是"右键就直接落在方块上"（用户 2026-09-23 实测指出的差别）。
        BlockPos clickedPos = context.getClickedPos();
        Direction direction = context.getClickedFace();
        BlockState state = level.getBlockState(clickedPos);
        BlockPos spawnPos = state.getCollisionShape(level, clickedPos).isEmpty()
                ? clickedPos
                : clickedPos.relative(direction);

        DroneEntity drone = ModEntities.DRONE.get().create(serverLevel);
        if (drone == null) {
            return InteractionResult.CONSUME;
        }
        drone.setPos(spawnPos.getX() + 0.5, spawnPos.getY() + 1.0, spawnPos.getZ() + 0.5);
        boolean offsetYMore = !clickedPos.equals(spawnPos) && direction == Direction.UP;
        double yOffset = getYOffset(level, spawnPos, offsetYMore, drone.getBoundingBox());
        // ⚠️ 照它：`moveTo(x, y, z)` **不带朝向** ⇒ 朝向恒为默认（yaw 0），
        //    不管从哪个方向放都一样、也不会"放出来先自己转一下"（我们原来传了玩家的 yaw ✗）。
        drone.moveTo(spawnPos.getX() + 0.5, spawnPos.getY() + yOffset, spawnPos.getZ() + 0.5);

        // ⚠️⚠️ 航空学：玩家站在飞艇上时 `spawnPos` 是**图幅坐标** ⇒ 要把它转到**世界坐标**再放出来
        //     （这样无人机一出生就在世界系、落在玩家眼前那格，而不是"先在图幅区出生、
        //      再靠 Sable 的 kick 搬过去"——搬这一下会和"甲板带着走"那套叠出怪行为 ✗）。
        // ⚠️ 高度那一格**仍然用图幅坐标算**（上面那句 `getYOffset` 查的是 level 里那块甲板，
        //     甲板就在图幅里 ✓），这里只是把**最终位置**整体搬一次。
        // ⚠️ 不在物理结构上时 `toWorld` 原样返回 ⇒ 地面行为零变化 ✓
        if (ActiveBallistics.sableLoaded()) {
            Vec3 world = SableCompat.toWorld(level, drone.position());
            drone.moveTo(world.x, world.y, world.z);
        }

        serverLevel.addFreshEntity(drone);

        consume(player, context.getItemInHand());
        level.gameEvent(player, GameEvent.ENTITY_PLACE, clickedPos);
        return InteractionResult.CONSUME;
    }

    /**
     * 把实体的碰撞箱<b>压到方块表面上</b>，返回相对 {@code pos} 的 y 偏移 —— 照抄卓越前线的
     * {@code AbstractDeployerItem#getYOffset}（也等于原版实体放置的算法）。
     *
     * <p>⚠️ 关键在那个 {@code Shapes.collide}：它按碰撞箱算出"最多能往下沉多少"，
     * 所以无人机是<b>正好落在方块上</b>，而不是悬空再往下掉。
     */
    private static double getYOffset(LevelReader level, BlockPos pos, boolean offsetYMore, AABB box) {
        AABB area = new AABB(pos);
        if (offsetYMore) {
            // pos 是"点击面外侧那一格"时，它下面那格才是真正要压的地面
            area = area.expandTowards(0.0, -1.0, 0.0);
        }
        return 1.0 + Shapes.collide(Direction.Axis.Y, box, level.getCollisions(null, area),
                offsetYMore ? -2.0 : -1.0);
    }

    /** 右键水面（准星打在液体上）也能放 —— 照卓越前线的处理。 */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.SOURCE_ONLY);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return InteractionResultHolder.pass(stack);
        }
        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }
        if (!(level instanceof ServerLevel serverLevel)
                || !(level.getBlockState(hit.getBlockPos()).getBlock() instanceof LiquidBlock)
                || !level.mayInteract(player, hit.getBlockPos())) {
            return InteractionResultHolder.pass(stack);
        }

        // 照它：右键水面时**不加 y 偏移**（直接落在水面那一格）
        DroneEntity drone = ModEntities.DRONE.get().create(serverLevel);
        if (drone == null) {
            return InteractionResultHolder.consume(stack);
        }
        BlockPos pos = hit.getBlockPos();
        // ⚠️ 照它：`moveTo(x, y, z)` **不带朝向** ⇒ 无人机的朝向恒为默认（yaw 0），
        //    不管从哪个方向放都一样、也不会"放出来先自己转一下"。
        drone.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        serverLevel.addFreshEntity(drone);
        consume(player, stack);
        level.gameEvent(player, GameEvent.ENTITY_PLACE, pos);
        return InteractionResultHolder.consume(stack);
    }

    /** 消耗一个并报一句（照它的 `stack.shrink(1)` + 我们那句提示）。 */
    private static void consume(Player player, ItemStack stack) {
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        player.displayClientMessage(Component.translatable("item.create_ballistics.drone.deployed")
                .withStyle(ChatFormatting.GREEN), true);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        Tooltips.addLines(tooltip, "item.create_ballistics.drone.help", ChatFormatting.GRAY);
    }
}
