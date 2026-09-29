package net.createballistics.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

/**
 * HUD 上少数几个<b>原版 {@code GuiGraphics} 画不出来</b>的几何图形。
 *
 * <p>目前只有圆环：准星那种"一圈细线"没法用 {@code fill()}（那是矩形）或
 * {@code blit()}（那要贴图）表达，只能用顶点缓冲自己拼。
 * 本类刻意做得极小、参数极少 —— 不学的图形不要提前加进来。
 */
public final class OverlayShapes {

    /** 蓄力圆环的两个半径（屏幕较短边的比例）—— 与卓越前线一致。 */
    public static final float CHARGE_OUTER = 0.07F;
    public static final float CHARGE_INNER = 0.052F;

    private OverlayShapes() {
    }

    /**
     * 蓄力圆环：深色底环（整圈）+ 白色进度弧，弧满一圈就是开火。
     *
     * <p><b>唯一实现</b> —— 火炮指示器的迭加层与<b>无人机图传 HUD</b> 都调它
     * （2026-09-23 用户要求：图传里也要看得到这圈读条，不然飞着的时候根本不知道
     * "按住左键到没到开火那一下"）。
     *
     * <p>⚠️ 它<b>不是</b>常显的准星 —— 2026-09-22 我第一版做成了"举镜时常显一个白圆环"，
     * 用户拿对比图指出那是<b>长按左键的蓄力进度</b>，而且常显的粗圆环正好糊在准星上挡视线。
     * 所以只有真的在蓄力（{@code progress > 0}）时才画。
     *
     * <p>⚠️ 调用方注意：本方法结尾会 {@code disableBlend()}，所以**要放在所有 {@code blit} 之后**
     * （{@code drawString} 自带渲染状态，不受影响；把 blit 排在它后面可能画成不带透明的）。
     */
    public static void chargeRing(GuiGraphics guiGraphics, float centerX, float centerY, float progress) {
        if (progress <= 0.0F) {
            return;
        }
        ring(guiGraphics, centerX, centerY, CHARGE_OUTER, CHARGE_INNER, 1.0F, 0.0F, 0.0F, 0.0F, 0.4F);
        ring(guiGraphics, centerX, centerY, CHARGE_OUTER, CHARGE_INNER, progress, 1.0F, 1.0F, 1.0F, 0.8F);
    }

    /**
     * 以 {@code (centerX, centerY)} 为心画一段圆环弧。
     *
     * <p>两个半径都是<b>屏宽/屏高较小者的比例</b>（不是像素）—— 这样换分辨率、
     * 换 GUI 缩放都不会变形。这个约定是从卓越前线的 {@code RenderHelper.renderCircularRing}
     * 照搬的（它的 {@code useRate} 分支）。
     *
     * <p>画法是 {@code TRIANGLE_STRIP}：每一步同时放<b>外圆</b>和<b>内圆</b>两个顶点，
     * 交替下去自然连成一圈环带 —— 比"内外两个圆分别画"少一半顶点，也不需要三角形索引。
     *
     * @param progress 画多少：1.0 = 整圈，0.5 = 半圈（蓄力进度就用它）
     * @param color    依次是 r / g / b / a，取值 0~1
     */
    public static void ring(GuiGraphics guiGraphics, float centerX, float centerY,
                            float outerRatio, float innerRatio, float progress, float... color) {
        if (progress <= 0.0F) {
            return;
        }
        float scale = Math.min(guiGraphics.guiWidth(), guiGraphics.guiHeight());
        float outer = outerRatio * scale;
        float inner = innerRatio * scale;
        float maxAngle = (float) (Math.PI * 2.0) * progress;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        // ⚠️ 这句决定了进度弧<b>从哪儿开始长</b>：不转的话起点在"3 点钟"（我第一版就是），
        //    转 −90° 之后起点落到"12 点钟"、顺时针长 —— 与卓越前线一致。
        //    2026-09-22 用户拿两张对比图指出来的；我当时把这句当成"整圈用不到的旋转"跳掉了，
        //    其实弧的起点恰恰由它决定。
        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        poseStack.rotateAround(Axis.ZP.rotationDegrees(-90.0F), centerX, centerY, 0.0F);

        Matrix4f matrix = poseStack.last().pose();
        BufferBuilder buffer = Tesselator.getInstance().begin(Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);

        // 2° 一步。圆的曲率肉眼分辨不出更细的步长，而顶点数只有它的 1/16。
        final float step = Mth.PI / 90.0F;
        int steps = (int) (180.0F * progress);
        for (int i = 0; i <= steps; i++) {
            // 最后一步夹到 maxAngle，弧的收尾才是干净的一条半径边（照卓越前线的写法）
            float angle = Math.min(i * step, maxAngle);
            float cos = Mth.cos(angle);
            float sin = Mth.sin(angle);
            buffer.addVertex(matrix, centerX + outer * cos, centerY + outer * sin, 0.0F)
                    .setColor(color[0], color[1], color[2], color[3]);
            buffer.addVertex(matrix, centerX + inner * cos, centerY + inner * sin, 0.0F)
                    .setColor(color[0], color[1], color[2], color[3]);
            if (angle >= maxAngle) {
                break;
            }
        }

        BufferUploader.drawWithShader(buffer.buildOrThrow());
        poseStack.popPose();
        RenderSystem.disableBlend();
    }
}
