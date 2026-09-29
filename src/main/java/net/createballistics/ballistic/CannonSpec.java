package net.createballistics.ballistic;

/**
 * 一门炮的静态射击参数。
 *
 * <p>由调用方（mod 侧）从 CBC 的炮座 / 火炮结构里读出后注入。整个 {@code ballistic} 包
 * <b>不依赖 Minecraft</b>，因此可以在普通 JVM 里跑数值自检。
 *
 * @param velocityPerCharge 每份装药提供的初速（格/tick）。CBC 内置火药 strength = 2，
 *                          即初速在数值上等于总装药强度，<b>与炮管长度无关</b>
 *                          （炮管长度只影响散布）。
 * @param barrelLength      炮口相对炮座枢轴的<b>前伸量</b>（格），用来把弹道起点摆到炮口。
 *
 *                           <p>⚠️ <b>这不是炮管的物理长度，而且和初速公式里的"管长"不是同一个量。</b>
 *                           两边各说各的：
 *                           <ul>
 *                             <li>CBC 在 {@code MountedBigCannonContraption.fireShot} 里把炮弹生成在
 *                                 「炮口方块再往外 1 格、再沿炮轴推 2 格」处 —— 所以 17 格炮
 *                                 （炮闩1 + 炮腔4 + 炮筒12）对应的前伸量是 <b>19.5</b>。</li>
 *                             <li>going_ballistic 算初速时用的是它自己的 {@code BarrelProfile.lengthMeters}
 *                                 —— 从炮闩那端逐格累加、每格 1 米，**含炮闩和炮腔**，所以是 <b>17</b>。</li>
 *                           </ul>
 *                           两个数混用会带来约 2.5 格的位置偏差和一段初速误差。
 * @param minPitch          最低仰角（度）。负数表示向下。CBC 普通炮座是 −30
 * @param maxPitch          最高仰角（度）。CBC 普通炮座是 60，迫击炮尾是 85
 * @param minCharges        最小装药数
 * @param maxCharges        最大装药数
 * @param ballistics        弹道物理参数
 */
public record CannonSpec(
        double velocityPerCharge,
        double barrelLength,
        double minPitch,
        double maxPitch,
        int minCharges,
        int maxCharges,
        ExternalBallistics ballistics
) {

    public CannonSpec {
        if (!(velocityPerCharge > 0.0)) {
            throw new IllegalArgumentException("velocityPerCharge 必须为正");
        }
        if (!(barrelLength > 0.0)) {
            throw new IllegalArgumentException("barrelLength 必须为正");
        }
        if (!(minPitch < maxPitch)) {
            throw new IllegalArgumentException("minPitch 必须小于 maxPitch");
        }
        if (minCharges < 1 || maxCharges < minCharges) {
            throw new IllegalArgumentException("装药范围非法: " + minCharges + ".." + maxCharges);
        }
    }

    /** 指定装药数时的炮口初速（格/tick）。 */
    public double velocityFor(int charges) {
        return charges * velocityPerCharge;
    }

    /** 一个便于对照 CBC 真实炮座构造的副本。 */
    public CannonSpec withPitchRange(double newMinPitch, double newMaxPitch) {
        return new CannonSpec(velocityPerCharge, barrelLength, newMinPitch, newMaxPitch,
                minCharges, maxCharges, ballistics);
    }
}
