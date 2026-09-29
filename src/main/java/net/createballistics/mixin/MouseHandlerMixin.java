package net.createballistics.mixin;

import net.createballistics.client.IndicatorClientEvents;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * 开镜时把视角灵敏度按倍率压下去。
 *
 * <h2>为什么非用 Mixin 不可</h2>
 *
 * 鼠标灵敏度是在 {@code MouseHandler.turnPlayer(double)} 里算完就直接喂给
 * {@code player.turn(...)} 的，NeoForge 没有为它提供事件。
 *
 * <p>⚠️ 顺带说明：原版 {@code turnPlayer} 里<b>本来就有</b>一支"开镜时降灵敏"的分支
 * （乘 {@code d3} 而不是 {@code d4 = d3 × 8}，也就是降 8 倍），但它判的是
 * {@code player.isScoping()} —— 也就是那个我们<b>故意不申请</b>的
 * {@code SPYGLASS_SCOPE} 物品能力。所以拿不到，只能自己接。
 * 参考模组卓越前线也是因此写了同样的 Mixin。
 *
 * <h2>注入点：{@code player.turn(...)} 这个<b>调用点</b>（2026-09-27 加固）</h2>
 *
 * 原版 {@code turnPlayer} 大体是：
 * <pre>
 *   d2 = 灵敏度 × 0.6 + 0.2
 *   d3 = d2³          ← 原版"开镜"分支用的量
 *   d4 = d3 × 8       ← 正常分支用的量（也就是正常比开镜快 8 倍）
 *   ... 三条分支各自算出 (d0, d1) ...
 *   player.turn(d0, d1 × i)
 * </pre>
 *
 * <p>⚠️⚠️ <b>原来改的是"方法里第 1 个 DSTORE"（{@code d3}，靠 {@code ordinal} 定位）</b>。
 * 那个做法在整合包里会静默失效：<b>越肩（ShoulderSurfing）也在动 {@code MouseHandler}</b>
 * （2026-09-27 静态扫描确认），只要它改了局部量的存盘顺序，`ordinal = 1` 就会落到别的量上
 * ⇒ **不崩、只是灵敏度不对**。
 *
 * <p>⇒ 改成<b>在 {@code player.turn(...)} 这个调用点上按语义改参数</b>：
 * 把两个参数各乘一次缩放系数。**与原来等价** ——
 * 因为 `d0`/`d1` 是三条分支各自的产物，而我们那个函数对"没开镜"返回原值（空操作）、
 * 对"操控无人机"返回 0（两条参数都归 0，与原来把 `d3` 置 0 的效果一致）。
 *
 * <p>⚠️ 唯一的细微差别：开了**电影镜头**（cinematic camera）时，原版那条分支的
 * 平滑滤波器吃的是 `d4` —— 原来我们缩的是滤波器的**输入**，现在缩的是**输出**。
 * 画面手感一致，只是滤波器的内部累积量不再被缩放（退出开镜时可能有一丁点差异）。
 * 电影镜头默认关闭，且这条路径与"开镜"无关，故接受。
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    @ModifyArgs(
            method = "turnPlayer(D)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void createballistics$scopeSensitivity(Args args) {
        args.set(0, IndicatorClientEvents.scopedSensitivity((double) args.get(0)));
        args.set(1, IndicatorClientEvents.scopedSensitivity((double) args.get(1)));
    }
}
