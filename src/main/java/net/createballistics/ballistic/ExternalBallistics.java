package net.createballistics.ballistic;

/**
 * 外弹道物理模型 —— 决定炮弹在空中「怎么被减速」和「怎么被拉下来」。
 *
 * <h2>为什么要有这一层</h2>
 *
 * CBC 原版和「弹道射程优化」(going_ballistic, 下称 GB) 是<b>两套完全不同的物理</b>：
 *
 * <pre>
 *             重力(格/tick²)     阻力
 * CBC 原版     0.05（= 2g）      a = formDrag·|v|，线性；标了 quadratic 才再 ×|v|
 * GB          0.024516625(真实1g) a = k·|v|²，k = 0.5·ρ·Cd·A/m，二维
 * </pre>
 *
 * 本 mod 做成联动兼容：装了 GB 就跟 GB，没装就跟原版。所以解算核心不能把物理写死，
 * 必须由调用方注入。{@code ballistic} 包仍然<b>不依赖 Minecraft</b>，注入的是纯数值对象。
 *
 * <h2>与 CBC 的对齐点</h2>
 *
 * CBC 的 {@code AbstractCannonProjectile} 是这么施加的：
 * <pre>
 * protected Vec3 getForces(Vec3 position, Vec3 velocity) {
 *     return velocity.normalize().scale(-this.getDragForce()).add(0, this.getGravity(), 0);
 * }
 * </pre>
 * 即 <b>阻力永远沿速度的反方向</b>（不是每个轴各衰减一次），大小由 {@code getDragForce()} 给。
 * 所以本接口暴露的是<b>阻力加速度的「大小」</b>，方向由积分器负责。
 *
 * <p><b>注意</b>：线性阻力下，「沿速度反方向施加 {@code d·|v|}」展开后正好等于「每轴 ×(1−d)」，
 * 所以两种写法等价；但<b>二次阻力下不等价</b>，必须按速度方向施加 —— 这是原版兼容的隐藏前提。
 */
public interface ExternalBallistics {

    /** 重力加速度，<b>取正值</b>（格/tick²），积分器会把它往 −Y 施加。 */
    double gravity();

    /**
     * 阻力产生的加速度<b>大小</b>（格/tick²），积分器会沿速度反方向施加。
     *
     * <p>与 CBC 的 {@code getDragForce()} 对齐：它最后会 {@code Math.min(drag, |v|)}
     * 夹一下，防止把速度反向。本方法的实现也应当保持这个上限。
     *
     * @param speed 当前速度大小（格/tick），恒为正
     */
    double dragAcceleration(double speed);

    // ---------------------------------------------------------------- 实现

    /**
     * CBC 原版的线性阻力（也能表达 CBC 自己那个 {@code quadratic_drag} 数据包标记）。
     *
     * @param gravity  重力大小（格/tick²）。CBC 内置炮弹是 <b>0.05</b>（数据包里是 −0.05，
     *                 还要再乘维度的 {@code gravity_multiplier}）
     * @param formDrag 数据包里的 {@code drag}。内置炮弹是 <b>0.01</b>，再乘维度的 {@code drag_multiplier}
     * @param quadratic 数据包里的 {@code quadratic_drag}。开启时阻力再乘一次速度
     */
    record Linear(double gravity, double formDrag, boolean quadratic) implements ExternalBallistics {

        /** CBC 内置炮弹 + 主世界倍率（1.0）下的默认值。 */
        public static final Linear CBC_DEFAULT = new Linear(0.05, 0.01, false);

        public Linear {
            if (!(gravity > 0.0)) {
                throw new IllegalArgumentException("gravity 必须为正（数据包里负值的相反数）");
            }
            if (!(formDrag >= 0.0)) {
                throw new IllegalArgumentException("formDrag 不能为负");
            }
        }

        @Override
        public double dragAcceleration(double speed) {
            double d = formDrag * speed;
            if (quadratic) {
                d *= speed;
            }
            return Math.min(d, speed);
        }
    }

    /**
     * going_ballistic 的二次阻力。
     *
     * <p>系数取自它自己的 {@code ProjectilePhysicsMath.quadraticDragCoefficient}：
     * {@code k = 0.5 · 空气密度 · Cd · π·r² / 弹重}。默认值
     * {@code 空气密度=1.225 / Cd=0.47 / 弹重=穿甲榴弹 3159.9kg / r=装药直径0.754441738242/2}。
     *
     * @param gravity 重力大小。GB 用 {@code ProjectilePhysicsMath.earthGravityBlocksPerTickSquared()}
     *                = <b>0.024516625</b>（真实地球重力；CBC 原版是它的两倍）
     * @param k       二次阻力系数（1/格）。实际加速度 = {@code k·|v|²}
     */
    record Quadratic(double gravity, double k) implements ExternalBallistics {

        /** GB 在「穿甲榴弹 + 主世界倍率 1」下的默认组合。 */
        public static final Quadratic GB_AP_SHELL =
                new Quadratic(0.024516625, 0.5 * 1.225 * 0.47 * Math.PI * Math.pow(0.754441738242 / 2.0, 2) / 3159.9);

        public Quadratic {
            if (!(gravity > 0.0)) {
                throw new IllegalArgumentException("gravity 必须为正");
            }
            if (!(k > 0.0)) {
                throw new IllegalArgumentException("k 必须为正");
            }
        }

        @Override
        public double dragAcceleration(double speed) {
            return Math.min(k * speed * speed, speed);
        }
    }
}
