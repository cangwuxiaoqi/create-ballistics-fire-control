package net.createballistics.registry;

import net.createballistics.CreateBallistics;
import net.createballistics.entity.DroneEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 实体注册。目前只有观测层的<b>无人机</b>。
 *
 * <p>分类用 {@link MobCategory#MISC}：不参与自然生成/刷怪上限，也<b>不会自己消失</b>
 * （玩家放出来的观测设备不该凭空不见）。
 */
public class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, CreateBallistics.MODID);

    /** 无人机：小、无重力、由监视器操控。碰撞箱按它的实际尺寸（约 0.2 格）给。 */
    public static final DeferredHolder<EntityType<?>, EntityType<DroneEntity>> DRONE =
            ENTITIES.register("drone", () -> EntityType.Builder.<DroneEntity>of(DroneEntity::new, MobCategory.MISC)
                    .sized(0.4F, 0.25F)
                    // 照卓越前线的无人机注册参数：setTrackingRange(512).setUpdateInterval(1)
                    // —— 追踪范围给足 512 格（32 区块）：图传要在客户端拿得到它，而且它常常飞离玩家。
                    .clientTrackingRange(32)
                    .updateInterval(1)           // 每 tick 同步（照它；飞行的东西不能 3 tick 一跳）
                    .build("drone"));

    public static void register(IEventBus eventBus) {
        ENTITIES.register(eventBus);
    }
    // ⚠️ 没有属性注册：无人机继承的是 {@code Entity}（不是 Mob/LivingEntity）——
    //    它是**可消耗的观测器材**，不该有血条，所以没有 Attributes 可注册。
}
