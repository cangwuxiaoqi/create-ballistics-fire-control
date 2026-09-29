package net.createballistics.block.entity;

/**
 * 「范围显示可以开关」的方块实体。
 *
 * <p>火控协调器与火控计算机都实现了它 —— 它们的区别只是半径（16 / 64 格），
 * 而扳手那一边不需要知道是哪一个，问这个接口就够了。
 */
public interface RangeDisplayToggle {

    /** 范围显示当前开着没有。 */
    boolean isRangeVisible();

    /** 切换范围显示（扳手右键调它）。 */
    void toggleRangeVisible();
}
