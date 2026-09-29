package net.createballistics.client;

import net.createballistics.CreateBallistics;
import net.createballistics.entity.DroneEntity;
import net.createballistics.item.ArtilleryIndicatorItem;
import net.createballistics.item.MonitorItem;
import net.createballistics.registry.ModDataComponents;
import net.createballistics.registry.ModItems;
import net.createballistics.util.StageText;
import net.createballistics.util.Texts;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 无人机图传的 HUD —— 照卓越前线的 {@code client/overlay/DroneHudOverlay}。
 *
 * <h2>画面元素</h2>
 * <ol>
 *   <li><b>准星</b>：屏幕中心，32×32；</li>
 *   <li><b>变焦竖条</b>：中心右侧 100 格、上移 64，64×129；滑块与倍率文字都随倍率上移 {@code (倍率−1) × 23.8}；</li>
 *   <li><b>距离行</b>（玩家↔无人机的水平距离）：中心右 10 / 下 33；有告警时整行变红；
 *       <b>告警两态</b>（居中画在中心上方 47，见 {@link #WARN_MARGIN_CHUNKS} 与 render 里的说明）：
 *       「即将飞出你的视距」/「即将超出模拟距离」—— **谁近提醒谁**，两句都是"再飞远会自爆"；</li>
 *   <li><b>耐久行</b>：中心左 77 / 下 33（它那一行是"血量"，我们这台是**耐久点数**，位置与读法照旧）；</li>
 *   <li><b>测距行</b>（c-②）：从相机沿准星打 512 格，报方块距离 / 实体距离+名字 / {@code ---m}；中心右 12 / 上 28；</li>
 *   <li><b>实体框 24×24</b>（c-②）：256 格 / 30° 视锥内、**且视线没被方块挡住**的实体，各框一个；</li>
 *   <li><b>屏幕标记 12×12</b>（c-②）：副手拿着火炮指示器时，标出**打击目标点**与 **IFF 友军**；</li>
 *   <li><b>「计算过程」</b>（c-②）：距离/耐久行<b>下面一个空行</b>，内容与举镜时右下角那条<b>同一份</b>
 *       （{@code StageText}，原料是副手指示器身上的镜像快照）；</li>
 *   <li><b>蓄力圆环</b>（c-②）：按住左键蓄力的进度环，与举镜时<b>同一份</b>（{@code OverlayShapes}）——
 *       用户 2026-09-23 要求：图传里也得看得到"到没到开火那一下"。</li>
 * </ol>
 *
 * <h2>⚠️ 三处刻意不做（都是用户拍板的）</h2>
 * <ul>
 *   <li><b>「电视取景条纹边框」</b>（它的 {@code textures/overlay/vehicle/land/tv_frame.png}）——
 *       2026-09-23 实机对比后拍板：那层在我们屏幕上表现为压满全屏的黑色横条
 *       （那张贴图本就是「纯黑不透 + 全透明缝」，原版看着淡只是**缩小绘制**把它滤灰了），
 *       不是它那种观感，**横条与边框都不要**（贴图已随之从本模组删除）；</li>
 *   <li>**弹药 / 神风**两行（我们的无人机是观测器材、没有武器）；</li>
 *   <li>它"不在图传里时给自己肉身画个友军图标"那一支（我们不缺这个）。</li>
 * </ul>
 *
 * <h2>⚠️ IFF 友军：我们的对应物是「计算机 + 协调器」，不是「炮」</h2>
 * 它那边遍历的是**指示器绑定的炮**的 UUID；而我们的一台指示器绑的是**计算机（方块）**
 * （这是刻意的架构差别：设置唯一存在计算机上）。客户端手上现成能拿到的只有计算机坐标与它的协调器列表，
 * 炮位本身没有同步到客户端 ⇒ **方案 A（2026-09-23 用户拍板）**：标计算机 + 协调器，不新增同步数据。
 */
public final class DroneHudOverlay implements LayeredDraw.Layer {

    private static final ResourceLocation CROSSHAIR = tex("drone_crosshair");
    private static final ResourceLocation FOV_BAR = tex("drone_fov");
    private static final ResourceLocation FOV_MARK = tex("drone_fov_move");
    /** 实体框（它那张是 64×64，照它画成 24×24）。 */
    private static final ResourceLocation ENTITY_FRAME = tex("drone_marker_frame");
    /** 滑块/读数随倍率上移的系数（照它写死的 23.8）。 */
    private static final double ZOOM_SLIDE = 23.8;

    /** 实体框的边长（照它）。 */
    private static final int FRAME_SIZE = 24;

    /** 超过这个距离就不给具体数字，显示 {@code ---m}（照它）。射线长度本身见 {@link CrosshairPick#RAY_LENGTH}。 */
    private static final double MAX_RANGE = 500.0;
    /**
     * 告警的<b>提前量</b>（区块）。撞上悬崖那一刻才提醒是没用的 ——
     * 那时图传已经冻住了。原版那句「48 格」也是同一个用意（约 3 区块）。
     */
    private static final int WARN_MARGIN_CHUNKS = 2;
    /** 「计算过程」画在距离/耐久那一行<b>下面一个空行</b>（2026-09-23 用户在截图上圈的位置）。 */
    private static final int STAGE_DY = 47;
    /** 与耐久行同一个左边界，三行文字左边缘对齐。 */
    private static final int STAGE_LEFT = 77;
    /** 实体框的搜索半径与视锥半角（照它 {@code seekLivingEntities(drone, 256, 30)}）。 */
    private static final double FRAME_RANGE = 256.0;
    private static final double FRAME_ANGLE = 30.0;

    private static ResourceLocation tex(String name) {
        return CreateBallistics.id("textures/overlay/drone/" + name + ".png");
    }

    @Override
    public void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        // 开着界面 / 按了 F1 不画（与世界空间那两块同一个规矩）
        if (player == null || mc.screen != null || mc.options.hideGui) {
            return;
        }
        if (!MonitorItem.isPiloting(player.getMainHandItem())) {
            return;
        }
        DroneEntity drone = MonitorItem.pilotedDrone(player);
        if (drone == null) {
            return;
        }
        // 照它：第一人称与第三人称背面才算"在图传里"
        CameraType type = mc.options.getCameraType();
        if (type != CameraType.FIRST_PERSON && type != CameraType.THIRD_PERSON_BACK) {
            return;
        }

        int w = guiGraphics.guiWidth();
        int h = guiGraphics.guiHeight();
        double zoom = DroneViewEvents.zoomLerp();
        float partial = (float) Math.min(deltaTracker.getGameTimeDeltaPartialTick(true), 1.6F);

        // ⚠️ 它这里还有一层「电视取景边框」（按宽高比外扩 w/h×48 / w/h×27 铺满屏幕）——
        //    2026-09-23 用户实机对比后拍板**不抄**，原因见类注释。整段连同贴图一起撤掉了。

        // 准星
        guiGraphics.blit(CROSSHAIR, w / 2 - 16, h / 2 - 16, 0, 0, 32, 32, 32, 32);

        // 变焦竖条 + 滑块 + 倍率读数（滑块与读数都随倍率上移）
        int slide = (int) ((zoom - 1.0) * ZOOM_SLIDE);
        guiGraphics.blit(FOV_BAR, w / 2 + 100, h / 2 - 64, 0, 0, 64, 129, 64, 129);
        guiGraphics.blit(FOV_MARK, w / 2 + 100, h / 2 - 64 - slide, 0, 0, 64, 129, 64, 129);
        guiGraphics.drawString(mc.font, Component.literal(Texts.num("%.1f", zoom) + "x"),
                w / 2 + 144, h / 2 + 56 - slide, 0xFFFFFFFF, false);

        // 距离（玩家↔无人机的**水平**距离）
        double distance = player.position().subtract(drone.position()).horizontalDistance();

        // ---- 告警：说清"**哪堵墙先来**"（2026-09-24 按用户实测两改；2026-09-26 改成"会自爆"）----
        //
        // 无人机身上有**两堵墙**，性质不同，所以措辞要能区分（玩家才知道往哪边退）：
        //   ① **客户端渲染距离**：客户端只 tick「它已加载的区块里」的实体
        //       （{@code ClientLevel#unload} → {@code entityStorage.stopTicking}）⇒
        //       那块地一卸载，**画面与操控当场停住**，而服务端其实还在飞它 —— 你在**盲飞**。
        //   ② **服务端模拟距离**：区块不再 tick ⇒ 无人机**真的**不动了（按什么都没反应），
        //       但**画面还是好的**；往前走一点就能恢复。
        //
        // ⚠️⚠️ 两堵墙**都会让无人机自爆**，而且越线**当 tick 就炸**（见 {@code DroneEntity#tick}）
        //    ⇒ 下面这两句就是"**再飞远会自爆**"的最后警告，不再是"会失去图传"这种后果描述。
        //    判据与那一侧**同源**（同一把"切比雪夫区块距离"的尺子、同一个 min(渲染, 模拟)），
        //    只多留 {@link #WARN_MARGIN_CHUNKS} 的提前量 ⇒ "提醒说会炸"与"真的会炸"必然落在
        //    同一格、且提醒一定更早。**改了那边记得回来对一遍。**
        //
        // ⚠️ 2026-09-26 用户拍板**删掉**了原来第三条「**已经**飞出你的视距 · 图传与操控已中断」：
        //    改成立即自爆之后，"已经断连、但无人机还活着"这个状态**不存在了**（越线那一刻就没了，
        //    客户端随即收到移除包、整块 HUD 都不再画）⇒ 那条告警永远没机会显示，留着只会误导。
        //    （同批删掉的还有语言文件里的 out_of_view 那条 key。）
        BlockPos dronePos = drone.blockPosition();
        int dxChunks = Math.abs((dronePos.getX() >> 4) - (player.blockPosition().getX() >> 4));
        int dzChunks = Math.abs((dronePos.getZ() >> 4) - (player.blockPosition().getZ() >> 4));
        int chunksAway = Math.max(dxChunks, dzChunks);
        int renderChunks = mc.options.renderDistance().get();
        int simChunks = mc.getConnection() != null ? mc.getConnection().serverSimulationDistance : 16;
        boolean viewEdgeIsNearer = renderChunks <= simChunks;

        Component warning = chunksAway >= Math.min(renderChunks, simChunks) - WARN_MARGIN_CHUNKS
                ? Component.translatable(viewEdgeIsNearer
                        ? "gui.create_ballistics.drone.hud.near_view_edge"
                        : "gui.create_ballistics.drone.hud.near_sim_edge")
                : null;
        int color = warning == null ? 0xFFFFFFFF : 0xFFFF0000;
        if (warning != null) {
            // 照它那一句的摆法：**水平居中**（它是拿"宽 36 ⇒ x = 中心 − 18"凑的，
            // 我们这句话长短不一，直接量宽度更稳），行高与它一致（中心上 47）
            guiGraphics.drawString(mc.font, warning,
                    Math.max(0, w / 2 - mc.font.width(warning) / 2), h / 2 - 47, 0xFFFF0000, false);
        }
        Component distanceLine = Component.translatable("gui.create_ballistics.drone.hud.distance",
                Texts.num("%.1f", distance) + "m");
        guiGraphics.drawString(mc.font, distanceLine, w / 2 + 10, h / 2 + 33, color, false);

        // 耐久（它那行是血量；我们这台是耐久点数，位置与读法照旧）
        Component durabilityLine = Component.translatable("gui.create_ballistics.drone.hud.durability",
                Texts.num("%.0f", drone.getDurability()), Texts.num("%.0f", DroneEntity.MAX_DURABILITY));
        guiGraphics.drawString(mc.font, durabilityLine, w / 2 - 77, h / 2 + 33, 0xFFFFFFFF, false);

        // 计算机的「计算过程」—— 与举镜时右下角那条**同一份实现**（StageText），
        // 玩家在图传里也能看到"炮转好没 / 瞄准完没 / 还有几秒落地"。
        // 原料是**副手**那份指示器身上的镜像快照（它每 10 tick 抄一次，见 ArtilleryIndicatorItem）。
        ItemStack indicator = ArtilleryIndicatorItem.actingIndicator(player);
        StageText.Snapshot stage = indicator.isEmpty() ? null : ArtilleryIndicatorItem.mirroredStage(indicator);
        if (stage != null) {
            // ⚠️ 右端钉在**变焦竖条的左边**（不是屏幕边缘）—— 否则长句会压在竖条上
            //    （2026-09-24 用户截图）。放不下先往左长，再放不下才整行缩（见 FittedText）。
            FittedText.fitRight(guiGraphics, StageText.of(stage, player.level()),
                    w / 2 - STAGE_LEFT, w / 2 + 100 - 6, 8, h / 2 + STAGE_DY,
                    StageText.color(stage, player.level()), true);
        }

        // ——— B3-c-②：测距 + 屏幕标记 ———

        // 测距（颜色跟着"过远告警"走，照它）
        guiGraphics.drawString(mc.font, rangeText(mc, drone), w / 2 + 12, h / 2 - 28, color, false);

        // 标记的"看不看得见"判据要用**变焦后**的视场角，否则开镜时会把画面外的点也判成可见
        double fov = mc.options.fov().get() / zoom;
        drawEntityFrames(guiGraphics, drone, partial);
        drawOffhandMarks(guiGraphics, player, w, h, fov);

        // 蓄力圆环：与举镜时**同一份实现**（OverlayShapes）—— 2026-09-23 用户要求图传里也有，
        // 不然飞着的时候根本不知道"按住左键到没到开火那一下"。
        // ⚠️ 必须放在最后：它结尾会 disableBlend()，排在 blit 之前会把后面的贴图画成不带透明的。
        OverlayShapes.chargeRing(guiGraphics, w / 2.0F, h / 2.0F, IndicatorClientEvents.chargeProgress());
    }

    /**
     * 测距：从<b>相机</b>沿准星打一条 512 格的射线（我们的相机就在无人机上，所以起点即机位）。
     *
     * <p>三档，照它的顺序：**先看有没有打到实体** → 再看方块距离是否 &gt; 500 → 否则给方块距离。
     * 打不到方块时 {@code getLocation()} 是射线的终点（512 格外），于是自然落进"太远"那一支，
     * 显示 {@code ---m} —— 不用单独判 MISS。
     *
     * <p>⚠️ 它取实体用的 {@code droneFindLookingEntity} 里其实写了"先 {@code entity.pick} 取方块命中、
     * 有实体命中就覆盖"，但 {@code pick} 只会返回 BLOCK/MISS，那一支永远走不到 ⇒ 实际语义就是
     * 「射线 512 格内最近的、过得了过滤器的实体」，**不看有没有墙挡着**。我们照这个语义实现。
     */
    private static Component rangeText(Minecraft mc, DroneEntity drone) {
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 view = drone.getViewVector(1.0F);
        BlockHitResult blockHit = drone.level().clip(new ClipContext(camera,
                camera.add(view.scale(CrosshairPick.RAY_LENGTH)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, drone));
        double blockRange = camera.distanceTo(blockHit.getLocation());

        Entity looked = CrosshairPick.lookingEntity(drone, camera, view);
        String value;
        if (looked != null) {
            value = Texts.num("%.1f", drone.distanceTo(looked)) + "m " + looked.getDisplayName().getString();
        } else if (blockRange > MAX_RANGE) {
            value = "---m";
        } else {
            value = Texts.num("%.1f", blockRange) + "m";
        }
        return Component.translatable("gui.create_ballistics.drone.hud.ranging", value);
    }

    /**
     * 实体框（24×24）：照它 {@code SeekTool.seekLivingEntities(drone, 256, 30)}。
     *
     * <p>那一串过滤器的实际语义（逐个读出来的）：
     * <ul>
     *   <li>{@code withinRange(256)}：到**无人机眼睛**的距离 ≤ 256；</li>
     *   <li>{@code withinAngle(30)}：<b>机头方向</b>与"无人机→目标"的夹角 &lt; 30°；</li>
     *   <li>{@code baseFilter()}：活着、非观察者、非弹丸；</li>
     *   <li>{@code noVehicle()}：坐在载具里的不算；</li>
     *   <li>⚠️ {@code noClip()}：**名字是反的** —— 它判的是 {@code clip(...).getType() != BLOCK}，
     *       也就是<b>必须有视线</b>，隔着方块的目标不框。这决定了"能不能看穿地形"，不能漏；</li>
     *   <li>友军/烟雾那两条我们用不上（我们没有队伍与烟雾弹机制）。</li>
     * </ul>
     *
     * <h2>为什么这里可以直接遍历实体表</h2>
     * 它取候选用的是 {@code LevelEntityGetter#getAll()} —— 走的是**客户端自己的实体列表**，
     * 不是"AABB 范围查询"（后者会去遍历包围盒里所有非空实体小节，那才是我们踩过的坑）。
     * 客户端已知的实体本来就有界，所以这是可以每帧跑的。
     */
    private static void drawEntityFrames(GuiGraphics guiGraphics, DroneEntity drone, float partial) {
        if (!(drone.level() instanceof ClientLevel level)) {
            return;
        }
        // 照它：距离与视线判定都用**无插值**的眼睛位置
        Vec3 eye = drone.getEyePosition();
        Vec3 look = drone.getLookAngle();
        Vec3 origin = drone.position();

        for (Entity e : level.getEntities().getAll()) {
            if (e == drone || !e.isAlive() || e.isSpectator() || e instanceof Projectile) {
                continue;
            }
            if (e.getVehicle() != null) {
                continue;
            }
            if (e.position().distanceTo(eye) > FRAME_RANGE) {
                continue;
            }
            Vec3 toTarget = e.position().subtract(origin);
            if (toTarget.lengthSqr() < 1.0E-6) {
                continue;
            }
            double angle = Math.toDegrees(Math.acos(Mth.clamp(toTarget.normalize().dot(look), -1.0, 1.0)));
            if (angle >= FRAME_ANGLE) {
                continue;
            }
            if (drone.level().clip(new ClipContext(eye, e.getEyePosition(), ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, drone)).getType() == HitResult.Type.BLOCK) {
                continue;
            }

            // 框画在**眼睛高度**（插值后）—— 照它
            Vec3 point = new Vec3(Mth.lerp(partial, e.xo, e.getX()),
                    Mth.lerp(partial, e.yo + e.getEyeHeight(), e.getEyeY()),
                    Mth.lerp(partial, e.zo, e.getZ()));
            Vec3 screen = WorldToScreen.toScreen(point);
            if (screen == null) {
                continue;
            }
            guiGraphics.blit(ENTITY_FRAME,
                    (int) Math.round(screen.x) - FRAME_SIZE / 2, (int) Math.round(screen.y) - FRAME_SIZE / 2,
                    FRAME_SIZE, FRAME_SIZE, 0.0F, 0.0F, FRAME_SIZE, FRAME_SIZE, FRAME_SIZE, FRAME_SIZE);
        }
    }

    /**
     * 副手拿着火炮指示器时才会画的屏幕标记（照它：那一整块判的是"副手是不是观测物品"）。
     *
     * <ol>
     *   <li><b>打击目标点</b> —— 坐标来自指示器身上的 {@code LAST_TARGET} 镜像快照。
     *       计算机没有目标时镜像被清掉，标记就跟着消失（与计算机界面上那行「未设定目标」一致）；</li>
     *   <li><b>IFF 友军</b> —— 绑定的计算机 + 它的协调器（方案 A，见类注释）。
     *       画法在 {@link OverlayMarks#friendlies}（**与举镜视角共用一份**）。
     *       ⚠️ 2026-09-24 之前协调器是客户端<b>现场读方块实体</b>的 ⇒ 玩家走远之后，
     *       就只有计算机那个标记还在、协调器全消失（强加载只对服务端生效）。
     *       现在两者同源：都由**服务端镜像**决定，镜像缺席时**一个都不画**。</li>
     * </ol>
     */
    private static void drawOffhandMarks(GuiGraphics guiGraphics, LocalPlayer player,
                                         int screenW, int screenH, double fov) {
        ItemStack off = player.getOffhandItem();
        if (!off.is(ModItems.ARTILLERY_INDICATOR.get())) {
            return;
        }

        BlockPos target = off.get(ModDataComponents.LAST_TARGET.get());
        if (target != null) {
            OverlayMarks.target(guiGraphics, Vec3.atCenterOf(target), screenW, screenH, fov);
        }
        // IFF 友军（绑定的计算机 + 它的协调器）—— 画法与举镜时**同一份**（OverlayMarks）
        OverlayMarks.friendlies(guiGraphics, off, screenW, screenH, fov);
    }

    /**
     * 图传的可靠距离 —— 照它：服务端模拟距离（区块）× 16。
     *
     * <p>⚠️ 判"客户端手上有没有那块地"**不能用** {@code ClientLevel#hasChunk} ——
     * 它<b>永远返回 {@code true}</b>（源码原文就是 {@code return true;}）。
     * 要用 {@link net.minecraft.world.level.chunk.ChunkSource#hasChunk(int, int)}
     * （经 {@code level.getChunkSource()} 拿到，那是**非加载式**的检查，
     * 不会顺手把区块拉进来）。本类里那处调用就是这么写的。
     */
    private static int maxDistance(Minecraft mc) {
        return (mc.getConnection() != null ? mc.getConnection().serverSimulationDistance : 16) * 16;
    }
}
