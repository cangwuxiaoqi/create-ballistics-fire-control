package net.createballistics.cbc;

import java.util.UUID;
import net.createballistics.CreateBallistics;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import org.jetbrains.annotations.Nullable;

/**
 * <b>给火控射出的那一发"保活"</b>：让它在飞行期间始终待在一个<b>实体 tick 级</b>的区块里。
 *
 * <h2>为什么非做不可（2026-09-26 大整合包实测定案）</h2>
 *
 * 现象：远射的高抛弹**在半空中"消失"**（不是落地）。三条硬数据拼出的链条：
 * <pre>
 *   炮弹飞向未探索区域 → c2me 生成区块 → 服务端卡 18.8 秒（落后 377 tick）
 *     → RPL 的区块票过期（CBC 靠 RPL 保活：`if (!isRemoved() && projectilesCanChunkload)`）
 *     → 弹丸掉出"实体 tick"集合 ⇒ **停止 tick**（实测：+241 tick 时还在 2306 格、速度 +6.01，
 *       +337 tick 才到 2324 格 ⇒ 早就停了）
 *     → 而 RPL 续票**恰恰要靠弹丸自己 tick** ⇒ **死锁，永远回不来**
 * </pre>
 * ⇒ 把 RPL 的 `projectileChunkAge` 从 3 拉到 20（代码上限）**也治不了**：它只是"票续久一点"，
 * 不是"票不过期"，而卡顿动辄几百 tick。
 *
 * <h2>本类做的两件事</h2>
 * <ol>
 *   <li><b>票不过期</b>：用 NeoForge 的 {@link TicketController} 显式挂票/放票，
 *       与"每 tick 续期"无关 ⇒ 卡顿多久，弹丸的区块都在；卡完它继续飞 ✓</li>
 *   <li><b>实体 tick 级</b>：{@code ticking = true} ⇒ 弹丸会继续 tick（普通"已加载"是不够的）✓</li>
 * </ol>
 *
 * <h2>作用域（刻意收窄）</h2>
 * 只对<b>火控射出的那一发</b>、<b>飞行期间</b>挂票；<b>换区块就换票、结账就放票</b>
 * ⇒ 任何时刻世界里只有 1~2 个我们挂的强制区块，不会沿着弹道留一串。
 *
 * <p>⚠️ 这些票是**会存档**的（NeoForge 的 ForcedChunksSavedData）⇒ 正常结账一定会放掉。
 * 万一在飞行途中崩了/退出：票会留着，而那恰好是"自愈"的 —— 下次进世界时弹丸所在的区块被加载、
 * 它继续飞，本模组的跟踪器也会继续跟着结账 ✓（所以这里刻意**不**做"陈旧票校验"：
 * 那个回调在实体尚未加载时就会被调用，会把**有效**的票一起误删 —— 反而制造死锁）。
 */
public final class ShellChunkKeeper {

    /** 由 mod 总线注册（{@link RegisterTicketControllersEvent} 是 mod 总线事件）。 */
    @Nullable
    private static TicketController controller;

    /** 正在保活哪颗弹丸（票的主人就是它的 UUID）。 */
    @Nullable
    private UUID owner;
    /** 当前挂着的区块；null = 没挂着。 */
    @Nullable
    private ChunkPos forced;

    /**
     * 注册票控制器。**只调一次**（见 {@code CreateBallistics} 的构造函数）。
     *
     * <p>回调传 {@code null}：见类注释里那段"为什么刻意不做陈旧票校验"。
     */
    public static void registerController(RegisterTicketControllersEvent event) {
        TicketController created = new TicketController(CreateBallistics.id("shell_tracking"));
        event.register(created);
        controller = created;
    }

    /**
     * 每 tick 调一次：让这颗弹丸所在的区块保持在"实体 tick 级"。
     *
     * <p>它换区块时才动票（挂新块 / 放旧块），所以平时是零开销。
     */
    public void keep(ServerLevel level, Entity shell) {
        if (controller == null) {
            return;
        }
        if (owner == null) {
            owner = shell.getUUID();
        }
        ChunkPos now = new ChunkPos(shell.blockPosition());
        if (now.equals(forced)) {
            return;   // 还在同一个区块里，什么都不用做
        }
        if (forced != null) {
            controller.forceChunk(level, owner, forced.x, forced.z, false, true);
            forced = null;
        }
        if (controller.forceChunk(level, owner, now.x, now.z, true, true)) {
            forced = now;
        }
    }

    /** 结账（落地 / 消失 / 放弃跟踪）时调：把票放掉，不在世界里留永久加载区。 */
    public void release(ServerLevel level) {
        if (controller != null && owner != null && forced != null) {
            controller.forceChunk(level, owner, forced.x, forced.z, false, true);
        }
        forced = null;
    }
}
