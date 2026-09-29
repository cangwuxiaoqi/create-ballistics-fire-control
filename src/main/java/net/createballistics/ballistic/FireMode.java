package net.createballistics.ballistic;

import java.util.Locale;

/**
 * 火力模式 —— <b>按下「开火！」之后，全网怎么打</b>。
 *
 * <p>和 {@link ArcMode} 的分工别搞混：
 * <ul>
 *   <li>{@link ArcMode} 管<b>每一门炮用哪条弹道</b>（低伸 / 高抛），是逐炮各自判断的；</li>
 *   <li>本枚举管<b>整个炮群按什么节奏开火</b>，是全网统一的。</li>
 * </ul>
 *
 * <p>放在 {@code ballistic} 包里而不是方块里，理由同 {@link ArcMode}：它是纯语义，
 * 而计算机 / 协调器 / 节点 / 网络包都要引用它。
 */
public enum FireMode {

    /** <b>齐射</b>：所有炮同时开炮。默认值。 */
    SALVO,

    /**
     * <b>同时弹着（TOT）</b>：按各自飞行时间<b>错开击发时刻</b>，让所有炮弹<b>同一瞬间落地</b>。
     *
     * <p>飞行时间长的（远炮）先开火，近炮等着 —— 这样落点是同时的。
     * 意义在于<b>远近混编</b>的炮群：同型炮本来就差不多同时落地，混编时才会差出几十秒。
     */
    TOT,

    /**
     * <b>轮射</b>：同样按飞行时间倒推击发时刻，但目标是让<b>落点等间隔</b>排开
     * （每发间隔玩家设定的 {@code d} 秒），形成"一发接一发打中目标"的节奏。
     *
     * <p>和 {@link #TOT} 是<b>同一块地基的两端</b>：
     * TOT 让落点间隔为 <b>0</b>，轮射让落点间隔为 <b>d</b>。两者都靠"落点时刻 − 自己的飞行时间"算击发刻。
     *
     * <p>顺序按<b>飞行时间从长到短</b>排（最远那门落第一个、也最先开火）。
     * 这样排的好处是击发时刻天然递增、<b>永远不会出现负延时</b> ——
     * 用户 2026-09-20 明确取消了"按协调器编号排序"的要求，远炮近炮谁先开火都无所谓
     * （玩家看的是打击目标那一头）。
     */
    ROLLING,

    /**
     * <b>范围火力覆盖</b>：各炮<b>不打同一个点</b>，而是绕目标铺开成一个圆面，
     * 用多门炮覆盖一整片区域（而不是戳一个点）。
     *
     * <p>⚠️ <b>一次铺开</b>：点数 = 炮数，一轮齐射打满一片，打完就没了。
     * 要"反复犁地"得多波覆盖 —— 那取决于装填产线，是另一件事（用户 2026-09-20 定的先做一次铺开）。
     *
     * <p>⚠️ 所以这门数少的时候点会很稀（4 门炮配半径 50 格 ⇒ 点间距三十多格），
     * 界面上要把"大约每点间隔多少格"告诉玩家，让他自己决定要不要把半径调小。
     */
    COVERAGE;

    public FireMode next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /** 语言键。 */
    public String langKey() {
        return "block.create_ballistics.firemode." + name().toLowerCase(Locale.ROOT);
    }

    /** 从存档里的名字还原；认不出来时退回 {@link #SALVO}。 */
    public static FireMode byName(String name) {
        for (FireMode m : values()) {
            if (m.name().equals(name)) {
                return m;
            }
        }
        return SALVO;
    }
}
