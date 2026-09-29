package net.createballistics.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * 本模组的按键。
 *
 * <p>⚠️ 用 {@link KeyMapping} 而不是在代码里直接判键码 —— 这样玩家能在
 * 「选项 → 控制」里自己改（用户 2026-09-22 明确要求 H 键可自定义），
 * 而且改了之后界面上的提示会自动跟着变。
 */
public final class ModKeyMappings {

    /** 控制界面里那一栏的名字。 */
    public static final String CATEGORY = "key.categories.create_ballistics";

    /** H：打开手持火炮指示器的配置界面。 */
    public static final KeyMapping OPEN_INDICATOR = new KeyMapping(
            "key.create_ballistics.open_indicator",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_H,
            CATEGORY);

    // ------------------------------------------------------------------
    // 操控无人机（照卓越前线：它同样是**自己注册一套**，默认键也同样是 WASD / 空格 / 左Shift）
    // ------------------------------------------------------------------

    /**
     * ⚠️ 默认键与"走路 / 上浮 / 潜行"**是同一个物理键**，这是**有意的**，也照它：
     * 操控无人机时玩家本体的移动输入会被清掉（见 {@code DroneControlClient#onMovementInput}），
     * 所以两套键不会互相打架；而不是操控状态时，这几个键只属于原版走路。
     *
     * <p>好处是玩家能在「选项 → 控制」里**单独**改无人机的键，不用连着走路键一起改。
     */
    public static final KeyMapping MOVE_FORWARD = droneKey("move_forward", GLFW.GLFW_KEY_W);
    public static final KeyMapping MOVE_BACK = droneKey("move_back", GLFW.GLFW_KEY_S);
    public static final KeyMapping MOVE_LEFT = droneKey("move_left", GLFW.GLFW_KEY_A);
    public static final KeyMapping MOVE_RIGHT = droneKey("move_right", GLFW.GLFW_KEY_D);
    public static final KeyMapping MOVE_UP = droneKey("move_up", GLFW.GLFW_KEY_SPACE);
    public static final KeyMapping MOVE_DOWN = droneKey("move_down", GLFW.GLFW_KEY_LEFT_SHIFT);

    private static KeyMapping droneKey(String name, int keyCode) {
        return new KeyMapping("key.create_ballistics." + name,
                KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM,
                keyCode,
                CATEGORY);
    }

    /**
     * 「打开配置界面」那个键<b>当前</b>的键名（玩家把 H 改成 G，提示里就显示 G）。
     *
     * <p>⚠️⚠️ <b>放在本类里（而不是调用方那个公共类里）是有意的</b>：{@link KeyMapping} 是
     * 客户端专属的类，专用服务端的分发包里根本没有它 ⇒ 任何"两端都会加载的类"只要在它的
     * **常量池**里出现 {@code net/minecraft/client/KeyMapping}（哪怕是方法描述符里），
     * 就有类加载期解析的风险。搬到这里之后，调用方（{@code ArtilleryIndicatorItem}）
     * 只看到一个"返回 String、且只在侧别判断里面调"的方法 ⇒ 它的常量池里干净了。
     *
     * <p>检查手段：{@code node tools/scan_client_refs.js <产物.jar>} 应当是 <b>0</b>。
     */
    public static String openIndicatorKeyName() {
        return OPEN_INDICATOR.getTranslatedKeyMessage().getString();
    }

    private ModKeyMappings() {
    }
}
