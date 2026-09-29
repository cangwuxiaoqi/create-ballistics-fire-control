package net.createballistics.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.createballistics.client.model.DroneModel;
import net.createballistics.entity.DroneEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 无人机渲染 —— <b>用卓越前线在用的那套 GeckoLib 渲染器</b>，与它逐字对齐。
 *
 * <p>为什么不再自己画：见 {@link DroneModel} 的类注释。一句话 ——
 * "同一份模型 + 同一个渲染器"是唯一能让外观必然一致的做法；
 * 之前"把模型转成原版格式、借物品管线画"那条路，十几个来回全卡在我猜原版管线行为上。
 *
 * <p>⚠️ <b>代价</b>：无人机的实体实现了 GeckoLib 的 {@code GeoAnimatable}，所以这是**硬依赖** ——
 * 单独用本模组（不装卓越前线）时需要自己装一份 GeckoLib（MIT，很小）。
 *
 * <p>⚠️ 朝向：卓越前线的模型"正面"是 **+Z**，所以它做的是 {@code -yaw}（不是原版惯例的 {@code 180 - yaw}），
 * 渲染类型也照它用 {@code entityTranslucent}（**双面、不剔除** —— 那份模型有 30 处"没画那个面"的开口，
 * 靠双面渲染透出内部结构才看着闭合）。
 */
public class DroneRenderer extends GeoEntityRenderer<DroneEntity> {

    public DroneRenderer(EntityRendererProvider.Context context) {
        super(context, new DroneModel());
        this.shadowRadius = 0.2F;
    }

    /** 照卓越前线：半透明实体渲染（双面、不剔除）。 */
    @Override
    public RenderType getRenderType(DroneEntity animatable, ResourceLocation texture,
                                    @Nullable MultiBufferSource bufferSource, float partialTick) {
        return RenderType.entityTranslucent(texture);
    }

    @Override
    public void render(DroneEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        poseStack.pushPose();
        // ⚠️ 逐字照卓越前线：它的模型正面是 +Z，所以是 -yaw
        //    （它写的是 entity.getYaw(partialTick)，那是它自己实体上的方法；原版 `Entity` 没有，
        //      而渲染器拿到的 entityYaw 参数就是同一个插值好的朝向 —— 等价。）
        poseStack.mulPose(Axis.YP.rotationDegrees(-entityYaw));
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
        poseStack.popPose();
    }
}
