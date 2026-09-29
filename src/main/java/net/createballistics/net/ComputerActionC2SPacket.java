package net.createballistics.net;

import net.createballistics.CreateBallistics;
import net.createballistics.block.entity.FireControlComputerBlockEntity;
import net.createballistics.item.ArtilleryIndicatorItem;
import net.createballistics.registry.ModDataComponents;
import net.createballistics.registry.ModSounds;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 火控计算机界面的操作。
 *
 * <p>{@code field} 指明改的是哪一项；{@code value} 是新值。用统一的
 * 「字段号 + 数值」而不是给每项单开一个包，后面加参数不用改协议。
 */
public record ComputerActionC2SPacket(BlockPos pos, byte field, double value,
                                      double x, double y, double z)
        implements CustomPacketPayload {

    public static final byte FIELD_MIN_PITCH = 0;
    public static final byte FIELD_MAX_PITCH = 1;
    public static final byte FIELD_BARREL_LENGTH = 2;
    public static final byte FIELD_VELOCITY_PER_CHARGE = 3;
    public static final byte FIELD_MIN_CHARGES = 4;
    public static final byte FIELD_MAX_CHARGES = 5;
    /** 目标坐标（用 x/y/z 三个字段）。 */
    public static final byte FIELD_TARGET = 100;
    /** 强制重算。 */
    public static final byte FIELD_RECALCULATE = 101;
    /** 清除目标。 */
    public static final byte FIELD_CLEAR_TARGET = 102;
    /** 把目标坐标下发全网（各炮自己解算）。 */
    public static final byte FIELD_LAY_TARGET = 103;
    /** 全网开火。 */
    public static final byte FIELD_FIRE_ALL = 104;
    /** 切换弹道模式（自动 → 低伸 → 高抛）。 */
    public static final byte FIELD_CYCLE_ARC_MODE = 105;
    /** 切换火力模式（齐射 → 同时弹着 → 轮射）。 */
    public static final byte FIELD_CYCLE_FIRE_MODE = 106;
    /** 切换轮射的落点间隔档位（0.5 → 1 → 2 → 5 秒）。 */
    public static final byte FIELD_CYCLE_INTERVAL = 107;
    /** 切换覆盖打击的半径档位（10 → 20 → 30 → 40 → 50 格）。 */
    public static final byte FIELD_CYCLE_COVERAGE_RADIUS = 108;
    /**
     * <b>火炮指示器报点</b>（中键）：设坐标 → 下发全网 → 在指示器上记一份画标记用的坐标。
     *
     * <p>前两步与界面上的「确认坐标并开始计算」<b>是同一套</b>，所以两个入口的语义永远一致。
     */
    public static final byte FIELD_INDICATOR_TARGET = 109;
    /**
     * <b>火炮指示器远程开火</b>（左键蓄力满）。
     *
     * <p>⚠️ 刻意与 {@link #FIELD_FIRE_ALL} <b>分开</b>：那个包是**计算机界面按钮**也在用的。
     * 归属检查（"这台计算机是不是归你这台指示器"）只能加在<b>指示器专用</b>的字段上 ——
     * 加在公用字段上会把"站在计算机前按界面"的人也一起拦掉
     * （2026-09-22 实际踩到：手里拿着别的指示器时，界面上的「开火！」按了没反应）。
     */
    public static final byte FIELD_INDICATOR_FIRE = 110;

    public static final Type<ComputerActionC2SPacket> TYPE =
            new Type<>(CreateBallistics.id("computer_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ComputerActionC2SPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeBlockPos(p.pos);
                        buf.writeByte(p.field);
                        buf.writeDouble(p.value);
                        buf.writeDouble(p.x);
                        buf.writeDouble(p.y);
                        buf.writeDouble(p.z);
                    },
                    buf -> new ComputerActionC2SPacket(
                            buf.readBlockPos(), buf.readByte(), buf.readDouble(),
                            buf.readDouble(), buf.readDouble(), buf.readDouble()));

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ComputerActionC2SPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (!(player.level().getBlockEntity(payload.pos)
                    instanceof FireControlComputerBlockEntity computer)) {
                // 指示器可以在很远处下指令，那条路靠的是"计算机那块区块加载着"
                // （服务端不受客户端视距限制，但区块没加载就真读不到方块实体）。
                // ⇒ 送不到就**直说**，别让玩家以为"按了没反应"是按钮坏了。
                player.displayClientMessage(Component.translatable(
                        "gui.create_ballistics.computer_unloaded"), true);
                return;
            }
            try {
                apply(computer, player, payload);
                mirrorToHeldIndicator(player, computer);
            } catch (RuntimeException e) {
                // ⚠️ 指令处理里抛出的异常会被网络层**吞掉**（只往日志里写一条 ERROR），
                //    玩家那边看到的是"点了没反应、什么提示都没有"。
                //    2026-09-23 那个"只有轮射开不了炮"就是这么查了两轮才在日志里翻到堆栈的
                //    （根因是 readyNodes() 返回不可变列表、轮射对它 sort）。
                //    ⇒ 让它**在游戏里也说出来**，别再让玩家对着一个静默失败猜。
                CreateBallistics.LOGGER.error("[火控] 处理指令出错（field={}）", payload.field, e);
                player.displayClientMessage(Component.translatable(
                        "gui.create_ballistics.command_error").withStyle(ChatFormatting.RED), true);
            }
        });
    }

    /** 真正执行一条指令。拆出来是为了外面能统一兜异常（见 {@link #handle}）。 */
    private static void apply(FireControlComputerBlockEntity computer, Player player,
                              ComputerActionC2SPacket payload) {
        switch (payload.field) {
            case FIELD_MIN_PITCH -> computer.setMinPitch(payload.value);
            case FIELD_MAX_PITCH -> computer.setMaxPitch(payload.value);
            case FIELD_BARREL_LENGTH -> computer.setBarrelLength(payload.value);
            case FIELD_VELOCITY_PER_CHARGE -> computer.setVelocityPerCharge(payload.value);
            case FIELD_MIN_CHARGES -> computer.setMinCharges((int) Math.round(payload.value));
            case FIELD_MAX_CHARGES -> computer.setMaxCharges((int) Math.round(payload.value));
            case FIELD_TARGET -> computer.setTarget(payload.x, payload.y, payload.z);
            case FIELD_RECALCULATE -> computer.calculate();
            case FIELD_CLEAR_TARGET -> computer.clearTarget();
            case FIELD_LAY_TARGET -> computer.layTarget();
            case FIELD_FIRE_ALL -> reportIfFailed(player, computer.fireAll());
            case FIELD_INDICATOR_FIRE -> {
                if (!blockedAsNonOwner(computer, player)) {
                    reportIfFailed(player, computer.fireAll());
                }
            }
            case FIELD_CYCLE_ARC_MODE -> computer.cycleArcMode();
            // ⚠️ 这里**刻意不发聊天栏提示**：2026-09-25 试过，玩家反馈"看不到 / 字是糊的" ——
            //    计算机面板正好把聊天栏盖住，举镜时镜筒又把它压住。
            //    ⇒ 提示改成**画在两块面板自己身上**（计算机界面底部、指示器 H 界面底部），
            //      那才是玩家真能看清的地方。切换的语义（跳过炮不够的模式）仍然在这里。
            case FIELD_CYCLE_FIRE_MODE -> computer.cycleFireMode();
            case FIELD_CYCLE_INTERVAL -> computer.cycleRollingInterval();
            case FIELD_CYCLE_COVERAGE_RADIUS -> computer.cycleCoverageRadius();
            case FIELD_INDICATOR_TARGET -> {
                if (!blockedAsNonOwner(computer, player)) {
                    markFromIndicator(computer, player, payload);
                }
            }
            default -> {
            }
        }
    }

    /**
     * 改完设置**立刻**把镜像抄一遍（指示器手上那份）—— 不然 H 键界面要等下一次报到
     * （最多半秒）标签才变，点起来发卡。见 {@code ArtilleryIndicatorItem#mirrorSettingsFrom}。
     */
    private static void mirrorToHeldIndicator(Player player, FireControlComputerBlockEntity computer) {
        // 「手上那份指示器」= 主手，或**操控无人机时的副手**（见 ArtilleryIndicatorItem#actingIndicator）
        ItemStack held = ArtilleryIndicatorItem.actingIndicator(player);
        if (!held.isEmpty()) {
            ArtilleryIndicatorItem.mirrorSettingsFrom(held, computer);
        }
    }

    /**
     * 开火没打出去时，把<b>原因</b>告诉玩家。
     *
     * <p>在此之前这一步是完全静默的：按了没反应，玩家只能猜是"卡了""按钮坏了""bug"——
     * 2026-09-22 用户实测就卡在这里（同一时刻界面还显示「已就位 4/4 · 待开火」，
     * 因为那是**客户端那份可能过期的副本**，而服务端其实打不出去）。
     *
     * @param reason {@code fireAll()} 返回的语言键；{@code null} = 打出去了，不用说话
     */
    private static void reportIfFailed(Player player, @Nullable String reason) {
        if (reason != null) {
            player.displayClientMessage(Component.translatable(reason).withStyle(ChatFormatting.RED), true);
        }
    }

    /**
     * 指示器发来的指令：本机现在要是正被<b>别人</b>的指示器用着，就拦下来并告诉他。
     *
     * <p>⚠️ <b>只用在"指示器专用"的那几个字段上</b>（{@code FIELD_INDICATOR_TARGET} /
     * {@code FIELD_INDICATOR_FIRE}）。<b>别用在公用字段上</b> ——
     * {@code FIELD_FIRE_ALL} 是计算机界面按钮也在用的，加在这里会把"站在计算机前按界面"的人
     * 一并拦掉（2026-09-22 实际踩到：手里拿着别的指示器时，界面上的「开火！」按了没反应）。
     *
     * <p>「手上那份指示器」走 {@link ArtilleryIndicatorItem#actingIndicator} —— 主手，
     * 或<b>操控无人机时的副手</b>（照卓越前线，无人机视角下指示器在副手）。
     *
     * <p>⚠️ 2026-09-23 顺带<b>收紧</b>了一处：原来"手上压根没拿指示器"是<b>放行</b>的
     * （判据写的是"拿着指示器 && 不是主人 ⇒ 拦"），等于谁都能凭空发个包改别人的计算机目标。
     * 这个包只有指示器会发，所以现在**没有指示器一律拦下**。界面那条路走的是
     * {@code FIELD_FIRE_ALL} 等公用字段，不受影响。
     *
     * @return {@code true} = 已被拦下（调用方不要执行）
     */
    private static boolean blockedAsNonOwner(FireControlComputerBlockEntity computer, Player player) {
        ItemStack indicator = ArtilleryIndicatorItem.actingIndicator(player);
        if (!indicator.isEmpty() && computer.isOwner(ArtilleryIndicatorItem.indicatorId(indicator))) {
            return false;
        }
        player.displayClientMessage(Component.translatable(computer.hasIndicator()
                        ? "item.create_ballistics.artillery_indicator.taken"
                        : "item.create_ballistics.artillery_indicator.not_bound")
                .withStyle(ChatFormatting.RED), true);
        return true;
    }

    /**
     * 指示器报点。<b>设坐标 + 下发</b>与界面上的「确认坐标并开始计算」完全相同；
     * 额外的第三步只是给客户端一个"标记画在哪儿"的<b>显示用缓存</b>
     * （指示器常常离计算机很远，那时客户端读不到计算机方块实体，见 {@code ModDataComponents#LAST_TARGET}）。
     */
    private static void markFromIndicator(FireControlComputerBlockEntity computer, Player player,
                                          ComputerActionC2SPacket payload) {
        computer.setTarget(payload.x, payload.y, payload.z);
        computer.layTarget();

        // 主手，或**操控无人机时的副手** —— 图传里报点，标记要写到副手那份指示器上，
        // 客户端画标记读的也正是副手那份（DroneHudOverlay#drawOffhandMarks）
        ItemStack held = ArtilleryIndicatorItem.actingIndicator(player);
        if (held.isEmpty()) {
            return;
        }
        BlockPos marked = BlockPos.containing(payload.x, payload.y, payload.z);
        held.set(ModDataComponents.LAST_TARGET.get(), marked);
        // 报点音效（抄卓越前线那颗）—— 在这一步放，是因为只有真的报出去了才该响：
        // 客户端那份乐观播放会在"没绑计算机 / 没打到方块"时也响，那是骗人。
        player.level().playSound(null, player.blockPosition(), ModSounds.CANNON_ZOOM_IN.get(),
                SoundSource.PLAYERS, 1.0F, 1.0F);
        player.displayClientMessage(Component.translatable(
                        "item.create_ballistics.artillery_indicator.mark",
                        marked.getX(), marked.getY(), marked.getZ())
                .withStyle(ChatFormatting.GREEN), true);
    }
}
