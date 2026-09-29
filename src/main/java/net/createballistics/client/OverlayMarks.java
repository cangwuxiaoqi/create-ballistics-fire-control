package net.createballistics.client;

import java.util.List;
import net.createballistics.CreateBallistics;
import net.createballistics.item.ArtilleryIndicatorItem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * 屏幕上那几种 <b>12×12 的小标记</b> —— 火炮指示器举镜时与无人机图传里<b>共用这一份</b>。
 *
 * <p>照卓越前线，两处本来各有一份（它的 {@code SpyglassRangeOverlay} 与 {@code DroneHudOverlay}
 * 画的是同样的图标）。我们只留一份：否则"同一个目标点"在两个视角里画得不一样，
 * 玩家一眼就能看出来（本模组"同义实现写两份"已经栽过）。
 *
 * <p>两种标记：
 * <ul>
 *   <li><b>打击目标点</b> —— 图标 {@code overlay/spyglass/indicator.png}；</li>
 *   <li><b>IFF 友军</b>（绑定的计算机 + 它的协调器）—— 图标 {@code overlay/drone/drone_iff_artillery.png}。
 *       ⚠️ 资源目录与用途是**交叉**的（{@code drone/} 下的图给望远镜用、{@code spyglass/} 下的给图传用）——
 *       目录是按"它出自卓越前线的哪个文件"分的，**不是**按"哪个视角用"。</li>
 * </ul>
 *
 * <p>共同的两个细节（都照它）：<b>转到画面外时贴屏幕边缘</b>继续指方向；
 * 判"看不看得见"要用<b>变焦后</b>的视场角（由调用方算好传进来）。
 */
public final class OverlayMarks {

    private static final ResourceLocation TARGET =
            CreateBallistics.id("textures/overlay/spyglass/indicator.png");
    private static final ResourceLocation IFF_FIREPOWER =
            CreateBallistics.id("textures/overlay/drone/drone_iff_artillery.png");

    /** 画多大（屏幕像素）。与卓越前线一致。 */
    private static final int SIZE = 12;

    private OverlayMarks() {
    }

    /**
     * 打击目标点（两个视角用的都是它）。
     *
     * <p>⚠️ 传进来的坐标我们取的是方块<b>中心</b>。卓越前线在无人机 HUD 里写的是方块最小角
     * （{@code new Vec3(x, y, z)}），但在自家的望远镜迭加层里用的是 {@code getCenter()} ——
     * 同一个"打击目标点"由我们两个界面同时标着，取中心才不会出现两个界面差半格。
     */
    public static void target(GuiGraphics guiGraphics, Vec3 world, int screenW, int screenH, double fov) {
        mark(guiGraphics, TARGET, world, screenW, screenH, fov);
    }

    /**
     * IFF 友军：<b>绑定的计算机 + 它的协调器</b>（方案 A，见 {@code DroneHudOverlay} 的类注释）。
     *
     * <p>两个坐标的<b>来源不同</b>：计算机来自物品上的 {@code BOUND_COMPUTER}（绑定时就写死了）；
     * 协调器来自<b>服务端抄过来的镜像</b>。
     *
     * <p>⭐ <b>镜像缺席（{@code null}）= 服务端此刻也读不到那台计算机 ⇒ 一个标记都不画</b>。
     * 这正是"计算机被拆掉之后，标记该跟着消失"的判据：拆了之后服务端
     * {@code getBlockEntity} 读不到，镜像就被删掉（见 {@code ArtilleryIndicatorItem#inventoryTick}）——
     * 客户端于是不再对着空气画一个友军图标。
     */
    public static void friendlies(GuiGraphics guiGraphics, ItemStack indicator,
                                  int screenW, int screenH, double fov) {
        List<BlockPos> coordinators = ArtilleryIndicatorItem.mirroredCoordinators(indicator);
        if (coordinators == null) {
            return;
        }
        BlockPos computer = ArtilleryIndicatorItem.boundComputer(indicator);
        if (computer != null) {
            mark(guiGraphics, IFF_FIREPOWER, Vec3.atCenterOf(computer), screenW, screenH, fov);
        }
        for (BlockPos coordinator : coordinators) {
            mark(guiGraphics, IFF_FIREPOWER, Vec3.atCenterOf(coordinator), screenW, screenH, fov);
        }
    }

    /** 一个 12×12 的图标：看不见就不画，看得见就夹在屏幕内。 */
    private static void mark(GuiGraphics guiGraphics, ResourceLocation icon, Vec3 world,
                             int screenW, int screenH, double fov) {
        if (!WorldToScreen.canSee(world, fov)) {
            return;
        }
        Vec3 screen = WorldToScreen.toScreen(world);
        if (screen == null) {
            return;
        }
        int x = Mth.clamp((int) Math.round(screen.x) - SIZE / 2, 0, screenW - SIZE);
        int y = Mth.clamp((int) Math.round(screen.y) - SIZE / 2, 0, screenH - SIZE);
        guiGraphics.blit(icon, x, y, SIZE, SIZE, 0.0F, 0.0F, SIZE, SIZE, SIZE, SIZE);
    }
}
