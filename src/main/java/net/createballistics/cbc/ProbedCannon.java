package net.createballistics.cbc;

import net.createballistics.ballistic.ExternalBallistics;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
import rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile;

/**
 * 从 CBC 探到的一门炮的<b>即时快照</b>。
 *
 * <p>刻意做成不可变的纯数据 —— 探针只在需要时跑一次，解算拿到快照后不再碰世界，
 * 这样解算逻辑与 Minecraft 解耦，也便于把快照送去自检里复现。
 *
 * @param mountPos   炮座方块坐标。⚠️ 它是**图幅坐标**（炮在航空学物理结构上时）——
 *                   只用来定位"这门炮属于哪个物理结构"（{@code SableCompat} 靠它查姿态），
 *                   <b>不要</b>拿去和玩家的打击坐标比较、也不要显示给玩家。
 *                   要显示/比较的炮坐标用 {@link #originX}
 * @param originX    弹道的<b>枢轴</b>世界坐标 —— 射程从这里量起。它已经在炮座方块中心的 X/Z 上，
 *                   但 {@code originY} 还叠加了 {@link #pivotOffsetY}（炮轴支点的高度），两者别搞混。
 *                   ⚠️ 它<b>已经是世界坐标</b>（{@link CannonProbe} 出口做过图幅→世界的转换）⇒
 *                   可以直接和打击坐标相减，<b>不要再转一次</b>
 * @param originY
 * @param originZ
 * @param barrelLength <b>沿炮轴</b>从炮轴支点到炮弹生成点的长度（格）—— 解算拿它算任意仰角下的炮口位置。
 *                     注意它<b>不是</b>「炮座方块中心到生成点的直线距离」，后者会随探测时的仰角变化，
 *                     见 {@link CannonProbe} 里那段反解的说明
 * @param pivotOffsetY 炮轴支点相对<b>炮座方块中心</b>的竖直偏移（格）。
 *                     实测这门炮约 −2.0 —— 炮轴架在方块中心偏下。
 *                     {@link #originY} 已经把 {@code 方块中心 + 本值} 算进去了，用它当落点基准即可
 * @param minPitch   最低仰角（度，负值向下）
 * @param maxPitch   最高仰角（度，正值向上）
 * @param chargePower 已装填的<b>总装药强度</b>（Σ {@code getChargePower}）。
 *                    CBC 原版直接用它当炮口初速；装了 going_ballistic 时它只是换 Robins 初速的输入
 * @param chargeCount 装药块数（用于 UI 显示与"装了几发药"的判断）
 * @param barrelBlocks 沿炮身扫到的方块格数。它是 CBC 药包散布「逐格递减」那一步的输入
 *                     （药包装得越多越散，炮管越长越收得住），护目镜上直接显示给玩家看
 * @param maxCharges  这门炮能安全承受的<b>装药上限</b>
 *                    = min(材质 max_safe_propellant_stress, 炮闩强度)。CBC 里最能代表火炮档次的值，
 *                    界面上当成"火炮等级"显示
 * @param muzzleVelocity 炮口初速（格/tick）。<b>与瞄准方向无关</b>，所以探针一次算好即可。
 *                       装了 going_ballistic 时由它自己的 Robins 实现给出
 * @param materialMaxStress 材质的装药上限，<b>不含炮闩</b>。与 {@link #breechStrength} 分开报，
 *                     玩家才知道「装不进去」到底是材质不够还是炮闩不够（新手最常踩的坑）
 * @param breechStrength 炮闩强度（{@code big_cannon_breech_strength} 数据包）。实际装药上限
 *                       = min({@link #materialMaxStress}, 本值)
 * @param breechName 炮闩方块的语言键，客户端自己翻译
 * @param minVelocityPerBarrel 卡弹系数。CBC 原版规则是
 *                      {@code 炮筒格数 > 装药强度 / 本值} 就逐 tick 掷骰卡弹 ——
 *                      ⚠️ 装了 going_ballistic 时这个判定被它接管、宽松得多，用本值给提醒时<b>必须注明</b>
 * @param shellItemId 已装填弹丸方块的语言键（{@code BlockItem} 的 descriptionId）。附属模组的新弹种
 *                    自带翻译；<b>只存键名、客户端再翻译</b>，因为服务端翻译只会拿到 en_us
 * @param shellExplosionPower 弹丸的方块破坏爆炸威力，用来估「最近安全距离」。惰性弹为 0
 * @param ballistics  空气物理（重力 + 阻力模型），由 {@link ActiveBallistics} 按整合包装配情况选
 * @param shell      已装填的炮弹实体。<b>注意这是为了向 going_ballistic 询问初速而临时创建的</b>，
 *                   不在世界里存在，用完即弃
 */
public record ProbedCannon(
        BlockPos mountPos,
        double originX, double originY, double originZ,
        double barrelLength,
        double pivotOffsetY,
        float minPitch, float maxPitch,
        float chargePower,
        int chargeCount,
        int barrelBlocks,
        int chamberBlocks,
        @Nullable String materialName,
        // ---- 组建信息（护目镜的「这门炮是怎么搭的」那块，以及新手向的风险提醒）----
        int materialMaxStress,
        int breechStrength,
        @Nullable String breechName,
        float minVelocityPerBarrel,
        double finalSpread,
        /**
         * <b>把散布压到材质下限所需的炮管格数</b>（{@code <= 0} = 没有这个阈值 / 数据缺失）。
         *
         * <p>CBC 的散布是 {@code max(装药数×2 − 炮管格数×每格减散, 材质最小散布)} ⇒
         * 炮管加到这么多格之后<b>再长也不会更准</b>。护目镜靠它给「炮管太短 ⇒ 散布压不下去」的提醒
         * （2026-09-27 用户拍板：好炮也要显示散布数值，散布偏大时还要说清是炮管短）。
         */
        int spreadFreeBarrelBlocks,
        int maxCharges,
        double muzzleVelocity,
        // ---- 弹种信息 ----
        @Nullable String shellItemId,
        float shellExplosionPower,
        // ---- 发射药信息 ----
        @Nullable String propellantItemId,
        int propellantPower,
        int propellantMaxPower,
        /**
         * 已装填发射药的总装药长（米）与 GB 估出的管长（米）。
         *
         * <p>这一对是 Robins 初速公式里的 {@code ln(管长 / 装药长)} ——
         * <b>装药长逼近管长时它趋近 0，初速也就趋近 0</b>，炮等于打不出去。
         * 护目镜靠这两个数给"加长炮筒"的提醒。没装 GB 时都是 0。
         */
        double chargeLengthMeters,
        double barrelMeters,
        /**
         * GB 判定这门炮<b>会卡弹</b>（火药的能量推不动弹丸走完这么长的炮管）。
         *
         * <p>卡弹时炮弹<b>打不出去</b>，而且火药会先被烧掉、炮弹留在膛里 ——
         * 玩家看到的是"按了开火什么都没发生"（2026-09-20 实测：63 格炮管 + 大型弹药 1/4~3/4）。
         * 没装 GB 时恒为 false（那条规则由 CBC 原版管，护目镜有另一处提醒）。
         */
        boolean squib,
        /**
         * 这次的几何（{@link #barrelLength} / {@link #pivotOffsetY}）是<b>精确反解出来的</b>，
         * 还是"仰角太高、反解不了"时<b>沿用/兜底</b>的。
         *
         * <p><b>为什么要有这一位</b>：反解要除以 {@code cos(探测时的仰角)}，炮口仰到 80° 以上时
         * 那个数趋近 0、噪声被放到极大，所以 {@code CannonProbe} 会主动跳过反解。
         * 跳过之后如果退回"直线距离 + 支点偏移 0"，起点高度会<b>凭空跳 2 格</b> ——
         * 足以让地形校验在"炮口前那一格"这种边界上反复翻面，AUTO 模式就会
         * 仰头→低头→仰头地卡在切换弹道的死循环里（2026-09-21 用户实测）。
         * 调用方（节点）靠这一位决定要不要把本次的几何<b>存成"上次的好值"</b>，
         * 以及反解不了时能不能沿用。
         */
        boolean geometryExact,
        ExternalBallistics ballistics,
        @Nullable AbstractBigCannonProjectile shell
) {

    /** 能不能打 —— 有药、有弹才算装填完毕。 */
    public boolean loaded() {
        return chargePower > 0.0F && shell != null;
    }

}
