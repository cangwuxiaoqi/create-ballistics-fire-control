package net.createballistics.util;

import java.util.Locale;
import net.minecraft.network.chat.Component;

/**
 * 语言键的小工具 —— 处理「查不到翻译」这一种情况。
 *
 * <p>本模组要显示不少<b>来自其他模组</b>的名字：炮身材质、炮闩方块、弹种。它们都是附属模组
 * 可能随时新增的东西（武器拓展那几种材质、先进技术的弹种……），所以我们手里只有语言键。
 *
 * <p>规矩是：<b>能翻就翻，翻不了就退回键名的最后一段</b> ——
 * {@code block.createbigcannons.ap_shell} 显示成 {@code ap_shell}，
 * 而不是把一长串语言键糊在玩家脸上。
 */
public final class Texts {

    private Texts() {
    }

    /**
     * 翻译一个语言键；没有对应翻译时退回键名最后一段。
     *
     * @param languageKey 语言键，例如 {@code block.createbigcannons.ap_shell}
     */
    public static String translatedOrShort(String languageKey) {
        if (languageKey == null || languageKey.isEmpty()) {
            return "";
        }
        String translated = Component.translatable(languageKey).getString();
        return translated.equals(languageKey) ? shortName(languageKey) : translated;
    }

    /** 与 {@link #translatedOrShort} 相同，但返回组件（要拼进别的组件时用）。 */
    public static Component of(String languageKey) {
        return Component.literal(translatedOrShort(languageKey));
    }

    /**
     * 退回用的短名：取键名最后一个 {@code .} 之后的部分。
     *
     * <p>{@code block.createbigcannons.ap_shell} → {@code ap_shell}
     */
    public static String shortName(String languageKey) {
        int dot = languageKey.lastIndexOf('.');
        return dot >= 0 ? languageKey.substring(dot + 1) : languageKey;
    }

    /** 统一用 {@link Locale#ROOT} 格式化数字 —— 别让某些地区的逗号小数点把 {@code %.1f} 弄出花来。 */
    public static String num(String format, double value) {
        return String.format(Locale.ROOT, format, value);
    }

    /**
     * 角度：<b>整数就写整数，带小数就写一位</b>。
     *
     * <p>⚠️ <b>不能一律 {@code %.0f}</b>：炮座仰角上限被抬到 89.9° 之后，{@code %.0f} 会把它显示成
     * 「90°」—— 而 90° 意味着炮口垂直朝上、开炮砸自己头上。玩家看一眼就会以为模组算错了
     * （2026-09-21 用户实际指出）。
     *
     * <p>也<b>不能一律 {@code %.1f}</b>：CBC 原版的 −30° / 60° 会变成「-30.0° / 60.0°」，
     * 平白多一位没有信息量的小数。
     */
    public static String deg(double value) {
        return Math.abs(value - Math.round(value)) < 0.05
                ? num("%.0f", value)
                : num("%.1f", value);
    }
}
