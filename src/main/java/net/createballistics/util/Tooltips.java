package net.createballistics.util;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * 多行 tooltip 文案 —— <b>唯一实现</b>（本模组的物品与三方块都调它）。
 *
 * <h2>⚠️ 为什么不把 {@code \n} 直接交给渲染器</h2>
 *
 * 实测过同一份写法（JSON 里是正统的 {@code \n} 转义、解析出来就是真换行符），
 * <b>两处表现不一致</b>：火炮指示器那条断得好好的，侦测无人机那条却整串挤成一行、
 * 换行的地方渲染成一个<b>缺字方块</b>。
 *
 * <p>与其去猜"渲染器什么时候肯断"，不如自己拆：<b>每条 {@code tooltip.add} 就是一行</b>，
 * 任何渲染路径下都成立。语言键里照旧可以写成多行（好读、好翻译）。
 */
public final class Tooltips {

    private Tooltips() {
    }

    /**
     * 把一条语言键按 {@code \n} 拆开、逐行加进 tooltip。
     *
     * @param style 每一行的颜色；{@code null} = 不改颜色
     * @param args  语言键里的 {@code %s} 参数（与 {@link Component#translatable} 同一套）
     */
    public static void addLines(List<Component> tooltip, String key, ChatFormatting style, Object... args) {
        String text = Component.translatable(key, args).getString();
        // split 带 -1：中间/末尾的空段要留下来，不然"空行"会被吃掉
        for (String line : text.split("\n", -1)) {
            // ⚠️ withStyle 只在 MutableComponent 上有（Component 上没有）—— literal/translatable 返回的都是它
            MutableComponent component = Component.literal(line);
            tooltip.add(style == null ? component : component.withStyle(style));
        }
    }
}
