package net.createballistics.client.screen;

import net.createballistics.ballistic.ArcMode;
import net.createballistics.ballistic.FireMode;
import net.createballistics.util.Texts;
import net.minecraft.network.chat.Component;

/**
 * 火控面板上那几颗「循环按钮」的标签 —— <b>计算机界面与火炮指示器界面共用这一份</b>。
 *
 * <p>⚠️ 用户 2026-09-22 定的规矩：<b>同义文案只能有一份实现</b>（本模组为此栽过两次：
 * 一次是"计算过程"在护目镜里又有第三份平行实现，一次是火力模式改名时漏改一处）。
 * 指示器的 H 键界面和计算机界面上的三颗按钮是<b>同一套语义</b>，
 * 标签当然也该是同一句话 —— 所以从这里取，别在各自的界面里再拼一遍。
 */
public final class ControlLabels {

    private ControlLabels() {
    }

    /** 「弹道：自动」。模式传 {@code null}（还没读过计算机）时按默认的自动算。 */
    public static Component arcMode(ArcMode mode) {
        return Component.translatable("gui.create_ballistics.btn_arc_mode",
                Component.translatable(mode == null ? ArcMode.AUTO.langKey() : mode.langKey()));
    }

    /** 「火力模式：齐射」。同上，{@code null} 时按默认的齐射算。 */
    public static Component fireMode(FireMode mode) {
        return Component.translatable("gui.create_ballistics.btn_fire_mode",
                Component.translatable(mode == null ? FireMode.SALVO.langKey() : mode.langKey()));
    }

    /**
     * 参数按钮的标签：轮射显示间隔（秒）、范围覆盖显示半径（格）。
     *
     * <p>其它火力模式<b>没有这个参数</b>，返回空串（那时按钮本来也该藏起来，见 {@link #hasParam}）。
     */
    public static Component fireParam(FireMode mode, int rollingTicks, int coverageRadius) {
        if (mode == FireMode.ROLLING) {
            return Component.translatable("gui.create_ballistics.btn_rolling_interval",
                    Texts.num("%.1f", rollingTicks / 20.0));
        }
        if (mode == FireMode.COVERAGE) {
            return Component.translatable("gui.create_ballistics.btn_coverage_radius", coverageRadius);
        }
        return Component.empty();
    }

    /** 这个火力模式有没有参数 —— 决定参数按钮显不显示。 */
    public static boolean hasParam(FireMode mode) {
        return mode == FireMode.ROLLING || mode == FireMode.COVERAGE;
    }
}
