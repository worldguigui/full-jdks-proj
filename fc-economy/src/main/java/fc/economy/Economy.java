package fc.economy;

import fc.config.world.RecipeDef;
import fc.config.world.ResourceDef;
import fc.config.world.WorldDefinition;
import fc.world.Ids;
import fc.world.Location;
import fc.world.WorldState;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 经济环境：资源价格参数与配方表。
 *
 * <p>价格是**按库存现算**的（无独立状态），因此存档只需保存库存与信用点，不必保存价格。</p>
 */
public final class Economy {

    private final Map<String, ResourceDef> resources;
    private final Map<String, RecipeDef> recipes;

    private Economy(Map<String, ResourceDef> resources, Map<String, RecipeDef> recipes) {
        this.resources = resources;
        this.recipes = recipes;
    }

    /** 从配置构造。 */
    public static Economy from(WorldDefinition.EconomyDef def) {
        Map<String, ResourceDef> res = new LinkedHashMap<>();
        for (ResourceDef r : def.resources()) {
            res.put(r.name(), r);
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

    public Optional<ResourceDef> resource(String name) {
        return Optional.ofNullable(resources.get(name));
    }

    public Map<String, ResourceDef> resources() {
        return java.util.Collections.unmodifiableMap(resources);
    }

    public List<RecipeDef> recipes() {
        return List.copyOf(recipes.values());
    }

    public Optional<RecipeDef> recipe(String id) {
        return Optional.ofNullable(recipes.get(id));
    }

    /** 某地点某资源的库存。 */
    public static BigDecimal stockAt(WorldState state, Ids.LocationId at, String resource) {
        Location loc = state.graph().get(at);
        return loc == null ? BigDecimal.ZERO : loc.stock().amount(resource);
    }

    /** 玩家买入价（无配置返回空）。 */
    public Optional<BigDecimal> buyPrice(WorldState state, Ids.LocationId at, String resource) {
        ResourceDef def = resources.get(resource);
        if (def == null) {
            return Optional.empty();
        }
        return Optional.of(PriceModel.buyPrice(def, stockAt(state, at, resource)));
    }

    /** 玩家卖出价。 */
    public Optional<BigDecimal> sellPrice(WorldState state, Ids.LocationId at, String resource) {
        ResourceDef def = resources.get(resource);
        if (def == null) {
            return Optional.empty();
        }
        return Optional.of(PriceModel.sellPrice(def, stockAt(state, at, resource)));
    }

    /** 基准价（用于展示与指数）。 */
    public Optional<BigDecimal> basePrice(WorldState state, Ids.LocationId at, String resource) {
        ResourceDef def = resources.get(resource);
        if (def == null) {
            return Optional.empty();
        }
        return Optional.of(PriceModel.basePrice(def, stockAt(state, at, resource)));
    }
}
