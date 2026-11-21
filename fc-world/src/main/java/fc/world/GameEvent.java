package fc.world;

import fc.world.Ids.AgentId;
import fc.world.Ids.DecisionId;
import fc.world.Ids.FactionId;
import fc.world.Ids.LocationId;
import fc.world.Ids.SupplyLineId;
import fc.world.Ids.TransferId;
import fc.world.time.Stardate;

import java.math.BigDecimal;
import java.util.List;

/**
 * 统一世界规则的第五抽象：世界事件（GameEvent）。
 *
 * <p>事件分两层：Tick 内确定性同步队列（结算产生的事件），与 tick 后投递的 {@code Flow}
 * 通知。所有事件都记录到编年史。</p>
 *
 * <p><strong>sealed 约束</strong>：sealed 根与全部 permitted 子接口必须位于 {@code fc-world}；
 * 具体记录可在 fc-world 或由配置/SPI 生成；扩展走 {@code non-sealed} 的
 * {@link ExtensionEvent}。</p>
 */
public sealed interface GameEvent
        permits GameEvent.WorldEvent, GameEvent.FleetEvent, GameEvent.CrewEvent,
                GameEvent.ColonyEvent, GameEvent.MarketEvent,
                GameEvent.DiplomacyEvent, GameEvent.DiscoveryEvent,
                GameEvent.ExtensionEvent {

    /** 事件发生的 Stardate。 */
    Stardate when();

    /** 事件发生的地点。 */
    LocationId where();

    /** 参与者（缺省为空）。 */
    default List<AgentId> who() {
        return List.of();
    }

    /** 原因。 */
    default String cause() {
        return "";
    }

    /** 过程/结果。 */
    default String outcome() {
        return "";
    }

    // ---- permitted 子接口（均为 non-sealed，具体记录可在任意 fc 模块） ----

    non-sealed interface WorldEvent extends GameEvent {}

    non-sealed interface FleetEvent extends GameEvent {}

    non-sealed interface CrewEvent extends GameEvent {}

    non-sealed interface ColonyEvent extends GameEvent {}

    non-sealed interface MarketEvent extends GameEvent {}

    non-sealed interface DiplomacyEvent extends GameEvent {}

    non-sealed interface DiscoveryEvent extends GameEvent {}

    /** mod 扩展口。 */
    non-sealed interface ExtensionEvent extends GameEvent {}

    // ---- 示例具体事件（世界事件 / 船员事件） ----

    /** 离子风暴：世界事件。 */
    record IonStorm(Stardate when, LocationId where, int severity) implements WorldEvent {
        @Override
        public String outcome() {
            return "severity=" + severity;
        }
    }

    /** 婚礼：船员事件。 */
    record Wedding(AgentId a, AgentId b, Stardate when, LocationId where) implements CrewEvent {
        @Override
        public List<AgentId> who() {
            return List.of(a, b);
        }
    }

    /** 舰船启航：舰队事件。 */
    record ShipDeparted(Stardate when, LocationId where, Ids.ShipId ship,
                        LocationId from, LocationId to, int totalTicks) implements FleetEvent {
        @Override
        public String cause() {
            return "启航";
        }

        @Override
        public String outcome() {
            return "预计 " + totalTicks + " 天后抵达 " + to;
        }
    }

    /** 舰船抵达：舰队事件。 */
    record ShipArrived(Stardate when, LocationId where, Ids.ShipId ship,
                       LocationId from) implements FleetEvent {
        @Override
        public String cause() {
            return "抵达";
        }

        @Override
        public String outcome() {
            return "自 " + from + " 抵达 " + where;
        }
    }

    /** 主体进食：船员/居民事件。 */
    record CrewAte(Stardate when, LocationId where, AgentId actor,
                   int hungerBefore) implements CrewEvent {
        @Override
        public List<AgentId> who() {
            return List.of(actor);
        }

        @Override
        public String cause() {
            return "进食";
        }

        @Override
        public String outcome() {
            return "饥饿 " + hungerBefore + " → 缓解";
        }
    }

    /** 主体拒绝指令：士气低落时发生。 */
    record CrewRefusedDuty(Stardate when, LocationId where, AgentId actor,
                           String reason) implements CrewEvent {
        @Override
        public List<AgentId> who() {
            return List.of(actor);
        }

        @Override
        public String cause() {
            return "拒绝指令";
        }

        @Override
        public String outcome() {
            return reason;
        }
    }

    /** 生产完成：殖民地产出。 */
    record ProductionCompleted(Stardate when, LocationId where, String recipeName,
                               String outputs) implements ColonyEvent {
        @Override
        public String cause() {
            return "生产完成";
        }

        @Override
        public String outcome() {
            return recipeName + " → " + outputs;
        }
    }

    /** 主体为觅食迁徙：附近有粮时自行移动。 */
    record CrewMigrated(Stardate when, LocationId where, AgentId actor,
                        LocationId to, String reason) implements CrewEvent {
        @Override
        public List<AgentId> who() {
            return List.of(actor);
        }

        @Override
        public String cause() {
            return "迁徙";
        }

        @Override
        public String outcome() {
            return reason + " → " + to.id();
        }
    }

    /** 主体迁徙抵达：沿航线到达目的地。 */
    record CrewArrived(Stardate when, LocationId where, AgentId actor,
                       LocationId from) implements CrewEvent {
        @Override
        public List<AgentId> who() {
            return List.of(actor);
        }

        @Override
        public String cause() {
            return "抵达";
        }

        @Override
        public String outcome() {
            return "自 " + from.id() + " 抵达";
        }
    }

    /** 主体饥饿告急：所在处无食物。 */
    record CrewStarving(Stardate when, LocationId where, AgentId actor,
                        int hunger) implements CrewEvent {
        @Override
        public List<AgentId> who() {
            return List.of(actor);
        }

        @Override
        public String cause() {
            return "饥饿告急";
        }

        @Override
        public String outcome() {
            return "饥饿度 " + hunger + "，本地无食物";
        }
    }

    /**
     * 离子风暴传播点：沿航线逐跳传播的因果链。
     *
     * @param upstream 上一跳地点；起源点为 null
     * @param step     传播步数（起源为 0）
     */
    record StormImpact(Stardate when, LocationId where, LocationId upstream,
                       int step, int severity, String effect) implements WorldEvent {
        @Override
        public String cause() {
            return step == 0 ? "离子风暴起源"
                    : "离子风暴传播（第 " + step + " 跳，来自 " + upstream.id() + "）";
        }

        @Override
        public String outcome() {
            return effect;
        }
    }

    /** 势力行动：目标驱动的地图级行为。 */
    record FactionAction(Stardate when, LocationId where, FactionId faction,
                         String goal, String detail) implements DiplomacyEvent {
        @Override
        public String cause() {
            return "势力目标：" + goal;
        }

        @Override
        public String outcome() {
            return detail;
        }
    }

    /** 重大事件产生待决决策。 */
    record DecisionRaised(Stardate when, LocationId where, DecisionId decision,
                          String title) implements WorldEvent {
        @Override
        public String cause() {
            return "待决决策";
        }

        @Override
        public String outcome() {
            return decision + " " + title;
        }
    }

    /** 玩家做出决策。 */
    record DecisionResolved(Stardate when, LocationId where, DecisionId decision,
                            String option, String outcome) implements WorldEvent {
        @Override
        public String cause() {
            return "决策：" + decision;
        }

        @Override
        public String outcome() {
            return option + " → " + outcome;
        }
    }

    /** 运输出发。 */
    record TransferStarted(Stardate when, LocationId where, TransferId transfer,
                           LocationId from, LocationId to, ResourceKind resource,
                           BigDecimal amount, int ticks) implements FleetEvent {
        @Override
        public String cause() {
            return "货物运输 " + transfer;
        }

        @Override
        public String outcome() {
            return amount.stripTrailingZeros().toPlainString() + " " + resource.id()
                    + " " + from.id() + " → " + to.id() + "，预计 " + ticks + " 天";
        }
    }

    /** 运输抵达。 */
    record TransferArrived(Stardate when, LocationId where, TransferId transfer,
                           LocationId from, ResourceKind resource,
                           BigDecimal amount) implements FleetEvent {
        @Override
        public String cause() {
            return "运输抵达 " + transfer;
        }

        @Override
        public String outcome() {
            return amount.stripTrailingZeros().toPlainString() + " " + resource.id()
                    + " 自 " + from.id() + " 入库";
        }
    }

    /** 殖民地升级。 */
    record ColonyUpgraded(Stardate when, LocationId where, int level) implements ColonyEvent {
        @Override
        public String cause() {
            return "殖民地升级";
        }

        @Override
        public String outcome() {
            return "达到 " + level + " 级";
        }
    }

    /** 补给线建立。 */
    record SupplyLineEstablished(Stardate when, LocationId where, SupplyLineId line,
                                 LocationId from, LocationId to, ResourceKind resource,
                                 BigDecimal amount) implements MarketEvent {
        @Override
        public String cause() {
            return "补给线 " + line;
        }

        @Override
        public String outcome() {
            return from.id() + " → " + to.id() + " 每批 "
                    + amount.stripTrailingZeros().toPlainString() + " " + resource.id();
        }
    }

    /** 市场价格冲击。 */
    record MarketPriceShocked(Stardate when, LocationId where, ResourceKind resource,
                              BigDecimal multiplier) implements MarketEvent {
        @Override
        public String cause() {
            return "市场冲击";
        }

        @Override
        public String outcome() {
            return resource.id() + " 价格 ×" + multiplier.stripTrailingZeros().toPlainString();
        }
    }

    /** 工资支付。 */
    record WagePaid(Stardate when, LocationId where, AgentId actor,
                    BigDecimal amount) implements MarketEvent {
        @Override
        public List<AgentId> who() {
            return List.of(actor);
        }

        @Override
        public String cause() {
            return "工资";
        }

        @Override
        public String outcome() {
            return "支付 " + amount.stripTrailingZeros().toPlainString() + " 信用点";
        }
    }

    /** 市场成交。 */
    record TradeSettled(Stardate when, LocationId where, ResourceKind resource,
                        BigDecimal amount, BigDecimal unitPrice, String side) implements MarketEvent {
        @Override
        public String cause() {
            return "市场交易：" + side;
        }

        @Override
        public String outcome() {
            return amount.stripTrailingZeros().toPlainString() + " " + resource.id()
                    + " @ " + unitPrice.stripTrailingZeros().toPlainString();
        }
    }

    /** 航行燃料消耗。 */
    record FuelConsumed(Stardate when, LocationId where, Ids.ShipId ship,
                        BigDecimal amount) implements FleetEvent {
        @Override
        public String cause() {
            return "燃料消耗";
        }

        @Override
        public String outcome() {
            return amount.stripTrailingZeros().toPlainString() + " 燃料";
        }
    }

    /** 航行事故：风险判定命中后的延误、货损与船体损伤。 */
    record VoyageIncident(Stardate when, LocationId where, Ids.ShipId ship,
                          String kind, int delayDays, BigDecimal cargoLoss,
                          int hullDamage) implements FleetEvent {
        @Override
        public String cause() {
            return "航行事故：" + kind;
        }

        @Override
        public String outcome() {
            return "延误 " + delayDays + " 天，货损 "
                    + cargoLoss.stripTrailingZeros().toPlainString()
                    + "，耐久 -" + hullDamage;
        }
    }

    /** 舰船维修。 */
    record ShipRepaired(Stardate when, LocationId where, Ids.ShipId ship,
                        int repaired, int hull) implements FleetEvent {
        @Override
        public String cause() {
            return "舰船维修";
        }

        @Override
        public String outcome() {
            return "修复 " + repaired + " 点，当前耐久 " + hull;
        }
    }

    /** 地点知识状态提升。 */
    record KnowledgeGained(Stardate when, LocationId where, KnowledgeState state,
                           String detail) implements DiscoveryEvent {
        @Override
        public String cause() {
            return "星图知识";
        }

        @Override
        public String outcome() {
            return state.displayName() + "：" + detail;
        }
    }

    /** 开始测绘。 */
    record SurveyStarted(Stardate when, LocationId where, Ids.ShipId ship,
                         int days) implements DiscoveryEvent {
        @Override
        public String cause() {
            return "测绘开始";
        }

        @Override
        public String outcome() {
            return "预计 " + days + " 天";
        }
    }

    /** 测绘完成。 */
    record SurveyCompleted(Stardate when, LocationId where, Ids.ShipId ship,
                           int researchYield, String discovery) implements DiscoveryEvent {
        @Override
        public String cause() {
            return "测绘完成";
        }

        @Override
        public String outcome() {
            return "科研数据 +" + researchYield
                    + (discovery == null || discovery.isBlank() ? "" : "；" + discovery);
        }
    }

    /** 探索发现：额外资源或遗迹。 */
    record DiscoveryMade(Stardate when, LocationId where, String detail,
                         ResourceKind resource, BigDecimal amount) implements DiscoveryEvent {
        @Override
        public String cause() {
            return "探索发现";
        }

        @Override
        public String outcome() {
            return detail;
        }
    }

    /** 科研项目开始。 */
    record ResearchStarted(Stardate when, LocationId where, String techId,
                           String techName, int days, BigDecimal dataCost) implements DiscoveryEvent {
        @Override
        public String cause() {
            return "科研立项 " + techId;
        }

        @Override
        public String outcome() {
            return techName + "，预计 " + days + " 天，投入科研数据 "
                    + dataCost.stripTrailingZeros().toPlainString();
        }
    }

    /** 科技解锁。 */
    record TechUnlocked(Stardate when, LocationId where, String techId,
                        String techName, String effect) implements DiscoveryEvent {
        @Override
        public String cause() {
            return "科技解锁 " + techId;
        }

        @Override
        public String outcome() {
            return techName + "：" + effect;
        }
    }

    /** 外交动作（赠礼、威胁、拒绝等）。 */
    record DiplomacyAction(Stardate when, LocationId where, FactionId faction,
                           String action, String detail) implements DiplomacyEvent {
        @Override
        public String cause() {
            return "外交：" + action;
        }

        @Override
        public String outcome() {
            return detail;
        }
    }

    /** 条约签署。 */
    record TreatySigned(Stardate when, LocationId where, FactionId faction,
                        Treaty treaty, BigDecimal cost) implements DiplomacyEvent {
        @Override
        public String cause() {
            return "签署" + treaty.displayName();
        }

        @Override
        public String outcome() {
            return "花费 " + cost.stripTrailingZeros().toPlainString() + " 信用点";
        }
    }

    /** 委托生成。 */
    record CommissionOffered(Stardate when, LocationId where, String commission,
                             FactionId faction, ResourceKind resource, BigDecimal amount,
                             LocationId to, Stardate deadline, BigDecimal reward)
            implements DiplomacyEvent {
        @Override
        public String cause() {
            return "委托 " + commission;
        }

        @Override
        public String outcome() {
            return amount.stripTrailingZeros().toPlainString() + " " + resource.id()
                    + " 送往 " + to.id() + "，截止 " + deadline + "，报酬 "
                    + reward.stripTrailingZeros().toPlainString();
        }
    }

    /** 委托接取。 */
    record CommissionAccepted(Stardate when, LocationId where, String commission)
            implements DiplomacyEvent {
        @Override
        public String cause() {
            return "接取委托";
        }

        @Override
        public String outcome() {
            return commission;
        }
    }

    /** 委托完成。 */
    record CommissionCompleted(Stardate when, LocationId where, String commission,
                               BigDecimal reward, int reputation) implements DiplomacyEvent {
        @Override
        public String cause() {
            return "完成委托";
        }

        @Override
        public String outcome() {
            return commission + "，报酬 " + reward.stripTrailingZeros().toPlainString()
                    + "，声望 +" + reputation;
        }
    }

    /** 委托失败。 */
    record CommissionFailed(Stardate when, LocationId where, String commission,
                            int reputation) implements DiplomacyEvent {
        @Override
        public String cause() {
            return "委托逾期";
        }

        @Override
        public String outcome() {
            return commission + "，声望 " + reputation;
        }
    }

    /** 冲突结算。 */
    record ConflictResolved(Stardate when, LocationId where, FactionId faction,
                            String outcome, int fleetPower, int threatPower,
                            int hullDamage, BigDecimal cargoLoss)
            implements DiplomacyEvent {
        @Override
        public String cause() {
            return "舰队冲突";
        }

        @Override
        public String outcome() {
            return outcome + "（我方 " + fleetPower + " / 对方 " + threatPower
                    + "，耐久 -" + hullDamage + "，货损 "
                    + cargoLoss.stripTrailingZeros().toPlainString() + "）";
        }
    }

    /** 船坞下单。 */
    record ShipOrdered(Stardate when, LocationId where, String order,
                       String shipType, int days, BigDecimal cost) implements FleetEvent {
        @Override
        public String cause() {
            return "船坞订单 " + order;
        }

        @Override
        public String outcome() {
            return shipType + "，" + days + " 天，花费 "
                    + cost.stripTrailingZeros().toPlainString();
        }
    }

    /** 新舰船交付。 */
    record ShipDelivered(Stardate when, LocationId where, String order,
                         Ids.ShipId ship, String shipName) implements FleetEvent {
        @Override
        public String cause() {
            return "舰船交付 " + order;
        }

        @Override
        public String outcome() {
            return shipName + "（舰船#" + ship.id() + "）停靠";
        }
    }

    /** 终局工程开工（星门/信标）。 */
    record ProjectStarted(Stardate when, LocationId where, String project,
                          int days, ResourceKind resource, BigDecimal amount)
            implements WorldEvent {
        @Override
        public String cause() {
            return "终局工程 " + project;
        }

        @Override
        public String outcome() {
            return "预计 " + days + " 天，投入 "
                    + amount.stripTrailingZeros().toPlainString() + " " + resource.id();
        }
    }

    /** 终局工程完成。 */
    record ProjectCompleted(Stardate when, LocationId where, String project)
            implements WorldEvent {
        @Override
        public String cause() {
            return "终局工程完成";
        }

        @Override
        public String outcome() {
            return project;
        }
    }

    /** 抵达终局：时间冻结，游戏完成。 */
    record EndingReached(Stardate when, LocationId where, String ending,
                         String detail) implements WorldEvent {
        @Override
        public String cause() {
            return "结局：" + ending;
        }

        @Override
        public String outcome() {
            return detail;
        }
    }
}
