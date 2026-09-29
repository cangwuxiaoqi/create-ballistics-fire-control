package net.createballistics.util;

import java.util.Locale;
import net.createballistics.ballistic.FireMode;
import net.createballistics.block.entity.FireControlComputerBlockEntity;
import net.createballistics.block.entity.FireControlNodeBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * 「计算过程」那一行的 <b>唯一实现</b> —— 计算机界面、计算机护目镜、协调器护目镜都从这里取。
 *
 * <h2>⚠️ 这是用户 2026-09-22 定下的规矩</h2>
 *
 * 「**计算机界面上的「计算过程」，都必须同步显示在护目镜上**」。
 * 所以这一行的**文字与颜色只在这里定义一次**，三处共用。
 *
 * <p>为什么强调"只此一份"：这个项目里已经栽过两次「同义文案写在两处、改了一处漏了另一处」
 * （一次是界面改了、护目镜没改；一次是"超出射程"同时散在三处）。
 * <b>再写第二份就一定会再次跑偏。</b>
 *
 * <p>放在 {@code util} 而不是 {@code client}：护目镜的 {@code addToGoggleTooltip} 由方块实体实现，
 * 那个类**两端都会加载**，引到客户端专属的类会让服务端直接崩。
 */
public final class StageText {

    /** 与界面一致的语义色。 */
    public static final int GOOD = 0xFF7CFF7C;
    public static final int WARN = 0xFFFFC46B;
    public static final int BAD = 0xFFFF7A7A;
    public static final int MUTED = 0xFFA8A8B4;

    private StageText() {
    }

    /**
     * 「计算过程」的<b>状态快照</b> —— 把这一行需要的原料从方块实体里摘出来。
     *
     * <h2>为什么要多这一层</h2>
     *
     * 火炮指示器举镜时也要在 HUD 上显示这一行，而<b>指示器常常离计算机很远</b> ——
     * 那时客户端读不到方块实体。所以服务端把这份快照<b>镜像</b>到指示器物品上
     * （见 {@code ModDataComponents#STAGE}），客户端拿到之后仍然调用<b>同一个</b>
     * {@link #of(Snapshot, Level)} 来渲染。
     *
     * <p>⚠️ <b>镜像的是"原料"，不是"成品文字"</b>：倒计时那一段要用当前 tick 现算，
     * 镜像一段写死的"还有 28.9 秒"会永远停在那一刻。原料里带的是排期结束的 tick，
     * 客户端自己减当前时间 ⇒ 倒计时照样一秒一秒走。
     */
    public record Snapshot(boolean powered, boolean hasTarget, int coordinators, int cannons,
                           int ready, int unsolvable, int failedFires, boolean rolling,
                           long salvoEndTick, boolean platformMoving, @Nullable String failKey) {

        /** 从方块实体摘一份快照（只在服务端调用）。 */
        public static Snapshot capture(FireControlComputerBlockEntity be) {
            FireControlComputerBlockEntity.UnsolvableNote note = be.findUnsolvableNote();
            return new Snapshot(be.hasRotationPower(), be.hasTarget(), be.getCoordinatorCount(),
                    be.getCannonCount(), be.getReadyCount(), be.getUnsolvableCount(),
                    be.getFailedFireCount(), be.getFireMode() == FireMode.ROLLING, be.getSalvoEndTick(),
                    be.isAnyNodeMoving(),
                    note == null ? null : note.fail().langKey());
        }

        /** 序列化进物品组件用。键名只在这里出现 —— 读写在相邻两行，不容易写偏。 */
        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putBoolean("Powered", powered);
            tag.putBoolean("HasTarget", hasTarget);
            tag.putInt("Coordinators", coordinators);
            tag.putInt("Cannons", cannons);
            tag.putInt("Ready", ready);
            tag.putInt("Unsolvable", unsolvable);
            tag.putInt("FailedFires", failedFires);
            tag.putBoolean("Rolling", rolling);
            tag.putLong("SalvoEndTick", salvoEndTick);
            tag.putBoolean("PlatformMoving", platformMoving);
            if (failKey != null) {
                tag.putString("FailKey", failKey);
            }
            return tag;
        }

        public static Snapshot fromTag(CompoundTag tag) {
            return new Snapshot(tag.getBoolean("Powered"), tag.getBoolean("HasTarget"),
                    tag.getInt("Coordinators"), tag.getInt("Cannons"), tag.getInt("Ready"),
                    tag.getInt("Unsolvable"), tag.getInt("FailedFires"), tag.getBoolean("Rolling"),
                    tag.getLong("SalvoEndTick"), tag.getBoolean("PlatformMoving"),
                    tag.contains("FailKey") ? tag.getString("FailKey") : null);
        }
    }

    /**
     * 这一行现在该说什么。
     *
     * @param level 客户端/服务端的世界；<b>只有排期倒计时需要它</b>（要读 {@code getGameTime}），
     *              传 null 时会跳过倒计时那一段
     */
    public static Component of(@Nullable FireControlComputerBlockEntity be, @Nullable Level level) {
        return be == null
                ? Component.translatable("gui.create_ballistics.no_data")
                : of(Snapshot.capture(be), level);
    }

    /** 与 {@link #of(FireControlComputerBlockEntity, Level)} 相同，但吃一份快照（指示器 HUD 走这条）。 */
    public static Component of(@Nullable Snapshot snapshot, @Nullable Level level) {
        if (snapshot == null) {
            return Component.translatable("gui.create_ballistics.no_data");
        }

        // ---- ① 同时弹着 / 轮射进行中：整行改成倒计时（优先级最高）----
        // 从按下开火到最后一发落地可能隔几十秒，这个数不说清楚玩家一定以为卡住了。
        long end = snapshot.salvoEndTick();
        if (end != 0 && level != null) {
            double left = Math.max(0.0, (end - level.getGameTime()) / 20.0);
            return Component.translatable(
                    snapshot.rolling()
                            ? "gui.create_ballistics.stage_rolling"
                            : "gui.create_ballistics.stage_tot",
                    num(left));
        }

        final int coordinators = snapshot.coordinators();
        final int cannons = snapshot.cannons();
        final int ready = snapshot.ready();
        final int unsolvable = snapshot.unsolvable();

        if (!snapshot.powered()) {
            return Component.translatable("gui.create_ballistics.stage_nopower");
        }
        if (coordinators == 0) {
            return Component.translatable("gui.create_ballistics.stage_nonet");
        }
        if (!snapshot.hasTarget()) {
            return Component.translatable("gui.create_ballistics.stage_notarget");
        }
        if (cannons == 0) {
            return Component.translatable("gui.create_ballistics.stage_nogun");
        }
        // ---- 有炮"最近一发没打出去"（CBC 判定卡弹/炸膛，整发消耗掉了却没出弹）----
        // ⚠️ 必须排在「未装填」那条**前面**：卡弹之后膛是空的，按"未装填"说就把玩家引去
        //    重新装填（装完还会再卡），而真正要做的是**加药或者把炮管改短**。
        //    ⇒ 又一次"最具体的判据放最前面"。
        if (snapshot.failedFires() > 0) {
            return Component.translatable("gui.create_ballistics.stage_fire_failed",
                    snapshot.failedFires());
        }
        // ---- ⭐ 实时计算（航空学）：载具在动 + 全部锁定 ----
        //
        // ⚠️ 必须排在「计算完毕」**前面**：两者在"全就位"这个条件上重叠，而判据要
        //    **最具体的放最前** —— 本项目为此栽过六次（见文末「多状态判据的顺序」）。
        if (snapshot.platformMoving() && ready >= cannons) {
            return Component.translatable("gui.create_ballistics.stage_realtime", ready, cannons);
        }
        if (ready >= cannons) {
            return Component.translatable("gui.create_ballistics.stage_ready");
        }
        // 有炮收到了目标却解不出来 —— 这时不能再显示"正在计算…"，否则玩家会一直干等。
        // ⚠️ 而且**必须说对原因**（被山挡 / 仰角不够 / 目标太近 / 没装填…），
        // 这句"为什么"的映射在 ArcFail 里只写一份，界面和护目镜共用。
        if (unsolvable > 0 && ready == 0) {
            return Component.translatable(snapshot.failKey() == null
                    ? FireControlNodeBlockEntity.ArcFail.OUT_OF_RANGE.langKey()
                    : snapshot.failKey());
        }
        if (unsolvable > 0) {
            // 「计算完毕」只在**没就位的炮全都是解不出来**时才敢说。否则还有炮在转，
            // 说"完毕"就是假的 —— ready + unsolvable >= cannons 正好是那个判据。
            return Component.translatable(
                    ready + unsolvable >= cannons
                            ? "gui.create_ballistics.stage_partial"
                            : "gui.create_ballistics.stage_partial_busy",
                    ready, cannons, unsolvable);
        }
        return Component.translatable("gui.create_ballistics.stage_computing")
                .copy().append(Component.literal("  " + ready + " / " + cannons));
    }

    /** 这一行该用什么颜色。语义与 {@link #of} 的分支一一对应。 */
    public static int color(@Nullable FireControlComputerBlockEntity be, @Nullable Level level) {
        return be == null ? BAD : color(Snapshot.capture(be), level);
    }

    /** 同上，吃快照的版本。 */
    public static int color(@Nullable Snapshot snapshot, @Nullable Level level) {
        if (snapshot == null) {
            return BAD;
        }
        // 排期进行中 → 绿字（在正常运转，不是出错）
        if (snapshot.salvoEndTick() != 0) {
            return GOOD;
        }
        // 有炮没打出去 → 橙字（能打，但要让玩家知道刚才那一发没出去）
        if (snapshot.failedFires() > 0) {
            return WARN;
        }
        if (!snapshot.powered() || snapshot.coordinators() == 0) {
            return BAD;
        }
        if (!snapshot.hasTarget() || snapshot.cannons() == 0) {
            return WARN;
        }
        int ready = snapshot.ready();
        int unsolvable = snapshot.unsolvable();
        if (unsolvable > 0 && ready == 0) {
            return BAD;    // 全军解不出来 —— 标红，别让玩家以为是"还在算"
        }
        if (unsolvable > 0) {
            return WARN;   // 部分炮打不了 —— 橙字，能打但要让玩家知道少了几门
        }
        if (snapshot.platformMoving()) {
            return GOOD;   // 实时计算：载具在动、炮在持续跟 —— 正常运转，绿字
        }
        return MUTED;
    }

    private static String num(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
