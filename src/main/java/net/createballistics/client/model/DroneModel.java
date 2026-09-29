package net.createballistics.client.model;

import net.createballistics.CreateBallistics;
import net.createballistics.entity.DroneEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

/**
 * 无人机模型 —— <b>直接读卓越前线那份 Bedrock 模型</b>（`geo/drone.geo.json` + 它的动画文件）。
 *
 * <h2>为什么绕了一大圈才回到这里</h2>
 *
 * 一开始的路线是"把 Bedrock 模型**转成原版模型**、借原版的方块/物品管线渲染"，想着能避开 GeckoLib。
 * 结果十几个来回全卡在那条路上：原版只接受固定角度、UV 单位与 PNG 像素无关、面名/朝向的约定、
 * 物品管线拿不到逐面明暗、`ItemRenderer` 那句 `-0.5`、"缺面的立方体"要自己补……
 * **每一个都是我自己在猜原版管线的行为。**
 *
 * <p>而卓越前线用的 GeckoLib 是 **MIT 许可的独立动画库**（与我们的 GPL-3.0 兼容），
 * 而且**整合包里已经装了它**（卓越前线用 JarJar 内嵌，JarJar 依赖会被当普通模组加载）。
 * ⇒ **同一份模型、同一个渲染器**，外观不可能再不一样。
 *
 * <h2>桨叶自转</h2>
 *
 * 照卓越前线的 {@code DroneModel#collectTransform}：给四个 {@code wingXX} 骨骼
 * 统一 {@code setRotY((System.currentTimeMillis() % 36000000L) / 12.0F)}。
 *
 * <p>⚠️⚠️ <b>GeckoLib 的骨骼旋转是弧度，不是度</b> —— 我当初把它当度用，慢了 57.3 倍，
 * 慢到"同一侧两片同相"被看成"整侧绕着一个偏心圆心公转"。**抄数值一定要连单位一起抄。**
 *
 * <p>⚠️ 它自带的 `drone.animation.json` 里写的是骨骼名 {@code wing/wing2/wing3/wing4}，
 * **和模型里的 {@code wingFL/FR/BR/BL} 对不上** ⇒ 那个动画对桨叶**完全没作用**，
 * "转"是它在代码里手写出来的（这里也一样）。
 */
public class DroneModel extends GeoModel<DroneEntity> {

    private static final ResourceLocation MODEL = CreateBallistics.id("geo/drone.geo.json");
    private static final ResourceLocation TEXTURE = CreateBallistics.id("textures/entity/drone.png");
    private static final ResourceLocation ANIMATION = CreateBallistics.id("animations/drone.animation.json");

    /** 四个桨叶骨骼的名字 —— 与 Bedrock 模型里逐字一致。 */
    private static final String[] ROTORS = {"wingFL", "wingFR", "wingBR", "wingBL"};

    @Override
    public ResourceLocation getModelResource(DroneEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(DroneEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(DroneEntity animatable) {
        return ANIMATION;
    }

    /**
     * 每帧在"动画之后、渲染之前"直接拨桨叶骨骼 —— 这就是卓越前线的做法。
     *
     * <p>⚠️ 必须 {@code super} 先跑（它会按动画状态重置骨骼），我们再把自转覆盖上去，
     * 否则会被动画那边覆盖掉。
     */
    @Override
    public void setCustomAnimations(DroneEntity animatable, long instanceId, AnimationState<DroneEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);

        float spin = (float) (System.currentTimeMillis() % 36000000L) / 12.0F;   // 弧度（照原版）
        for (String name : ROTORS) {
            GeoBone bone = this.getAnimationProcessor().getBone(name);
            if (bone != null) {
                bone.setRotY(spin);
            }
        }
    }
}
