package net.createballistics.client;

import net.createballistics.cbc.ActiveBallistics;
import net.createballistics.cbc.SableCompat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * <b>「准星指着什么」的唯一实现</b> —— 图传的测距、图传与指示器的中键取点都用它。
 *
 * <p>照卓越前线，这三处其实各写了一份（它的 {@code TraceTool.droneFindLookingEntity}、
 * {@code SeekTool.seekLivingEntity}…）。我们在本模组里只留这一份：
 * 否则"测距说有实体"与"中键锁得到它"会各判各的，玩家一眼就能看出不对劲
 * （本模组"同义判据写两份"已经栽过）。
 */
public final class CrosshairPick {

    /** 射线长度。测距、中键取点、实体锁定走的都是这一条线。 */
    public static final double RAY_LENGTH = 512.0;

    private CrosshairPick() {
    }

    /**
     * 射线上第一个<b>能当目标</b>的实体。过滤器照它 {@code droneFindLookingEntity} 里那个 lambda：
     * 活着 / 非观察者 / 非弹丸 / 不是自己。
     *
     * <p>⚠️ 它<b>不看有没有墙挡着</b> —— 这是照抄它的语义（它那一路就是纯射线 + 距离，没有遮挡判定）。
     * 而图传里"实体框"那条用的是另一个判据（{@code SeekTool#noClip}，<b>要求有视线</b>），
     * 两者刻意不同：框是"看得见的"，锁定是"准星指着的"。
     */
    @Nullable
    public static Entity lookingEntity(Entity shooter, Vec3 from, Vec3 look) {
        Vec3 to = from.add(look.scale(RAY_LENGTH));
        // ⚠️ 搜索盒用**射线自己的** from→to 建，**不要**用 shooter 的包围盒 ——
        //    在物理结构上时射线已经在世界系，而 shooter 还在图幅系，混两套坐标系会一个实体都搜不到。
        //    （不在结构上时两者本来就同系 ⇒ 与原来等价。）
        AABB scan = new AABB(from, to).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(shooter, from, to, scan,
                e -> !e.isSpectator() && e.isAlive() && !(e instanceof Projectile) && e != shooter,
                RAY_LENGTH * RAY_LENGTH);
        return hit != null ? hit.getEntity() : null;
    }

    /**
     * 中键要报出去的那一个坐标。照卓越前线的两步：
     * <ol>
     *   <li>射线打到<b>生物</b> → 取它的位置；</li>
     *   <li>否则射线打到的<b>方块</b> → 取方块中心；</li>
     *   <li>两者都有时**以生物优先**，除非玩家按着<b>潜行</b>（潜行 = 强制只取方块）。</li>
     * </ol>
     *
     * <p>⚠️ 用户 2026-09-22 的那条边界仍然成立：**既没打到方块、也没锁到实体（对着天空）
     * 就返回 {@code null}** —— 宁可"按了没反应"，也不要凭空设一个打不着的目标
     * （卓越前线会把 MISS 结果当成坐标报出去）。
     *
     * <p>⚠️ 潜行键与"无人机下降"是同一个物理键（默认都是左 Shift）⇒ <b>一边下降一边取点时
     * 只会取方块</b>。卓越前线也是这么耦合的，照抄。
     *
     * @return 没取到返回 {@code null}
     */
    @Nullable
    public static Vec3 targetPoint(Player player, Entity shooter, Vec3 eye, Vec3 look) {
        return targetPoint(player.level(), player, shooter, eye, look);
    }

    /**
     * 同上，但显式带上 {@code level} —— 供"眼睛与 {@code level} 不是同一个东西"的调用方使用
     * （例如无人机在另一个维度/另一个坐标系里取点时）。
     */
    @Nullable
    public static Vec3 targetPoint(Level level, Player player, Entity shooter, Vec3 eye, Vec3 look) {
        // ---- ⚠️⚠️ 在航空学物理结构上时，把整条射线搬到**世界系**里打 ----
        //
        // 为什么非这样不可（2026-09-25 飞艇实测，标记出来的坐标是 2896 万格外）：
        // ① 站在飞艇上时，玩家（以及在他手上放出的无人机）的位置就是**图幅坐标**
        //    —— 那是 Sable 给每艘船留的一块保留区块区，离世界原点 2896 万格；
        // ② 而**图幅区里根本没有外面的地形** ⇒ 在原地打射线只能碰到自家船的方块，
        //    标出来的自然是"船上的某一格"（转成世界坐标也只是船上那格的正确位置，
        //    但完全不是玩家想标的敌方/地面目标）；
        // ③ 转到世界系之后，地形是**真的存在**的 ⇒ 射线能打到地面上真正的目标。
        //
        // ⚠️ 转换必须**眼睛 + 视线一起**做，而且**查结构要用转之前的眼睛** ——
        //    这也正是 SableCompat 把两者合成一个方法返回的原因。
        // ⚠️ 不在物理结构上时它返回 null ⇒ 下面逐字走原路径，行为一个字不变。
        Vec3 rayEye = eye;
        Vec3 rayLook = look;
        if (ActiveBallistics.sableLoaded()) {
            Vec3[] world = SableCompat.eyeAndLookToWorld(level, eye, look);
            if (world != null) {
                rayEye = world[0];
                rayLook = world[1];
            }
        }

        Entity looked = player.isShiftKeyDown() ? null : lookingEntity(shooter, rayEye, rayLook);
        if (looked != null) {
            // ⚠️ 实体位置也要：命中的实体可能与射线不同系（船上放的生物/别的船）
            //     —— 不在物理结构上时 toWorld 原样返回，等于没动。
            Vec3 pos = looked.position();
            return ActiveBallistics.sableLoaded() ? SableCompat.toWorld(level, pos) : pos;
        }
        BlockHitResult blockHit = level.clip(new ClipContext(rayEye,
                rayEye.add(rayLook.scale(RAY_LENGTH)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, shooter));
        if (blockHit.getType() != HitResult.Type.BLOCK) {
            return null;   // 准星对着天空 —— 按设计不报坐标
        }
        return Vec3.atCenterOf(blockHit.getBlockPos());
    }
}
