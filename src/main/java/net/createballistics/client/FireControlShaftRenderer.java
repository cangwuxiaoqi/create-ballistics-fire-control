package net.createballistics.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DirectionProperty;

/**
 * 三个火控方块共用的传动轴渲染器 —— 把 Create 标准的半截轴插进「应力接入面」并按转速旋转。
 *
 * <p>节点是轴在<b>正面</b>（{@code FACING}），计算机/协调器是轴在<b>背面</b>
 * （{@code FACING.getOpposite()}）。除了这一处差别，其余逻辑完全一样，所以合成一个类，
 * 用 {@code shaftOnFacing} 区分。
 *
 * <h2>① 只走 BER 一条路，不做 {@code supportsVisualization} 判断</h2>
 *
 * 见 {@link ModClientRegistrations} 的类注释：包内「动力加载器 / simulated」那一派都是这么写的，
 * 而且它们的方块实体注册里都<b>没有</b> {@code .visual(...)}。混着来一定出事。
 *
 * <h2>② ⚠️ 光照必须自己取，不能直接用 BER 给的 light</h2>
 *
 * {@code BlockEntityRenderDispatcher} 给的是<b>方块自己所在一格</b>的光照，而我们的方块是
 * 实心不透明立方体 —— MC 的天光在第一个不透明方块处就断掉，那一格恒为 0，
 * 于是轴被画成<b>纯黑</b>。2026-09-16 就是卡在这里，折腾了七八轮。
 *
 * <p>Create 自己没事是因为它由 Flywheel visual 渲染（visual 的 relight 从相邻格子取光）；
 * 动力加载器没事是因为方块属性写了 {@code .noOcclusion() + .lightLevel(...)}。
 * 这里直接取<b>轴所伸向的那一格</b>（FACING 前方或背面那一格）的光照，语义上也最准。
 *
 * <h2>③ 渲染层用 cutoutMipped</h2>
 *
 * {@code solid()} 无视 alpha，而 {@code create:block/axis} 有 192/256 个「alpha=0 且
 * RGB 纯黑」的像素。动力加载器那个活代码实例用的就是 {@code cutoutMipped()}。
 *
 * <h2>④ 空值兜底</h2>
 *
 * {@code PartialModel.get()} 在模型没烘焙好时会返回 null，而 Catnip 的 {@code CachedBuffers}
 * 对 null 不做判断、直接 NPE 崩客户端。渲染器里出异常是要崩游戏的，宁可不画这一帧。
 */
public class FireControlShaftRenderer<T extends KineticBlockEntity> implements BlockEntityRenderer<T> {

    private final DirectionProperty facingProperty;
    private final boolean shaftOnFacing;

    /**
     * @param facingProperty 方块状态里装朝向的那个属性
     * @param shaftOnFacing  {@code true} = 轴接在 FACING 那一面（火控节点）；
     *                       {@code false} = 轴接在 FACING 的背面（火控计算机 / 协调器）
     */
    public FireControlShaftRenderer(DirectionProperty facingProperty, boolean shaftOnFacing) {
        this.facingProperty = facingProperty;
        this.shaftOnFacing = shaftOnFacing;
    }

    @Override
    public void render(T be, float partialTick, PoseStack ms,
                       MultiBufferSource bufferSource, int light, int overlay) {
        if (be.getLevel() == null) {
            return;
        }
        BlockState state = be.getBlockState();
        if (!state.hasProperty(facingProperty)) {
            return;
        }
        if (AllPartialModels.SHAFT_HALF.get() == null) {
            return;   // partial 没烘焙好：跳过这一帧，不要崩
        }

        Direction facing = state.getValue(facingProperty);
        Direction shaftFace = shaftOnFacing ? facing : facing.getOpposite();
        Direction.Axis axis = facing.getAxis();
        BlockPos pos = be.getBlockPos();

        // 取轴所伸向那一格的光照（见类注释 ②）
        int packedLight = LevelRenderer.getLightColor(be.getLevel(), pos.relative(shaftFace));

        float time = AnimationTickHolder.getRenderTime(be.getLevel());
        float offset = KineticBlockEntityRenderer.getRotationOffsetForPosition(be, pos, axis);
        float angle = time * be.getSpeed() * 3.0F / 10.0F % 360.0F;

        // 转向正负：照抄 Create 的 GearboxRenderer —— 动力从哪一端进来决定正负，
        // 否则从另一端接应力时轴的转向是反的。
        if (be.getSpeed() != 0.0F && be.hasSource()) {
            BlockPos source = be.source.subtract(pos);
            Direction sourceFacing = Direction.getNearest(source.getX(), source.getY(), source.getZ());
            if (sourceFacing.getAxis() == axis) {
                angle *= sourceFacing == shaftFace ? 1.0F : -1.0F;
            } else if (sourceFacing.getAxisDirection() == shaftFace.getAxisDirection()) {
                angle *= -1.0F;
            }
        }

        angle += offset;
        angle = angle / 180.0F * (float) Math.PI;

        SuperByteBuffer shaft = CachedBuffers.partialFacing(
                AllPartialModels.SHAFT_HALF, state, shaftFace);
        KineticBlockEntityRenderer.kineticRotationTransform(shaft, be, axis, angle, packedLight);
        shaft.renderInto(ms, bufferSource.getBuffer(RenderType.cutoutMipped()));
    }
}
