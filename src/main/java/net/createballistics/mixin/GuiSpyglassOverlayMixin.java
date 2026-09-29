package net.createballistics.mixin;

import net.createballistics.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 举起指示器时<b>压掉原版那层望远镜圆形遮罩</b>。
 *
 * <h2>为什么非压不可</h2>
 *
 * 我们<b>故意不给</b>指示器申请 {@code SPYGLASS_SCOPE} 能力，理由写在 {@code IndicatorOverlay}
 * 的类注释里：申请了就会连着拿到原版<b>固定 10 倍变焦</b>与那层<b>圆形遮罩</b>，
 * 而我们要的是自己那套可调焦的镜筒。{@code UseAnim.SPYGLASS} 只借来"举到眼前"的手臂姿势。
 *
 * <p>⚠️⚠️ 但 300+ 模组的大整合包里，"哨戒动力臂"（{@code sentrymechanicalarm}）的
 * {@code SpyglassZoomMixin} 把 {@code Player.isScoping()} 改成了
 * <b>「拿着任何用 {@code UseAnim.SPYGLASS} 的物品」就返回 true</b> ⇒ 原版于是：
 * <ul>
 *   <li>在基准 FOV 上乘 0.1（10 倍）—— 这一层我们靠"忽略入参、从选项值重算"绕开了；</li>
 *   <li><b>画那层圆形遮罩</b> —— 这一层只能在这里压掉
 *       （它自己的 {@code SpyglassOverlayMixin} 只取消它自己物品的遮罩，管不到我们）。</li>
 * </ul>
 *
 * <p>⇒ 判据与它一致但有界：<b>只有"正在使用的物品就是我们的指示器"时才取消</b> ——
 * 玩家拿真正的望远镜时，遮罩照旧。
 *
 * <p>⚠️ 这是本项目第 4 处 Mixin，加它的理由是"恢复既有设计意图"（镜子不要原版遮罩），
 * 不是为了新增行为。签名照 {@code sentrymechanicalarm} 那份实物核对过
 * （{@code renderSpyglassOverlay(GuiGraphics, float)}）。
 */
@Mixin(Gui.class)
public abstract class GuiSpyglassOverlayMixin {

    @Inject(method = "renderSpyglassOverlay", at = @At("HEAD"), cancellable = true)
    private void createballistics$noVanillaScopeMask(GuiGraphics graphics, float partialTick, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getUseItem().is(ModItems.ARTILLERY_INDICATOR.get())) {
            ci.cancel();
        }
    }
}
