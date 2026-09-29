package net.createballistics.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.createballistics.registry.ModItems;
import net.minecraft.client.model.ArmedModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HeadedModel;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.layers.PlayerItemInHandLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 第三人称举起指示器时，走原版「举望远镜」那条手臂姿势 —— 把物品抬到眼前，
 * 而不是像普通物品那样垂在体侧。
 *
 * <h2>为什么非用 Mixin 不可</h2>
 *
 * 原版 {@code PlayerItemInHandLayer.renderArmWithItem} 的分支条件是：
 * <pre>
 * itemStack.canPerformAction(ItemAbilities.SPYGLASS_SCOPE) &amp;&amp; entity.getUseItem() == itemStack
 * </pre>
 * 又是那个 {@code SPYGLASS_SCOPE} <b>物品能力</b>（NeoForge 把它从
 * {@code is(Items.SPYGLASS)} 换成了能力判断）。我们的指示器<b>故意不申请</b>它
 * （理由见 {@code IndicatorOverlay} 的类注释：申请了会连带拿到原版 10 倍变焦与圆形遮罩），
 * 于是永远走不进那个分支。
 *
 * <p>{@code renderArmWithSpyglass} 是 {@code PlayerItemInHandLayer} 的
 * <b>私有</b>方法，只能靠 {@code @Shadow} 摸到。参考模组卓越前线用的是同一套写法
 * （它在自己的 {@code PlayerItemInHandLayerMixin} 里就干这一件事）。
 *
 * <p>判据与它一致：是<b>指示器本身</b>、并且<b>正被使用</b>（{@code getUseItem() == stack}）、
 * 且没有挥臂动画（{@code swingTime == 0}）。
 */
@Mixin(PlayerItemInHandLayer.class)
public abstract class PlayerItemInHandLayerMixin<T extends Player, M extends EntityModel<T> & ArmedModel & HeadedModel>
        extends ItemInHandLayer<T, M> {

    @Shadow
    protected abstract void renderArmWithSpyglass(LivingEntity entity, ItemStack stack, HumanoidArm arm,
                                                  PoseStack poseStack, MultiBufferSource buffer, int packedLight);

    public PlayerItemInHandLayerMixin(RenderLayerParent<T, M> renderer, ItemInHandRenderer itemInHandRenderer) {
        super(renderer, itemInHandRenderer);
    }

    @Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
    private void createballistics$spyglassArmPose(LivingEntity entity, ItemStack stack,
                                                  ItemDisplayContext displayContext, HumanoidArm arm,
                                                  PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                                  CallbackInfo ci) {
        if (stack.is(ModItems.ARTILLERY_INDICATOR.get())
                && entity.getUseItem() == stack
                && entity.swingTime == 0) {
            ci.cancel();
            this.renderArmWithSpyglass(entity, stack, arm, poseStack, buffer, packedLight);
        }
    }
}
