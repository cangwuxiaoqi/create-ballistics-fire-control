package net.createballistics.cbc;

import java.util.ArrayList;
import java.util.List;

import com.dsvv.cbcat.base.CustomPropellantContext;
import com.dsvv.cbcat.base.IBigCannonBlockPhysics;
import com.dsvv.cbcat.cartridge.IProjectileCartridgeBlock;
import com.dsvv.cbcat.config.CBCATConfigs;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannons.big_cannons.IBigCannonBlockEntity;
import rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile;
import rbasamoyai.createbigcannons.munitions.big_cannon.ProjectileBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.propellant.BigCannonPropellantBlock;

/**
 * 与「机械动力火炮：先进技术」(cbc_at, 下称 CBcat) 的对接层。
 *
 * <h2>⚠️ 这个类的每一个方法都只能在 CBcat 已加载时调用</h2>
 *
 * 本类直接引用了 CBcat 的类。它在 {@code build.gradle} 里是 <b>compileOnly 可选依赖</b>，
 * 没装它的整合包里这些类根本不存在 —— 一旦被加载就会 {@code NoClassDefFoundError} 崩游戏。
 * 所以调用方必须先用 {@code ModList.get().isLoaded("cbc_at")} 判断，见 {@link ActiveBallistics}。
 *
 * <h2>为什么非要复刻它不可</h2>
 *
 * CBcat 的 {@code MountedBigCannonContraptionMixin} 在
 * {@code MountedBigCannonContraption.fireShot} 的 <b>shoot(...) 调用点</b>上有一个
 * {@code @WrapOperation}，把初速整个换成它自己算的
 * {@code CustomPropellantContext.getVelocity()}：
 *
 * <pre>
 * // CBcat 的 @WrapOperation
 * original.call(instance, x, y, z, this.propelCtx.getVelocity(), this.propelCtx.spread);
 * </pre>
 *
 * 而 going_ballistic 在<b>同一个调用点</b>上有一个 {@code @Redirect}，它拿到的就是这个被换过的值，
 * 却当成「装药强度」去反推药重：
 *
 * <pre>
 * scale = min(1, 收到的值 / Σ装药强度)      // 药重按这个比例缩水
 * 初速  = Robins(弹重, 药重 × scale, ...)
 * </pre>
 *
 * <p><b>2026-09-17 实测</b>（青铜炮 / 4 药包 / 穿甲榴弹）：CBcat 给 4.186058，
 * scale = 4.186058 / 8.0 = 0.523，药重 486.37 → 254.50 kg，初速 <b>9.493 格/tick</b>
 * —— 与三发实弹反推的 9.53 吻合。而<b>不复刻这一步</b>（直接问 going_ballistic）会得到 12.985，
 * <b>偏高 37%</b>，打出去会远 2000 多格。
 *
 * <h2>做法：不猜公式，直接用它的类</h2>
 *
 * {@code CustomPropellantContext} 及其 {@code addPropellant} / {@code addBarrel} / {@code getVelocity}
 * 都是 <b>public</b> 的。所以这里把 CBcat 自己的扫描循环（逐格走炮身、药包计入装药、
 * 每个炮管方块调 {@code applyBarrelPhysic}）原样跑一遍，拿到与游戏一字不差的初速 ——
 * 零复刻误差，且它日后调整公式我们也自动跟上。
 *
 * <p>唯一的例外是它自己的 {@code disablePhysicRework} 开关：打开时 CBcat 不接管初速，
 * 那我们就必须返回 0（= 没有改写者），让解算退回 going_ballistic 的原生分支。
 */
public final class CbcAtCompat {

    /** CBcat 的模组 id。 */
    public static final String MOD_ID = "cbc_at";

    /** 沿炮身扫描的上限（格），与 {@link CannonProbe#MAX_SCAN} 一致。 */
    private static final int MAX_SCAN = 512;

    private CbcAtCompat() {
    }

    /**
     * CBcat 算出的「炮口初速」（格/tick）—— 就是它在 shoot 调用点塞进去的那个值。
     *
     * @return CBcat 没接管（未加载 / 开关关闭 / 炮不完整）时返回 {@code 0}
     */
    public static double barrelVelocity(Level level, AbstractMountedCannonContraption cannon) {
        if (cannon == null) {
            return 0.0;
        }

        Direction dir = cannon.initialOrientation();
        BlockPos startPos = cannon.getStartPos();
        if (dir == null || startPos == null) {
            return 0.0;
        }
        // 它自己的开关：打开时 CBcat 不接管初速，我们就不是"改写者"
        if (CBCATConfigs.SERVER == null || CBCATConfigs.SERVER.bigCannons.disablePhysicRework.get()) {
            return 0.0;
        }

        CustomPropellantContext ctx = new CustomPropellantContext();
        boolean allowsMultipleCharges = true;
        List<StructureTemplate.StructureBlockInfo> projectileBlocks = new ArrayList<>();
        AbstractBigCannonProjectile projectile = null;
        BlockPos pos = startPos;

        for (int i = 0; i < MAX_SCAN; i++) {
            if (!(cannon.presentBlockEntities.get(pos) instanceof IBigCannonBlockEntity cbe)) {
                break;
            }
            StructureTemplate.StructureBlockInfo contained = cbe.cannonBehavior().block();
            StructureTemplate.StructureBlockInfo cannonInfo = cannon.getBlocks().get(pos);
            if (cannonInfo == null) {
                break;
            }

            Block block = contained.state().getBlock();
            if (!contained.state().isAir()) {
                boolean handled = false;
                if (block instanceof IProjectileCartridgeBlock projCart) {
                    allowsMultipleCharges = projCart.allowsMultipleCharges();
                    if (!allowsMultipleCharges && ctx.chargesUsed > 0.0F) {
                        ctx.isDoomedToFail();
                    }
                    ctx.addPropellant(projCart, contained, dir);
                    projectileBlocks.add(contained);
                    projectile = projCart.getProjectile(level, projectileBlocks);
                    ctx.chargesUsed += projectile.addedChargePower();
                    handled = true;
                } else if (block instanceof BigCannonPropellantBlock cpropel
                        && !(block instanceof ProjectileBlock)) {
                    ctx.addPropellant(cpropel, contained, dir);
                    if (!allowsMultipleCharges) {
                        ctx.isDoomedToFail();
                    }
                    handled = true;
                }
                if (!handled && projectile == null && block instanceof ProjectileBlock<?> projBlock) {
                    projectileBlocks.add(contained);
                    projectile = projBlock.getProjectile(level, projectileBlocks);
                    ctx.chargesUsed += projectile.addedChargePower();
                }
            }

            // 每个炮管方块都会推动一次"燃气柱"（含装药格与空炮管格），这正是 getVelocity 的主体
            if (cannonInfo.state().getBlock() instanceof IBigCannonBlockPhysics physics) {
                ctx.addBarrel(physics);
            }
            pos = pos.relative(dir);
        }

        return ctx.getVelocity();
    }
}
