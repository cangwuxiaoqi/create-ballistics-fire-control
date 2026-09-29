package net.createballistics.client;

import net.createballistics.CreateBallistics;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

/**
 * 进入 / 退出无人机视角时，那点<b>相机类型</b>的状态 —— 只此一处。
 *
 * <h2>为什么进入视角要切到「第三人称背面」（照卓越前线）</h2>
 *
 * 我们的相机由 {@code CameraMixin} 顶到无人机上去，但**玩家的身体还在原地**：
 * <ul>
 *   <li>留在<b>第一人称</b>的话，原版会把玩家自己的手 / 手持物品画在镜头前 ——
 *       于是无人机图传画面上会飘着一条自己的胳膊；</li>
 *   <li>切到<b>第三人称背面</b>，原版不画手，而"第三人称往后拉"那一段
 *       正好被我们 cancel 掉了（{@code CameraMixin} 在 {@code setup} 里就掐断），
 *       所以相机既不在玩家背后、也没有手的干扰。</li>
 * </ul>
 *
 * <p>退出时把玩家<b>原来选的那个</b>视角还回去（他可能一直用第三人称正面，别给他改掉）。
 * ⚠️ 这份"原来是哪个"故意只放客户端静态字段里、不存盘：它只对"这一次操控"有意义，
 * 而切视角本来就是纯客户端的事。
 */
public final class PilotCamera {

    /** 进视角之前的相机类型；{@code null} = 现在没在视角里。 */
    @Nullable
    private static CameraType previous;

    private PilotCamera() {
    }

    /**
     * 现在是不是"正处在无人机视角里"（= {@link #enter()} 记过一笔、还没还回去）。
     *
     * <p>给 {@code DroneControlClient} 的每-tick 对账用：它靠这个闸门决定"要不要补一次 enter"，
     * 而不必去问那个会被**服务端**抢先清掉的 {@code MONITOR_USING} 组件。
     */
    public static boolean isActive() {
        return previous != null;
    }

    /** 进入无人机视角：记下玩家原来的选择，然后切到第三人称背面。 */
    public static void enter() {
        Minecraft mc = Minecraft.getInstance();
        if (previous == null) {
            previous = mc.options.getCameraType();
        }
        // ⚠️ 只在**真的会变**时打一行：这是排查"视角进不去、相机类型反复翻转"的判据
        //   （症状是原版反复弹「第一人称/第三人称」提示）。**不是每 tick 一行**。
        if (mc.options.getCameraType() != CameraType.THIRD_PERSON_BACK) {
            CreateBallistics.LOGGER.info("[火控] 进入无人机视角：相机 {} → THIRD_PERSON_BACK",
                    mc.options.getCameraType());
        }
        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
    }

    /**
     * 退出无人机视角：把相机类型还原。
     *
     * <p>⚠️ **没进过视角时什么都不做** —— 退出这条路会被好几个地方调到
     * （再右键一次、切槽、无人机没了、无人机被炸），不能要求"调用者一定是刚进过的那个人"。
     */
    public static void exit() {
        if (previous != null) {
            // ⚠️ 与 enter() 同一条判据：只在真会变时打一行（排查"相机类型反复翻转"用）
            if (Minecraft.getInstance().options.getCameraType() != previous) {
                CreateBallistics.LOGGER.info("[火控] 退出无人机视角：相机 {} → {}",
                        Minecraft.getInstance().options.getCameraType(), previous);
            }
            Minecraft.getInstance().options.setCameraType(previous);
            previous = null;
        }
    }
}
