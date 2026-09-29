package net.createballistics.client;

import net.createballistics.CreateBallistics;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * 把<b>世界坐标投影到屏幕坐标</b> —— HUD 上"某个点在哪"的标记全靠它。
 * 对应卓越前线的 {@code VectorToolKt.worldToScreen / canSee}。
 *
 * <h2>为什么要在渲染世界里"顺手抓"矩阵</h2>
 *
 * 视图矩阵和投影矩阵每帧都在变（视角、开镜变焦、镜头晃动都算在里面），
 * 而 HUD 是在<b>世界渲染之后</b>才画的，那时 {@code RenderSystem} 里已经是 GUI 的矩阵了。
 * 所以必须在世界渲染途中把这两张矩阵<b>拷一份</b>下来。
 *
 * <p>⚠️ 必须 {@code set()} 拷贝，不能存引用：{@code RenderSystem.getModelViewMatrix()}
 * 返回的是那块<b>会被反复改写</b>的矩阵本体，存引用的话等 HUD 画的时候读到的就是 GUI 的矩阵了。
 *
 * <p>卓越前线为这件事写了一个 {@code LevelRenderer} 的 Mixin；我们不用 ——
 * NeoForge 的 {@link RenderLevelStageEvent} 本来就在世界渲染途中触发，拿到的就是同一对矩阵。
 *
 * <p>⇒ 顺带一个好处：<b>开镜变焦是算在投影矩阵里的</b>，所以标记会自动跟着变焦走，不用额外处理。
 */
@EventBusSubscriber(modid = CreateBallistics.MODID, value = Dist.CLIENT)
public final class WorldToScreen {

    private static final Matrix4f MODEL_VIEW = new Matrix4f();
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static boolean captured;

    private WorldToScreen() {
    }

    /**
     * ⚠️⚠️ 两个细节都不能省，它们是同一类错误的两次教训（2026-09-22 标记乱飘的根因）：
     *
     * <ol>
     *   <li><b>用事件自带的矩阵，不要用 {@code RenderSystem.getModelViewMatrix()}</b>。
     *       后者是<b>会被反复改写的全局状态</b>：渲染区块图层时它带着区块偏移，
     *       在那些时刻抓到的根本不是相机矩阵。而事件里这两张矩阵是 {@code renderLevel}
     *       直接传进来的局部量，<b>任何一次触发都是相机视图 + 关卡投影</b>。
     *       （这正是卓越前线必须把 Mixin 精确插在 {@code applyModelViewMatrix()} 之后的原因 ——
     *       它读的是全局状态，所以只能挑那一刻。）</li>
     *   <li><b>只认 {@code AFTER_WEATHER} 这一个阶段</b>。NeoForge 还有一路"按渲染类型"的触发
     *       （{@code dispatchRenderStage(RenderType, …)}），它会复用同一个事件类、
     *       在一帧里触发几十次。虽然那一路传的也是相机矩阵，但挑一个明确的阶段就不用去赌这件事。</li>
     * </ol>
     */
    @SubscribeEvent
    public static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            return;
        }
        MODEL_VIEW.set(event.getModelViewMatrix());
        PROJECTION.set(event.getProjectionMatrix());
        captured = true;
    }

    /**
     * 世界坐标 → 屏幕坐标（GUI 缩放后的坐标系，与 {@code GuiGraphics} 同一套）。
     *
     * @return 屏幕坐标（z 是相机空间深度，<b>负数表示在相机背后</b>）；矩阵还没抓到过则返回 {@code null}
     */
    public static Vec3 toScreen(Vec3 world) {
        if (!captured) {
            return null;
        }
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 relativeToCamera = camera.getPosition().vectorTo(world);

        Vector4f p = new Vector4f((float) relativeToCamera.x, (float) relativeToCamera.y,
                (float) relativeToCamera.z, 1.0F);
        p.mul(MODEL_VIEW);
        p.mul(PROJECTION);
        // ⚠️ 深度要在 div(w) **之前**取：div(w) 会把 w 自己变成 1.0，
        //    之后再读 p.w 拿到的永远是 1（第一版就是这么写的，返回的 z 恒为 1，与注释不符）。
        float depth = p.w;
        if (depth == 0.0F) {
            return null;
        }
        p.div(depth);

        return new Vec3(
                Minecraft.getInstance().getWindow().getGuiScaledWidth() * (0.5 + p.x * 0.5),
                Minecraft.getInstance().getWindow().getGuiScaledHeight() * (0.5 - p.y * 0.5),
                depth);
    }

    /**
     * 这个点在不在视野里（朝向夹角小于给定的视场角）。
     *
     * <p>⚠️ 传进来的应该是<b>当前实际的</b>视场角 —— 开镜变焦之后真实视场会变小，
     * 拿没变焦的 FOV 去判会把"其实在画面外"的点也当成看得见。
     * 调用方用 {@code mc.options.fov() / 当前倍率} 算即可。
     */
    public static boolean canSee(Vec3 world, double fovDegrees) {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 toPoint = camera.getPosition().vectorTo(world);
        if (toPoint.lengthSqr() < 1.0E-6) {
            return true;   // 就在相机位置上，算看得见
        }
        double cos = toPoint.normalize().dot(new Vec3(camera.getLookVector()));
        double degrees = Math.toDegrees(Math.acos(Mth.clamp(cos, -1.0, 1.0)));
        return degrees < fovDegrees;
    }
}
