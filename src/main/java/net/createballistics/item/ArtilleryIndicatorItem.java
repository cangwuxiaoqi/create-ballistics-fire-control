package net.createballistics.item;

import java.util.List;
import java.util.UUID;
import net.createballistics.ballistic.ArcMode;
import net.createballistics.ballistic.FireMode;
import net.createballistics.block.entity.FireControlComputerBlockEntity;
import net.createballistics.client.ModKeyMappings;
import net.createballistics.registry.ModDataComponents;
import net.createballistics.util.PosDisplay;
import net.createballistics.util.StageText;
import net.createballistics.util.Tooltips;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import org.jetbrains.annotations.Nullable;

/**
 * 火炮指示器 —— 观测层的核心物品，<b>三合一</b>（照着卓越前线的 {@code ArtilleryIndicatorItem} 的分工）：
 *
 * <ol>
 *   <li><b>望远镜</b>：按住右键。{@link UseAnim#SPYGLASS} 让原版自己给出开镜姿势与变焦。</li>
 *   <li><b>绑定器</b>：潜行右键<b>火控计算机</b> = 绑定 / 解绑。</li>
 *   <li><b>配置与开火</b>：H 键开配置界面、左键按住开火、中键取坐标 —— <b>后面几轮再做</b>。</li>
 * </ol>
 *
 * <h2>为什么一台指示器只绑一台计算机</h2>
 *
 * 指示器<b>不存设置</b>，它只是遥控器 —— 弹道模式 / 火力模式 / 轮射间隔 / 覆盖半径这四个设置
 * <b>唯一地存在计算机上</b>（2026-09-22 用户定）。如果一台指示器能绑多台计算机，
 * 或者反过来一个玩家拿两台绑了不同计算机的指示器，"屏幕上的设置"就有两套说法，
 * 而计算机还会照自己那套下发军令 —— 玩家会以为界面骗人。绑定唯一，这套语义就没有歧义。
 *
 * <p>对照：卓越前线把射击诸元存在<b>物品</b>上；我们刻意相反，因为我们的"炮"是<b>成网</b>的
 * （最多 4 × 10 = 40 门），物品上的设置没法说清"这 40 门听谁的"。
 */
public class ArtilleryIndicatorItem extends Item {

    /** 与卓越前线和原版望远镜一致：72000 tick（1 小时）基本等于"按住不放"。 */
    private static final int USE_DURATION = 72000;

    /** 每隔多少 tick 把标记用的坐标跟计算机对一次表（每秒 2 次）。 */
    private static final int TARGET_MIRROR_INTERVAL = 10;

    public ArtilleryIndicatorItem(Properties properties) {
        super(properties);
    }

    // ------------------------------------------------------------------
    // 望远镜
    // ------------------------------------------------------------------

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return USE_DURATION;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.SPYGLASS;
    }

    /**
     * ⚠️⚠️ <b>明确否认「望远镜」物品能力</b> —— 这条是 2026-09-26 大整合包实测补上的。
     *
     * <p>NeoForge 把原版那三件事（<b>固定 10 倍变焦</b>、开镜灵敏度削减、<b>圆形遮罩</b>）
     * 全挂在 {@code ItemAbilities.SPYGLASS_SCOPE} 上，而 {@code Player.isScoping()}
     * 判的就是 {@code getUseItem().canPerformAction(SPYGLASS_SCOPE)}。
     *
     * <p>本物品**故意不申请**这个能力（理由见类注释与 {@link IndicatorOverlay}）：
     * 申请了就会拿到那 10 倍与遮罩，还得反过来跟它们打架。
     * NeoForge 的默认实现本来就是 {@code false} —— 但实测在 300+ 模组的包里
     * {@code isScoping()} 竟会是 {@code true}（原版那 10 倍于是叠到我们自己的变焦上，
     * 用户看到"放大得离谱"）⇒ 这里**显式写死**，把这条路彻底焊死，
     * 免得日后又有什么东西把能力塞给我们的物品。
     */
    @Override
    public boolean canPerformAction(ItemStack stack, net.neoforged.neoforge.common.ItemAbility ability) {
        return ability != net.neoforged.neoforge.common.ItemAbilities.SPYGLASS_SCOPE
                && super.canPerformAction(stack, ability);
    }

    /**
     * 右键<b>空气</b> = 举起望远镜。方块上的右键走 {@link #useOn}，不会到这里。
     *
     * <p>副手直接拒绝：副手要留给 B3 的「监视器 + 副手指示器 = 在无人机上操作」那套组合，
     * 副手也举镜子只会让那个组合变得没法用。
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (hand == InteractionHand.OFF_HAND) {
            return InteractionResultHolder.fail(player.getItemInHand(hand));
        }
        player.playSound(SoundEvents.SPYGLASS_USE, 1.0F, 1.0F);
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        entity.playSound(SoundEvents.SPYGLASS_STOP_USING, 1.0F, 1.0F);
        return stack;
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeCharged) {
        entity.playSound(SoundEvents.SPYGLASS_STOP_USING, 1.0F, 1.0F);
    }

    // ------------------------------------------------------------------
    // 绑定
    // ------------------------------------------------------------------

    /**
     * 潜行右键<b>火控计算机</b> = 绑定 / 解绑。
     *
     * <p>⚠️⚠️ <b>这个手势只能实现在物品这一层，写在方块层永远不会被调用。</b>
     * {@code ServerPlayerGameMode.useItemOn} 的原话是：
     * <pre>
     * boolean flag1 = (player.isSecondaryUseActive() &amp;&amp; flag)
     *                 &amp;&amp; !(mainhand.doesSneakBypassUse(...) &amp;&amp; offhand.doesSneakBypassUse(...));
     * if (event.getUseBlock().isTrue() || (event.getUseBlock().isDefault() &amp;&amp; !flag1)) {
     *     blockstate.useItemOn(...)      // ← 潜行时这整段被跳过
     *     ...
     * }
     * ... stack.useOn(useoncontext)      // ← 直接到这里
     * </pre>
     * 也就是说：<b>玩家潜行且手持非空物品时，方块的 {@code useItemOn} 与 {@code useWithoutItem}
     * 一概不执行</b>。客户端 {@code MultiPlayerGameMode.performUseItemOn} 是同一套判断，
     * 两边不会不同步。
     *
     * <p>⚠️ 这与本模组里「<b>扳手必须写在方块层</b>」那条教训<b>恰好相反</b>，别记混：
     * 扳手那次成立的唯一原因是玩家<b>没有</b>潜行 —— 所以方块的 {@code useItemOn} 先跑、
     * 先把右键消费掉了，物品层根本轮不到。手势<b>潜行不潜行</b>决定了它该写在哪一层。
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isSecondaryUseActive()) {
            return InteractionResult.PASS;
        }
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        // 只认自己的火控计算机；对着别的方块潜行右键 = 什么都不做（返回 PASS，不抢原版行为）
        if (!(level.getBlockEntity(pos) instanceof FireControlComputerBlockEntity computer)) {
            return InteractionResult.PASS;
        }

        ItemStack stack = context.getItemInHand();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;   // 客户端只负责把手势消费掉，真正的改动全在服务端
        }

        // 这台指示器的身份（第一次绑定时现发一个）—— 计算机靠它认"是不是同一台指示器"
        UUID myId = ensureIndicatorId(stack);
        BlockPos previous = boundComputer(stack);
        boolean boundHere = pos.equals(previous);
        // ⚠️ 聊天里报的坐标走 PosDisplay：在航空学物理结构上时 `pos` 是图幅坐标
        //    （直接报等于给玩家一个 2896 万格的假坐标）。**只转显示**，pos 仍用于比对/存档。
        BlockPos shownPos = PosDisplay.of(level, pos);

        if (boundHere && computer.isOwner(myId)) {
            // ① 解绑：两边一起清。⚠️ 只清物品不清计算机的话，这台指示器就把它<b>永久占死</b>了 ——
            //    因为配对不会自己过期，那台计算机从此谁也用不了，只能拆。
            stack.remove(ModDataComponents.BOUND_COMPUTER.get());
            stack.remove(ModDataComponents.LAST_TARGET.get());
            stack.remove(ModDataComponents.MIRROR_COORDINATORS.get());
            computer.releaseIndicator(myId);
            player.displayClientMessage(Component.translatable(
                    "item.create_ballistics.artillery_indicator.bind.off",
                    shownPos.getX(), shownPos.getY(), shownPos.getZ()).withStyle(ChatFormatting.RED), true);
        } else if (computer.hasIndicator() && !computer.isOwner(myId)) {
            // ② 已经被别的指示器绑走了 —— 一对一永久配对，这里解不开（重置只能拆掉这台计算机）
            player.displayClientMessage(Component.translatable(
                    "item.create_ballistics.artillery_indicator.taken").withStyle(ChatFormatting.RED), true);
        } else {
            // ③ 绑定 / 改绑
            //    改绑时先把旧的那台放开，否则它会被一个再也回不来的指示器永久占着（同样只能拆）
            if (previous != null && !boundHere
                    && level.getBlockEntity(previous) instanceof FireControlComputerBlockEntity old) {
                old.releaseIndicator(myId);
            }
            computer.claimIndicator(myId);
            stack.set(ModDataComponents.BOUND_COMPUTER.get(), pos);
            stack.remove(ModDataComponents.LAST_TARGET.get());
            stack.remove(ModDataComponents.MIRROR_COORDINATORS.get());
            player.displayClientMessage(Component.translatable(
                    "item.create_ballistics.artillery_indicator.bind.ok",
                    shownPos.getX(), shownPos.getY(), shownPos.getZ()).withStyle(ChatFormatting.GREEN), true);
        }
        // 绑定/解绑刻意<b>不给音效</b>：2026-09-22 用户明确说不要（只有中键报点那一声）。
        return InteractionResult.CONSUME;
    }

    /** 这台指示器的身份；从没绑过就是 {@code null}。 */
    @Nullable
    public static UUID indicatorId(ItemStack stack) {
        return stack.get(ModDataComponents.INDICATOR_ID.get());
    }

    /** 取身份，没有就现发一个（只在服务端调用）。 */
    private static UUID ensureIndicatorId(ItemStack stack) {
        UUID id = indicatorId(stack);
        if (id == null) {
            id = UUID.randomUUID();
            stack.set(ModDataComponents.INDICATOR_ID.get(), id);
        }
        return id;
    }

    /** 绑定的计算机坐标；没绑过返回 {@code null}。 */
    @Nullable
    public static BlockPos boundComputer(ItemStack stack) {
        return stack.get(ModDataComponents.BOUND_COMPUTER.get());
    }

    /**
     * 玩家此刻<b>当作指示器在用</b>的那一份 —— 主手拿着就是主手；
     * 否则若主手是"正在操控中"的遥控器、而副手是指示器，那就是<b>副手</b>那一份。
     *
     * <p>照卓越前线：<b>无人机视角下指示器是放在副手上的</b>（主手必须留给遥控器）。
     * 它那边凡是"读/写手上那份指示器"的地方，判据都是「主手是监视器 + 副手是指示器」。
     * 我们把这<b>一个</b>判据收在这里 —— 客户端取点、服务端归属检查、镜像回写三处都调它，
     * 免得各判一遍、还判得不一致（本模组"同义判据写两份"已经栽过）。
     *
     * @return 没在用指示器时返回 {@link ItemStack#EMPTY}
     */
    public static ItemStack actingIndicator(Player player) {
        ItemStack main = player.getMainHandItem();
        if (main.getItem() instanceof ArtilleryIndicatorItem) {
            return main;
        }
        ItemStack off = player.getOffhandItem();
        if (MonitorItem.isPiloting(main) && off.getItem() instanceof ArtilleryIndicatorItem) {
            return off;
        }
        return ItemStack.EMPTY;
    }

    /**
     * 绑定的计算机<b>此刻还在不在</b>。
     *
     * <p>拆掉计算机之后指示器会自己失效（而不是留着一个指向空气的绑定）——
     * 与协调器/节点的 {@code verifyMaster()} 是同一个思路：只读源头、不复制状态。
     */
    @Nullable
    public static BlockPos liveBoundComputer(ItemStack stack, Level level) {
        BlockPos bound = boundComputer(stack);
        return bound != null && level.getBlockEntity(bound) instanceof FireControlComputerBlockEntity
                ? bound
                : null;
    }

    /**
     * 每隔多少 tick 把"标记用的坐标"跟计算机对一次表。
     *
     * <h2>为什么要在物品上镜像一份</h2>
     *
     * 屏幕上的目标标记要画在客户端，而客户端<b>只拥有自己视野范围内的区块</b> ——
     * 站得离计算机远了，{@code level.getBlockEntity(...)} 在客户端就是 {@code null}。
     * ⇒ 只能由物品随身带一份给客户端看。
     *
     * <p>但这份数据<b>不是独立的第二份真相</b>：服务端<b>不受客户端视距限制</b>，
     * 只要那块区块是加载着的（例如放了区块加载器强加载），服务端就能读到计算机，
     * 于是这里定期把它抄过来。**权威坐标永远在计算机上，这里只是它的镜像。**
     *
     * <p>⚠️ 换句话说：区块没加载时镜像会停在最后一次的值 —— 那不是"另一套数据"，
     * 而是"最后一次看到的样子"。
     *
     * <p>⚠️ 计算机<b>没有目标</b>时这里会把镜像清掉 ⇒ 标记跟着消失。这与计算机界面上那行
     * 「未设定目标」是一致的（开火后计算机自己会清目标，标记也就该消失）。
     */
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (level.isClientSide || level.getGameTime() % TARGET_MIRROR_INTERVAL != 0
                || !(entity instanceof Player player)) {
            return;
        }
        BlockPos bound = boundComputer(stack);
        if (bound == null) {
            return;
        }
        if (!(level.getBlockEntity(bound) instanceof FireControlComputerBlockEntity computer)) {
            // 绑着、但此刻读不到它（**已被拆掉** / 不在这个维度）⇒ 把这份镜像**整个删掉**。
            // ⚠️ 删掉（而不是写一个空表）是**有意**的：**缺席 = "读不到"**，客户端据此
            //    连"计算机"那个标记也一起不画 —— 否则会对着空气画一个友军图标。
            //    （与 `BOUND_COMPUTER` 缺席 = 没绑定、`MONITOR_USING` 缺席 = 没在操控是同一个习惯。）
            // ⚠️ 这里与坐标 / 计算过程那两份镜像的"留着最后一次看到的样子"**刻意不同**：
            //    那两份是**缓存**，停在旧值顶多是稍旧一点；而这一份说的是"**哪儿有友军**"，
            //    留着它就等于**画一批早就不在的标记** —— 错的信息比没有信息更糟。
            stack.remove(ModDataComponents.MIRROR_COORDINATORS.get());
            return;
        }

        // 顺便报到 —— 计算机据此显示「主人离多远」（归属由永久配对决定，报到不改变它）。
        // 绑定关系只存在物品上、计算机枚举不到，所以是"下级报到"而不是"上级扫描"。
        computer.reportIndicator(player, indicatorId(stack));

        BlockPos mirrored = computer.hasTarget()
                ? BlockPos.containing(computer.getTargetX(), computer.getTargetY(), computer.getTargetZ())
                : null;
        BlockPos cached = stack.get(ModDataComponents.LAST_TARGET.get());
        if (mirrored == null) {
            if (cached != null) {
                stack.remove(ModDataComponents.LAST_TARGET.get());
            }
        } else if (!mirrored.equals(cached)) {
            stack.set(ModDataComponents.LAST_TARGET.get(), mirrored);
        }

        // 计算机那三个设置也镜像一份 —— H 键界面要在很远的地方也能把按钮标签画对
        mirrorSettingsFrom(stack, computer);

        // 炮数也镜像一份 —— **只为了在镜筒迭加层上画「火力模式至少要 2 门炮」那句提示**
        // （举镜时聊天栏被镜筒压着，字是糊的，所以提示得自己画）。
        // ⚠️ **不要拿它去禁用任何按钮** —— 2026-09-25 那样干过一次，把玩家锁死了，
        //    见 FireControlComputerScreen#refreshFireModeButton。
        mirror(stack, ModDataComponents.MIRROR_CANNON_COUNT.get(), computer.getCannonCount());

        // 协调器列表也镜像一份 —— 屏幕上的 IFF 友军标记要画它们，而那是"方块"：
        // 远处的客户端读不到那块区块，只能由服务端抄（见 ModDataComponents#MIRROR_COORDINATORS）
        mirror(stack, ModDataComponents.MIRROR_COORDINATORS.get(), List.copyOf(computer.getCoordinators()));

        // 「计算过程」的原料也镜像一份 —— 举镜观测时右下角要显示它（由客户端用同一个 StageText 渲染）
        CompoundTag stage = StageText.Snapshot.capture(computer).toTag();
        if (!stage.equals(stack.get(ModDataComponents.STAGE.get()))) {
            stack.set(ModDataComponents.STAGE.get(), stage);
        }
    }

    /** 镜像的「计算过程」原料；没有（没绑过计算机）返回 {@code null}。 */
    @Nullable
    public static StageText.Snapshot mirroredStage(ItemStack stack) {
        CompoundTag tag = stack.get(ModDataComponents.STAGE.get());
        return tag == null ? null : StageText.Snapshot.fromTag(tag);
    }

    /**
     * 把计算机那三个设置抄到物品上（镜像）。
     *
     * <p><b>两处调用</b>：① 指示器每 10 tick 报一次到（保底同步）；
     * ② 玩家在 H 键界面点完按钮之后<b>立刻</b>再抄一次 —— 不然界面要等下一次报到
     * （最多半秒）字才变，点起来发卡（2026-09-22 用户反馈"切换模式有种卡顿卡卡的感觉"）。
     */
    public static void mirrorSettingsFrom(ItemStack stack, FireControlComputerBlockEntity computer) {
        mirror(stack, ModDataComponents.MIRROR_ARC_MODE.get(), computer.getArcMode().name());
        mirror(stack, ModDataComponents.MIRROR_FIRE_MODE.get(), computer.getFireMode().name());
        mirror(stack, ModDataComponents.MIRROR_PARAM.get(), computer.getFireMode() == FireMode.COVERAGE
                ? computer.getCoverageRadius()
                : computer.getRollingIntervalTicks());
    }

    /**
     * ⚠️ <b>同一种物品之间不播"换手"动画。</b>
     *
     * <p>物品的组件一变，客户端就把它当成"换了物品"，手上演一次拿起/放下的动作。
     * 而我们的镜像每 10 tick 就会更新、点一次按钮也会更新 ⇒ <b>那个动画会一直在演</b>，
     * 用户明确说"很不喜欢"（2026-09-22）。
     *
     * <p>所以只有真的换了槽位、或者换了别的物品才播 —— 那是正常的换手手感，要保留。
     */
    @Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return slotChanged || !oldStack.is(newStack.getItem());
    }

    /** 只在真的变了才写 —— 每次 set 都会让物品栏同步一次，别每 10 tick 白灌一个包。 */
    private static <T> void mirror(ItemStack stack, DataComponentType<T> type, T value) {
        if (!value.equals(stack.get(type))) {
            stack.set(type, value);
        }
    }

    /** 镜像的弹道模式；从没镜像过（没绑过计算机）返回 {@code null}。 */
    @Nullable
    public static ArcMode mirroredArcMode(ItemStack stack) {
        String name = stack.get(ModDataComponents.MIRROR_ARC_MODE.get());
        return name == null ? null : ArcMode.byName(name);
    }

    /** 镜像的火力模式；同上。 */
    @Nullable
    public static FireMode mirroredFireMode(ItemStack stack) {
        String name = stack.get(ModDataComponents.MIRROR_FIRE_MODE.get());
        return name == null ? null : FireMode.byName(name);
    }

    /** 镜像的参数（轮射间隔 tick / 覆盖半径）；没有就是 0。 */
    public static int mirroredParam(ItemStack stack) {
        Integer value = stack.get(ModDataComponents.MIRROR_PARAM.get());
        return value == null ? 0 : value;
    }

    /**
     * 镜像的<b>炮数</b>（计算机下登记到多少门）。
     *
     * <p>只给<b>显示</b>用：镜筒迭加层靠它决定要不要画「火力模式至少要 2 门炮」那句
     * （举镜时聊天栏是糊的，提示得自己画）。
     *
     * <p>没镜像过时返回 0 ⇒ 调用方按"不够"处理（多说一句提示没坏处，
     * 而**禁用按钮会锁死玩家**，所以这里只影响"说不说"，不影响"能不能点"）。
     */
    public static int mirroredCannonCount(ItemStack stack) {
        Integer value = stack.get(ModDataComponents.MIRROR_CANNON_COUNT.get());
        return value == null ? 0 : value;
    }

    /**
     * 镜像的协调器列表（IFF 友军标记要画的位置）。
     *
     * @return {@code null} = <b>服务端此刻也读不到那台计算机</b>（没绑过 / 已被拆掉 / 不在这个维度）。
     *         ⚠️ 与<b>空表</b>是两回事：空表 = "这台计算机确实一个协调器都没有"，
     *         而 {@code null} = "我不知道" —— 两者在屏幕上的表现也该不同（见
     *         {@code OverlayMarks#friendlies}）。
     */
    @Nullable
    public static List<BlockPos> mirroredCoordinators(ItemStack stack) {
        return stack.get(ModDataComponents.MIRROR_COORDINATORS.get());
    }

    // ------------------------------------------------------------------
    // 物品提示
    // ------------------------------------------------------------------

    /**
     * 「打开配置界面」那个键的<b>当前</b>键名 —— 玩家把 H 改成 G，tooltip 里就跟着显示 G。
     *
     * <p>⚠️ 键位是<b>客户端专属</b>的东西 ⇒ 两道保险，都别省：
     * ① 这里先过 {@code FMLEnvironment.dist != Dist.CLIENT} 的闸门；
     * ② 真正的取值在 {@code client/ModKeyMappings}（客户端专属的类，服务端不加载它），
     *    而且它返回 {@code String} —— <b>调用方这一侧的常量池里不会出现 {@code KeyMapping}</b>。
     * <b>不能写成静态字段</b>：那样类一加载就会去解析客户端类，专用服务器上直接崩。
     * （检查手段：{@code node tools/scan_client_refs.js <产物.jar>} 应当是 0。）
     */
    private static String openKeyName() {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return "?";
        }
        return ModKeyMappings.openIndicatorKeyName();
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        // ⚠️ [H] 那个键名**跟着键位走**（玩家改成 G，这里就显示 G）—— 见 openKeyName()
        Tooltips.addLines(tooltip, "item.create_ballistics.artillery_indicator.help",
                ChatFormatting.GRAY, openKeyName());
        tooltip.add(Component.empty());   // 操作说明与"副手玩法"之间空一行（用户 2026-09-24 定的版式）
        Tooltips.addLines(tooltip, "item.create_ballistics.artillery_indicator.help2", ChatFormatting.GRAY);

        BlockPos bound = boundComputer(stack);
        if (bound == null) {
            tooltip.add(Component.translatable("item.create_ballistics.artillery_indicator.unbound")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }

        // ⚠️ getBlockEntity 在「区块没加载」和「方块被拆了」两种情况下都给 null ——
        // 分不清就不要乱猜，两种可能都说（与协调器那句「（区块未加载）」是同一个谨慎）。
        Level level = context.level();
        // ⚠️ 显示用坐标走 PosDisplay（航空学物理结构上是图幅坐标）；bound 本身保持原坐标查方块实体。
        BlockPos shownBound = PosDisplay.of(level, bound);
        if (level != null && liveBoundComputer(stack, level) == null) {
            tooltip.add(Component.translatable("item.create_ballistics.artillery_indicator.bound_missing",
                    shownBound.getX(), shownBound.getY(), shownBound.getZ()).withStyle(ChatFormatting.GOLD));
        } else {
            tooltip.add(Component.translatable("item.create_ballistics.artillery_indicator.bound",
                    shownBound.getX(), shownBound.getY(), shownBound.getZ()).withStyle(ChatFormatting.AQUA));
        }
    }
}
