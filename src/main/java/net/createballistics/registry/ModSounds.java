package net.createballistics.registry;

import net.createballistics.CreateBallistics;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 本模组的音效。
 *
 * <p>文件抄自卓越前线（它自己的 {@code cannon_zoom_in}），出处记在工程根的 NOTICE 里。
 *
 * <p>目前<b>只用于中键报点</b>。绑定 / 解绑刻意不给音效 —— 2026-09-22 用户明确说不要那一处。
 *
 * <h2>⚠️⚠️ 这里记着一个真 bug，别再犯：服务端<b>不要</b>用 {@code player.playSound(...)} 给玩家放回执音效</h2>
 *
 * {@code Player} 覆写了 {@code playSound(SoundEvent, float, float)}：
 * <pre>
 * public void playSound(SoundEvent sound, float volume, float pitch) {
 *     this.level().playSound(<b>this</b>, this.getX(), this.getY(), this.getZ(), sound, this.getSoundSource(), volume, pitch);
 * }                                    ^^^^ 把自己当成"要排除的玩家"传进去了
 * </pre>
 * 也就是说，它<b>把音效放给周围所有人，唯独把做动作的玩家自己排除在外</b>
 * （{@code Entity.playSound} 传的是 {@code null} = 所有人，包括自己）。
 * ⇒ 症状是「操作生效了、聊天提示也出来了，就是<u>自己</u>听不到声音」，而且<b>换什么音效都没用</b>。
 *
 * <p>2026-09-22 实际踩到：绑定音效就是这么哑掉的，害我先误判成"原版那颗音效太轻"、白换了一轮素材。
 * 正解是显式传 {@code null}：
 * <pre>level.playSound(null, pos, sound, SoundSource.PLAYERS, volume, pitch);</pre>
 * 卓越前线的音效正是这么写的 —— 所以它的响、我们的不响。
 *
 * <p>⚠️ 另外：id 必须与 {@code assets/create_ballistics/sounds.json} 里的键<b>逐字一致</b>，
 * 对不上就是静默无声，而且不会有任何报错。
 */
public class ModSounds {

    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, CreateBallistics.MODID);

    /** 中键报点（确认打击坐标）。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> CANNON_ZOOM_IN = register("cannon_zoom_in");

    /**
     * 无人机飞行时的「嗡嗡」循环音（抄自卓越前线的 {@code vehicle/drone/drone_engine}）。
     *
     * <p>它是<b>循环播放的位置音</b>，不是一次性音效 —— 由客户端在无人机进入世界时起一条
     * {@code DroneEngineSound}（{@code AbstractTickableSoundInstance}）来跟机播放，
     * 音量/音调随飞行速度变、并照原版做了多普勒与淡入淡出。见 {@code client/sound/DroneEngineSound}。
     */
    public static final DeferredHolder<SoundEvent, SoundEvent> DRONE_ENGINE = register("drone_engine");

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(CreateBallistics.id(name)));
    }

    public static void register(IEventBus eventBus) {
        SOUNDS.register(eventBus);
    }
}
