package net.createballistics.util;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;

/**
 * 「哪些方块的范围显示被打开了」—— <b>客户端侧的记录</b>。
 *
 * <h2>为什么要这么个东西</h2>
 *
 * 范围显示由<b>扳手右键切换</b>，开关状态存在方块实体里。可是渲染发生在客户端，
 * 而且范围框有 64 格那么大 —— 玩家一旦转身走开，准星就离开方块了，渲染器不可能靠
 * 「玩家正在看着谁」来找该画哪个框。所以必须有一份「谁开着」的名单。
 *
 * <p>MC 没有「遍历已加载的方块实体」这种 API，于是换成<b>登记制</b>：
 * 方块实体在客户端 {@code onLoad()} 时照着自己的开关状态来这里登记，
 * 渲染器只遍历这份名单。
 *
 * <h2>它是公共代码，但只在客户端被填</h2>
 *
 * 类本身放在公共包里（{@code util}），因为方块实体是公共代码、服务端也要加载这个类。
 * 但它<b>只在 {@code level.isClientSide} 为真时被写入</b>，服务端那份永远是空集合。
 * 里面只有 {@link BlockPos}，不牵扯任何客户端专属类型，所以放在公共包是安全的。
 */
public final class RangeDisplayState {

    private static final Set<BlockPos> VISIBLE = new HashSet<>();

    private RangeDisplayState() {
    }

    /** 登记 / 注销一个方块的范围显示。方块实体在客户端加载与切换开关时调用。 */
    public static void set(BlockPos pos, boolean visible) {
        BlockPos key = pos.immutable();
        if (visible) {
            VISIBLE.add(key);
        } else {
            VISIBLE.remove(key);
        }
    }

    /**
     * 当前所有开着范围显示的方块。
     *
     * <p><b>渲染器可以直接改这个集合</b>（比如发现某个坐标已经不是我们的方块了就剔掉）——
     * 它是故意的，不然还得再开一套清理接口。
     */
    public static Set<BlockPos> visible() {
        return VISIBLE;
    }

    /** 换存档 / 退出世界时清空 —— 名单是按世界来的，跨世界留着会画出幽灵框。 */
    public static void clear() {
        VISIBLE.clear();
    }
}
