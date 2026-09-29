package net.createballistics.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * <b>把一行字画进一段宽度里</b> —— 「计算过程」那种长短差很大的行全靠它。
 *
 * <h2>为什么要它（2026-09-24 用户实测报的）</h2>
 *
 * 「计算过程」最长的那种（{@code 计算完毕 21 / 40 已就位 · 19 门打不了（不参与本轮）}）
 * 在**火炮指示器的镜筒迭加层**和**无人机图传 HUD**上都放不下：
 * 前者直接跑出屏幕右边缘（尾巴被切掉），后者撞在变焦竖条上。
 *
 * <p>光缩字号不是个好答案 —— 要让那行塞进"右下角剩下的一点地方"，得缩到 <b>0.5 倍以下</b>，
 * 中文小到看不清。所以这里的顺序是：
 * <ol>
 *   <li>放得下 ⇒ **原样画**；</li>
 *   <li>放不下 ⇒ 先<b>往左长</b>（右端钉住不动，起点向左挪）—— 那一行左边通常是空的；</li>
 *   <li>长到头（{@code minLeft}，一般留 8 像素边距）还是放不下 ⇒ 才<b>整行按比例缩小</b>，
 *       下限 {@link #MIN_SCALE}（再小就不如让它溢出，至少还看得见几个字）。</li>
 * </ol>
 *
 * <p>⚠️ 右端是"钉死的"：调用方给的那个 {@code right} 一定要落在**真的不能再往右**的地方 ——
 * 图传那边是**变焦竖条的左边**（不是屏幕边缘，否则文字会压在竖条上），
 * 指示器那边因为这一行被抬到了快捷栏上方，才可以用屏幕右边缘。
 */
public final class FittedText {

    /** 缩放下限 —— 再小就不画了（宁可溢出也别缩成一团）。 */
    private static final float MIN_SCALE = 0.5F;

    private FittedText() {
    }

    /**
     * 画一行字，右端尽量贴住 {@code right}。
     *
     * @param preferredLeft 想从哪儿开始（放得下时就是它，例如"快捷栏右边一点"）
     * @param right         右端上限（**不能被越过**的地方）
     * @param minLeft       左端下限（一般给 8 像素边距）
     * @param scale         额外缩放（调用方自己的文字缩放，通常是 1.0）
     */
    public static void fitRight(GuiGraphics guiGraphics, Component text, int preferredLeft,
                                int right, int minLeft, int y, int color, boolean shadow, float scale) {
        var font = Minecraft.getInstance().font;
        int width = font.width(text);
        int left = Math.max(minLeft, Math.min(preferredLeft, right - width));
        float fit = 1.0F;
        int available = right - left;
        if (available < width) {
            fit = Math.max(MIN_SCALE, (float) available / width);
        }
        float total = scale * fit;
        if (total >= 0.999F) {
            guiGraphics.drawString(font, text, left, y, color, shadow);
            return;
        }
        // ⚠️ 缩放绕**原点**进行，所以必须先平移到起点，否则会缩放"屏幕左上角"那个距离
        var pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(left, y, 0.0F);
        pose.scale(total, total, 1.0F);
        guiGraphics.drawString(font, text, 0, 0, color, shadow);
        pose.popPose();
    }

    /** 同上，不额外缩放（{@code scale = 1}）。 */
    public static void fitRight(GuiGraphics guiGraphics, Component text, int preferredLeft,
                                int right, int minLeft, int y, int color, boolean shadow) {
        fitRight(guiGraphics, text, preferredLeft, right, minLeft, y, color, shadow, 1.0F);
    }

    /**
     * 画一行字、<b>水平居中</b>在 {@code [centerX − maxWidth/2, centerX + maxWidth/2]} 里；
     * 整行放不下就按比例缩（下限同 {@link #MIN_SCALE}）。
     *
     * <p>给"面板上的一句话提示"用（例如指示器 H 键面板底部那句）—— 那句话是要**居中**摆的，
     * 而 {@link #fitRight} 是"右端钉死"，两者不通用。
     */
    public static void fitCentered(GuiGraphics guiGraphics, Component text, int centerX, int y,
                                   int maxWidth, int color, boolean shadow, float scale) {
        var font = Minecraft.getInstance().font;
        int width = font.width(text);
        float fit = width > maxWidth ? Math.max(MIN_SCALE, (float) maxWidth / width) : 1.0F;
        float total = scale * fit;
        if (total >= 0.999F) {
            guiGraphics.drawString(font, text, centerX - width / 2, y, color, shadow);
            return;
        }
        // ⚠️ 缩放绕原点 ⇒ 先平移到"这一行的中心"，再以 -width/2 为起点画（等价于居中）
        var pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(centerX, y, 0.0F);
        pose.scale(total, total, 1.0F);
        guiGraphics.drawString(font, text, -width / 2, 0, color, shadow);
        pose.popPose();
    }
}
