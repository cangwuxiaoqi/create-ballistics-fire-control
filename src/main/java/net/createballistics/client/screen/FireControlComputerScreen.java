package net.createballistics.client.screen;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.createballistics.CreateBallistics;
import net.createballistics.block.entity.FireControlComputerBlockEntity;
import net.createballistics.block.entity.FireControlCoordinatorBlockEntity;
import net.createballistics.block.entity.FireControlNodeBlockEntity;
import net.createballistics.ballistic.FireMode;
import net.createballistics.net.ComputerActionC2SPacket;
import net.createballistics.util.PosDisplay;
import net.createballistics.util.StageText;
import net.createballistics.util.Texts;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 火控计算机界面 —— 手动输入打击坐标、下达、开火。
 *
 * <h2>只有两个按键</h2>
 * <ol>
 *   <li><b>确认坐标并开始计算</b> —— 把输入栏里的坐标记下来并下发全网，各炮自己解算；</li>
 *   <li><b>开火！</b></li>
 * </ol>
 * 刻意不留"视线落点""我的位置"之类 —— 那些取巧的输入会让调试时无法确定到底打的是哪一点。
 * <b>只认手输坐标</b>，这样每次试验的目标点都是确定的。
 *
 * <p>输入栏做成聊天框那样一条长条，接受 {@code x y z} / {@code x,y,z} 两种写法，
 * <b>按回车等同于点按钮</b>。
 *
 * <h2>计算过程为什么显示"就位 X / M"而不是进度条</h2>
 *
 * 诸元是<b>每门炮各自解算</b>的，计算机不下发角度、也无从知道每一门的中间过程。它能诚实汇报的
 * 是"有多少门已经转到位"—— 这个数在动，就说明计算在推进。
 */
public class FireControlComputerScreen extends Screen {

    private static final ResourceLocation PANEL = CreateBallistics.id("textures/gui/panel/computer.png");

    public static final int W = 560;
    public static final int H = 300;
    public static final float TEXT_SCALE = 1.15F;
    public static final int LINE_H = 12;

    // ---- 版面（2026-09-24 重做；**必须与 tools/gen_gui.ps1 里画的井一一对应**）----
    //
    // 为什么按键挪到底部：原来那排竖在右侧的 6 个井占掉 174 像素宽，左边文字区只剩约 370，
    // "打不着的炮"一旦逐门点名就横向溢出（当时只能临时砍成"前 3 门 + …等 N 门"）。挪到底部之后
    // 文字区拿到整幅 560 宽 —— 汇总行才排得开。
    //
    // ⚠️ 贴图上画了几个井，这里就必须有几颗按钮：画了井不放按钮，玩家会去点它。
    //    当前是 **6 颗**（确认 / 清除 / 弹道 / 火力 / 参数 / 开火）+ 1 个输入框。

    /** 坐标输入框 —— 贴图上的一个凹槽，按钮以外**唯一的**输入控件。 */
    private static final int INPUT_X = 16;
    private static final int INPUT_Y = 222;
    private static final int INPUT_W = 360;
    private static final int INPUT_H = 22;

    private static final int BTN_H = 22;

    /** 第一行：输入框右边的「确认坐标并开始计算」。 */
    private static final int BTN_COMMIT_X = 384;
    private static final int BTN_COMMIT_Y = 222;
    private static final int BTN_COMMIT_W = 160;

    /** 第二行：清除 / 弹道 / 火力 / 参数（四颗并排）。 */
    private static final int BTN_ROW2_Y = 248;
    private static final int BTN_CLEAR_X = 16;
    private static final int BTN_CLEAR_W = 110;
    private static final int BTN_ARC_X = 132;
    private static final int BTN_ARC_W = 132;
    private static final int BTN_FIRE_MODE_X = 272;
    private static final int BTN_FIRE_MODE_W = 132;
    private static final int BTN_PARAM_X = 412;
    private static final int BTN_PARAM_W = 132;

    /** 第三行：**开火！** —— 整幅宽，最显眼。 */
    private static final int BTN_FIRE_X = 16;
    private static final int BTN_FIRE_Y = 274;
    private static final int BTN_FIRE_W = 528;

    // ---- 正文各块的**固定锚点**（面板像素）----
    //
    // ⚠️ 刻意**不用"一路 ty += 累加"**：中间那块「无法射击」是**按需出现**的
    //    （有问题的协调器才占行，最多 4 行）—— 靠累加的话，它一出现就会把下面所有内容
    //    整体往下推，玩家每次打开看到的版面都在跳。固定锚点等于**提前把那 4 行预留出来**
    //    （用户 2026-09-24 的要求）：没有问题时空着，有问题时刚好填进去。
    private static final int Y_TITLE = 12;
    /** 「协调器 x / x（已达上限）」。 */
    private static final int Y_NET = 36;
    /** 协调器列表（一行两个，最多 2 行）—— 紧跟在那行计数下面。 */
    private static final int Y_COORD_LIST = 48;
    /** 「本级炮位 x　　已就位 y / x」—— 排在协调器列表之后。 */
    private static final int Y_NET2 = 72;
    /** 「无法射击」的表头。 */
    private static final int Y_UNREACH_HEAD = 84;
    /** 「无法射击」每个协调器一行的起始 y，往下每行 +LINE_H。 */
    private static final int Y_UNREACH_FIRST = 96;
    /** 预留几个槽 —— 协调器上限就是 4，所以永远最多 4 行。 */
    private static final int Y_UNREACH_SLOTS = 4;
    private static final int Y_TARGET = 156;
    private static final int Y_STAGE = 168;

    private static final int TITLE = 0xFFFFD75E;
    private static final int VALUE = 0xFFF2F2F2;
    private static final int MUTED = 0xFFA8A8B4;
    private static final int BAD = 0xFFFF7A7A;
    private static final int GOOD = 0xFF7CFF7C;
    private static final int WARN = 0xFFFFC46B;

    private final BlockPos pos;
    private FireControlComputerBlockEntity be;
    private EditBox coordinate;
    /** 「开火！」按钮 —— 每帧按"就位了几门炮"改写它的可点状态，见 {@link #refreshFireButton()}。 */
    private PanelButton fireButton;
    /**
     * 「弹道」按钮 —— 点一下切下一个模式（自动 → 低伸 → 高抛）。
     *
     * <p>标签每帧按当前模式改写，见 {@link #refreshArcButton()}。做成<b>循环按钮而不是三个单选</b>，
     * 是因为面板上只剩这一个空井 —— 三个单选按钮放不下（用户 2026-09-20 拍板走这条路）。
     */
    private PanelButton arcButton;
    /** 「火力模式」按钮 —— 同样是循环按钮（齐射 → 同时弹着 → 轮射），标签每帧按当前模式改写。 */
    private PanelButton fireModeButton;
    /**
     * 「火力模式参数」按钮 —— <b>轮射间隔与覆盖半径共用这一个</b>，标签跟着当前模式走。
     *
     * <p>点它时发哪个包也由当前模式决定（在 onClick 里读，而不是建按钮时定死）。
     */
    private PanelButton paramButton;

    private int originX;
    private int originY;

    public FireControlComputerScreen(BlockPos pos) {
        super(Component.translatable("block.create_ballistics.fire_control_computer"));
        this.pos = pos;
    }

    private int px(int panelX) {
        return originX + panelX;
    }

    private int py(int panelY) {
        return originY + panelY;
    }

    // ---------------------------------------------------------------- 初始化

    @Override
    protected void init() {
        super.init();
        originX = (width - W) / 2;
        originY = (height - H) / 2;

        if (minecraft != null && minecraft.level != null
                && minecraft.level.getBlockEntity(pos) instanceof FireControlComputerBlockEntity found) {
            be = found;
        }

        // ⚠️ 位置**刻意偏移 (+4, +7)**，不要图省事直接对齐凹槽：
        //    EditBox 关掉边框之后（setBordered(false)），它把文字的位置从
        //    「右缩 4 + 垂直居中」改成了「直接画在控件左上角」——
        //    源码：`int j = bordered ? getX() + 4 : getX();`
        //          `int k = bordered ? getY() + (height - 8) / 2 : getY();`
        //    后果就是数字贴着凹槽上沿、看着像跑出框了（2026-09-24 用户实测截图）。
        //    ⇒ 既然要保留贴图上的琥珀凹槽当边框，就得**自己把这 (+4, +7) 补回来**：
        //      +7 = (22 - 8) / 2，22 是凹槽高度 —— **凹槽改高的话这个数要跟着改**。
        //      （提示字、光标、选区高亮也都用同一对 j/k，所以补这一处就全对了。）
        coordinate = new EditBox(font,
                px(INPUT_X + 4), py(INPUT_Y + 7), INPUT_W - 8, INPUT_H - 14,
                Component.translatable("gui.create_ballistics.coord_hint"));
        coordinate.setBordered(false);
        coordinate.setMaxLength(64);
        coordinate.setHint(Component.translatable("gui.create_ballistics.coord_hint"));
        if (be != null && be.hasTarget()) {
            coordinate.setValue(String.format(Locale.ROOT, "%.0f %.0f %.0f",
                    be.getTargetX(), be.getTargetY(), be.getTargetZ()));
        }
        addRenderableWidget(coordinate);
        // ⚠️ **刻意不 setInitialFocus(coordinate)**：一打开界面就抢焦点的话，
        //    输入框会立刻进入"亮着 + 显示光标"的状态，看着像玩家已经点进去了
        //    （2026-09-24 用户要求：右键打开时不要自动点输入框）。
        //    想输入就自己点一下 —— 回车提交那条路不受影响。

        addRenderableWidget(new PanelButton(px(BTN_COMMIT_X), py(BTN_COMMIT_Y), BTN_COMMIT_W, BTN_H,
                Component.translatable("gui.create_ballistics.btn_commit"), b -> commit()));
        fireButton = new PanelButton(px(BTN_FIRE_X), py(BTN_FIRE_Y), BTN_FIRE_W, BTN_H,
                Component.translatable("gui.create_ballistics.btn_fire"),
                b -> send(ComputerActionC2SPacket.FIELD_FIRE_ALL, 0, 0, 0, 0));
        addRenderableWidget(fireButton);
        addRenderableWidget(new PanelButton(px(BTN_CLEAR_X), py(BTN_ROW2_Y), BTN_CLEAR_W, BTN_H,
                Component.translatable("gui.create_ballistics.btn_clear"), b -> clearTarget()));
        arcButton = new PanelButton(px(BTN_ARC_X), py(BTN_ROW2_Y), BTN_ARC_W, BTN_H,
                arcLabel(), b -> send(ComputerActionC2SPacket.FIELD_CYCLE_ARC_MODE, 0, 0, 0, 0));
        addRenderableWidget(arcButton);
        fireModeButton = new PanelButton(px(BTN_FIRE_MODE_X), py(BTN_ROW2_Y), BTN_FIRE_MODE_W, BTN_H,
                fireModeLabel(), b -> send(ComputerActionC2SPacket.FIELD_CYCLE_FIRE_MODE, 0, 0, 0, 0));
        addRenderableWidget(fireModeButton);
        paramButton = new PanelButton(px(BTN_PARAM_X), py(BTN_ROW2_Y), BTN_PARAM_W, BTN_H, paramLabel(),
                b -> cycleParam());
        addRenderableWidget(paramButton);
    }

    /**
     * 参数按钮的标签 —— <b>跟着当前火力模式走</b>。
     *
     * <p>轮射显示间隔、范围覆盖显示半径；两者都不适用时空串（那时按钮本来也是隐藏的）。
     */
    private Component paramLabel() {
        return be == null
                ? Component.empty()
                : ControlLabels.fireParam(be.getFireMode(), be.getRollingIntervalTicks(),
                        be.getCoverageRadius());
    }

    /**
     * 点参数按钮 —— <b>发哪个包由当前模式决定</b>。
     *
     * <p>⚠️ 不能在建按钮时把 field 定死：那样切了模式这个按钮还发原来那个包，
     * 点了没反应（而且是在另一个参数上乱改）。
     */
    private void cycleParam() {
        if (be == null) {
            return;
        }
        send(switch (be.getFireMode()) {
            case ROLLING -> ComputerActionC2SPacket.FIELD_CYCLE_INTERVAL;
            case COVERAGE -> ComputerActionC2SPacket.FIELD_CYCLE_COVERAGE_RADIUS;
            default -> ComputerActionC2SPacket.FIELD_CYCLE_INTERVAL;   // 兜底；那时按钮是隐藏的
        }, 0, 0, 0, 0);
    }

    /** 按钮标签："火力模式：齐射" —— 实现在 {@link ControlLabels}，与指示器界面共用同一份。 */
    private Component fireModeLabel() {
        return ControlLabels.fireMode(be == null ? null : be.getFireMode());
    }

    /** 按钮标签："弹道：自动" —— 实现在 {@link ControlLabels}，与指示器界面共用同一份。 */
    private Component arcLabel() {
        return ControlLabels.arcMode(be == null ? null : be.getArcMode());
    }

    /** 清空输入栏并让全网解除目标 —— 各节点会把炮座交还给 CBC。 */
    private void clearTarget() {
        coordinate.setValue("");
        send(ComputerActionC2SPacket.FIELD_CLEAR_TARGET, 0, 0, 0, 0);
    }

    /** 回车 = 点「确认坐标并开始计算」。 */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) {   // Enter / 小键盘 Enter
            commit();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ---------------------------------------------------------------- 下达

    private void commit() {
        double[] xyz = parseCoordinate(coordinate.getValue());
        if (xyz == null) {
            return;   // 解析不了就不发，免得把目标打成 (0,0,0)
        }
        send(ComputerActionC2SPacket.FIELD_TARGET, 0, xyz[0], xyz[1], xyz[2]);
        send(ComputerActionC2SPacket.FIELD_LAY_TARGET, 0, 0, 0, 0);
    }

    /** 接受 {@code x y z} 或 {@code x,y,z}。解析不了返回 null。 */
    private static double[] parseCoordinate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.trim().split("[,\\s]+");
        if (parts.length < 3) {
            return null;
        }
        try {
            return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void send(byte field, double value, double x, double y, double z) {
        PacketDistributor.sendToServer(new ComputerActionC2SPacket(pos, field, value, x, y, z));
    }

    // ---------------------------------------------------------------- 渲染

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 必须在 super.render 之前刷新 —— 否则按钮这一帧画的还是上一帧的状态
        refreshFireButton();
        refreshArcButton();
        refreshFireModeButton();

        // ⚠️⚠️ **顺序要紧：面板是不透明的（实测整幅 A=255），必须先铺底、再画按钮。**
        //     原来这两句是反的（先 super.render 再盖面板）⇒ 按钮的**文字与悬停高亮
        //     全被面板贴图压掉**，玩家看到的是一排空框、"按了没反应"（2026-09-26 大包实测报的）。
        //     指示器那个界面当初就是按正确顺序写的（见 ArtilleryIndicatorScreen 里同一句注释），
        //     这两个机器界面在 2026-09-24 重做版面时漏了。
        graphics.blit(PANEL, originX, originY, 0.0F, 0.0F, W, H, W, H);

        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.pose().pushPose();
        graphics.pose().translate(originX, originY, 0.0F);
        graphics.pose().scale(TEXT_SCALE, TEXT_SCALE, 1.0F);
        renderValues(graphics);
        graphics.pose().popPose();
    }

    /**
     * 「开火！」按钮只在<b>至少有一门炮已就位</b>时可点。
     *
     * <p>为什么不干脆让它永远可点、由服务端忽略这次操作：那样玩家按了没反应，
     * 却看不出是"没人就位"还是"模组坏了"。置灰是让按钮自己说实话。
     *
     * <p>为什么不到"全部就位"才允许：多炮时只要有一门炮解不出诸元（超射程、没装填），
     * 按钮就会永久禁用 —— 玩家反而被困死。<b>就位几门打几门</b>更实用，
     * 未就位的炮由 {@code FireControlNodeBlockEntity.fire()} 自己挡下来。
     */
    private void refreshFireButton() {
        if (fireButton == null) {
            return;
        }
        fireButton.active = be != null && be.hasTarget() && be.getReadyCount() > 0;
    }

    /**
     * 弹道按钮的标签跟着当前模式走。
     *
     * <p>⚠️ 每帧改写是<b>必须的</b>：模式是从服务端同步过来的，玩家点完按钮这一帧
     * 客户端那份副本还是旧值，下一帧才更新 —— 不每帧刷的话按钮上的字会永远停在
     * "自动"（或者更糟，停在两次点击之前的那个值）。
     */
    private void refreshArcButton() {
        if (arcButton == null) {
            return;
        }
        arcButton.setMessage(arcLabel());
        arcButton.active = be != null;
    }

    /** 火力模式按钮的标签同样每帧改写（模式从服务端同步过来，下一帧才是新值）。 */
    private void refreshFireModeButton() {
        if (fireModeButton == null) {
            return;
        }
        // ⚠️⚠️ **这颗按钮永远可点、而且永远显示"当前模式"** —— 2026-09-25 的教训：
        //    我一度在"炮不够 2 门"时把它置灰 + 把标签换成"需 ≥2 门炮"，结果**玩家被锁死**：
        //    炮不够时本来就不该再切进去，可按钮一禁，**连切回齐射都点不动了**（用户实测报的）。
        //    ⇒ 规矩：**"不许进入"不能用"不许操作"来表达**。现在改成：
        //      · 服务端切模式时**跳过**炮不够的那些（见 cycleFireMode）⇒ 进不去 ✓
        //      · 按钮照旧可点 ⇒ **随时切得回来** ✓
        //      · 真按了却没变化**才**给一句提示（在包处理器里，那儿才有 player）✓
        fireModeButton.setMessage(fireModeLabel());
        fireModeButton.active = be != null;

        // 参数按钮：轮射 / 范围覆盖各有自己的参数，**共用一个井**；
        // 别的模式（齐射 / 同时弹着）没有参数，整个藏起来，免得玩家以为它有什么用。
        if (paramButton != null) {
            FireMode mode = be == null ? FireMode.SALVO : be.getFireMode();
            boolean hasParam = ControlLabels.hasParam(mode);
            paramButton.setMessage(paramLabel());
            paramButton.visible = hasParam;
            paramButton.active = hasParam;
        }
    }

    /** 面板像素 → 正文坐标系（正文整体按 {@link #TEXT_SCALE} 缩放，所以要先除回去）。 */
    private static int at(int panelPx) {
        return Math.round(panelPx / TEXT_SCALE);
    }

    private void renderValues(GuiGraphics graphics) {
        int tx = Math.round(16 / TEXT_SCALE);
        int ty = at(Y_TITLE);

        graphics.drawString(font, title, tx, ty, TITLE);

        if (be == null) {
            graphics.drawString(font, Component.translatable("gui.create_ballistics.no_data"), tx, at(Y_NET), BAD);
            return;
        }

        // ---- 转速：**只在不足时才出现**，而且画在**标题那一行的右边** ----
        // 平时不占地方；一旦没接轴/太慢就直说，否则界面只会显示"扫不到东西"、无从诊断。
        // ⚠️ 刻意**不占自己的一行**：一占行，它出现时就会把下面所有内容推下去 ——
        //    和「无法射击」那块一个道理（见 Y_* 常量的说明）。
        //    文案用的是**计算机专用的**那条键：原来共用协调器那条，写着"没有扫描任何炮位"，
        //    而计算机根本不扫描炮位（它是"算不出诸元"）—— 已在文案体检里拆开。
        if (!be.hasRotationPower()) {
            graphics.drawString(font, Component.translatable("gui.create_ballistics.power_short_computer",
                            Texts.num("%.0f", Math.abs(be.getSpeed())),
                            Texts.num("%.0f", be.requiredRpm())).getString(),
                    tx + font.width(title) + 16, ty, BAD);
        }
        ty = at(Y_NET);

        // ---- 协调器：一行计数 ----
        // ⚠️ 「4 / 4」的位宽要按上限（个位数）留够，「（已达上限）」接在**同一行**末尾；
        //    单独占一行会把下面的汇总整体往下推，而这块的纵向余量是按行算的。
        int coordinators = be.getCoordinatorCount();
        int maxCoordinators = FireControlComputerBlockEntity.MAX_COORDINATORS;
        ty = line(graphics, tx, ty, "gui.create_ballistics.coordinators",
                coordinators + " / " + maxCoordinators,
                coordinators == 0 ? BAD : (coordinators >= maxCoordinators ? WARN : VALUE));
        if (coordinators >= maxCoordinators) {
            String cLabel = Component.translatable("gui.create_ballistics.coordinators").getString() + ": ";
            String cValue = coordinators + " / " + maxCoordinators;
            graphics.drawString(font, Component.translatable("gui.create_ballistics.at_limit").getString(),
                    tx + font.width(cLabel) + font.width(cValue) + 6, ty - LINE_H, WARN);
        }

        // ---- 协调器列表：一行两个（#N + 坐标 + 炮位 x / y）----
        // ⭐ 2026-09-24 用户把版式调成了：**先列协调器、再说总炮数** ——
        //    所以列表紧跟上面那行计数，而「本级炮位」被挪到列表之后。
        // 一行两个是量出来的：`coordinator_entry` 约 130 像素，两个加间距约 270，
        // 文字区有 528 ⇒ 一排放两个还有富余。
        ty = at(Y_COORD_LIST);
        List<BlockPos> coordinatorList = be.getCoordinators();
        if (!coordinatorList.isEmpty()) {
            int x = tx;
            int perRow = 0;
            int index = 1;
            for (BlockPos p : coordinatorList) {
                FireControlCoordinatorBlockEntity coordinator = minecraft != null && minecraft.level != null
                        && minecraft.level.getBlockEntity(p) instanceof FireControlCoordinatorBlockEntity c
                        ? c : null;
                // ⚠️ 显示用坐标走 PosDisplay（航空学物理结构上是图幅坐标）；`p` 本身保持原坐标查方块实体。
                BlockPos shownP = PosDisplay.of(minecraft == null ? null : minecraft.level, p);

                String row;
                int color;
                if (coordinator == null) {
                    row = Component.translatable("gui.create_ballistics.coordinator_unloaded",
                            index, shownP.getX(), shownP.getY(), shownP.getZ()).getString();
                    color = MUTED;
                } else {
                    row = Component.translatable("gui.create_ballistics.coordinator_entry",
                            index, shownP.getX(), shownP.getY(), shownP.getZ(),
                            coordinator.getNodeCount(), FireControlCoordinatorBlockEntity.MAX_NODES).getString();
                    // 登记在册、但对方没把我们记成它的上级 —— 两边状态不一致，值得标出来
                    color = coordinator.isOnline() ? VALUE : WARN;
                }
                graphics.drawString(font, row, x, ty, color);
                x += font.width(row) + 12;
                index++;
                if (++perRow == 2) {
                    perRow = 0;
                    x = tx;
                    ty += LINE_H;
                }
            }
            if (perRow != 0) {
                ty += LINE_H;
            }
        }

        // ---- 总数：本级炮位 + 已就位 ----
        int cannons = be.getCannonCount();
        int ready = be.getReadyCount();
        int unsolvable = be.getUnsolvableCount();
        // 「本级炮位」与「已就位」并成一行 —— 纵向余量按行算，省一行是一行
        ty = at(Y_NET2);
        String nodesLabel = Component.translatable("gui.create_ballistics.nodes").getString() + ": ";
        graphics.drawString(font, nodesLabel, tx, ty, MUTED);
        int nx = tx + font.width(nodesLabel);
        String cannonsText = String.valueOf(cannons);
        graphics.drawString(font, cannonsText, nx, ty, cannons == 0 ? BAD : VALUE);
        String readyLabel = "    " + Component.translatable("gui.create_ballistics.ready").getString() + ": ";
        int rx = nx + font.width(cannonsText);
        graphics.drawString(font, readyLabel, rx, ty, MUTED);
        graphics.drawString(font, ready + " / " + cannons, rx + font.width(readyLabel), ty,
                cannons > 0 && ready == cannons ? GOOD : (unsolvable > 0 ? WARN : MUTED));
        ty += LINE_H;

        // ---- 无法射击：**按协调器汇总**（2026-09-24 用户定的版式）-------------------
        // 原来是把打不着的炮逐门点名（「#1协调器的#3炮」…），炮一多就横向溢出，
        // 当时只能临时砍成「前 3 门 + …等 N 门」。现在改成**按协调器汇总**：
        // 行数 = 有问题的协调器个数 ≤ 4（协调器上限就是 4）
        // ⇒ 再多的炮打不着，这一块的高度都有界，不会挤占别的行。
        // 至于**为什么**打不着，玩家照编号去那台协调器的界面看（那边逐门列出真实原因）。
        // ⚠️ **只有真有炮打不着时才出现**（用户 2026-09-24 明确）：一切正常时这几行是**留白**，
        //    不写"无"之类的字 —— 版面靠下面的 Y_* 锚点保持稳定，不会因为这块有无而跳动。
        //    **一个协调器一行、按 #1 → #4 的顺序**排。
        // ⚠️⚠️ 2026-09-26：**文案一字不改**，但口径变宽了 —— 除"解不出来"之外，
        //    "**炮塔转速跟不上目标**"（P3）也算本轮不参与（两者表现完全一样：isAimed 永不成立）。
        //    玩家照编号去协调器看，"转速不够"那条原因就写在那边逐门的状态列里。
        Map<Integer, Integer> unreachable = be.notParticipatingByCoordinator();
        if (!unreachable.isEmpty()) {
            int uy = at(Y_UNREACH_HEAD);
            graphics.drawString(font,
                    Component.translatable("gui.create_ballistics.unreachable_header").getString(), tx, uy, BAD);
            uy = at(Y_UNREACH_FIRST);
            int slots = 0;
            // 汇总表自己就是 #1 → #4 的顺序（LinkedHashMap + 序号递增遍历），照序画即可
            for (Map.Entry<Integer, Integer> entry : unreachable.entrySet()) {
                if (slots >= Y_UNREACH_SLOTS) {
                    break;
                }
                graphics.drawString(font, Component.translatable("gui.create_ballistics.unreachable_entry",
                        entry.getKey(), entry.getValue()).getString(), tx, uy, BAD);
                uy += LINE_H;
                slots++;
            }
        }
        // 预留的那 4 行到此为止 —— 下面从固定锚点继续，与上面这块的多少无关

        // ---- 目标 + 已绑定火炮指示器：并成一行 ----
        // 两件事都很短，各占一行会把最后那点纵向余量吃光（本面板的余量是按行算的）。
        ty = at(Y_TARGET);
        String targetLabel = Component.translatable("gui.create_ballistics.target").getString() + ": ";
        graphics.drawString(font, targetLabel, tx, ty, MUTED);
        int cx = tx + font.width(targetLabel);
        String targetText = be.hasTarget()
                ? String.format(Locale.ROOT, "[%.0f, %.0f, %.0f]",
                        be.getTargetX(), be.getTargetY(), be.getTargetZ())
                : Component.translatable("gui.create_ballistics.no_target").getString();
        graphics.drawString(font, targetText, cx, ty, be.hasTarget() ? GOOD : BAD);
        cx += font.width(targetText) + 20;

        String indLabel = Component.translatable("gui.create_ballistics.indicators").getString() + ": ";
        graphics.drawString(font, indLabel, cx, ty, MUTED);
        graphics.drawString(font, be.indicatorSummary().getString(), cx + font.width(indLabel), ty,
                be.hasIndicator() ? VALUE : MUTED);
        ty += LINE_H;

        // ---- 计算过程：标签与内容并成一行（省下的那一行留给上面的汇总）----
        ty = at(Y_STAGE);
        String stageLabel = Component.translatable("gui.create_ballistics.stage").getString() + ": ";
        graphics.drawString(font, stageLabel, tx, ty, MUTED);
        graphics.drawString(font, stageText().getString(), tx + font.width(stageLabel), ty, stageColor());

        // 输入栏标签
        graphics.drawString(font, Component.translatable("gui.create_ballistics.coord_label").getString(),
                Math.round(INPUT_X / TEXT_SCALE), Math.round((INPUT_Y - 11) / TEXT_SCALE), MUTED);

        // ⚠️ 「火力模式至少要 2 门炮」这句**必须画在界面里**，光靠聊天栏不行 ——
        //    面板一开就正好把聊天栏盖住，玩家**根本看不到**（2026-09-25 用户实测报的）。
        //    位置：与「打击坐标」标签**同一行**、在整个面板里居中 —— 用户 2026-09-25 圈的就是这儿。
        //    那一行本来就只有一个短标签，所以这句只在需要时出现（炮够时不画），也不会挤到谁。
        if (!be.hasEnoughCannonsForFireModes()) {
            Component hint = Component.translatable("gui.create_ballistics.fire_mode_needs_two_hint");
            graphics.drawString(font, hint,
                    Math.max(0, (at(W) - font.width(hint)) / 2), at(211), WARN);
        }
    }

    /**
     * 「计算过程」那一行 —— <b>实现在 {@link StageText} 里，界面和护目镜共用同一份</b>。
     *
     * <p>⚠️ 用户 2026-09-22 定下的规矩：计算机界面上的「计算过程」**必须同步显示在护目镜上**。
     * 所以这里只是转发，**不要把逻辑搬回来** —— 这个项目里"同义文案写在两处、改了一处漏了另一处"
     * 已经栽过两次。
     */
    private Component stageText() {
        return StageText.of(be, minecraft == null ? null : minecraft.level);
    }

    private int stageColor() {
        return StageText.color(be, minecraft == null ? null : minecraft.level);
    }

    private int line(GuiGraphics graphics, int x, int y, String key, String value, int color) {
        String text = Component.translatable(key).getString() + ": ";
        graphics.drawString(font, text, x, y, MUTED);
        if (value != null) {
            graphics.drawString(font, value, x + font.width(text), y, color);
        } else {
            graphics.drawString(font, Component.translatable("gui.create_ballistics.no_target").getString(),
                    x + font.width(text), y, color);
        }
        return y + LINE_H;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
