package net.createballistics.item;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.createballistics.CreateBallistics;
import net.createballistics.client.PilotCamera;
import net.createballistics.entity.DroneEntity;
import net.createballistics.registry.ModDataComponents;
import net.createballistics.util.PosDisplay;
import net.createballistics.util.Tooltips;
import net.minecraft.world.phys.Vec3;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * 遥控器（遥控 + 图传）—— 对应卓越前线的 {@code MonitorItem}。
 *
 * <ol>
 *   <li><b>右键无人机</b> = 绑定；<b>潜行右键无人机</b> = 解绑</li>
 *   <li><b>右键空气</b> = 进入 / 退出无人机视角（相机顶到无人机上，见
 *       {@code CameraMixin}；本轮 = B3-b-①，<b>还飞不动</b>，操控在 b-②）</li>
 * </ol>
 *
 * <p>⚠️ 本类<b>两端都会加载</b>（物品要在服务端也注册），所以里面所有碰客户端类的地方
 * （{@link Minecraft}、{@link PilotCamera}）都<b>只出现在客户端分支里</b> ——
 * Java 的类解析是惰性的，服务端走不到那一句就永远不会去碰那些类。
 *
 * <p>放无人机是<b>另一个物品</b>（{@link DroneItem}）的事 —— 一台遥控器可以一直用，
 * 摔的是无人机（与卓越前线一致，也让"消耗"这件事看得见）。
 *
 * <h2>⚠️ 绑定是<b>两处</b>的，而且两边各有各的用</h2>
 *
 * 「我绑着哪台无人机」记在<b>物品</b>上（{@code LINKED_DRONE}）；
 * 「谁在用它」记在<b>无人机</b>上（{@code CONTROLLER} + 归属名）。
 * 前者是权威的绑定关系，后者只干两件事：<b>无人机消失时知道该去解谁的手</b>、
 * 以及<b>名牌上那句归属</b>。
 */
public class MonitorItem extends Item {

    public MonitorItem(Properties properties) {
        super(properties);
    }

    /** 这台遥控器绑着的无人机 UUID；没绑过返回 {@code null}。 */
    @Nullable
    public static UUID linkedDrone(ItemStack stack) {
        return stack.get(ModDataComponents.LINKED_DRONE.get());
    }

    /** 这台遥控器是不是<b>正在操控</b>（= 正处在无人机视角里）。组件缺席 = 没在操控。 */
    public static boolean isPiloting(ItemStack stack) {
        return Boolean.TRUE.equals(stack.get(ModDataComponents.MONITOR_USING.get()));
    }

    /**
     * 这个人此刻正在操控的那台无人机（{@code null} = 现在不在操控）。
     *
     * <p>两道闸门：<b>主手是遥控器</b>（副手不算，副手留给 B3-d 的组合）+ <b>它正处在操控状态</b>，
     * 然后按物品上记的 UUID 去把无人机找出来（服务端按 UUID 直接查，客户端范围查）。
     *
     * <p>⚠️ 刻意<b>不</b>再加"无人机上的 CONTROLLER 必须是我"这一条：绑定时就已经要求过
     * （见 {@link #toggleLink}），而多一条"必须对上当前对象"的前置条件正是本项目在
     * 「解绑死锁」上栽过的坑 —— 它会把"状态没同步过来"变成"操控指令被静默丢掉"。
     */
    @Nullable
    public static DroneEntity pilotedDrone(Player player) {
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof MonitorItem) || !isPiloting(stack)) {
            return null;
        }
        UUID linked = linkedDrone(stack);
        return linked == null ? null : findLinked(player.level(), linked);
    }

    /**
     * 按 UUID 找那台无人机 —— **O(1) 的哈希查找**，服务端 / 客户端各走自己那条。
     *
     * <h2>⚠️⚠️ 千万不要改回"AABB 范围查询"</h2>
     *
     * 那是我最初写的版本（{@code level.getEntities(EntityTypeTest, AABB, predicate)}），
     * 看着"公开、没依赖"，实际是**扫描包围盒里所有非空实体小节**。围着玩家取 512 格立方，
     * 那是上万个格子，而**相机每帧都要找一次这台无人机** ⇒ 明显的掉帧，
     * 连无人机那条引擎音都跟着炸音（主线程卡顿 ⇒ 音频欠载）。
     * 2026-09-23 用户实测报的就是"飞起来明显卡顿、音效卡音炸音"。
     *
     * <p>⇒ 正解是卓越前线那条：{@code getEntities().get(uuid)}。为此本模组加了一条
     * <b>访问转换器</b>（{@code META-INF/accesstransformer.cfg}）—— 它也是为了这一处加的。
     *
     * <p>⚠️⚠️ <b>专用服务器安全</b>：客户端那条（要 {@code ClientLevel} + 访问转换器）**搬去了
     * {@code client/DroneClientAccess}**，这里只在 {@code level.isClientSide} 的闸门里调它 ——
     * 服务端的分发包里没有 {@code net/minecraft/client/**}，公共类一旦在服务端的执行路径上碰到
     * 那些类就会 {@code NoClassDefFoundError}。
     * （⚠️ 原来这里写的是 {@code if (level instanceof ClientLevel …)} 加一句"服务端永远走不到"——
     * 那句话**对，但不够**：判断本身仍在公共方法里。搬走之后公共类的常量池里一个客户端类型都没有，
     * 发布前的 {@code tools/scan_client_refs.js} 扫描结果就是 0。）
     */
    @Nullable
    public static DroneEntity findLinked(Level level, UUID id) {
        if (level.isClientSide) {
            return net.createballistics.client.DroneClientAccess.findClient(level, id);
        }
        if (level instanceof ServerLevel server) {
            // 服务端这条路本来就是 O(1) 且不需要 AT
            return server.getEntity(id) instanceof DroneEntity drone ? drone : null;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 进入 / 退出无人机视角（右键空气）
    // ------------------------------------------------------------------

    /**
     * 手持遥控器<b>右键空气</b> = 进入 / 退出无人机视角（照卓越前线的 {@code MonitorItem#use}）。
     *
     * <p>⚠️ 为什么是"右键空气"而不是一个按键：视角本来就是**同一件东西的开关**（在看它 / 不看了），
     * 而一个物品最自然的开关就是它自己的右键 —— 键位留给操控用（B3-b-②）。
     *
     * <p>⚠️ 副手一律拒绝：副手要留给 B3-d「副手拿火炮指示器」那套组合。
     *
     * <p>⚠️ 与卓越前线一致的一处粗糙：右键**没有交互的方块**（比如泥巴）时，原版会退回到
     * {@link Item#use}，所以那种情况也会切一次视角。先照它，不为此加特判。
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResultHolder.pass(stack);
        }
        if (linkedDrone(stack) == null) {
            // 禁用态要把原因说出来（本模组的规矩）——不然玩家只会觉得"右键没反应"
            player.displayClientMessage(Component.translatable("item.create_ballistics.monitor.unbound")
                    .withStyle(ChatFormatting.RED), true);
            return InteractionResultHolder.fail(stack);
        }
        boolean next = !isPiloting(stack);
        // ⚠️ 进视角前先确认**这台无人机现在真的找得到**，找不到就别进（2026-09-26 修）。
        //    不拦的话会变成：点一下进视角 → 下一 tick 兜底发现找不到 → 自动退出
        //    ⇒ **同一毫秒内"进入 / 退出"来回切**（日志实测那一串 23:01:07.488 就是这样），
        //    玩家看到的是"原版反复弹第一人称 / 视角进不去"，而且全程黑屏。
        //    ⚠️ 只拦"进"，**不拦"出"** —— 退出这条路必须永远走得通（见下方 release 的谨慎）。
        if (next && findLinked(level, linkedDrone(stack)) == null) {
            player.displayClientMessage(Component.translatable("item.create_ballistics.monitor.no_signal")
                    .withStyle(ChatFormatting.RED), true);
            return InteractionResultHolder.fail(stack);
        }
        stack.set(ModDataComponents.MONITOR_USING.get(), next);
        if (level.isClientSide) {
            // ⚠️ 只在客户端分支里碰客户端类（见类注释）
            if (next) {
                PilotCamera.enter();
            } else {
                PilotCamera.exit();
            }
        }
        return InteractionResultHolder.success(stack);
    }

    /**
     * 兜底：人不看着它了，就自动退出视角（照卓越前线的 {@code inventoryTick}）。
     *
     * <p>⚠️ 这三件事都**没有事件**会来通知物品，只能每 tick 看一次：
     * 遥控器被换到别的槽位、解绑了、那台无人机没了（撞坏炸掉 / 被收起 / 主人离线时被摧毁）。
     */
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (!isPiloting(stack)) {
            return;
        }
        UUID linked = linkedDrone(stack);
        if (!selected || linked == null || findLinked(level, linked) == null) {
            // ⚠️ 这条兜底是"视角进不去 / 相机类型反复翻转"时最关键的一条日志：
            //    哪一条判据成立、以及**它是不是被当成常态在每 tick 触发**。
            //    它只在"这一步真的会退出"时打一行，不是每 tick 刷屏（2026-09-26 加）。
            CreateBallistics.LOGGER.info(
                    "[火控] 遥控器自动退出视角：槽位选中={} 有绑定={} 找得到无人机={}（侧别={}）",
                    selected, linked != null, linked != null && findLinked(level, linked) != null,
                    level.isClientSide ? "客户端" : "服务端");
            stack.remove(ModDataComponents.MONITOR_USING.get());
            if (level.isClientSide) {
                PilotCamera.exit();
            }
        }
    }

    // ------------------------------------------------------------------
    // 绑定 / 解绑（无人机那边右键时调进来）
    // ------------------------------------------------------------------

    /**
     * 绑定或解绑这台无人机。
     *
     * <h2>⚠️ 解绑刻意是"无论它指着谁，都放开"</h2>
     *
     * 最初的写法要求"潜行右键的必须是它绑着的那一台"才给解 —— 于是无人机一旦消失
     * （炸了、被收了、或者主人离线时它被摧毁），那台遥控器就**指着一个再也见不到的 UUID 死锁**：
     * 绑新的（"已经绑着一台了"）和放开的（"没绑着它"）两条路都被自己的记录挡死。
     * 让"放开"只看物品自己身上那条记录，就**永远有一条走得通的路**（用户 2026-09-23 撞到的正是这个）。
     *
     * @return 有没有真的改动（{@code false} = 状态不对，外面据此反馈失败）
     */
    public boolean toggleLink(Player player, ItemStack stack, DroneEntity drone) {
        UUID linked = linkedDrone(stack);
        UUID id = drone.getUUID();

        if (player.isSecondaryUseActive()) {
            release(player, stack, drone);
            return linked != null;   // 本来就没绑 ⇒ 外面会看到 false（那正是"没绑着它"那句）
        }

        // 幂等：本来就绑着它，再说一次就好
        if (linked != null && linked.equals(id)) {
            player.displayClientMessage(Component.translatable("item.create_ballistics.monitor.linked")
                    .withStyle(ChatFormatting.GREEN), true);
            return true;
        }
        // ⚠️ 判据顺序：**最具体的放前面**。被别人占着是玩家无论怎么做都改不了的事，
        //    先说它，才不会用"你先解绑"这种他做得到但没用的话把他引偏。
        String controller = drone.getController();
        if (controller != null && !controller.equals(player.getStringUUID())) {
            player.displayClientMessage(Component.translatable("item.create_ballistics.monitor.taken")
                    .withStyle(ChatFormatting.RED), true);
            return false;
        }
        // ⚠️⚠️ **自愈：本子上记的那台其实已经没了**（炸了 / 被收了，而服务端的自动解绑那一环
        //    不知为何没走到）—— 这时**绝不能用一句"已经绑着了"把玩家挡回去**，
        //    那正是本项目在「解绑死锁」上栽过的坑：他绑新的被拒、放开的又走不通，卡死。
        //    ⇒ 直接放开这条指着一个**当场查不到**的 UUID 的旧记录，再往下走。
        //    ⚠️ "查不到"本身是有歧义的（也可能只是区块没加载），但**玩家此刻正在右键另一台
        //    活着的无人机**，意图明确 ⇒ 按他的意思办，代价只是"顺带放开了那台（还在的）"。
        if (linked != null && findLinked(player.level(), linked) == null) {
            CreateBallistics.LOGGER.info("[火控] 遥控器上那条记录指向的无人机不在了 ⇒ 自愈解绑（旧={}）", linked);
            stack.remove(ModDataComponents.LINKED_DRONE.get());
            linked = null;
        }
        // 剩下两种冲突：无人机已绑在这个人的另一台遥控器上 / 这台的记录还指着别的无人机
        if (controller != null || linked != null) {
            player.displayClientMessage(Component.translatable("item.create_ballistics.monitor.busy")
                    .withStyle(ChatFormatting.RED), true);
            return false;
        }

        stack.set(ModDataComponents.LINKED_DRONE.get(), id);
        drone.setController(player);
        player.displayClientMessage(Component.translatable("item.create_ballistics.monitor.linked")
                .withStyle(ChatFormatting.GREEN), true);
        return true;
    }

    /** 放开这台遥控器（不管它现在指着谁），顺手把**眼前这台**的归属清掉。 */
    private static void release(Player player, ItemStack stack, DroneEntity drone) {
        UUID linked = linkedDrone(stack);
        if (linked == null) {
            player.displayClientMessage(Component.translatable("item.create_ballistics.monitor.not_linked")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        stack.remove(ModDataComponents.LINKED_DRONE.get());
        if (linked.equals(drone.getUUID())) {
            drone.clearController();
        }
        // 原来指着另一台也没关系 —— 那一台会自己复核（见 DroneEntity#verifyController）
        player.displayClientMessage(Component.translatable("item.create_ballistics.monitor.unlinked")
                .withStyle(ChatFormatting.RED), true);
    }

    // ------------------------------------------------------------------
    // 无人机没了 ⇒ 把绑着它的遥控器一起解开
    // ------------------------------------------------------------------

    /**
     * 无人机被<b>摧毁 / 回收</b>时调用：把主人身上所有绑着它的遥控器解开。
     *
     * <h2>⚠️ 为什么必须有这一步</h2>
     *
     * 绑定关系只存在物品上，无人机身上没有 ⇒ **无人机消失时没有任何"另一边"会自己清掉**，
     * 于是那台遥控器会指着一个<b>永远回不来的 UUID</b>，两条路都走不通。
     * 这正是不做这一步的症状（2026-09-23 用户实机撞到）。
     *
     * <p>卓越前线同样是这个做法（{@code DroneEntity#destroy} 里按 {@code CONTROLLER} 找到主人、
     * 逐格解绑）—— 只不过它的"主人"记在无人机上，我们把绑定记在物品上，
     * 所以这里由无人机把 <b>操控者 UUID</b> 带过来（它本来就有）。
     *
     * @param droneId      没了的无人机
     * @param controllerId 它的操控者（可能为 {@code null} —— 比如从没被绑定过）
     */
    public static void unlinkAll(Level level, UUID droneId, @Nullable UUID controllerId) {
        // 主人不在线就**没法**改了（物品在他存档里）—— 那种情况留给"潜行右键放开"那条路
        ServerPlayer owner = onlineOwner(level, controllerId);
        if (owner == null) {
            // ⚠️ 这一行是排查"炸毁后遥控器没自动解绑"的判据（主人不在线 / 无人机从没被绑过）
            CreateBallistics.LOGGER.info("[火控] 无人机没了但自动解绑做不了：无人机={} 主人={}（不在线或没绑过）",
                    droneId, controllerId);
            return;
        }
        int cleared = 0;
        for (ItemStack stack : heldStacks(owner)) {
            if (unlinkIfLinkedTo(stack, droneId)) {
                cleared++;
            }
        }
        CreateBallistics.LOGGER.info("[火控] 无人机没了 ⇒ 自动解绑：无人机={} 主人={} 清掉 {} 台遥控器",
                droneId, owner.getGameProfile().getName(), cleared);
        if (cleared > 0) {
            // ⚠️⚠️ **必须再强推一次整份容器同步**（2026-09-26 修「炸毁后遥控器还显示已绑定」）。
            //    `broadcastChanges()` 只比对"服务端槽位 vs 上次发出去的那份"，**组件级的改动
            //    在某些时序下会被它漏掉**（实测：服务端已经 `有绑定=false`，而客户端那条
            //    inventoryTick 还在报 `有绑定=true` ⇒ 遥控器 tooltip 一直挂着「已绑定无人机」、
            //    下面是「读不到它的位置」）。`broadcastFullState()` **无条件**把每个槽位重发一遍，
            //    不依赖那次比对。解绑是低频事件，这点开销无所谓。
            owner.containerMenu.broadcastFullState();
        }
    }

    /** 这个人身上有没有一台遥控器还绑着这台无人机（无人机自己复核归属时用）。 */
    public static boolean holdsMonitorLinkedTo(Player player, UUID droneId) {
        for (ItemStack stack : heldStacks(player)) {
            if (stack.getItem() instanceof MonitorItem && droneId.equals(linkedDrone(stack))) {
                return true;
            }
        }
        return false;
    }

    /** 这个人身上所有可能放物品的格子（主栏 + 副手）。 */
    private static Iterable<ItemStack> heldStacks(Player player) {
        List<ItemStack> all = new java.util.ArrayList<>(player.getInventory().items.size() + 1);
        all.addAll(player.getInventory().items);
        all.addAll(player.getInventory().offhand);
        return all;
    }

    @Nullable
    private static ServerPlayer onlineOwner(Level level, @Nullable UUID ownerId) {
        if (!(level instanceof ServerLevel server) || ownerId == null) {
            return null;
        }
        return server.getServer().getPlayerList().getPlayer(ownerId);
    }

    /** @return 有没有真的清掉一台（调用方据此决定要不要强推容器同步） */
    private static boolean unlinkIfLinkedTo(ItemStack stack, UUID droneId) {
        if (stack.getItem() instanceof MonitorItem && droneId.equals(linkedDrone(stack))) {
            stack.remove(ModDataComponents.LINKED_DRONE.get());
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------

    /**
     * ⚠️ 与卓越前线一致：**别让"组件变了"演成"换了个物品"**。
     *
     * <p>物品的组件一变，客户端就当成换了物品、播一次换手动画 —— 绑定/解绑虽然不频繁，
     * 但那是"我在操作这台遥控器"，不该看见它被收起来又拿出来。
     */
    @Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return false;
    }

    /**
     * tooltip：绑了谁、它在哪、离多远（照卓越前线的 {@code des.superbwarfare.monitor} 那两行）。
     *
     * <p>⚠️ 位置的取法<b>刻意不用镜像组件</b>：无人机可能离得很远，
     * 但"能查到就显示、查不到就直说查不到"已经够用，而且不用为一行 tooltip
     * 引入一条每 N tick 改物品组件的同步链（那正是 B1 那轮换手动画的来源）。
     */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        Tooltips.addLines(tooltip, "item.create_ballistics.monitor.help", ChatFormatting.GRAY);

        UUID linked = linkedDrone(stack);
        if (linked == null) {
            tooltip.add(Component.translatable("item.create_ballistics.monitor.unbound")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }

        Player player = net.createballistics.client.DroneClientAccess.localPlayer();
        DroneEntity drone = player == null ? null : findLinked(player.level(), linked);
        if (drone == null) {
            tooltip.add(Component.translatable("item.create_ballistics.monitor.bound")
                    .withStyle(ChatFormatting.AQUA));
            // 分不清"飞远了"与"区块没加载"，两种可能都说 —— 与 node_unavailable 那句同一个谨慎
            tooltip.add(Component.translatable("item.create_ballistics.monitor.no_signal")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }

        tooltip.add(Component.translatable("item.create_ballistics.monitor.bound")
                .withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("item.create_ballistics.monitor.distance",
                format(drone.distanceTo(player))).withStyle(ChatFormatting.GRAY));
        // ⚠️ 坐标不能用 format(...)：那个帮手是给距离用的，会自己补一个 "m"
        // ⚠️ 坐标读数走 PosDisplay：无人机还在船附近（图幅系）时，直接报的坐标是 2896 万格那种。
        Vec3 shown = PosDisplay.of(drone.level(), drone.position());
        tooltip.add(Component.translatable("item.create_ballistics.monitor.position",
                String.format(Locale.ROOT, "%.1f", shown.x()),
                String.format(Locale.ROOT, "%.1f", shown.y()),
                String.format(Locale.ROOT, "%.1f", shown.z())).withStyle(ChatFormatting.GRAY));
    }

    // 说明：客户端"看不看得见那台无人机"**不再由我们自己定半径**，而是由实体同步范围决定 ——
    // {@code ModEntities.DRONE} 的 {@code clientTrackingRange(16)}（16 区块 = 256 格）。
    // 超出它，服务端就不再把这台无人机同步给客户端，tooltip 与相机自然都读不到。

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value) + "m";
    }
}
