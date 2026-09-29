package net.createballistics.client;

import net.createballistics.CreateBallistics;
import net.createballistics.item.ArtilleryIndicatorItem;
import net.createballistics.registry.ModDataComponents;
import net.createballistics.registry.ModItems;
import net.createballistics.util.StageText;
import net.createballistics.util.Texts;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 火炮指示器举起时的镜筒迭加层 —— 对应卓越前线的 {@code SpyglassRangeOverlay}。
 *
 * <h2>屏幕上这几样，都是照它抄的</h2>
 *
 * <ol>
 *   <li><b>镜筒</b>：贴图 {@code overlay/spyglass/spyglass.png}（<b>2100×900 = 正好 21:9</b>）。
 *       目标矩形也按 21:9 算，把整张贴图铺进去 —— 等比、不变形，也不挑分辨率。
 *       放大倍率走 {@code scopeScale}：从 1.0 渐变到 1.35。</li>
 *   <li><b>左键蓄力环</b>：<b>只有按住左键时才画</b>，深色底环 + 白色进度弧，
 *       弧转满一圈就开火（见 {@link #drawChargeRing}）。</li>
 *   <li><b>测距</b>：从眼睛沿视线打一条 512 格的射线，把距离显示在准星右侧。</li>
 * </ol>
 *
 * <h2>⚠️ 为什么这里判的是「正在使用中」，而不是 {@code isScoping()}</h2>
 *
 * NeoForge 把 {@code Player.isScoping()} 改成了查物品能力
 * {@code ItemAbilities.SPYGLASS_SCOPE}（不再是 {@code UseAnim} 或 {@code is(Items.SPYGLASS)}）。
 * 那一个能力同时管着<b>三件事</b>：原版 10 倍变焦、开镜时的灵敏度削减、第三人称的举镜手臂姿势。
 *
 * <p><b>我们故意不给这个能力</b> —— 给了就会连原版的固定 10 倍变焦和圆形遮罩一起拿到，
 * 得反过来跟它们打架。所以那三件事我们自己接：变焦与灵敏度见 {@link IndicatorClientEvents}，
 * 手臂姿势见 {@code PlayerItemInHandLayerMixin}。卓越前线是同样的取舍。
 *
 * <p>⇒ 玩家<b>看不到原版望远镜的外观是预期行为</b>，不是坏掉（2026-09-22 用户实测确认过这一点）。
 */
public final class IndicatorOverlay implements LayeredDraw.Layer {

    private static final ResourceLocation FRAME = CreateBallistics.id("textures/overlay/spyglass/spyglass.png");

    /** 贴图的原始尺寸，也是那个 21:9 的来历。 */
    private static final int FRAME_TEX_W = 2100;
    private static final int FRAME_TEX_H = 900;
    private static final float FRAME_ASPECT = (float) FRAME_TEX_W / FRAME_TEX_H;

    /** 举起镜筒后镜筒贴图铺到多大（相对屏幕较短边）。卓越前线是 1.35 + 后坐抖动，我们没有枪，取常数。 */
    private static final float SCOPE_SCALE = 1.35F;

    /** 准星射线长度、以及超过多远就不再给具体数字（射线本来就只有 512 格，所以两条实际是同一条）。 */
    private static final double RAY_LENGTH = 512.0;
    private static final double MAX_RANGE = 500.0;

    /** 快捷栏是 182 宽、居中 —— 取一半，用来把状态文字摆在它右边。 */
    private static final int HOTBAR_HALF = 91;

    /** 镜筒铺得多大 —— 渐变量，避免举起/放下时"啪"地跳。 */
    private static float scopeScale = 1.0F;

    @Override
    public void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        if (player == null || mc.options.getCameraType() != CameraType.FIRST_PERSON || !isScoping(player)) {
            scopeScale = 1.0F;
            return;
        }

        float partial = (float) Math.min(deltaTracker.getGameTimeDeltaPartialTick(true), 1.6F);
        scopeScale = Mth.lerp(Math.min(0.5F * partial, 1.0F), scopeScale, SCOPE_SCALE);

        int screenW = guiGraphics.guiWidth();
        int screenH = guiGraphics.guiHeight();
        float centerX = screenW / 2.0F;
        float centerY = screenH / 2.0F;

        drawFrame(guiGraphics, screenW, screenH);
        drawMarks(guiGraphics, player, screenW, screenH);
        drawChargeRing(guiGraphics, centerX, centerY);
        drawStageText(guiGraphics, player, screenW, screenH);

        guiGraphics.drawString(mc.font, rangeText(player, partial),
                screenW / 2 + 12, screenH / 2 - 28, -1, false);

        // ⚠️ 这里**刻意不画「火力模式需 2 门炮」那句提示** —— 2026-09-25 用户要求撤掉：
        //    举镜时玩家要的是"看画面"，那句话挪到**指示器的 H 键面板**上了（那才看得清）。
    }

    /**
     * 右下角显示计算机的「计算过程」—— <b>远程就能看到</b>炮转好没、瞄准完没、
     * 还有几秒落地（同时弹着 / 轮射的倒计时都在这条里）。
     *
     * <p>文字与颜色都来自 {@link StageText}（<b>唯一实现</b>，与计算机界面、护目镜同一份 ——
     * 用户 2026-09-22 定的规矩）。
     *
     * <p>原料来自指示器身上的<b>镜像快照</b>：倒计时用的是排期结束的 tick，客户端拿自己的
     * 游戏时间现算 ⇒ 照样一秒一秒走，不会停在快照那一刻。
     */
    private static void drawStageText(GuiGraphics guiGraphics, LocalPlayer player, int screenW, int screenH) {
        StageText.Snapshot snapshot = ArtilleryIndicatorItem.mirroredStage(player.getMainHandItem());
        if (snapshot == null) {
            return;
        }
        // 位置：快捷栏右边那块空白（用户圈的）。字压在世界上，所以带阴影。
        //
        // ⚠️ 2026-09-24 把这一行**抬到了快捷栏上方**（原来是 screenH-16，和快捷栏同一行）：
        //    它最长时有 400 多像素，而"与快捷栏同行"可用的横向只有屏宽的一半左右
        //    ⇒ 要么跑出屏幕右边缘（用户截图：尾巴被切掉），要么缩到 0.5 倍以下、中文看不清。
        //    抬上来之后它可以从**屏幕最左**一直用到最右，长句也能全尺寸画完 ✓
        //    （右端仍钉在屏幕右边缘 —— 还是"右下角"那块）。
        //
        // ⚠️ 高度选 **screenH-48** 是量出来的，不是随手写的：原版底部那一摞得让开 ——
        //    快捷栏顶 -22 · 经验条 -29~-24 · 等级数字约 -35 · 血量/饥饿那一排约 -39
        //    ⇒ 这一行画在 -48（字高 9 ⇒ 占 -48~-39）正好落在它们**上面**，互不遮挡。
        FittedText.fitRight(guiGraphics, StageText.of(snapshot, player.level()),
                screenW / 2 + HOTBAR_HALF + 8, screenW - 8, 8, screenH - 48,
                StageText.color(snapshot, player.level()), true);
    }

    /**
     * 屏幕标记两件（画法都在 {@link OverlayMarks}，**与无人机图传共用一份**）：
     *
     * <ol>
     *   <li><b>打击目标点</b> —— 坐标来自指示器自己身上的 {@code LAST_TARGET}。指示器的意义
     *       就是"离计算机很远时用"，那时客户端读不到计算机的方块实体，只能由物品自己带一份
     *       显示用缓存（见 {@code ModDataComponents}）。所以它画的是<b>「我最近报出去的点」</b>：
     *       若目标是在计算机界面上改的，这个标记不会跟着动。</li>
     *   <li><b>IFF 友军</b> —— 绑定的计算机 + 它的协调器，<b>照卓越前线</b>：
     *       它的 {@code SpyglassRangeOverlay} 在望远镜里也会把已绑定的炮位标出来。
     *       ⚠️ 我们标的是**计算机与协调器**（方块）而不是炮 —— 理由见
     *       {@code DroneHudOverlay} 的类注释（方案 A）。</li>
     * </ol>
     */
    private static void drawMarks(GuiGraphics guiGraphics, LocalPlayer player, int screenW, int screenH) {
        // 开镜变焦会收窄真实视场，判"看不看得见"要用变焦后的
        double fov = Minecraft.getInstance().options.fov().get() / IndicatorClientEvents.currentZoom();
        BlockPos marked = player.getMainHandItem().get(ModDataComponents.LAST_TARGET.get());
        if (marked != null) {
            OverlayMarks.target(guiGraphics, Vec3.atCenterOf(marked), screenW, screenH, fov);
        }
        OverlayMarks.friendlies(guiGraphics, player.getMainHandItem(), screenW, screenH, fov);
    }

    /** 举起指示器了吗（第一人称的直接开镜）。 */
    private static boolean isScoping(LocalPlayer player) {
        return player.isUsingItem() && player.getUseItem().is(ModItems.ARTILLERY_INDICATOR.get());
    }

    /**
     * 左键蓄力环：深色底环（整圈）+ 白色进度弧，弧转满一圈就是开火。
     *
     * <p>⚠️ 它<b>不是</b>常显的准星。2026-09-22 第一版被我做成了"举镜时常显一个白圆环"，
     * 用户拿截图跟卓越前线一对比就指出：那是<b>长按左键的蓄力进度</b>，
     * 而且常显的粗圆环正好糊在准星上、挡视线。所以现在<b>只有按住左键</b>时才画。
     */
    private static void drawChargeRing(GuiGraphics guiGraphics, float centerX, float centerY) {
        // 画法、半径、颜色都在 OverlayShapes 那**唯一一份**里（图传 HUD 用的是同一个）
        OverlayShapes.chargeRing(guiGraphics, centerX, centerY, IndicatorClientEvents.chargeProgress());
    }

    /**
     * 镜筒：把整张 21:9 的贴图铺进一个 21:9 的矩形里。
     *
     * <p>⚠️ 这里<b>不是</b>卓越前线那种"按像素 1:1 贴"——它的 {@code preciseBlit} 把
     * {@code uWidth/textureWidth} 一起传成同一个值，UV 恰好算成 0→1，<b>实际效果就是满铺</b>。
     * 我们直接用原版 {@code blit} 表达同一件事：源区域 = 整张贴图，目标 = 算出来的矩形。
     */
    private static void drawFrame(GuiGraphics guiGraphics, int screenW, int screenH) {
        float shorter = Math.min(screenW, screenH) * scopeScale;
        int height = Math.max(1, Math.round(shorter));
        int width = Math.max(1, Math.round(shorter * FRAME_ASPECT));
        guiGraphics.blit(FRAME, (screenW - width) / 2, (screenH - height) / 2, width, height,
                0.0F, 0.0F, FRAME_TEX_W, FRAME_TEX_H, FRAME_TEX_W, FRAME_TEX_H);
    }

    /**
     * 测距文字。射线打不到方块时 {@code getLocation()} 是射线的终点（512 格外），
     * 于是自然落进"太远"那一支，显示 {@code ---} —— 不用单独判 MISS。
     */
    private static Component rangeText(LocalPlayer player, float partial) {
        Vec3 eye = player.getEyePosition(partial);
        BlockHitResult hit = player.level().clip(new ClipContext(eye,
                eye.add(player.getViewVector(partial).scale(RAY_LENGTH)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        double range = eye.distanceTo(hit.getLocation());
        return range > MAX_RANGE
                ? Component.translatable("gui.create_ballistics.indicator.range_far")
                : Component.translatable("gui.create_ballistics.indicator.range", Texts.num("%.1f", range));
    }
}
