package fc.extensions;

import javax.naming.Binding;
import javax.naming.CompositeName;
import javax.naming.Context;
import javax.naming.InvalidNameException;
import javax.naming.LinkRef;
import javax.naming.Name;
import javax.naming.NameAlreadyBoundException;
import javax.naming.NameClassPair;
import javax.naming.NameNotFoundException;
import javax.naming.NameParser;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.NotContextException;
import javax.naming.Reference;
import javax.naming.Referenceable;
import javax.naming.StringRefAddr;
import javax.naming.event.EventContext;
import javax.naming.event.NamespaceChangeListener;
import javax.naming.event.NamingListener;
import javax.naming.event.NamingEvent;
import javax.naming.spi.InitialContextFactory;
import javax.naming.spi.NamingManager;
import javax.naming.spi.ObjectFactory;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@code fc://} 统一资源命名上下文：按项目命名规范提供的进程内 JNDI 实现。
 *
 * <p>命名格式为 {@code fc:<scope>/<path>[/<subpath>...]}，顶级 scope 固定为
 * {@code mod}/{@code config}/{@code save}/{@code service}/{@code chronicle}。
 * 通过自研 {@link InitialContextFactory} 返回进程内 {@link Context}，不连接任何
 * 外部目录服务器；{@link Reference}/{@link ObjectFactory} 用于表达可重建的服务引用。</p>
 *
 * <p>每个 {@link #open()} 得到独立的命名空间，适合按会话隔离。绑定、解绑会向注册的
 * {@link NamespaceChangeListener} 发送 {@link NamingEvent}。所有修改操作在上下文关闭后
 * 抛出 {@link NamingException}。</p>
 */
public final class FcNaming implements AutoCloseable {

    /** 固定协议前缀。 */
    public static final String SCHEME = "fc:";
    /** 顶级命名空间（固定顺序）。 */
    public static final List<String> SCOPES =
            List.of("mod", "config", "save", "service", "chronicle");

    /** 环境键：把 {@link FcRoot} 传给 {@link FcInitialContextFactory}。 */
    static final String ROOT_KEY = FcNaming.class.getName() + ".root";

    private final InitialContextHolder holder;

    private FcNaming(InitialContextHolder holder) {
        this.holder = holder;
    }

    /** 打开一个独立的进程内命名空间。 */
    public static FcNaming open() throws NamingException {
        FcRoot root = new FcRoot();
        Hashtable<Object, Object> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, FcInitialContextFactory.class.getName());
        env.put(ROOT_KEY, root);
        root.env = env;
        return new FcNaming(new InitialContextHolder(env));
    }

    public void bind(String name, Object value) throws NamingException {
        holder.context().bind(name, value);
    }

    public void rebind(String name, Object value) throws NamingException {
        holder.context().rebind(name, value);
    }

    public void unbind(String name) throws NamingException {
        holder.context().unbind(name);
    }

    public Object lookup(String name) throws NamingException {
        return holder.context().lookup(name);
    }

    /** 解析链接（LinkRef）；非链接名字与 {@link #lookup} 等价。 */
    public Object lookupLink(String name) throws NamingException {
        return holder.context().lookupLink(name);
    }

    @SuppressWarnings("unchecked")
    public <T> T lookup(String name, Class<T> type) throws NamingException {
        Object value = lookup(name);
        if (!type.isInstance(value)) {
            throw new NamingException("名字 " + name + " 绑定的是 "
                    + value.getClass().getName() + "，不是 " + type.getName());
        }
        return (T) value;
    }

    /** 列出某个上下文下的直接子项（名称按绑定顺序）。 */
    public List<String> list(String contextName) throws NamingException {
        List<String> names = new ArrayList<>();
        NamingEnumeration<NameClassPair> enumeration = holder.context().list(contextName);
        try {
            while (enumeration.hasMore()) {
                names.add(enumeration.next().getName());
            }
        } finally {
            enumeration.close();
        }
        return List.copyOf(names);
    }

    /** 列出某个上下文下的直接绑定。 */
    public List<Binding> listBindings(String contextName) throws NamingException {
        List<Binding> bindings = new ArrayList<>();
        NamingEnumeration<Binding> enumeration = holder.context().listBindings(contextName);
        try {
            while (enumeration.hasMore()) {
                bindings.add(enumeration.next());
            }
        } finally {
            enumeration.close();
        }
        return List.copyOf(bindings);
    }

    /** 绑定一个可重建的服务引用（Reference + ObjectFactory）。 */
    public void registerService(String name, String kind, String endpoint)
            throws NamingException {
        bind(name, new FcService(kind, endpoint).getReference());
    }

    /** 解析 {@code fc:service} 下的全部服务引用。 */
    public List<FcService> services() throws NamingException {
        List<FcService> services = new ArrayList<>();
        for (Binding binding : listBindings("fc:service")) {
            Object value = lookup("fc:service/" + binding.getName());
            if (value instanceof FcService service) {
                services.add(service);
            }
        }
        return List.copyOf(services);
    }

    /** 注册命名空间变更监听器（绑定/解绑/重绑）。 */
    public void addListener(NamespaceChangeListener listener) {
        holder.root().addListener(listener);
    }

    public void removeListener(NamespaceChangeListener listener) {
        holder.root().removeListener(listener);
    }

    /** 当前命名空间的根 Context（用于互操作）。 */
    public Context context() throws NamingException {
        return holder.context();
    }

    /** 关闭命名空间并解除全部绑定。 */
    @Override
    public void close() {
        holder.close();
    }

    /** {@code fc:service} 中的服务引用；{@link ObjectFactory} 负责重建。 */
    public record FcService(String kind, String endpoint) implements Referenceable {
        @Override
        public Reference getReference() {
            String factory = FcObjectFactory.class.getName();
            Reference reference = new Reference(FcService.class.getName(),
                    new StringRefAddr("kind", kind), factory, null);
            reference.add(new StringRefAddr("endpoint", endpoint));
            return reference;
        }

        @Override
        public String toString() {
            return kind + " → " + endpoint;
        }
    }

    /** 供 {@code InitialContext} 使用的环境持有者。 */
    private static final class InitialContextHolder {
        private final Hashtable<Object, Object> env;
        private Context context;

        InitialContextHolder(Hashtable<Object, Object> env) throws NamingException {
            this.env = env;
            this.context = new javax.naming.InitialContext(env);
        }

        Context context() throws NamingException {
            if (context == null) {
                throw new NamingException("命名上下文已关闭");
            }
            return context;
        }

        FcRoot root() {
            return (FcRoot) env.get(ROOT_KEY);
        }

        void close() {
            Context current = context;
            context = null;
            if (current != null) {
                try {
                    current.close();
                } catch (NamingException ignored) {
                    // 关闭尽力而为。
                }
            }
        }
    }

    /** 自研 InitialContextFactory：从环境取独立根，缺省时新建。 */
    public static final class FcInitialContextFactory implements InitialContextFactory {
        @Override
        public Context getInitialContext(Hashtable<?, ?> environment)
                throws NamingException {
            Object root = environment == null ? null : environment.get(ROOT_KEY);
            if (root instanceof FcRoot fcRoot) {
                return fcRoot;
            }
            FcRoot fresh = new FcRoot();
            fresh.env = environment == null ? new Hashtable<>() : environment;
            return fresh;
        }
    }

    /** 把 {@link Reference} 重建为 {@link FcService}。 */
    public static final class FcObjectFactory implements ObjectFactory {
        @Override
        public Object getObjectInstance(Object object, Name name, Context context,
                                        Hashtable<?, ?> environment) {
            if (!(object instanceof Reference reference)) {
                return null;
            }
            if (!FcService.class.getName().equals(reference.getClassName())) {
                return null;
            }
            String kind = text(reference, "kind");
            String endpoint = text(reference, "endpoint");
            if (kind == null || endpoint == null) {
                return null;
            }
            return new FcService(kind, endpoint);
        }

        private static String text(Reference reference, String type) {
            return reference.get(type) == null ? null
                    : String.valueOf(reference.get(type).getContent());
        }
    }

    /** 命名根：固定 scope 子上下文、事件监听与环境。 */
    static final class FcRoot implements Context, EventContext {
        private final Map<String, Object> bindings = new LinkedHashMap<>();
        private final CopyOnWriteArrayList<NamingListener> listeners =
                new CopyOnWriteArrayList<>();
        private final Hashtable<Object, Object> ownEnv = new Hashtable<>();
        private volatile Hashtable<?, ?> env = ownEnv;
        private volatile boolean closed;

        FcRoot() {
            for (String scope : SCOPES) {
                bindings.put(scope, new FcContext(this, scope));
            }
        }

        void addListener(NamingListener listener) {
            if (listener != null) {
                listeners.add(listener);
            }
        }

        void removeListener(NamingListener listener) {
            listeners.remove(listener);
        }

        private void fire(int type, String name, Object oldValue, Object newValue) {
            if (listeners.isEmpty()) {
                return;
            }
            Binding oldBinding = oldValue == null ? null
                    : new Binding(name, oldValue.getClass().getName(), oldValue);
            Binding newBinding = newValue == null ? null
                    : new Binding(name, newValue.getClass().getName(), newValue);
            NamingEvent event = new NamingEvent(this, type, newBinding, oldBinding, null);
            for (NamingListener listener : listeners) {
                event.dispatch(listener);
            }
        }

        private void checkOpen() throws NamingException {
            if (closed) {
                throw new NamingException("命名上下文已关闭");
            }
        }

        @Override
        public Object lookup(Name name) throws NamingException {
            return lookup(name.toString());
        }

        @Override
        public Object lookup(String name) throws NamingException {
            checkOpen();
            String canonical = Names.canonical(name);
            if (canonical.isEmpty()) {
                return this;
            }
            Object value = Names.resolve(this, canonical);
            return Names.dereference(value, canonical, this, env);
        }

        @Override
        public void bind(Name name, Object object) throws NamingException {
            bind(name.toString(), object);
        }

        @Override
        public void bind(String name, Object object) throws NamingException {
            checkOpen();
            if (object == null) {
                throw new InvalidNameException("绑定对象不能为空");
            }
            Names.Slot slot = Names.slot(this, name);
            if (slot.parent().bindings.containsKey(slot.leaf())) {
                throw new NameAlreadyBoundException("名字已绑定：" + slot.canonical());
            }
            slot.parent().bindings.put(slot.leaf(), object);
            fire(NamingEvent.OBJECT_ADDED, slot.canonical(), null, object);
        }

        @Override
        public void rebind(Name name, Object object) throws NamingException {
            rebind(name.toString(), object);
        }

        @Override
        public void rebind(String name, Object object) throws NamingException {
            checkOpen();
            if (object == null) {
                throw new InvalidNameException("绑定对象不能为空");
            }
            Names.Slot slot = Names.slot(this, name);
            Object old = slot.parent().bindings.put(slot.leaf(), object);
            fire(old == null ? NamingEvent.OBJECT_ADDED : NamingEvent.OBJECT_CHANGED,
                    slot.canonical(), old, object);
        }

        @Override
        public void unbind(Name name) throws NamingException {
            unbind(name.toString());
        }

        @Override
        public void unbind(String name) throws NamingException {
            checkOpen();
            Names.Slot slot = Names.slot(this, name);
            if (!slot.parent().bindings.containsKey(slot.leaf())) {
                throw new NameNotFoundException("名字未绑定：" + slot.canonical());
            }
            Object old = slot.parent().bindings.remove(slot.leaf());
            fire(NamingEvent.OBJECT_REMOVED, slot.canonical(), old, null);
        }

        @Override
        public void rename(Name oldName, Name newName) throws NamingException {
            checkOpen();
            String from = Names.canonical(oldName.toString());
            String to = Names.canonical(newName.toString());
            Object value = Names.resolve(this, from);
            if (value == null) {
                throw new NameNotFoundException("名字未绑定：" + from);
            }
            unbind(from);
            rebind(to, value);
        }

        @Override
        public void rename(String oldName, String newName) throws NamingException {
            rename(new CompositeName(Names.canonical(oldName)),
                    new CompositeName(Names.canonical(newName)));
        }

        @Override
        public NamingEnumeration<NameClassPair> list(Name name) throws NamingException {
            return list(name.toString());
        }

        @Override
        public NamingEnumeration<NameClassPair> list(String name) throws NamingException {
            checkOpen();
            String canonical = Names.canonical(name);
            Map<String, Object> children = canonical.isEmpty()
                    ? bindings : Names.context(this, canonical).bindings;
            List<NameClassPair> pairs = new ArrayList<>();
            for (Map.Entry<String, Object> entry : children.entrySet()) {
                pairs.add(new NameClassPair(entry.getKey(),
                        entry.getValue().getClass().getName()));
            }
            return new FcEnumeration<>(pairs.iterator());
        }

        @Override
        public NamingEnumeration<Binding> listBindings(Name name) throws NamingException {
            return listBindings(name.toString());
        }

        @Override
        public NamingEnumeration<Binding> listBindings(String name)
                throws NamingException {
            checkOpen();
            String canonical = Names.canonical(name);
            Map<String, Object> children = canonical.isEmpty()
                    ? bindings : Names.context(this, canonical).bindings;
            List<Binding> results = new ArrayList<>();
            for (Map.Entry<String, Object> entry : children.entrySet()) {
                results.add(new Binding(entry.getKey(),
                        entry.getValue().getClass().getName(), entry.getValue()));
            }
            return new FcEnumeration<>(results.iterator());
        }

        @Override
        public void destroySubcontext(Name name) throws NamingException {
            destroySubcontext(name.toString());
        }

        @Override
        public void destroySubcontext(String name) throws NamingException {
            checkOpen();
            Names.Slot slot = Names.slot(this, name);
            Object value = slot.parent().bindings.get(slot.leaf());
            if (value == null) {
                throw new NameNotFoundException("名字未绑定：" + slot.canonical());
            }
            if (!(value instanceof Context)) {
                throw new NotContextException("不是子上下文：" + slot.canonical());
            }
            slot.parent().bindings.remove(slot.leaf());
            fire(NamingEvent.OBJECT_REMOVED, slot.canonical(), value, null);
        }

        @Override
        public Context createSubcontext(Name name) throws NamingException {
            return createSubcontext(name.toString());
        }

        @Override
        public Context createSubcontext(String name) throws NamingException {
            checkOpen();
            Names.Slot slot = Names.slot(this, name);
            if (slot.parent().bindings.containsKey(slot.leaf())) {
                throw new NameAlreadyBoundException("名字已绑定：" + slot.canonical());
            }
            FcContext context = new FcContext(this, slot.canonical());
            slot.parent().bindings.put(slot.leaf(), context);
            fire(NamingEvent.OBJECT_ADDED, slot.canonical(), null, context);
            return context;
        }

        @Override
        public Object lookupLink(Name name) throws NamingException {
            return lookupLink(name.toString());
        }

        @Override
        public Object lookupLink(String name) throws NamingException {
            Object value = lookup(name);
            for (int hops = 0; hops < 8 && value instanceof LinkRef link; hops++) {
                value = lookup(link.getLinkName());
            }
            return value;
        }

        @Override
        public NameParser getNameParser(Name name) throws NamingException {
            return Names.PARSER;
        }

        @Override
        public NameParser getNameParser(String name) throws NamingException {
            return Names.PARSER;
        }

        @Override
        public Name composeName(Name name, Name prefix) throws NamingException {
            Name composed = (Name) prefix.clone();
            composed.addAll((Name) name.clone());
            return composed;
        }

        @Override
        public String composeName(String name, String prefix) throws NamingException {
            if (prefix == null || prefix.isEmpty()) {
                return name;
            }
            if (name == null || name.isEmpty()) {
                return prefix;
            }
            return prefix + "/" + name;
        }

        @Override
        public Object addToEnvironment(String propName, Object propVal)
                throws NamingException {
            checkOpen();
            Hashtable<Object, Object> target = mutableEnv();
            return target.put(propName, propVal);
        }

        @Override
        public Object removeFromEnvironment(String propName) throws NamingException {
            checkOpen();
            return mutableEnv().remove(propName);
        }

        private Hashtable<Object, Object> mutableEnv() {
            if (env == ownEnv) {
                return ownEnv;
            }
            Hashtable<Object, Object> copy = new Hashtable<>();
            env.forEach(copy::put);
            ownEnv.putAll(copy);
            env = ownEnv;
            return ownEnv;
        }

        @Override
        public Hashtable<?, ?> getEnvironment() {
            return env;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            bindings.clear();
            listeners.clear();
        }

        @Override
        public String getNameInNamespace() {
            return "";
        }

        @Override
        public void addNamingListener(Name target, int scope, NamingListener listener)
                throws NamingException {
            addNamingListener(target.toString(), scope, listener);
        }

        @Override
        public void addNamingListener(String target, int scope, NamingListener listener)
                throws NamingException {
            addListener(listener);
        }

        @Override
        public void removeNamingListener(NamingListener listener) throws NamingException {
            removeListener(listener);
        }

        @Override
        public boolean targetMustExist() {
            return true;
        }
    }

    /** 普通子上下文（scope 或显式创建）。 */
    static final class FcContext implements Context {
        private final FcRoot root;
        private final String prefix;
        private final Map<String, Object> bindings = new LinkedHashMap<>();

        FcContext(FcRoot root, String prefix) {
            this.root = root;
            this.prefix = prefix;
        }

        private String absolute(String name) throws NamingException {
            String stripped = name == null ? null : name.strip();
            boolean rooted = stripped != null && (stripped.equals("fc")
                    || stripped.equals("fc:") || stripped.startsWith(SCHEME));
            String suffix = Names.canonicalPath(name);
            if (rooted) {
                return Names.canonical(suffix);
            }
            return suffix.isEmpty() ? prefix : prefix + "/" + suffix;
        }

        @Override
        public Object lookup(Name name) throws NamingException {
            return root.lookup(absolute(name.toString()));
        }

        @Override
        public Object lookup(String name) throws NamingException {
            return root.lookup(absolute(name));
        }

        @Override
        public void bind(Name name, Object object) throws NamingException {
            root.bind(absolute(name.toString()), object);
        }

        @Override
        public void bind(String name, Object object) throws NamingException {
            root.bind(absolute(name), object);
        }

        @Override
        public void rebind(Name name, Object object) throws NamingException {
            root.rebind(absolute(name.toString()), object);
        }

        @Override
        public void rebind(String name, Object object) throws NamingException {
            root.rebind(absolute(name), object);
        }

        @Override
        public void unbind(Name name) throws NamingException {
            root.unbind(absolute(name.toString()));
        }

        @Override
        public void unbind(String name) throws NamingException {
            root.unbind(absolute(name));
        }

        @Override
        public void rename(Name oldName, Name newName) throws NamingException {
            root.rename(absolute(oldName.toString()), absolute(newName.toString()));
        }

        @Override
        public void rename(String oldName, String newName) throws NamingException {
            root.rename(absolute(oldName), absolute(newName));
        }

        @Override
        public NamingEnumeration<NameClassPair> list(Name name) throws NamingException {
            return root.list(absolute(name.toString()));
        }

        @Override
        public NamingEnumeration<NameClassPair> list(String name) throws NamingException {
            return root.list(absolute(name));
        }

        @Override
        public NamingEnumeration<Binding> listBindings(Name name) throws NamingException {
            return root.listBindings(absolute(name.toString()));
        }

        @Override
        public NamingEnumeration<Binding> listBindings(String name) throws NamingException {
            return root.listBindings(absolute(name));
        }

        @Override
        public void destroySubcontext(Name name) throws NamingException {
            root.destroySubcontext(absolute(name.toString()));
        }

        @Override
        public void destroySubcontext(String name) throws NamingException {
            root.destroySubcontext(absolute(name));
        }

        @Override
        public Context createSubcontext(Name name) throws NamingException {
            return root.createSubcontext(absolute(name.toString()));
        }

        @Override
        public Context createSubcontext(String name) throws NamingException {
            return root.createSubcontext(absolute(name));
        }

        @Override
        public Object lookupLink(Name name) throws NamingException {
            return root.lookupLink(absolute(name.toString()));
        }

        @Override
        public Object lookupLink(String name) throws NamingException {
            return root.lookupLink(absolute(name));
        }

        @Override
        public NameParser getNameParser(Name name) throws NamingException {
            return Names.PARSER;
        }

        @Override
        public NameParser getNameParser(String name) throws NamingException {
            return Names.PARSER;
        }

        @Override
        public Name composeName(Name name, Name prefix) throws NamingException {
            return root.composeName(name, prefix);
        }

        @Override
        public String composeName(String name, String prefix) throws NamingException {
            return root.composeName(name, prefix);
        }

        @Override
        public Object addToEnvironment(String propName, Object propVal)
                throws NamingException {
            return root.addToEnvironment(propName, propVal);
        }

        @Override
        public Object removeFromEnvironment(String propName) throws NamingException {
            return root.removeFromEnvironment(propName);
        }

        @Override
        public Hashtable<?, ?> getEnvironment() throws NamingException {
            return root.getEnvironment();
        }

        @Override
        public void close() {
            // 子上下文随根关闭。
        }

        @Override
        public String getNameInNamespace() {
            return prefix;
        }
    }

    /** 名称解析与工具方法。 */
    static final class Names {
        static final NameParser PARSER = name -> new CompositeName(canonical(name));

        private Names() {
        }

        static String canonical(String raw) throws InvalidNameException {
            String result = canonicalPath(raw);
            if (result.isEmpty()) {
                return result;
            }
            String scope = result.contains("/") ? result.substring(0, result.indexOf('/'))
                    : result;
            if (!SCOPES.contains(scope)) {
                throw new InvalidNameException("未知命名空间：" + scope
                        + "（可用 " + String.join("/", SCOPES) + "）");
            }
            return result;
        }

        static String canonicalPath(String raw) throws InvalidNameException {
            if (raw == null) {
                throw new InvalidNameException("名字不能为空");
            }
            String text = raw.strip();
            if (text.equals("fc") || text.equals("fc:")) {
                return "";
            }
            if (text.startsWith(SCHEME)) {
                text = text.substring(SCHEME.length());
            }
            while (text.startsWith("/")) {
                text = text.substring(1);
            }
            while (text.endsWith("/")) {
                text = text.substring(0, text.length() - 1);
            }
            if (text.isEmpty()) {
                return "";
            }
            CompositeName parsed = new CompositeName(text);
            StringBuilder canonical = new StringBuilder();
            for (String part : java.util.Collections.list(parsed.getAll())) {
                if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                    throw new InvalidNameException("非法路径段：" + part);
                }
                if (canonical.length() > 0) {
                    canonical.append('/');
                }
                canonical.append(part);
            }
            return canonical.toString();
        }

        static Object resolve(FcRoot root, String canonical) throws NamingException {
            if (canonical.isEmpty()) {
                return root;
            }
            String[] parts = canonical.split("/");
            Object current = root.bindings.get(parts[0]);
            if (current == null) {
                throw new NameNotFoundException("名字未绑定：" + canonical);
            }
            for (int i = 1; i < parts.length; i++) {
                if (!(current instanceof FcContext context)) {
                    throw new NotContextException("路径段不是上下文：" + parts[i - 1]);
                }
                current = context.bindings.get(parts[i]);
                if (current == null) {
                    throw new NameNotFoundException("名字未绑定：" + canonical);
                }
            }
            return current;
        }

        static Slot slot(FcRoot root, String name) throws NamingException {
            String canonical = canonical(name);
            if (canonical.isEmpty() || !canonical.contains("/")) {
                throw new InvalidNameException("必须在 scope 下绑定具体名字：" + canonical);
            }
            int slash = canonical.lastIndexOf('/');
            String parentName = canonical.substring(0, slash);
            Object parent = resolve(root, parentName);
            if (!(parent instanceof FcContext context)) {
                throw new NotContextException("父路径不是上下文：" + parentName);
            }
            return new Slot(context, canonical.substring(slash + 1), canonical);
        }

        static FcContext context(FcRoot root, String name) throws NamingException {
            String canonical = canonical(name);
            Object value = resolve(root, canonical);
            if (!(value instanceof FcContext context)) {
                throw new NotContextException("名字不是上下文：" + canonical);
            }
            return context;
        }

        static Object dereference(Object value, String canonical, Context context,
                                  Hashtable<?, ?> env) throws NamingException {
            if (!(value instanceof Reference reference)) {
                return value;
            }
            try {
                return NamingManager.getObjectInstance(reference,
                        new CompositeName(canonical), context, env);
            } catch (NamingException e) {
                throw e;
            } catch (Exception e) {
                throw new NamingException("引用解析失败（" + canonical + "）："
                        + e.getMessage());
            }
        }

        record Slot(FcContext parent, String leaf, String canonical) {
        }
    }

    /** 把列表包装成 NamingEnumeration。 */
    private static final class FcEnumeration<T> implements NamingEnumeration<T> {
        private final Iterator<T> iterator;

        FcEnumeration(Iterator<T> iterator) {
            this.iterator = iterator;
        }

        @Override
        public boolean hasMore() {
            return iterator.hasNext();
        }

        @Override
        public T next() throws NamingException {
            return iterator.next();
        }

        @Override
        public void close() {
            // 内存枚举无需释放。
        }

        @Override
        public boolean hasMoreElements() {
            return iterator.hasNext();
        }

        @Override
        public T nextElement() {
            return iterator.next();
        }
    }
}
