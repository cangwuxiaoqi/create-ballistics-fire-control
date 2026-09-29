package net.createballistics.client;

import java.util.UUID;
import net.createballistics.entity.DroneEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * <b>「公共代码 → 客户端」的唯一一座桥</b>：凡是两端都会加载的类（实体 / 物品 / 方块实体）需要
 * 碰客户端类时，都从这里走。
 *
 * <h2>⚠️⚠️ 为什么必须有这一层（专用服务器安全）</h2>
 *
 * <b>专用服务端的分发包里根本没有 {@code net/minecraft/client/**}</b>（Minecraft 的客户端类只在
 * 客户端发行版里；NeoForge 的 universal jar 也不含它们）。所以：<b>公共类里只要有一处会在
 * 类加载期碰到客户端类，专用服务器就会 {@code NoClassDefFoundError} 起不来。</b>
 *
 * <p>本类在 {@code net.createballistics.client} 包下 ⇒ <b>服务端永远不会加载它</b> ⇒
 * 它里面的客户端引用（{@code Minecraft} / {@code LocalPlayer} / {@code ClientLevel}）
 * 全都安全。
 *
 * <h2>公共类该怎么用它（两条硬规矩）</h2>
 * <ol>
 *   <li><b>只在"侧别判断里面"调</b>：{@code if (level.isClientSide) { … DroneClientAccess.xxx() … }}</b>
 *       或者只在客户端专属的方法里调（如 {@code appendHoverText}、{@code lerpTo}）——
 *       这样服务端的执行路径永远碰不到本类，连"解析"都不会发生；</li>
 *   <li><b>本类方法的签名只许用公共类型</b>（{@code Player} / {@code Entity} / {@code Level}，
 *       <b>不是</b> {@code LocalPlayer} / {@code ClientLevel}）—— 否则调用方的常量池里会出现
 *       客户端类型的描述符，等于把风险又带回公共类里了。</li>
 * </ol>
 * ⚠️ 反面教材（会崩）：把客户端类写成**静态字段**、当成**父类/接口**、或放进**方法签名** ——
 * 那三种都是"类一加载就解析"，服务端直接起不来。
 * ⚠️ 检查手段：{@code node tools/scan_client_refs.js <产物.jar>} ——
 * 扫"非客户端类里出现了 {@code net/minecraft/client/…}"，发布前应当是 <b>0</b>。
 */
public final class DroneClientAccess {

    private DroneClientAccess() {
    }

    /** 本地玩家（没有就 null）。<b>返回类型是 {@link Player}</b>，见类注释第 2 条。 */
    @Nullable
    public static Player localPlayer() {
        return Minecraft.getInstance().player;
    }

    /** 本地玩家<b>正在操控</b>这台无人机吗（按手里的遥控器认，与 {@code DroneEntity} 的判据一致）。 */
    public static boolean isPilotingLocally(UUID droneId) {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null
                && net.createballistics.item.MonitorItem.isPiloting(player.getMainHandItem())
                && droneId.equals(net.createballistics.item.MonitorItem
                        .linkedDrone(player.getMainHandItem()));
    }

    /**
     * 按 UUID 找那台无人机 —— <b>客户端</b>这条（{@code ClientLevel.getEntities().get(uuid)} 是 O(1)，
     * 那条访问转换器就是为它加的）。
     *
     * <p>⚠️ 服务端那条路在 {@code MonitorItem#findLinked} 里（{@code ServerLevel#getEntity} 本来就 O(1)）。
     * 这里只在 {@code level.isClientSide} 的分支里被调到。
     */
    @Nullable
    public static DroneEntity findClient(@Nullable Level level, UUID id) {
        return level instanceof ClientLevel client
                ? (client.getEntities().get(id) instanceof DroneEntity drone ? drone : null)
                : null;
    }
}
