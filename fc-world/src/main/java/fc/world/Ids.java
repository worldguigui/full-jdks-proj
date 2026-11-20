package fc.world;

/**
 * 统一标识值对象。
 *
 * <p>本包集中定义所有领域实体的标识类型（record），它们是不可变、可比较、可序列化的。
 * 使用强类型标识（而非裸 {@code long}）是为了在类型系统层面防止不同实体 id 混用。</p>
 */
public final class Ids {

    private Ids() {
    }

    /** 地点标识（Location 的身份，进入 LocationGraph）。 */
    public record LocationId(long id) implements Comparable<LocationId> {
        @Override
        public int compareTo(LocationId o) {
            return Long.compare(this.id, o.id);
        }
    }

    /** 行为主体标识（船员/居民/使者共用 Agent 类型）。 */
    public record AgentId(long id) implements Comparable<AgentId> {
        @Override
        public int compareTo(AgentId o) {
            return Long.compare(this.id, o.id);
        }
    }

    /** 舰船标识（Ship 是移动 Location，另有舰队引用）。 */
    public record ShipId(long id) implements Comparable<ShipId> {
        @Override
        public int compareTo(ShipId o) {
            return Long.compare(this.id, o.id);
        }
    }

    /** 势力标识。 */
    public record FactionId(long id) implements Comparable<FactionId> {
        @Override
        public int compareTo(FactionId o) {
            return Long.compare(this.id, o.id);
        }
    }
}
