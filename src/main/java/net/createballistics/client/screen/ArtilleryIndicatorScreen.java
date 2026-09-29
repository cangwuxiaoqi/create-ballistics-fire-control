package net.createballistics.client.screen;

import net.createballistics.CreateBallistics;
import net.createballistics.ballistic.ArcMode;
import net.createballistics.ballistic.FireMode;
import net.createballistics.client.FittedText;
import net.createballistics.client.ModKeyMappings;
import net.createballistics.item.ArtilleryIndicatorItem;
import net.createballistics.net.ComputerActionC2SPacket;
import net.createballistics.registry.ModItems;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * 火炮指示器的配置界面（<b>H 键</b>打开）—— 对应卓越前线的 {@code ArtilleryIndicatorScreen}。
 *
 * <h2>与卓越前线的差别（都是用户 2026-09-22 定的）</h2>
 *
 * <ol>
 *   <li>左边不是 X/Y/Z/R 输入框，而是<b>三颗循环按钮</b>：弹道模式 / 火力模式 / 参数。
 *       参数按钮只在轮射、范围覆盖时出现（其它模式没有这个参数）。<b>目标坐标一律由中键取点</b>，
 *       这里不提供输入 —— 本模组刻意不给"手填坐标"这条捷径（见 {@code ArtilleryIndicatorItem}）。</li>
 *   <li>右边的弹道图<b>点了不会切换模式</b>（卓越前线可以）。它只是"现在用的是哪条弹道"的图示：
 *       低伸→低伸图、高抛→高抛图、<b>自动→什么都不画</b>（那块黑框保持黑的）。
 *       切模式只能按左边那颗按钮。</li>
 *   <li>设置<b>存在于计算机上</b>，这里只是遥控器：按钮点下去发的是现成的
 *       {@code ComputerActionC2SPacket}；界面显示的是指示器身上那份<b>镜像</b>
 *       （见 {@code ModDataComponents} 的 MIRROR_*），所以离计算机很远也能用。</li>
 * </ol>
 */
@OnlyIn(Dist.CLIENT)
public class ArtilleryIndicatorScreen extends Screen {

    private static final ResourceLocation PANEL = CreateBallistics.id("textures/gui/indicator.png");
    /** 贴图总尺寸（面板与右侧那些小块都在这张图里）。 */
    private static final int TEX = 256;
    private static final int W = 176;
    private static final int H = 84;

    /** ✓ 按钮的贴图区（普通 / 悬停），48×15。 */
    private static final int BTN_U = 177;
    private static final int DONE_V = 0;
    private static final int DONE_V_HOVER = 16;
    /** 三颗设置按钮用的「干净按钮底」（把对勾抹掉后的副本），同样 48×15。 */
    private static final int WELL_TEX_V = 80;
    private static final int WELL_TEX_V_HOVER = 96;
    private static final int BTN_TEX_W = 48;
    private static final int BTN_TEX_H = 15;

    /** 弹道图的贴图区：高抛在 v=33（61×28）、低伸在 v=62（61×14）。 */
    private static final int CHART_U = 177;
    private static final int CHART_V_HIGH = 33;
    private static final int CHART_V_FLAT = 62;
    private static final int CHART_W = 61;
    private static final int CHART_HIGH_H = 28;
    private static final int CHART_FLAT_H = 14;

    /** 三颗按钮的井（面板内坐标）—— 按用户设计稿量的：左边缘 8、宽 80、高 15、三行 20/37/54。 */
    private static final int WELL_X = 8;
    private static final int WELL_W = 80;
    private static final int WELL_H = 15;
    /** 按钮上文字的缩放 —— 见 {@code WellButton.renderWidget}（面板窄，1.0 会溢出）。 */
    private static final float LABEL_SCALE = 0.8F;
    private static final int[] WELL_Y = {20, 37, 54};

    /** 弹道图那一小块（面板内坐标）—— 与贴图上那块黑框对齐，也用它判悬停。 */
    private static final int CHART_X = 99;
    private static final int CHART_Y = 19;
    private static final int CHART_HOVER_H = 30;

    /** ✓ 按钮的位置（面板内坐标）—— 同样是设计稿量出来的。 */
    private static final int DONE_X = 113;
    private static final int DONE_Y = 53;

    private static final int TITLE_COLOR = 0xFF404040;
    private static final int LABEL_COLOR = 0xFF404040;
    private static final int LABEL_DISABLED = 0xFF9A9A9A;

    private int leftPos;
    private int topPos;

    private Button arcButton;
    private Button fireButton;
    private Button paramButton;

    public ArtilleryIndicatorScreen() {
        super(Component.translatable("item.create_ballistics.artillery_indicator"));
    }

    // ------------------------------------------------------------------
    // 手上那把指示器
    // ------------------------------------------------------------------

    /** 手上拿着的指示器；换走了（或不在手上）返回 {@code null}。 */
    @Nullable
    private ItemStack held() {
        if (minecraft == null || minecraft.player == null) {
            return null;
        }
        ItemStack stack = minecraft.player.getMainHandItem();
        return stack.is(ModItems.ARTILLERY_INDICATOR.get()) ? stack : null;
    }

    /** 绑定的计算机坐标；没绑定返回 {@code null}。 */
    @Nullable
    private BlockPos computer() {
        ItemStack stack = held();
        return stack == null ? null : ArtilleryIndicatorItem.boundComputer(stack);
    }

    /** 当前火力模式（读镜像；读不到按齐射算）。 */
    private FireMode fireMode() {
        ItemStack stack = held();
        FireMode mode = stack == null ? null : ArtilleryIndicatorItem.mirroredFireMode(stack);
        return mode == null ? FireMode.SALVO : mode;
    }

    /** 当前弹道模式（读镜像；读不到按自动算）。 */
    private ArcMode arcMode() {
        ItemStack stack = held();
        ArcMode mode = stack == null ? null : ArtilleryIndicatorItem.mirroredArcMode(stack);
        return mode == null ? ArcMode.AUTO : mode;
    }

    // ------------------------------------------------------------------
    // 建界面
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        leftPos = (width - W) / 2;
        topPos = (height - H) / 2;

        arcButton = addRenderableWidget(new WellButton(0, b -> send(ComputerActionC2SPacket.FIELD_CYCLE_ARC_MODE)));
        fireButton = addRenderableWidget(new WellButton(1, b -> send(ComputerActionC2SPacket.FIELD_CYCLE_FIRE_MODE)));
        paramButton = addRenderableWidget(new WellButton(2, b -> send(paramField())));

        addRenderableWidget(new DoneButton());
    }

    /**
     * 参数按钮点下去发哪个包 —— <b>由当前火力模式决定</b>。
     *
     * <p>⚠️ 和计算机界面同一条规矩：<b>不能在点之前把包定死</b>，否则切了模式它还发原来那个包，
     * 点了等于在另一个参数上乱改。
     */
    private byte paramField() {
        return fireMode() == FireMode.COVERAGE
                ? ComputerActionC2SPacket.FIELD_CYCLE_COVERAGE_RADIUS
                : ComputerActionC2SPacket.FIELD_CYCLE_INTERVAL;
    }

    private void send(byte field) {
        BlockPos computer = computer();
        if (computer != null) {
            PacketDistributor.sendToServer(new ComputerActionC2SPacket(computer, field, 0, 0, 0, 0));
        }
    }

    // ------------------------------------------------------------------
    // 每帧刷新
    // ------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        // 手上换成了别的东西就自己关掉 —— 和「监视器切走槽位就退出无人机视角」是一个道理
        if (held() == null) {
            onClose();
        }
    }

    /**
     * 按钮上的字<b>每帧刷新</b>。
     *
     * <p>⚠️ 必须每帧刷：设置是从服务端（经镜像）同步过来的，点完按钮这一帧客户端那份还是旧值，
     * 下一帧才更新 —— 不刷的话按钮上的字会永远停在上一次的值。
     */
    private void refreshButtons() {
        ItemStack stack = held();
        boolean bound = isBound();
        FireMode fire = fireMode();
        int param = stack == null ? 0 : ArtilleryIndicatorItem.mirroredParam(stack);

        arcButton.setMessage(ControlLabels.arcMode(arcMode()));
        arcButton.active = bound;

        // ⚠️⚠️ **这颗按钮永远可点、永远显示"当前模式"**（与计算机界面同一条规矩）：
        //    一度在"炮不够 2 门"时把它置灰 + 换成长提示，结果**玩家被锁死、切不回齐射**，
        //    而且指示器面板的按钮井只有 80 像素宽，那句提示**直接溢出**（2026-09-25 用户截图）。
        //    ⇒ "不许进入"由**服务端跳模式**来保证（见 cycleFireMode），不靠禁用按钮；
        //      真按了没变化才给提示（在包处理器里）。
        fireButton.setMessage(ControlLabels.fireMode(fire));
        fireButton.active = bound;

        boolean hasParam = ControlLabels.hasParam(fire);
        // 镜像里只有一个数：轮射时是间隔 tick、覆盖时是半径 —— 两处都传它，用哪个由模式决定
        paramButton.setMessage(ControlLabels.fireParam(fire, param, param));
        paramButton.visible = hasParam;
        paramButton.active = hasParam && bound;
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        refreshButtons();

        // 世界先糊一层，面板才看得清（但<b>不要</b>原版那层压暗的菜单底，见 renderBackground）
        renderBlurredBackground(partialTick);

        // 顺序要紧：面板是不透明的，必须先铺底、再画按钮（否则按钮会被盖住）
        graphics.blit(PANEL, leftPos, topPos, 0.0F, 0.0F, W, H, TEX, TEX);
        graphics.drawString(font, title, leftPos + 6, topPos + 6, TITLE_COLOR, false);

        super.render(graphics, mouseX, mouseY, partialTick);

        drawChart(graphics, mouseX, mouseY);

        // ⚠️ 没绑计算机时**必须把原因写出来**：三颗按钮这时是禁用的、点不动，
        //    只把字压暗的话玩家只会觉得"按钮坏了"（2026-09-22 用户实测反馈）。
        if (!isBound()) {
            graphics.drawCenteredString(font,
                    Component.translatable("item.create_ballistics.artillery_indicator.unbound"),
                    leftPos + W / 2, topPos + H + 6, 0xFFFF5555);
        }

        // ⚠️ 「至少需要 2 门炮才能切换火力模式」—— 画在**面板最底下那条横带**上。
        //    位置是用户 2026-09-25 在截图上圈出来的：三颗按钮与 ✓ 之下、整条都空着 ✓。
        //
        //    ⚠️⚠️ 为什么是这里而不是别处（同一天试过两处、都被用户否掉）：
        //      · **聊天栏**：计算机面板正好把它盖住 ⇒ 玩家看不到 ✗；
        //      · **举镜迭加层**：镜筒压在它上面 ⇒ "背景糊的，字也跟着糊" ✗。
        //    只有**面板自己身上**这一处是玩家真看得清的 ✓。
        //
        //    居中 + 按宽度缩放：这句话 15 个字，1.0 倍是 135 像素，而面板内宽只有 160 ——
        //    缩到 0.8 更从容，也给以后改文案留了余量 ✓。
        ItemStack hand = held();
        if (hand != null && ArtilleryIndicatorItem.boundComputer(hand) != null
                && ArtilleryIndicatorItem.mirroredCannonCount(hand) < 2) {
            FittedText.fitCentered(graphics,
                    Component.translatable("gui.create_ballistics.fire_mode_needs_two_hint"),
                    leftPos + W / 2, topPos + H - 12, W - 16, 0xFF9E2B2B, false, 0.8F);
        }
    }

    /** 手上这台指示器有没有绑上计算机（按钮能不能用就看它）。 */
    private boolean isBound() {
        ItemStack stack = held();
        return stack != null && ArtilleryIndicatorItem.boundComputer(stack) != null;
    }

    /** 画一行不带阴影的居中文字（面板上的文字都要用这个，理由见按钮里那条注释）。 */
    private void drawNoShadowCentered(GuiGraphics graphics, Component text, int centerX, int y, int color) {
        graphics.drawString(font, text, centerX - font.width(text) / 2, y, color, false);
    }

    /**
     * ⚠️ <b>必须覆写成空操作</b>，照卓越前线的做法。
     *
     * <p>{@code Screen.render} 的<b>第一件事</b>就是调 {@code renderBackground}，
     * 而默认实现会模糊一次画面、再压一层深色的「菜单底」。我们这里有两处会撞上它：
     * <ol>
     *   <li>面板是在 {@code super.render} <b>之前</b>铺的 ⇒ 那层深色底会盖在面板上，把它压暗；</li>
     *   <li>模糊是<b>就地糊整个帧缓冲</b>的 ⇒ 再糊一次会把面板和按钮一起糊花。</li>
     * </ol>
     * 所以模糊由 {@link #render} 自己在铺面板<b>之前</b>手动做一次，这里什么都不做。
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    /**
     * 右边的弹道图 —— <b>只是图示，不是控件</b>（点了不会切模式，这是用户明确要求的）。
     *
     * <p>画哪张完全跟着弹道模式走：低伸→低伸图、高抛→高抛图、<b>自动→不画</b>
     * （面板贴图上那块黑框本来就是黑的，不画就是空的）。
     */
    private void drawChart(GuiGraphics graphics, int mouseX, int mouseY) {
        ArcMode mode = arcMode();
        if (mode == ArcMode.FLAT) {
            graphics.blit(PANEL, leftPos + CHART_X, topPos + CHART_Y + (CHART_HIGH_H - CHART_FLAT_H),
                    CHART_W, CHART_FLAT_H, CHART_U, CHART_V_FLAT, CHART_W, CHART_FLAT_H, TEX, TEX);
        } else if (mode == ArcMode.HIGH) {
            graphics.blit(PANEL, leftPos + CHART_X, topPos + CHART_Y, CHART_W, CHART_HIGH_H,
                    CHART_U, CHART_V_HIGH, CHART_W, CHART_HIGH_H, TEX, TEX);
        } else {
            return;   // 自动：留黑
        }

        // 悬停提示（照卓越前线）：告诉玩家这张图是哪条弹道
        if (mouseX >= leftPos + CHART_X && mouseX <= leftPos + CHART_X + CHART_W
                && mouseY >= topPos + CHART_Y && mouseY <= topPos + CHART_Y + CHART_HOVER_H) {
            graphics.renderTooltip(font, Component.translatable(mode == ArcMode.FLAT
                    ? "gui.create_ballistics.chart.flat" : "gui.create_ballistics.chart.high"), mouseX, mouseY);
        }
    }

    /** H 键再按一次就关掉（和开关一个界面一样）。 */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (ModKeyMappings.OPEN_INDICATOR.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 遥控器界面，不该把世界暂停下来（和火控计算机界面一致）。 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------
    // 两颗按钮
    // ------------------------------------------------------------------

    /** 左边那三颗：浅灰底 + 深色字，悬停时换一块高亮底。 */
    private class WellButton extends Button {

        WellButton(int row, OnPress onPress) {
            super(leftPos + WELL_X, topPos + WELL_Y[row], WELL_W, WELL_H,
                    Component.empty(), onPress, DEFAULT_NARRATION);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            boolean hot = active && isHoveredOrFocused();
            graphics.blit(PANEL, getX(), getY(), getWidth(), getHeight(),
                    BTN_U, hot ? WELL_TEX_V_HOVER : WELL_TEX_V, BTN_TEX_W, BTN_TEX_H, TEX, TEX);
            // ⚠️ 最后那个 false = **不画阴影**。`drawCenteredString` 默认是带阴影的，
            //    那样按钮上的字和左上角标题（不带阴影）看起来是两种字体（2026-09-22 用户指出）。
            // ⚠️ 按钮井只有 {@code WELL_W = 80} 像素，而「火力模式：同时弹着」有 10 个汉字
            //    （= 90 像素）⇒ **1.0 倍会溢出到井外**（2026-09-25 用户截图指出）。
            //    整句缩到 {@link #LABEL_SCALE}，最长的标签也放得下（90 × 0.8 = 72 < 80）。
            //    ⚠️ 缩放绕**原点**进行，所以要先平移到文字该在的位置再 scale。
            var pose = graphics.pose();
            pose.pushPose();
            pose.translate(getX() + getWidth() / 2.0F, getY() + (getHeight() - 8) / 2.0F + 1.0F, 0.0F);
            pose.scale(LABEL_SCALE, LABEL_SCALE, 1.0F);
            drawNoShadowCentered(graphics, getMessage(), 0, 0, active ? LABEL_COLOR : LABEL_DISABLED);
            pose.popPose();
        }
    }

    /** 右边那颗 ✓（对勾已经画在贴图里了，这里不写字）。 */
    private class DoneButton extends Button {

        DoneButton() {
            super(leftPos + DONE_X, topPos + DONE_Y, BTN_TEX_W, BTN_TEX_H,
                    Component.empty(), b -> onClose(), DEFAULT_NARRATION);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.blit(PANEL, getX(), getY(), getWidth(), getHeight(),
                    BTN_U, isHoveredOrFocused() ? DONE_V_HOVER : DONE_V, BTN_TEX_W, BTN_TEX_H, TEX, TEX);
        }
    }
}
