package net.createballistics.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.text.DecimalFormat;
import java.util.Optional;
import net.createballistics.entity.DroneEntity;
import net.createballistics.item.MonitorItem;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

/**
 * 准星对着无人机时，在它头顶画一块名牌：<b>名字 + 归属 · 耐久条 · 距离</b>。
 * 对应卓越前线的 {@code client/overlay/VehicleTeamOverlay}（那一段是它的"载具信息牌"）。
 *
 * <h2>⚠️ 无人机头顶那块牌子是"我们的"，不是原版给的</h2>
 *
 * 原版只给 {@code LivingEntity} 画血条、给有自定义名的实体画名牌；我们的无人机继承的是
 * {@code Entity}（**刻意不是生物**，见 {@link DroneEntity} 的类注释）—— 所以这三样全都要自己画。
 *
 * <h2>怎么画到"世界里的那个点"上</h2>
 *
 * 三步，全靠现成的工具：
 * <ol>
 *   <li>取<b>包围盒顶再往上半格</b>那个点做锚点（照它：{@code bbox 中心 + 半高 + 0.5}）——
 *       牌子就浮在无人机正上方。⚠️⚠️ 锚点必须用<b>插值后</b>的位置，见 {@code render()} 里那段
 *       （不然机身是平滑的、牌子是 20 Hz 硬跳的，飞起来就是"疯狂抽搐"）。</li>
 *   <li>{@link WorldToScreen#canSee} 判它在不在画面里（⚠️ 要用<b>变焦后</b>的视场角）。</li>
 *   <li>{@link WorldToScreen#toScreen} 投到屏幕坐标，然后<b>把坐标系推到那个点并整体缩放</b> ——
 *       牌子离得越远越小（照它那条 {@code 30 / fov × …} 的式子和 0.4~1.0 的夹取），
 *       所以远近都读得清。</li>
 * </ol>
 *
 * <p>⚠️ 用<b>射线自己找</b>准星锁的是谁，而不是读 {@code mc.crosshairPickEntity}：
 * 后者只有原版伸手可及的那几格（3 格），而观测无人机本来就是"站远了看"的事
 * （卓越前线给的也是 512 格）。
 *
 * <p>⚠️ 顺带把"被墙挡住"也算进来了：方块的射线结果比无人机更近就什么都不画 ——
 * 隔着山看见自己无人机的名字会很怪。
 */
public final class DroneInfoOverlay implements LayeredDraw.Layer {

    /** 看多远算数（照卓越前线：512 格）。 */
    private static final double LOOK_RANGE = 512.0;
    /**
     * 实体命中箱的宽容度。
     *
     * <p>无人机只有 0.4×0.25 格 —— 不给点余量，要在 512 格之外用准星"正中"它几乎不可能。
     */
    private static final double ENTITY_PADDING = 0.3;

    /** 名牌浮在包围盒顶再往上多少格。 */
    private static final double HEAD_GAP = 0.5;

    /** 名牌整体上移多少像素（先移到锚点，再抬一点，别糊在机身上）。 */
    private static final float ANCHOR_LIFT = -12.0F;

    /** 名字行与距离行相对锚点的纵向位置（照它：-13 在上、7 在下，中间留给那条横条）。 */
    private static final int NAME_Y = -13;
    private static final int RANGE_Y = 7;

    /**
     * 名字行与距离行的颜色 —— **照卓越前线**：它那三处（名字 / 距离 / 横条）用的都是同一个
     * {@code color}，取自 {@code controller.getTeamColor()}，而"没有队伍"时是 {@code -1} = **纯白**。
     * 本模组没有队伍概念 ⇒ 永远纯白。
     */
    private static final int COLOR_TEXT = 0xFFFFFFFF;

    /** 距离的格式 —— **照抄它的 {@code FormatTool.format1D(num, "M")}：{@code DecimalFormat("##.#") + "M"}**。 */
    private static final DecimalFormat DECIMAL_FORMAT_1 = new DecimalFormat("##.#");

    /**
     * 耐久条的几何 —— **逐字取卓越前线 {@code VehicleTeamOverlay} 的数值**（浮点，不做任何换算）。
     *
     * <p>⚠️ 为什么不能换算成整数：{@code GuiGraphics.fill} **只有 int 重载**，而它的数值里带 0.5
     * （进度条半高 1.5、槽半宽 40.5）⇒ 只能像它一样自己发顶点，见下面的 {@link #fill}。
     * ⚠️ 我试过"用 {@code pose.scale(2, 2, 1)} 把坐标系放大一倍、再用 ×2 的整数写"这条路：
     * **实测画出来整整大了 2 倍**（坐标系是放大了，但尺寸也被那层缩放乘了一遍）。
     * **别再走**：数值想与它一致，就用和它一样的浮点路径。
     */
    private static final float BAR_FRAME_OUTER_X = 41.5F;   // 外框半宽
    private static final float BAR_FRAME_Y = 3.0F;          // 外框半高（框本身 1 格厚）
    private static final float BAR_TRACK_X = 40.5F;         // 槽半宽
    private static final float BAR_TRACK_Y = 2.0F;          // 槽半高
    private static final float BAR_FILL_X = 40.0F;          // 进度条左端
    private static final float BAR_FILL_Y = 1.5F;           // 进度条半高（比槽小 0.5 ⇒ 上下各留半格）
    private static final float BAR_FILL_WIDTH = 80.0F;      // 进度条满长

    /**
     * 进度条与外框的配色 —— **照卓越前线**：它写的是 {@code argb = 0xFF000000 | 队伍色}，
     * 而"没有队伍"时队伍色就是纯白 0xFFFFFF ⇒ 也就是**纯白**。
     * 本模组没有队伍概念，所以永远走这一支 —— 与它在单人 / 无队伍下的呈现完全一致。
     */
    private static final int COLOR_BAR = 0xFFFFFFFF;
    /** 槽：卓越前线那里写的是 {@code Integer.MIN_VALUE}（半透明黑）。 */
    private static final int COLOR_BAR_TRACK = 0x80000000;

    /**
     * 画一块矩形，**坐标收 float** —— 照抄卓越前线的 {@code RenderHelper#fill}（它那条耐久条就靠它画）。
     *
     * <p>⚠️ 非这样不可：那条横条的数值里带 0.5，而 {@code GuiGraphics.fill} 只有 int 重载。
     * 顶点直接交给 {@code RenderType.guiOverlay()} 的 vertex consumer，姿态矩阵从
     * {@code guiGraphics} 当前姿态取 —— 与 {@code GuiGraphics.fill} 用的是同一个。
     */
    private static void fill(GuiGraphics guiGraphics, float minX, float minY, float maxX, float maxY, int color) {
        Matrix4f matrix = guiGraphics.pose().last().pose();
        // 照它：允许调用方把两端写反（进度为 0 时第三个参数就落在左端左边）
        if (minX > maxX) {
            float swap = minX;
            minX = maxX;
            maxX = swap;
        }
        if (minY > maxY) {
            float swap = minY;
            minY = maxY;
            maxY = swap;
        }
        VertexConsumer buffer = guiGraphics.bufferSource().getBuffer(RenderType.guiOverlay());
        buffer.addVertex(matrix, minX, minY, 0.0F).setColor(color);
        buffer.addVertex(matrix, minX, maxY, 0.0F).setColor(color);
        buffer.addVertex(matrix, maxX, maxY, 0.0F).setColor(color);
        buffer.addVertex(matrix, maxX, minY, 0.0F).setColor(color);
        guiGraphics.flush();
    }

    @Override
    public void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        // 开着界面 / 按了 F1 就别画 —— 它是压在**世界**上的一层提示，不是 UI
        if (player == null || mc.screen != null || mc.options.hideGui) {
            return;
        }
        // 坐在无人机视角里时，它就在镜头前 —— 再给它自己画一块名牌是多余的
        if (MonitorItem.isPiloting(player.getMainHandItem())) {
            return;
        }
        DroneEntity drone = lookedAtDrone(player);
        if (drone == null) {
            return;
        }

        // ⚠️⚠️ 锚点必须用**插值后**的位置（2026-09-26 定案）。
        //
        // `getBoundingBox()` 是**每 tick 一跳**的原始位置（`setPos` 里直接更新），而机身是渲染器用
        // `Mth.lerp(partialTick, xo, x)` **平滑插值**画出来的 —— 两者差着一整 tick 的位移。
        // 无人机慢的时候看不出来，可它现在**满速跟着飞艇走**（0.35 格/tick ≈ 7 格/秒），
        // 在 1.5 格的距离上看，牌子就是**20 Hz 硬跳几百像素** ⇒ 用户实测"耐久条疯狂抽搐、
        // 抽搐出幻影"（用手机拍屏还把它拖成好几份重影）。
        // ⇒ 锚点用 `getPosition(partialTick)`，包围盒跟着平移同一段，牌子才和机身**同一处**。
        // ⚠️ partialTick 取**相机自己用的那个**（`Camera.getPartialTickTime()`，相机 `setup` 时存下的）
        //    —— `WorldToScreen` 用的就是 `camera.getPosition()`，两边必须同一个 partialTick，
        //    否则锚点和相机各插值各的，还是错开一点。
        float partialTick = mc.gameRenderer.getMainCamera().getPartialTickTime();
        AABB box = drone.getBoundingBox().move(drone.getPosition(partialTick).subtract(drone.position()));
        Vec3 anchor = box.getCenter().add(0.0, box.getYsize() / 2.0 + HEAD_GAP, 0.0);
        // ⚠️ 用变焦后的视场角判"看不看得见"：开镜时真实视场会收窄，拿没变焦的判会把画面外的点也当成看得见
        double fov = mc.options.fov().get() / IndicatorClientEvents.currentZoom();
        if (!WorldToScreen.canSee(anchor, fov)) {
            return;
        }
        Vec3 screen = WorldToScreen.toScreen(anchor);
        if (screen == null) {
            return;
        }

        double range = player.distanceTo(drone);
        if (range > LOOK_RANGE) {
            return;
        }
        float size = (float) (30.0 / fov * 0.9 * Mth.clamp(Math.max((LOOK_RANGE - range) / LOOK_RANGE, 0.1), 0.4, 1.0));


        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(screen.x, screen.y + ANCHOR_LIFT, 0.0);
        pose.scale(size, size, size);

        // ⚠️ 顺序也照它：名字 → 距离 → 最后画横条（它是这个次序，见 VehicleTeamOverlay）
        drawName(guiGraphics, drone, mc);
        drawRange(guiGraphics, range, mc);
        drawDurabilityBar(guiGraphics, drone);

        pose.popPose();
    }

    /** 名字 + 归属（照它：{@code 名字 + " " + 归属} 拼成一行、同一个颜色、**不带阴影**）。没绑定时只有名字。 */
    private static void drawName(GuiGraphics guiGraphics, DroneEntity drone, Minecraft mc) {
        MutableComponent line = drone.getDisplayName().copy();
        String owner = drone.getControllerName();
        if (owner != null) {
            line.append(Component.literal(" " + owner));
        }
        guiGraphics.drawString(mc.font, line, -mc.font.width(line) / 2, NAME_Y, COLOR_TEXT, false);
    }

    /**
     * 耐久条。
     *
     * <p>⚠️ 它画的是 {@link DroneEntity#getDurability()}（我们自己的耐久点数，满值
     * {@link DroneEntity#MAX_DURABILITY}），**不是**血量 —— 无人机不是生物，没有血。
     * 但读法就是玩家熟悉的那条血条：<b>满格、越打越短、见底就是最后一撞</b>。
     *
     * <p>几何与配色**逐字照卓越前线的载具信息牌**（{@code VehicleTeamOverlay}）：
     * 外框半宽 41.5 / 半高 3（框本身 1 格厚）、槽 40.5×2、进度条从 -40 起、最多 80 宽、半高 1.5 ——
     * 所以进度条比槽**上下各内缩半格**，读起来是"条在框里"，而不是和框连成一整块颜色。
     * ⚠️ **位置也照它**：横条以锚点为中心，我们**不再**额外下移（原先那一格是自作的偏离，已撤）。
     */
    private static void drawDurabilityBar(GuiGraphics guiGraphics, DroneEntity drone) {
        float ratio = Mth.clamp((float) (drone.getDurability() / DroneEntity.MAX_DURABILITY), 0.0F, 1.0F);

        // 顺序与数值都照它：先槽、再外框（四条 1 格厚的边）、最后进度条
        fill(guiGraphics, -BAR_TRACK_X, -BAR_TRACK_Y, BAR_TRACK_X, BAR_TRACK_Y, COLOR_BAR_TRACK);
        fill(guiGraphics, -BAR_FRAME_OUTER_X, -BAR_FRAME_Y, -BAR_TRACK_X, BAR_FRAME_Y, COLOR_BAR);   // 左
        fill(guiGraphics, -BAR_TRACK_X, -BAR_FRAME_Y, BAR_TRACK_X, -BAR_TRACK_Y, COLOR_BAR);         // 上
        fill(guiGraphics, -BAR_TRACK_X, BAR_TRACK_Y, BAR_TRACK_X, BAR_FRAME_Y, COLOR_BAR);           // 下
        fill(guiGraphics, BAR_TRACK_X, -BAR_FRAME_Y, BAR_FRAME_OUTER_X, BAR_FRAME_Y, COLOR_BAR);     // 右
        fill(guiGraphics, -BAR_FILL_X, -BAR_FILL_Y, -BAR_FILL_X + BAR_FILL_WIDTH * ratio, BAR_FILL_Y, COLOR_BAR);
    }

    /** 距离，写在横条下面（**照它**：一位小数 + {@code M}、同一个颜色、不带阴影）。 */
    private static void drawRange(GuiGraphics guiGraphics, double range, Minecraft mc) {
        String line = DECIMAL_FORMAT_1.format(range) + "M";
        guiGraphics.drawString(mc.font, line, -mc.font.width(line) / 2, RANGE_Y, COLOR_TEXT, false);
    }

    /**
     * 准星指着的那台无人机（没有就 {@code null}）。
     *
     * <p>先打方块、再打实体，最后**两个一起比谁近** —— 这样隔着墙看见的名字不会冒出来。
     */
    @Nullable
    private static DroneEntity lookedAtDrone(LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(LOOK_RANGE));

        BlockHitResult blockHit = player.level().clip(new ClipContext(eye, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        double limit = blockHit.getType() == HitResult.Type.MISS
                ? LOOK_RANGE
                : eye.distanceTo(blockHit.getLocation());

        DroneEntity best = null;
        double bestDistance = limit;
        AABB search = new AABB(eye, end).inflate(1.0);
        for (Entity entity : player.level().getEntities(player, search, e -> e instanceof DroneEntity)) {
            Optional<Vec3> hit = entity.getBoundingBox().inflate(ENTITY_PADDING).clip(eye, end);
            if (hit.isEmpty()) {
                continue;
            }
            double distance = eye.distanceTo(hit.get());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = (DroneEntity) entity;
            }
        }
        return best;
    }
}
