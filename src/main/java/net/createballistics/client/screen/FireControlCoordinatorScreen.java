package net.createballistics.client.screen;

import java.util.List;
import java.util.Locale;
import net.createballistics.CreateBallistics;
import net.createballistics.block.entity.FireControlCoordinatorBlockEntity;
import net.createballistics.block.entity.FireControlNodeBlockEntity;
import net.createballistics.ballistic.FireMode;
import net.createballistics.util.PosDisplay;
import net.createballistics.util.Texts;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 火控协调器界面 —— <b>纯显示，没有任何按键</b>。
 *
 * <p>按分工：协调器只负责把火控节点整理进网络并汇报状态，目标坐标由火控计算机下达，
 * 所以这里不提供输入、也不提供操作。
 *
 * <h2>这里也是"为什么打不着"的<b>唯一</b>解释处（2026-09-24）</h2>
 *
 * 计算机面板不再逐门点名打不着的炮（一门一行会把版面挤爆），改成按协调器汇总：
 * 「#3 协调器有 2 门炮无法射击」—— 玩家照着编号来这台协调器看<b>原因</b>。
 * ⇒ 所以每门炮那一行的"状态"必须是**真实原因**，不能把"弹道被挡住"含糊成"超出射程"
 * （{@link FireControlNodeBlockEntity.ArcFail#shortKey()}，映射只此一份）。
 *
 * <h2>为什么要有「供电」那一行</h2>
 *
 * 协调器<b>自己没有转速就完全不扫描</b>（这是设计如此：没应力不干活）。于是"一门炮都识别不到"
 * 有两种完全不同的原因 —— 周围确实没有可用节点，或者协调器自己的转速不够。不把转速显示出来，
 * 这两种情况在界面上长得一模一样，没法排查。
 *
 * <h2>渲染顺序</h2>
 *
 * 先铺干净无字的背景图 {@code gui/panel/coordinator.png}（560×300，1:1），再逐条画文字。
 * 缩放绕原点进行，所以必须先平移到面板左上角。
 */
public class FireControlCoordinatorScreen extends Screen {

    private static final ResourceLocation PANEL = CreateBallistics.id("textures/gui/panel/coordinator.png");

    public static final int W = 560;
    public static final int H = 300;

    /**
     * 数据区文字放大倍率 —— <b>2026-09-24 从 1.15 降到 1.0</b>。
     *
     * <p>原因就是"炮位列表每门一行"：一行要塞下材质 / 弹种 / 装药 / 初速 / 射界 / 射程 / 飞行 / 状态，
     * 1.15 倍下最长的那些行（军用板岩合金 + 长弹种名）会顶到面板边框上。
     * 降到 1.0 之后最长那行约 440 像素，面板宽 560 ⇒ 留了余量给以后再加字段。
     */
    public static final float TEXT_SCALE = 1.0F;
    /**
     * 行高。
     *
     * <p>⚠️ <b>2026-09-24 从 12 加到 16</b>（用户实测摆了 10 门炮之后"下面还有好多空余地方"）。
     *
     * <p><b>为什么加的是行距而不是字号</b>：一门一行的内容**已经顶满宽度**了 ——
     * 实测最长那行（钢制 / 装药 4 包 / 极限 5049 格）约 436 像素，面板文字区 528，
     * 还要给「军用板岩合金」这类长材质名留约 36 像素 ⇒ 字号最多只能再放大约 5%，肉眼看不出来。
     * ⇒ <b>要填满竖向，能动的是行距</b>。
     *
     * <p>（真想把字放大，得先砍列 —— 比如去掉「极限」「飞行」两列能腾出约 80 像素，
     * 字号就能到 1.15~1.2。这一条等用户点头再说。）
     *
     * <p>10 门 × 16 = 160，加上上面那 7 行，最后一门落在 y≈276、字底约 285 —— 面板高 300，刚刚好。
     */
    public static final int LINE_H = 16;

    private static final int TITLE = 0xFFFFD75E;
    private static final int VALUE = 0xFFF2F2F2;
    private static final int MUTED = 0xFFA8A8B4;
    private static final int BAD = 0xFFFF7A7A;
    private static final int GOOD = 0xFF7CFF7C;
    private static final int WARN = 0xFFFFC46B;

    private final BlockPos pos;
    private FireControlCoordinatorBlockEntity be;

    private int originX;
    private int originY;

    public FireControlCoordinatorScreen(BlockPos pos) {
        super(Component.translatable("block.create_ballistics.fire_control_coordinator"));
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
                && minecraft.level.getBlockEntity(pos) instanceof FireControlCoordinatorBlockEntity found) {
            be = found;
        }
    }

    // ---------------------------------------------------------------- 渲染

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // ⚠️⚠️ **顺序要紧：面板不透明（实测 A=255），必须先铺底、再画按钮** ——
        //     反了的话按钮文字与悬停高亮会被面板整块压掉（2026-09-26 大包实测报的"按键失效"）。
        graphics.blit(PANEL, originX, originY, 0.0F, 0.0F, W, H, W, H);

        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.pose().pushPose();
        graphics.pose().translate(originX, originY, 0.0F);
        graphics.pose().scale(TEXT_SCALE, TEXT_SCALE, 1.0F);
        renderValues(graphics);
        graphics.pose().popPose();
    }

    private void renderValues(GuiGraphics graphics) {
        int tx = Math.round(16 / TEXT_SCALE);
        int ty = Math.round(12 / TEXT_SCALE);

        graphics.drawString(font, title, tx, ty, TITLE);
        ty += LINE_H * 2;

        if (be == null) {
            graphics.drawString(font, Component.translatable("gui.create_ballistics.no_data").getString(), tx, ty, BAD);
            return;
        }

        // ---- 上线到哪台火控计算机 ----
        // 这个关系是计算机扫描时写回的，不是"64 格内有没有计算机"的猜测。
        BlockPos master = be.getMasterPos();
        if (master != null) {
            // ⚠️ 坐标显示走 PosDisplay：在航空学物理结构上时它是图幅坐标（2896 万格那种），
            //    直接显示等于报了个假坐标。**只转显示**，master 本身保持原坐标。
            BlockPos shownMaster = PosDisplay.of(minecraft == null ? null : minecraft.level, master);
            ty = line(graphics, tx, ty, "gui.create_ballistics.master",
                    String.format(Locale.ROOT, "[%d, %d, %d]",
                            shownMaster.getX(), shownMaster.getY(), shownMaster.getZ()), GOOD);
        } else {
            // ⚠️ 这里**不能**再拿 master 当标签前缀：那个键现在是「已接入火控计算机」，
            // 接在「未接入…」前面会变成「已接入火控计算机: 未接入」—— 自相矛盾。
            // 未接入时直接把话说完整就行。
            graphics.drawString(font,
                    Component.translatable("gui.create_ballistics.master_none").getString(), tx, ty, WARN);
            ty += LINE_H;
        }

        // ---- 转速：**只在不足时才出现** ----
        // 平时不占地方；一旦没接轴/太慢就直说，否则"一门炮都扫不到"会变成无法诊断的黑盒。
        // 文案用**协调器专用**的那条键（"没有扫描任何炮位"只说协调器；
        // 计算机那边是"算不出射击诸元"，见 power_short_computer）。
        if (!be.hasRotationPower()) {
            graphics.drawString(font, Component.translatable("gui.create_ballistics.power_short_coordinator",
                    Texts.num("%.0f", Math.abs(be.getSpeed())),
                    Texts.num("%.0f", be.requiredRpm())).getString(), tx, ty, BAD);
            ty += LINE_H;
        }

        // ---- 登记数量与上限 ----
        // ⚠️ 「10 / 10」的位宽要留够（上限本身是两位数），「（已达上限）」接在**同一行**末尾；
        //    换行会把"已就位"那一行往下推、和下面列表挤在一起。
        int count = be.getNodeCount();
        int max = FireControlCoordinatorBlockEntity.MAX_NODES;
        ty = line(graphics, tx, ty, "gui.create_ballistics.nodes", count + " / " + max,
                count == 0 ? BAD : (count >= max ? WARN : VALUE));
        if (count >= max) {
            // 接在数值后面（同样的坐标算法，只是再往右挪一点点）
            String nodesLabel = Component.translatable("gui.create_ballistics.nodes").getString() + ": ";
            String countText = count + " / " + max;
            graphics.drawString(font, Component.translatable("gui.create_ballistics.at_limit").getString(),
                    tx + font.width(nodesLabel) + font.width(countText) + 6, ty - LINE_H, WARN);
        }

        ty = line(graphics, tx, ty, "gui.create_ballistics.ready",
                be.getReadyCount() + " / " + count,
                count > 0 && be.getReadyCount() == count ? GOOD : MUTED);

        // ---- 目标（只显示，输入在计算机那边）----
        if (be.hasTarget()) {
            ty = line(graphics, tx, ty, "gui.create_ballistics.target",
                    String.format(Locale.ROOT, "[%.0f, %.0f, %.0f]",
                            be.getTargetX(), be.getTargetY(), be.getTargetZ()), GOOD);
        } else {
            ty = line(graphics, tx, ty, "gui.create_ballistics.target", null, BAD);
        }
        ty += LINE_H / 2;

        // ---- 每门炮**一行**（2026-09-24：原来两行，一行能把可见门数翻倍）----
        List<BlockPos> nodes = be.getNodes();
        if (nodes.isEmpty()) {
            // 区分两种"扫不到"：范围内压根没有节点（距离/摆放），还是节点都在但不可用
            // （转速不足 / 火炮没组装）。光看「0 / 10」分不出是哪一种。
            int nearby = be.getNearbyNodeCount();
            int taken = be.getNearbyTakenCount();
            Component msg;
            if (taken > 0 && nearby == 0) {
                // 周围有炮、但**都归别人了** —— 这是第三种"扫不到"，说错话会把玩家引去查转速/组装
                msg = Component.translatable("gui.create_ballistics.nodes_all_taken",
                        FireControlCoordinatorBlockEntity.SCAN_RADIUS, taken);
            } else if (nearby > 0) {
                msg = Component.translatable("gui.create_ballistics.nodes_unusable",
                        FireControlCoordinatorBlockEntity.SCAN_RADIUS, nearby);
            } else {
                msg = Component.translatable("gui.create_ballistics.no_nodes",
                        FireControlCoordinatorBlockEntity.SCAN_RADIUS);
            }
            graphics.drawString(font, msg.getString(), tx, ty, MUTED);
            return;
        }

        graphics.drawString(font, Component.translatable("gui.create_ballistics.node_list").getString(), tx, ty, MUTED);
        ty += LINE_H;

        int index = 1;
        for (BlockPos p : nodes) {
            FireControlNodeBlockEntity node = minecraft != null && minecraft.level != null
                    && minecraft.level.getBlockEntity(p) instanceof FireControlNodeBlockEntity n ? n : null;
            // ⚠️ 显示用坐标走 PosDisplay（航空学物理结构上是图幅坐标）；`p` 本身保持原坐标查方块实体。
            BlockPos shownP = PosDisplay.of(minecraft == null ? null : minecraft.level, p);

            String row;
            int color;
            if (node == null) {
                row = String.format(Locale.ROOT, "  #%d  [%d, %d, %d]  %s", index,
                        shownP.getX(), shownP.getY(), shownP.getZ(),
                        Component.translatable("gui.create_ballistics.node_unloaded").getString());
                color = MUTED;
            } else {
                // 状态：判断顺序与原来一致（**先看有没有目标** → 再看解不解得出来 → 最后才是"在转"），
                // 但"解不出来"那一档改成**按真实原因**说，不再一律报"超出射程"
                // （弹道被挡住、炮口被堵、目标太近…… 都是不同的处置办法）。
                // ⭐ 2026-09-26 P3：最后那一档再分一层 —— 「**转速不够**」（炮塔一直在追、追不上）
                //    与「正在跟踪」（真的在转、马上就到）是两件事，而它们**在这一列里长得一模一样**，
                //    玩家照计算机界面「无法射击 #N 协调器有 x 门炮」找过来，只能从这里看出区别。
                final long now = minecraft != null && minecraft.level != null
                        ? minecraft.level.getGameTime() : 0L;
                String state = node.awaitingSalvoEnd(now)
                        ? Component.translatable(node.getFireMode() == FireMode.ROLLING
                                ? "gui.create_ballistics.state_rolling" : "gui.create_ballistics.state_tot")
                                .getString()
                        : (node.isAimed()
                        ? Component.translatable("gui.create_ballistics.state_ready").getString()
                        : (!node.hasTarget()
                                ? Component.translatable("gui.create_ballistics.state_idle").getString()
                                : (!node.isSolvable()
                                        ? Component.translatable(node.getArcFail().shortKey()).getString()
                                        : (node.isRpmShort()
                                                ? Component.translatable(
                                                        "gui.create_ballistics.state_rpm_short").getString()
                                                : Component.translatable(
                                                        "gui.create_ballistics.state_moving").getString()))));
                // 配色：转速不够 = **橙字**（能打，只是要加转速）—— 与"打不着"的红字分开
                color = node.isAimed() ? GOOD
                        : (node.hasTarget() && !node.isSolvable() ? BAD
                        : (node.hasTarget() && node.isRpmShort() ? WARN : MUTED));

                row = Component.translatable("gui.create_ballistics.node_line",
                        index, shownP.getX(), shownP.getY(), shownP.getZ(),
                        materialText(node.getProbedMaterial()), shellText(node),
                        node.getProbedCharges(), node.chargeCapacity(),
                        Texts.num("%.1f", node.getProbedVelocity()),
                        Texts.deg(node.getProbedMinPitch()), Texts.deg(node.getProbedMaxPitch()),
                        node.getProbedMaxRange() > 0.0F ? Texts.num("%.0f", node.getProbedMaxRange()) : "—",
                        node.isSolvable() ? Texts.num("%.1f", node.getSolutionAirTime() / 20.0) : "—",
                        state).getString();
            }

            graphics.drawString(font, row, tx, ty, color);
            ty += LINE_H;
            if (ty > Math.round(288 / TEXT_SCALE)) {
                break;
            }
            index++;
        }
    }

    /** 炮身材质的显示名；查不到翻译就退回原始 id（附属模组的新材质也不会显示成键名）。 */
    private static String materialText(String id) {
        if (id == null || id.isEmpty()) {
            return Component.translatable("gui.create_ballistics.material.unknown").getString();
        }
        String key = "gui.create_ballistics.material." + id;
        String translated = Component.translatable(key).getString();
        return translated.equals(key) ? id : translated;
    }

    /** 弹种显示名；没装弹时给个明确的说法，而不是留空。 */
    private static String shellText(FireControlNodeBlockEntity node) {
        String id = node.getProbedShellItemId();
        return id == null || id.isEmpty()
                ? Component.translatable("block.create_ballistics.fire_control_node.shell_none").getString()
                : Texts.translatedOrShort(id);
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
