package fc.economy;

import fc.config.world.RecipeDef;
import fc.config.world.ResourceDef;
import fc.config.world.WorldDefinition;
import fc.world.Ids;
import fc.world.ResourceKind;
import fc.world.WorldState;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 经济环境：资源价格参数与配方表。
 *
 * <p>价格是**按库存现算**的（无独立状态），因此存档只需保存库存与信用点，不必保存价格。
 * 事件造成的临时价格冲击由 {@link WorldState#priceShock(ResourceKind)} 叠加。</p>
 */
public final class Economy {

    private final Map<ResourceKind, ResourceDef> resources;
    private final Map<String, RecipeDef> recipes;

    private Economy(Map<ResourceKind, ResourceDef> resources, Map<String, RecipeDef> recipes) {
        this.resources = resources;
        this.recipes = recipes;
    }

    /** 从配置构造。资源名必须对应 {@link ResourceKind} 的 11 种货物之一。 */
    public static Economy from(WorldDefinition.EconomyDef def) {
        Map<ResourceKind, ResourceDef> res = new EnumMap<>(ResourceKind.class);
        for (ResourceDef r : def.resources()) {
            res.put(ResourceKind.goodsFromId(r.name()), r);
        }
        Map<String, RecipeDef> rec = new LinkedHashMap<>();
        for (RecipeDef r : def.recipes()) {
            rec.put(r.id(), r);
        }
        return new Economy(res, rec);
    }

    /** 是否已配置经济（无资源配置时交易与生产不可用）。 */
    public boolean enabled() {
        return !resources.isEmpty();
    }

    public Optional<ResourceDef> resource(ResourceKind kind) {
        return Optional.ofNullable(resources.get(kind));
    }

    public Map<ResourceKind, ResourceDef> resources() {
        return Collections.unmodifiableMap(resources);
    }

    /** 配方按 id 排序（开工顺序确定）。 */
    public List<RecipeDef> recipes() {
        return recipes.values().stream()
                .sorted(java.util.Comparator.comparing(RecipeDef::id))
                .toList();
    }

    public Optional<RecipeDef> recipe(String id) {
        return Optional.ofNullable(recipes.get(id));
    }

    /** 某地点某货物的库存。 */
    public static BigDecimal stockAt(WorldState state, Ids.LocationId at, ResourceKind kind) {
        return state.goods(at, kind);
    }

    /** 玩家买入价（无配置返回空）。 */
    public Optional<BigDecimal> buyPrice(WorldState state, Ids.LocationId at, ResourceKind kind) {
        ResourceDef def = resources.get(kind);
        if (def == null) {
            return Optional.empty();
        }
        return Optional.of(PriceModel.buyPrice(def, stockAt(state, at, kind),
                state.priceShock(kind)));
    }

    /** 玩家卖出价。 */
    public Optional<BigDecimal> sellPrice(WorldState state, Ids.LocationId at, ResourceKind kind) {
        ResourceDef def = resources.get(kind);
        if (def == null) {
            return Optional.empty();
        }
        return Optional.of(PriceModel.sellPrice(def, stockAt(state, at, kind),
                state.priceShock(kind)));
    }

    /** 基准价（展示用）。 */
    public Optional<BigDecimal> basePrice(WorldState state, Ids.LocationId at, ResourceKind kind) {
        ResourceDef def = resources.get(kind);
        if (def == null) {
            return Optional.empty();
        }
        return Optional.of(PriceModel.basePrice(def, stockAt(state, at, kind),
                state.priceShock(kind)));
    }
}
