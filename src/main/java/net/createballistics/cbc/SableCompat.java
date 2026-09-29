package net.createballistics.cbc;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

/**
 * 与 <b>Sable（航空学 / 物理结构）</b> 的对接层。
 *
 * <h2>⚠️⚠️ 调用方必须先用 {@code ActiveBallistics.sableLoaded()} 判断，再调本类</h2>
 *
 * 本类直接引用了 Sable 的类。Sable 在 {@code build.gradle} 里是 <b>compileOnly 可选依赖</b>，
 * 没装它的整合包里这些类根本不存在 —— 而且失败发生在<b>类验证期</b>：
 * <b>本类内部的 try/catch 挡不住 {@code NoClassDefFoundError}</b>，只有"压根不碰这个类"才安全。
 *
 * <p>所以约定是（与 {@code GoingBallisticCompat} 同一套）：
 * <pre>{@code
 *   boolean found = ActiveBallistics.sableLoaded();
 *   Vec3 world = found ? SableCompat.toWorld(level, plotPos) : plotPos;   // ✅ 三元/if，不是提前缓存
 * }</pre>
 * JVM 只在实际执行到那一行时才去加载本类 ⇒ 判断为假就永远不会触碰。
 * <b>不要把这些引用挪到别处、也不要把判断省掉。</b>
 *
 * <p>本类内部的 {@link #available()} 与逐方法 {@code catch(Throwable)} 是<b>第二道</b>保险：
 * 防的是"Sable 装了、但版本漂移导致某个调用抛异常"那种情况（那时退回中性答案，不让火控崩）。
 *
 * <h2>为什么必须有这一层：坐标有两套</h2>
 *
 * Sable 把物理结构的方块搬进一块**保留区块区（"图幅"，plot）**里，用 {@code Pose3d} 把它映射回世界。
 * Sable 自己对实体做的就是这件事（{@code SubLevelHelper#pushEntityLocal / popEntityLocal}）：
 * <pre>
 *   进图幅： pos = pose.transformPositionInverse(pos);  deltaMovement = transformNormalInverse(deltaMovement);
 *   出图幅： pos = pose.transformPosition(pos);         deltaMovement = transformNormal(deltaMovement);
 * </pre>
 * ⇒ <b>在物理结构内部，方块坐标与朝向都是「图幅坐标」，不是世界坐标。</b>
 *
 * <p>对我们的意义：炮的装配体（Create 的 contraption）在 {@code sable:retain_in_sub_level} 标签里
 * ⇒ 它**留在图幅内**，于是 {@code CannonProbe} 用 {@code poce.toGlobalVector(...)} 拿到的炮口位置、
 * 以及写进 CBC 的 {@code cannonPitch/cannonYaw}，<b>全都是图幅系</b>；
 * 而玩家在计算机里输入的打击坐标是**世界系**。
 * ⇒ <b>读侧要转到世界（解算用），写侧要把世界瞄准方向转回图幅（CBC 只认图幅角）。</b>
 *
 * <h2>⚠️ 读与写必须用同一个 pose，否则会变成系统性偏瞄</h2>
 *
 * 本类一律用 {@code subLevel.logicalPose()}（服务端权威姿态），
 * <b>不要</b>换成渲染用的 {@code renderPose(partialTicks)} / {@code projectOutOfSubLevel} 那条路
 * （后者在客户端会取插值姿态）—— 读写两次取到不同的姿态，误差就是系统性的。
 *
 * <h2>⚠️ 前提：结构缩放必须 ≈ 1</h2>
 *
 * {@code transformNormal} 会乘 {@code scale}、反向会除 {@code scale}。
 * 非均匀缩放会让"沿炮轴的长度"随方向变化 ⇒ 我们反解出来的 {@code barrelLength} 不能跨坐标系直接用。
 * 所以 {@link #scaleIsUsable} 单独暴露出来，不满足时上层要**明说"不支持"**而不是硬算。
 */
public final class SableCompat {

    /** Sable 的模组 id。 */
    public static final String MOD_ID = "sable";

    private static boolean checked = false;
    private static boolean loaded = false;

    private SableCompat() {
    }

    /** Sable 在不在场。只问一次，之后走缓存。 */
    public static boolean available() {
        if (!checked) {
            checked = true;
            try {
                loaded = ModList.get().isLoaded(MOD_ID);
            } catch (Throwable t) {
                loaded = false;
            }
        }
        return loaded;
    }

    /**
     * 取这个位置所在的物理结构；不在任何物理结构上（或 Sable 不在场）时返回 null。
     *
     * <p>⚠️⚠️ <b>服务端与客户端走的是两套查询</b>，Sable 自己就把它们分开了
     * （{@code getContaining(Level,…)} 与 {@code getContainingClient(…)}，后者内部用
     * {@code SableDistUtil.getClientLevel()} 而<b>不是</b>传进去的那个 level）。
     * 客户端用错那一套的后果很隐蔽：<b>查询静默失败 ⇒ 静默不转换 ⇒ 坐标看着"没变"</b>，
     * 于是你会以为是"这里不需要转"。**客户端必须走 {@code getContainingClient}。**
     */
    @Nullable
    private static SubLevel subLevelAt(Level level, Vec3 pos) {
        if (!available() || level == null || pos == null) {
            return null;
        }
        try {
            // BlockPos/Vec3 都实现了 Vec3i，走的都是 getContaining*(Vec3i) 那个重载。
            BlockPos blockPos = BlockPos.containing(pos);
            return level.isClientSide
                    ? Sable.HELPER.getContainingClient(blockPos)
                    : Sable.HELPER.getContaining(level, blockPos);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 这个方块位置是否在某个物理结构上。 */
    public static boolean isOnPhysicsStructure(@Nullable Level level, @Nullable BlockPos pos) {
        return level != null && pos != null && subLevelAt(level, Vec3.atCenterOf(pos)) != null;
    }

    /**
     * 这一点所在结构<b>「上一 tick → 这一 tick」的位移</b>（世界系，格）；不在结构里时返回零矢量。
     *
     * <p>⭐ 这是"让实体跟着平台走"的<b>正确</b>做法：取 {@code logicalPose} 与 {@code lastPose}
     * 在<b>同一个局部点</b>上的世界坐标差 = <b>刚体位移本身</b>，不多不少 ⇒
     * <b>不可能超量、不可能振荡</b>。把它**加**在实体位置上即可（别去"硬拉"回某个固定点 ——
     * 那会表现为"往后飘一段又被强行拉回"，2026-09-26 用户实测 ✗）。
     *
     * <p>⚠️ 与 {@link #velocityAt} 的差别：那个问的是**速度**（走物理句柄，含 {@code ω×r} 那一项，
     * 坐标系容易喂错 ⇒ 我一用就超量 ✗ —— 实测会"带走 ⇒ 冲出包围盒 ⇒ 不再带 ⇒ 落后 ⇒ 又回盒"地振荡）；
     * 这个只用**前后两帧姿态**，跟 Sable 自己客户端那条近似算法同源 ✓。
     *
     * <p>⭐⭐ <b>服务端与客户端用的是同一个公式，两侧都要调</b>（2026-09-26 定案）：
     * 服务端调它 = 甲板把它带走；客户端调它 = <b>预测</b>甲板会把它带走。
     * 只在服务端调的话，客户端仍然只能"听包" ⇒ 位置**永远落后包目标约 {@code steps} 个 tick**
     * ⇒ 画面上就是"往后飘一段、又被包拉回"的往复（前四种做法全在改服务端，一个也没治到它）。
     * ⚠️ 客户端那侧读到的姿态来自 {@code ClientSubLevel.tick()}（每客户端 tick 从插值器更新
     * {@code logicalPose}、{@code lastPose} 记上一 tick）⇒ 差值正是"客户端这一 tick 的甲板位移"，
     * 而且与渲染那张 {@code renderPose(partialTick)} 插的是**同一对姿态** ✓。
     */
    public static Vec3 structureDisplacementAt(@Nullable Level level, @Nullable Vec3 worldPos) {
        SubLevel sub = intersectingSubLevel(level, worldPos);
        if (sub == null) {
            return Vec3.ZERO;
        }
        try {
            Vector3d local = sub.logicalPose().transformPositionInverse(
                    new Vector3d(worldPos.x, worldPos.y, worldPos.z));
            Vector3d now = sub.logicalPose().transformPosition(new Vector3d(local));
            Vector3d before = sub.lastPose().transformPosition(local);
            return new Vec3(now.x - before.x, now.y - before.y, now.z - before.z);
        } catch (Throwable t) {
            return Vec3.ZERO;
        }
    }

    /**
     * 这个世界点所在的第一条结构；没有就返回 null。**世界**坐标走这一条（不是 getContaining）。
     *
     * <p>⚠️⚠️ <b>它与 {@code getContaining*} 不同：<b>客户端也能用</b></b>（2026-09-26 核实过实现）——
     * {@code getAllIntersecting} 内部只做"取这个 level 的 {@code SubLevelContainer}、问它
     * {@code queryIntersecting(box)}"，而 {@code ClientLevel} 与 {@code ServerLevel} 都是
     * {@code SubLevelContainerHolder}（客户端容器同样实现了 {@code queryIntersecting}）。
     * 会"客户端静默查不到"的只有 {@code getContaining} 那一族（见 {@link #subLevelAt} 的说明）。
     *
     * <p>客户端要不要用它 ⇒ 见 {@link #structureDisplacementAt} 与 {@code DroneEntity} 里的
     * "客户端预测甲板位移"那段。
     */
    @Nullable
    private static SubLevel intersectingSubLevel(@Nullable Level level, @Nullable Vec3 worldPos) {
        if (!available() || level == null || worldPos == null) {
            return null;
        }
        try {
            for (Object obj : Sable.HELPER.getAllIntersecting(level,
                    new BoundingBox3d(BlockPos.containing(worldPos)))) {
                if (obj instanceof SubLevel sub) {
                    return sub;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 结构自身的缩放是不是 ≈ 1。不满足时"沿炮轴的长度"不能跨坐标系用（见类注释）。
     * 不在物理结构上时返回 {@code true}（没结构 ⇒ 不涉及这件事）。
     */
    public static boolean scaleIsUsable(@Nullable Level level, @Nullable Vec3 pos) {
        SubLevel subLevel = subLevelAt(level, pos);
        if (subLevel == null) {
            return true;
        }
        try {
            var scale = subLevel.logicalPose().scale();
            return Math.abs(scale.x() - 1.0) < 1.0E-3
                    && Math.abs(scale.y() - 1.0) < 1.0E-3
                    && Math.abs(scale.z() - 1.0) < 1.0E-3;
        } catch (Throwable t) {
            return false;   // 读不出来就当不可用 —— 宁可明说不支持，也不要算歪
        }
    }

    /**
     * <b>图幅坐标 → 世界坐标</b>（点）。不在物理结构上时<b>原样返回</b>，
     * 所以调用点不需要自己判断"有没有上船"。
     */
    public static Vec3 toWorld(@Nullable Level level, Vec3 plotPos) {
        SubLevel subLevel = subLevelAt(level, plotPos);
        if (subLevel == null) {
            return plotPos;
        }
        try {
            return subLevel.logicalPose().transformPosition(plotPos);
        } catch (Throwable t) {
            return plotPos;
        }
    }

    /**
     * <b>世界瞄准方向 → 物理结构局部的 (yaw, pitch)</b>（单位：度，MC 约定）。
     *
     * <p>解算全程在世界系里做（因为炮弹出生就被搬进世界系，见
     * {@code sable.mixin.entity.entity_kicking.ServerLevelMixin}），
     * 而写进 CBC 的 {@code cannonPitch/cannonYaw} 只认图幅系 ⇒ 出口处转这一次。
     *
     * @return {@code {yaw, pitch}}；不在物理结构上时返回 {@code null}
     *         （调用方应当原样使用世界角度 —— 那是没装 Sable 时的既有行为）
     */
    @Nullable
    public static float[] worldAimToLocal(@Nullable Level level, @Nullable Vec3 plotPos,
                                          double worldYawDeg, double worldPitchDeg) {
        SubLevel subLevel = subLevelAt(level, plotPos);
        if (subLevel == null) {
            return null;
        }
        try {
            // 世界角度 → 世界方向向量（MC 约定：yaw 0 = +Z，90 = −X；pitch 正 = 上）
            double yaw = Math.toRadians(worldYawDeg);
            double pitch = Math.toRadians(worldPitchDeg);
            double horizontal = Math.cos(pitch);
            Vector3d world = new Vector3d(-Math.sin(yaw) * horizontal, Math.sin(pitch),
                    Math.cos(yaw) * horizontal);
            // 世界方向 → 图幅方向（纯旋转，不含平移）
            Vector3d local = subLevel.logicalPose().transformNormalInverse(world, new Vector3d());
            // 图幅方向 → 图幅角度
            double localHorizontal = Math.sqrt(local.x() * local.x() + local.z() * local.z());
            float localYaw = (float) Math.toDegrees(Math.atan2(-local.x(), local.z()));
            float localPitch = (float) Math.toDegrees(Math.atan2(local.y(), localHorizontal));
            return new float[]{localYaw, localPitch};
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 物理结构<b>在这一点上</b>的速度（格/tick，世界系）—— 含结构自身的平移**与**转动带出来的那一部分。
     * 不在物理结构上时返回 {@link Vec3#ZERO}。
     *
     * <p>用途：判断"载具在动"（见实时计算状态机）。
     */
    public static Vec3 velocityAt(@Nullable Level level, Vec3 plotPos) {
        SubLevel subLevel = subLevelAt(level, plotPos);
        if (subLevel == null) {
            return Vec3.ZERO;
        }
        try {
            return Sable.HELPER.getVelocity(level, plotPos);
        } catch (Throwable t) {
            return Vec3.ZERO;
        }
    }

    /**
     * 给玩家看的方块坐标：在物理结构上时转成<b>世界坐标</b>，其余情况原样返回。
     *
     * <p>⚠️ <b>只用于显示</b> —— 方块查找、存档、网络同步**一律继续用原坐标**
     * （同一个字段常常两者都用，比如"已接入的火控计算机坐标"：既要 `level.getBlockEntity(...)`
     * 去找它，又要显示给玩家 —— <b>千万别去改字段本身</b>，只在这里转显示）。
     */
    public static BlockPos displayPos(@Nullable Level level, BlockPos levelPos) {
        if (levelPos == null) {
            return null;
        }
        return BlockPos.containing(toWorld(level, Vec3.atCenterOf(levelPos)));
    }

    /**
     * <b>「眼睛位置 + 视线方向」整体从图幅系转到世界系</b>；不在物理结构上时返回 {@code null}
     * （调用方原样使用原值）。
     *
     * <p>为什么合成一个方法、而不是让调用方分两次转：<b>查物理结构必须用「转之前」的眼睛</b> ——
     * 先转眼睛再拿世界坐标去查，什么都查不到（图幅区在 2896 万格外）。
     *
     * <p>用途：准星取点（{@code CrosshairPick}）。在物理结构上时射线要在<b>世界系</b>里打，
     * 因为**图幅区里根本没有外面的地形** —— 在那里打射线只能碰到自家船的方块。
     *
     * @return {@code {世界眼, 世界视线}}，两者都是单位/原始尺度不变的方向量（纯旋转）
     */
    @Nullable
    public static Vec3[] eyeAndLookToWorld(@Nullable Level level, Vec3 eye, Vec3 look) {
        SubLevel subLevel = subLevelAt(level, eye);
        if (subLevel == null) {
            return null;
        }
        try {
            Pose3dc pose = subLevel.logicalPose();
            Vec3 worldEye = pose.transformPosition(eye);
            // ⚠️ 方向走 transformNormal（局部→世界），与整数值无关（scale 只影响长度，方向归一化后无碍）。
            Vector3d worldLook = pose.transformNormal(
                    new Vector3d(look.x, look.y, look.z), new Vector3d());
            return new Vec3[]{worldEye, new Vec3(worldLook.x, worldLook.y, worldLook.z)};
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 物理结构当前姿态的四元数（x, y, z, w）；不在物理结构上时返回 {@code null}。
     *
     * <p>用途：判断"载具在转动"。位置不动但原地打转的载具，炮塔一样要追 ——
     * 上层把相邻两 tick 的四元数一比就知道转了没有（本类刻意不存状态）。
     */
    @Nullable
    public static double[] orientationAt(@Nullable Level level, @Nullable Vec3 plotPos) {
        SubLevel subLevel = subLevelAt(level, plotPos);
        if (subLevel == null) {
            return null;
        }
        try {
            var q = subLevel.logicalPose().orientation();
            return new double[]{q.x(), q.y(), q.z(), q.w()};
        } catch (Throwable t) {
            return null;
        }
    }
}
