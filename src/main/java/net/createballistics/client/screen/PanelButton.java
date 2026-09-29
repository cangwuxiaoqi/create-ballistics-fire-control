package net.createballistics.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * 面板上的按钮。
 *
 * <p>按钮的边框<b>已经画在背景贴图里</b>（琥珀色的“井”），所以这里只负责：
 * 悬停时叠一层淡淡的琥珀色高亮，再把文字居中画上去 —— 不画任何自己的边框，
 * 免得和贴图里的框叠成两层。
 *
 * <p><b>禁用态</b>（{@code active = false}）只把文字压暗，<b>不</b>往贴图的框里填灰底 ——
 * 那会盖住贴图上的琥珀色描边，看上去像块补丁。压暗 + 不响应悬停已经够说明"现在按不了"。
 */
public class PanelButton extends Button {

    private static final int HOVER_FILL = 0x38FFD75E;
    private static final int LABEL = 0xFFE6E6E6;
    private static final int LABEL_HOVER = 0xFFFFD75E;
    private static final int LABEL_DISABLED = 0xFF6E6E78;

    public PanelButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!active) {
            graphics.drawCenteredString(Minecraft.getInstance().font, getMessage(),
                    getX() + width / 2, getY() + (height - 8) / 2 + 1, LABEL_DISABLED);
            return;
        }
        boolean hot = isHoveredOrFocused();
        if (hot) {
            graphics.fill(getX(), getY(), getX() + width, getY() + height, HOVER_FILL);
        }
        graphics.drawCenteredString(Minecraft.getInstance().font, getMessage(),
                getX() + width / 2, getY() + (height - 8) / 2 + 1, hot ? LABEL_HOVER : LABEL);
    }
}
