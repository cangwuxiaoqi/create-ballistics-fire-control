package net.createballistics.client;

import com.simibubi.create.AllSpecialTextures;
import java.util.Iterator;
import net.createballistics.CreateBallistics;
import net.createballistics.block.entity.FireControlComputerBlockEntity;
import net.createballistics.block.entity.FireControlCoordinatorBlockEntity;
import net.createballistics.block.entity.RangeDisplayToggle;
import net.createballistics.util.RangeDisplayState;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 画出火控协调器 / 火控计算机的<b>生效范围</b>。
 *
 * <h2>怎么开</h2>
 *
 * <b>扳手右键</b>方块切换（见 {@code AbstractFireControlMachineBlock.onWrenched}）：
 * 右键一下开、再右键一下关。开关状态存在方块实体里，会随存档保留。
 *
 * <p>早先的版本是"准星对准方块 + 按住 TAB"，已废弃 —— 范围有 64 格那么大，
 * 玩家必须转头才看得全，而一转头脑门上的准星就离开方块、框立刻消失，根本没法看。
 *
 * <h2>怎么画的（照抄「机械动力：赛博护目镜」）</h2>
 *
 * <pre>
 * Outliner.showAABB(slot, box)
 *         .withFaceTextures(CHECKERED, HIGHLIGHT_CHECKERED)   // ← 关键
 *         .lineWidth(1/16).colored(color);
 * </pre>
 *
 * <b>{@code withFaceTextures} 是全部要点</b>：它给盒子的六个面铺上 Create 的半透明棋盘格贴图，
 * 于是范围变成一个<b>实心可见的方块</b>；只画 12 条边线的话，3×3×3 是 33 格、64 半径是 129 格，
 * 那么远的线在视野里根本看不见（这是上一版最大的毛病）。地下那一半会被地形挡住，
 * 看上去就"落在地面上"。
 *
 * <h2>绿色填充 + 红色边框</h2>
 *
 * <ul>
 *   <li><b>绿色（填充）</b> = 有效识别范围。扫描走的就是这个体积，里面的方块/火炮一定被认到。
 *       语义与「赛博护目镜」一致 —— 绿就是"有效范围"，<b>不</b>表示"这个方块在工作"。</li>
 *   <li><b>红色（只有边框，在外一圈）</b> = 边界。方块摆到这一层就<b>已经扫不到</b>了 ——
 *       让玩家一眼看出"别再往外放了"，不用去试。</li>
 * </ul>
 *
 * ⚠️ 红色**故意不填充**：套一个填充盒子在外面会把绿色整个包住，看到的只有红色，
 * 分界反而没了。所以红色只画加粗的 12 条棱，才是"边框"该有的样子。
 */
@EventBusSubscriber(modid = CreateBallistics.MODID, value = Dist.CLIENT)
public final class RangeOutlineRenderer {

    /** 线宽（格）。绿框与 Create 调试框、赛博护目镜一致的 1/16。 */
    private static final float LINE_WIDTH = 1.0F / 16.0F;
    /**
     * 红框的线宽（格）。<b>故意比绿框粗得多</b> —— 它离玩家可能有几十上百格，
     * 1/16 的细线在那么远的地方等于看不见（这正是前一版"范围框完全看不见"的原因）。
     */
    private static final float EDGE_LINE_WIDTH = 0.25F;
    /** 红框比绿框外扩多少格。1 格 = 恰好是"再往外一层就扫不到"。 */
    private static final int EDGE_MARGIN = 1;

    /** 绿 = 有效识别范围。 */
    private static final int COLOR_VALID = 0x55FF55;
    /** 红 = 边界（这一层已经扫不到了）。 */
    private static final int COLOR_EDGE = 0xFF5555;

    /**
     * 上一帧渲染的是哪个世界。
     *
     * <p>它只用来判断"换存档了没有" —— 名单是按世界记的，不换世界就清空的话，
     * 从上个存档带过来的坐标会在新世界里画出幽灵框。
     */
    private static ClientLevel lastLevel;

    private RangeOutlineRenderer() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            RangeDisplayState.clear();
            lastLevel = null;
            return;
        }
        if (level != lastLevel) {
            RangeDisplayState.clear();
            lastLevel = level;
        }

        Outliner outliner = Outliner.getInstance();
        for (Iterator<BlockPos> it = RangeDisplayState.visible().iterator(); it.hasNext(); ) {
            BlockPos pos = it.next();

            // 区块没加载 ≠ 被拆了 —— 这种情况保留开关，不然玩家跑远一趟回来设置就没了。
            if (!level.isLoaded(pos)) {
                continue;
            }

            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof RangeDisplayToggle toggle) || !toggle.isRangeVisible()) {
                // 方块没了 / 被关掉了：清掉线框和名单（自愈，不依赖拆除时有人来通知）
                outliner.remove(slot(pos, false));
                outliner.remove(slot(pos, true));
                it.remove();
                continue;
            }

            int radius = scanRadius(be);
            if (radius <= 0) {
                continue;
            }

            // 绿：有效识别范围，填充棋盘格
            outliner.showAABB(slot(pos, false), new AABB(pos).inflate(radius))
                    .withFaceTextures(AllSpecialTextures.CHECKERED, AllSpecialTextures.HIGHLIGHT_CHECKERED)
                    .lineWidth(LINE_WIDTH)
                    .colored(COLOR_VALID);

            // 红：边界，只画加粗的棱、不填充（填充会把绿色整个包住，分界就没了）
            outliner.showAABB(slot(pos, true), new AABB(pos).inflate(radius + EDGE_MARGIN))
                    .lineWidth(EDGE_LINE_WIDTH)
                    .colored(COLOR_EDGE);
        }
    }

    /**
     * 每个方块、每个框一个 slot —— 多个框可以同时开着，互不覆盖。
     *
     * @param edge 是红框（边界）还是绿框（有效范围）
     */
    private static Object slot(BlockPos pos, boolean edge) {
        return "create_ballistics_range@" + pos.asLong() + (edge ? ":edge" : ":valid");
    }

    /** 该方块以自身为中心扫描多大范围；不是火控协调器/计算机就返回 0（不画）。 */
    private static int scanRadius(BlockEntity be) {
        if (be instanceof FireControlCoordinatorBlockEntity) {
            return FireControlCoordinatorBlockEntity.SCAN_RADIUS;
        }
        if (be instanceof FireControlComputerBlockEntity) {
            return FireControlComputerBlockEntity.SCAN_RADIUS;
        }
        return 0;
    }
}
