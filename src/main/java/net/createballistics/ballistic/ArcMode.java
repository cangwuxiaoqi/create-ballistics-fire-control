package net.createballistics.ballistic;

import java.util.Locale;

/**
 * 弹道弧线模式 —— 玩家想让这门炮用哪条弹道。
 *
 * <p>同一个目标点通常有两个解（见 {@link FiringSolution.Arc}）：<b>低伸</b>飞行时间短、更准，
 * <b>高抛</b>能越障与攻顶。本枚举就是"选哪条"的开关。
 *
 * <p><b>为什么在 {@code ballistic} 包里而不是方块里</b>：它是纯语义（跟 Minecraft 无关），
 * 而节点、协调器、计算机、网络包都要引用它 —— 挂在某个方块实体里会让别的层反过来依赖方块。
 * 这个包不依赖 Minecraft，所以谁都能用。
 */
public enum ArcMode {

    /**
     * 自动 —— 默认低伸；只有<b>地形校验真的识别出低伸被挡住</b>时才改用高抛。
     *
     * <p>地形判断不了时（目标那一段区块没加载）退回低伸并如实告知，见 {@code TerrainCheck}。
     */
    AUTO,
    /** 强制低伸。解不出来就报原因，<b>不会</b>自作主张换高抛。 */
    FLAT,
    /** 强制高抛。解不出来就报原因。 */
    HIGH;

    public ArcMode next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /**
     * 语言键。<b>复用协调器那边早就写好、但一直没有消费者的三条翻译</b>
     * （{@code block.create_ballistics.mode.auto / .flat / .high}）。
     */
    public String langKey() {
        return "block.create_ballistics.mode." + name().toLowerCase(Locale.ROOT);
    }

    /** 从存档里的名字还原；认不出来时退回 {@link #AUTO}。 */
    public static ArcMode byName(String name) {
        for (ArcMode m : values()) {
            if (m.name().equals(name)) {
                return m;
            }
        }
        return AUTO;
    }
}
