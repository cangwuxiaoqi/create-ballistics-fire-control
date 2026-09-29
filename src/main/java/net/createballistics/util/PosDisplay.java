package net.createballistics.util;

import net.createballistics.cbc.ActiveBallistics;
import net.createballistics.cbc.SableCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * <b>「坐标给玩家看」的唯一入口</b> —— 在航空学物理结构上时把图幅坐标转成世界坐标。
 *
 * <h2>为什么要有这一层（而不是各处直接调 SableCompat）</h2>
 *
 * ① {@link SableCompat} 直接引用了 Sable 的类，<b>没装 Sable 时它连加载都会失败</b>
 *    （{@code NoClassDefFoundError} 在<b>类验证期</b>，类内部的 try/catch 挡不住）⇒
 *    每个调用点都必须先用 {@code ModList} 判断。本类把这个判断收在一处，
 *    于是八九个显示点各自只需要一行 {@code PosDisplay.of(level, p)}。
 *
 * ② 本项目栽过多次"同义实现写两份、改了一处漏了另一处" ⇒
 *    **所有"给玩家看的坐标"必须过这里**，别再各写各的。
 *
 * <h2>⚠️ 只用于显示 —— 不要拿它去做方块查找 / 存档 / 网络同步</h2>
 *
 * 同一个字段常常两者都用（例如"已接入的火控计算机坐标"：既要 {@code level.getBlockEntity(...)}
 * 去找它，又要显示给玩家）。**改字段本身是错的**，只能在"显示"这一刻转。
 * 同理，<b>渲染用的位置一律不转</b> —— 图幅系里的渲染才是对的，Sable 自己会按姿态把它映射到世界。
 */
public final class PosDisplay {

    private PosDisplay() {
    }

    /**
     * 给玩家看的方块坐标。不在物理结构上（或没装 Sable）时<b>原样返回</b>。
     *
     * @return 传入 {@code null} 时返回 {@code null}
     */
    @Nullable
    public static BlockPos of(@Nullable Level level, @Nullable BlockPos levelPos) {
        if (levelPos == null) {
            return null;
        }
        if (!ActiveBallistics.sableLoaded()) {
            return levelPos;   // ⚠️ 这一关不能省：不碰 SableCompat 就不会去加载它
        }
        return SableCompat.displayPos(level, levelPos);
    }

    /**
     * 给玩家看的位置（带小数）。用于实体坐标读数（例如无人机 HUD 的坐标行）。
     * 不在物理结构上时原样返回 ⇒ **世界系里的实体完全不受影响**。
     */
    public static Vec3 of(@Nullable Level level, Vec3 levelPos) {
        if (levelPos == null) {
            return null;
        }
        if (!ActiveBallistics.sableLoaded()) {
            return levelPos;
        }
        return SableCompat.toWorld(level, levelPos);
    }
}
