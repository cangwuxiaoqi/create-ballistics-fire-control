package net.createballistics.ballistic;

/**
 * 一组射击诸元。
 *
 * <p>同一个目标点通常有两个解：<b>低伸</b>（弹道平直、飞行时间短）与<b>高抛</b>
 * （弹道弯曲、可用于越障与攻顶）。二者在"最大射程角"处合流。
 *
 * @param pitch     仰角（度，<b>正值向上</b> —— 与 MC 内部的 xRot 约定相反，
 *                  交给 CBC 前要用 {@link #toCannonPitch(double)} 转符号）
 * @param yaw       方位角（度，MC 约定：0=+Z、90=−X、180=−Z、270=+X）
 * @param charges   装药数
 * @param airTime   飞行时间（tick）
 * @param residual  解算残差：两个独立飞行时间估计之差（tick）。越接近 0 越可信；
 *                  可作为"这条解有多准"的廉价指标，无需真模拟
 * @param arc       低伸还是高抛
 */
public record FiringSolution(
        double pitch,
        double yaw,
        int charges,
        double airTime,
        double residual,
        Arc arc
) {

    public enum Arc {
        /** 低伸（平射）弹道：飞行时间短，受风偏/散布影响小。 */
        FLAT,
        /** 高抛（曲射）弹道：可越障、可攻顶，但飞行时间长。 */
        HIGH
    }

    public double airTimeSeconds() {
        return airTime / 20.0;
    }

    /** 是否在指定的仰角范围内（含端点，允许 1e-6 的数值毛刺）。 */
    public boolean withinPitchRange(double minPitch, double maxPitch) {
        return pitch >= minPitch - 1e-6 && pitch <= maxPitch + 1e-6;
    }

    @Override
    public String toString() {
        return String.format("%s 仰角 %.4f° 方位 %.3f° 装药 %d 飞行 %.2fs (残差 %.6f)",
                arc == Arc.FLAT ? "低伸" : "高抛", pitch, yaw, charges, airTimeSeconds(), residual);
    }
}
