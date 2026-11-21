package fc.extensions;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.JMException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanNotificationInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 动态 MBean 形式的遥测目录：在运行期自描述可见的游戏指标，并提供一个
 * 结构化快照操作，供 {@code jconsole} 等通用客户端直接浏览。
 *
 * <p>注册名 {@code fc.telemetry:type=Catalog}。三个只读属性分别是
 * {@code BeanCount}（平台 MBean 总数）、{@code GameDay}、{@code GameHash}
 * （游戏遥测未注册时为零值）。操作 {@code snapshot} 返回
 * {@link CompositeData}（OpenType {@code GameSnapshot}），{@code find} 按 JMX
 * 域枚举 MBean 名称。</p>
 *
 * <p>动态 MBean 不使用 {@code *MBean} 接口：{@link #getMBeanInfo()} 返回的
 * {@link MBeanInfo} 就是契约本身，客户端据此渲染属性与操作。</p>
 */
public final class FrontierCatalog implements DynamicMBean, AutoCloseable {

    public static final String OBJECT_NAME = "fc.telemetry:type=Catalog";

    private static final String CLASS_NAME = FrontierCatalog.class.getName();
    private static final String SNAPSHOT_TYPE_NAME = "GameSnapshot";
    private static final String[] SNAPSHOT_ITEMS = {
            "day", "now", "stateHash", "locations", "ships", "credits", "ending"};
    private static final String[] SNAPSHOT_DESCRIPTIONS = {
            "游戏日", "当前星历", "状态哈希", "地点数", "舰船数", "信用点", "结局"};
    private static final CompositeType SNAPSHOT_TYPE = snapshotType();

    private static final MBeanAttributeInfo[] ATTRIBUTES = {
            new MBeanAttributeInfo("BeanCount", Integer.class.getName(),
                    "平台 MBeanServer 中注册的 MBean 总数", true, false, false),
            new MBeanAttributeInfo("GameDay", Long.class.getName(),
                    "游戏遥测中的当前日（未注册时 0）", true, false, false),
            new MBeanAttributeInfo("GameHash", String.class.getName(),
                    "游戏遥测中的状态哈希（未注册时为空串）", true, false, false)
    };

    private static final MBeanOperationInfo[] OPERATIONS = {
            new MBeanOperationInfo("snapshot", "读取游戏遥测的结构化快照",
                    new MBeanParameterInfo[0], CompositeData.class.getName(),
                    MBeanOperationInfo.INFO),
            new MBeanOperationInfo("find", "按 JMX 域枚举 MBean 名称",
                    new MBeanParameterInfo[] {new MBeanParameterInfo("domain",
                            String.class.getName(), "域，如 fc.telemetry")},
                    String[].class.getName(), MBeanOperationInfo.INFO)
    };

    private final MBeanServer server;
    private final ObjectName name;

    private FrontierCatalog(MBeanServer server, ObjectName name) {
        this.server = server;
        this.name = name;
    }

    /** 在平台 MBeanServer 上注册；重复注册会抛 InstanceAlreadyExistsException。 */
    public static FrontierCatalog register() throws JMException {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        FrontierCatalog catalog = new FrontierCatalog(server, new ObjectName(OBJECT_NAME));
        server.registerMBean(catalog, catalog.name);
        return catalog;
    }

    private static CompositeType snapshotType() {
        try {
            return new CompositeType(SNAPSHOT_TYPE_NAME, "边境纪事游戏遥测快照",
                    SNAPSHOT_ITEMS, SNAPSHOT_DESCRIPTIONS,
                    new OpenType<?>[] {SimpleType.LONG, SimpleType.STRING, SimpleType.STRING,
                            SimpleType.INTEGER, SimpleType.INTEGER, SimpleType.STRING,
                            SimpleType.STRING});
        } catch (OpenDataException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public Object getAttribute(String attribute) throws AttributeNotFoundException {
        return switch (attribute) {
            case "BeanCount" -> server.getMBeanCount();
            case "GameDay" -> telemetryValue("Day", 0L);
            case "GameHash" -> telemetryValue("StateHash", "");
            default -> throw new AttributeNotFoundException("未知属性：" + attribute);
        };
    }

    @Override
    public void setAttribute(Attribute attribute) throws AttributeNotFoundException {
        throw new AttributeNotFoundException("目录属性只读：" + attribute.getName());
    }

    @Override
    public AttributeList getAttributes(String[] attributes) {
        AttributeList list = new AttributeList();
        for (String attribute : attributes) {
            try {
                list.add(new Attribute(attribute, getAttribute(attribute)));
            } catch (AttributeNotFoundException | RuntimeException ignored) {
                // 与 JMX 语义一致：跳过无法读取的属性。
            }
        }
        return list;
    }

    @Override
    public AttributeList setAttributes(AttributeList attributes) {
        return new AttributeList();
    }

    @Override
    public Object invoke(String actionName, Object[] params, String[] signature)
            throws MBeanException, ReflectionException {
        try {
            return switch (actionName) {
                case "snapshot" -> snapshot();
                case "find" -> {
                    if (params == null || params.length != 1 || !(params[0] instanceof String)) {
                        throw new MBeanException(new IllegalArgumentException("find 需要一个域参数"));
                    }
                    yield find((String) params[0]);
                }
                default -> throw new ReflectionException(
                        new NoSuchMethodException(actionName), "未知操作：" + actionName);
            };
        } catch (OpenDataException e) {
            throw new MBeanException(e, "快照构造失败");
        }
    }

    @Override
    public MBeanInfo getMBeanInfo() {
        return new MBeanInfo(CLASS_NAME, "fc 遥测目录：动态属性与游戏快照",
                ATTRIBUTES, new javax.management.MBeanConstructorInfo[0],
                OPERATIONS, new MBeanNotificationInfo[0]);
    }

    /** 读取游戏遥测属性；MBean 未注册或属性缺失时返回默认值。 */
    private Object telemetryValue(String attribute, Object fallback) {
        try {
            return server.getAttribute(new ObjectName(GameTelemetry.OBJECT_NAME), attribute);
        } catch (JMException | RuntimeException e) {
            return fallback;
        }
    }

    private CompositeData snapshot() throws OpenDataException {
        return new CompositeDataSupport(SNAPSHOT_TYPE, SNAPSHOT_ITEMS,
                new Object[] {
                        ((Number) telemetryValue("Day", 0L)).longValue(),
                        telemetryValue("Now", ""),
                        telemetryValue("StateHash", ""),
                        ((Number) telemetryValue("Locations", 0)).intValue(),
                        ((Number) telemetryValue("Ships", 0)).intValue(),
                        telemetryValue("Credits", "0"),
                        telemetryValue("Ending", "")
                });
    }

    private String[] find(String domain) throws MBeanException {
        if (domain == null || domain.isBlank() || domain.contains(":")) {
            throw new MBeanException(new IllegalArgumentException("域不合法：" + domain));
        }
        try {
            Set<ObjectName> names = new TreeSet<>(
                    server.queryNames(new ObjectName(domain + ":*"), null));
            List<String> result = new ArrayList<>();
            for (ObjectName found : names) {
                result.add(found.getCanonicalName());
            }
            return result.toArray(String[]::new);
        } catch (JMException | RuntimeException e) {
            throw new MBeanException(e, "查询域失败：" + domain);
        }
    }

    /** 注销目录 MBean；幂等。 */
    @Override
    public void close() {
        try {
            if (server.isRegistered(name)) {
                server.unregisterMBean(name);
            }
        } catch (JMException ignored) {
            // 注销失败不影响退出。
        }
    }
}
