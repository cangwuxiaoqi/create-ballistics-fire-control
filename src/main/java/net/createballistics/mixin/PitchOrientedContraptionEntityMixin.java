package net.createballistics.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;

/**
 * 把火炮的<b>仰角上限</b>抬到 {@link #RAISED_LIMIT}，让普通火炮底座也能打高抛 / 攻顶。
 *
 * <h2>为什么非用 Mixin 不可</h2>
 *
 * CBC 的仰角上限来自数据包（`data/createbigcannons/cannon_mounts/...` 里的
 * {@code maximum_elevation}，普通火炮底座是 <b>60°</b>），而 {@code maximumElevation()}
 * 是它把那个值吐出来的<b>唯一出口</b>，CBC 没有开放任何 API 去改它。
 * 改数据包当然也行（我们实测过），但那是**每个存档各自的事**，装个 mod 就该直接生效 ——
 * 所以只能在这一层拦。参考模组 {@code firecontrol} 也是这么做的，而且这是它<b>全 mod 唯一一处 Mixin</b>。
 *
 * <h2>为什么这不是"侵入"</h2>
 *
 * 我们只在<b>返回值</b>上做一次 {@code max}，不碰 CBC 的任何内部状态、不改它的逻辑分支。
 * 而且我们的探针（{@code CannonProbe}）本来就是调这个方法读射界的 ——
 * 改了这里，<b>解算器、护目镜显示、自动切高抛全部自动跟随，一行代码都不用改</b>。
 *
 * <h2>为什么取 89.9 而不是 90</h2>
 *
 * 90° 是"正上方"，那一堆三角函数会退化（{@code cos = 0}），而且炮弹会直上直下砸回炮位自己头上。
 * 留 0.1° 让数学保持良好形态。
 */
@Mixin(PitchOrientedContraptionEntity.class)
public abstract class PitchOrientedContraptionEntityMixin {

    /** 抬到多少度。留 0.1° 余量，避开 90° 的退化。 */
    private static final float RAISED_LIMIT = 89.9F;

    /**
     * <b>原版普通火炮底座</b>的仰角上限 —— CBC 数据包里的原值。
     *
     * <p>只有<b>正好等于这个值</b>时才抬到 {@link #RAISED_LIMIT}，
     * 因为需求是「把<b>原版火炮</b>的俯仰角调高」—— 而不是"把所有炮座都改成 89.9"。
     */
    private static final float VANILLA_BIG_CANNON = 60.0F;

    @Inject(method = "maximumElevation()F", at = @At("RETURN"), cancellable = true)
    private void createballistics$raiseElevationLimit(CallbackInfoReturnable<Float> cir) {
        final float original = cir.getReturnValueF();

        if (original == VANILLA_BIG_CANNON) {
            cir.setReturnValue(RAISED_LIMIT);
            return;
        }
        // ⚠️ 其余炮座<b>只封顶、不动它本来的值</b>：
        //   · 数据包<b>明确指定</b>过的角度（比如别的整合包把上限设成 45）必须尊重 ——
        //     2026-09-21 我们一开始写成 `原值 >= 60 就抬到 89.9`，结果是
        //     **把用户自己的测试数据包（85°）也覆盖掉了**，症状是"数据包看起来没生效"。
        //   · 90°（自动火炮 / 固定炮架）仍然压到 89.9：90° 是炮口垂直朝上、
        //     开炮等于砸自己头上，而且 cos 在那里退化；玩家看到「射界 …~90°」
        //     第一反应就是模组算错了（2026-09-21 用户指出）。
        //   · 15°（倒挂炮座）原样保留 —— 参考模组无条件抬到 88°，把那种炮座的设计意图直接抹掉了。
        cir.setReturnValue(Math.min(original, RAISED_LIMIT));
    }
}
