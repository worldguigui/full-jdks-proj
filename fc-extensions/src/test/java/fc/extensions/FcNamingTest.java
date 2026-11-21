package fc.extensions;

import fc.testkit.FcTest;
import fc.testkit.TestRunner;

import javax.naming.Binding;
import javax.naming.Context;
import javax.naming.InvalidNameException;
import javax.naming.NameAlreadyBoundException;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.naming.event.NamespaceChangeListener;
import javax.naming.event.NamingEvent;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** fc:// 命名上下文：绑定/查找、层级枚举、服务引用、事件、链接与关闭语义。 */
public final class FcNamingTest {

    public static void main(String[] args) {
        if (!TestRunner.report(TestRunner.run(FcNamingTest.class))) {
            System.exit(1);
        }
    }

    @FcTest
    public void bindLookupAndListRoundTrip() throws Exception {
        try (FcNaming naming = FcNaming.open()) {
            assert naming.list("fc:").equals(FcNaming.SCOPES) : naming.list("fc:");

            naming.bind("fc:config/recipes", "配方面板");
            assert naming.lookup("fc:config/recipes").equals("配方面板");
            assert naming.list("fc:config").equals(List.of("recipes"));
            List<Binding> bindings = naming.listBindings("fc:config");
            assert bindings.size() == 1;
            assert bindings.get(0).getName().equals("recipes");
            assert bindings.get(0).getObject().equals("配方面板");

            naming.rebind("fc:config/recipes", "新配方");
            assert naming.lookup("fc:config/recipes").equals("新配方");

            naming.unbind("fc:config/recipes");
            assert naming.list("fc:config").isEmpty();
        }
    }

    @FcTest
    public void absoluteAndRelativeNamesResolveToTheSameObject() throws Exception {
        try (FcNaming naming = FcNaming.open()) {
            Context service = (Context) naming.lookup("fc:service");
            assert service.getNameInNamespace().equals("service");

            Context slot = service.createSubcontext("fc:save/slot-3");
            naming.bind("fc:save/slot-3/world", "世界数据");
            assert service.lookup("fc:save/slot-3/world").equals("世界数据");
            assert naming.lookup("save/slot-3/world").equals("世界数据");

            Context nested = slot.createSubcontext("chunks");
            assert nested.getNameInNamespace().equals("save/slot-3/chunks");
            assert naming.list("fc:save/slot-3").equals(List.of("world", "chunks"));
            slot.destroySubcontext("chunks");
            assert naming.list("fc:save/slot-3").equals(List.of("world"));
            service.destroySubcontext("fc:save/slot-3");
            assert naming.list("fc:save").isEmpty();
        }
    }

    @FcTest
    public void serviceReferencesAreRebuiltByObjectFactory() throws Exception {
        try (FcNaming naming = FcNaming.open()) {
            naming.registerService("fc:service/chat", "chat", "fc://chat/loopback:4100");
            naming.registerService("fc:service/jmx", "jmx",
                    "service:jmx:rmi:///jndi/rmi://127.0.0.1:4101/jmxrmi");

            Object raw = naming.lookup("fc:service/chat");
            assert raw instanceof FcNaming.FcService : raw.getClass();
            FcNaming.FcService service = (FcNaming.FcService) raw;
            assert service.kind().equals("chat");
            assert service.endpoint().equals("fc://chat/loopback:4100");
            assert service.toString().contains("chat →");

            List<FcNaming.FcService> services = naming.services();
            assert services.size() == 2 : services;
            assert services.stream().anyMatch(s -> s.kind().equals("jmx"));

            naming.unbind("fc:service/chat");
            assert naming.services().size() == 1;
        }
    }

    @FcTest
    public void invalidNamesAndMissingBindingsAreRejected() throws Exception {
        try (FcNaming naming = FcNaming.open()) {
            assertThrows(InvalidNameException.class,
                    () -> naming.lookup("fc:unknown/x"));
            assertThrows(InvalidNameException.class,
                    () -> naming.lookup("fc:service/../escape"));
            assertThrows(InvalidNameException.class,
                    () -> naming.bind("fc:service", "不能绑在 scope 上"));
            assertThrows(NameNotFoundException.class,
                    () -> naming.lookup("fc:service/absent"));
            assertThrows(NameNotFoundException.class,
                    () -> naming.unbind("fc:service/absent"));

            naming.bind("fc:mod/combat", "mod 资源");
            assertThrows(NameAlreadyBoundException.class,
                    () -> naming.bind("fc:mod/combat", "重复"));
            assert naming.lookup("fc:mod/combat").equals("mod 资源");
        }
    }

    @FcTest
    public void linksResolveAndListenersObserveChanges() throws Exception {
        try (FcNaming naming = FcNaming.open()) {
            naming.registerService("fc:service/real", "chat", "fc://chat/loopback:4200");
            naming.bind("fc:service/alias",
                    new javax.naming.LinkRef("fc:service/real"));
            Object linked = naming.lookupLink("fc:service/alias");
            assert linked instanceof FcNaming.FcService : linked;

            AtomicInteger added = new AtomicInteger();
            AtomicInteger removed = new AtomicInteger();
            NamespaceChangeListener listener = new NamespaceChangeListener() {
                @Override
                public void objectAdded(NamingEvent event) {
                    added.incrementAndGet();
                }

                @Override
                public void objectRemoved(NamingEvent event) {
                    removed.incrementAndGet();
                }

                @Override
                public void objectRenamed(NamingEvent event) {
                    // 本用例不触发重命名。
                }

                @Override
                public void namingExceptionThrown(
                        javax.naming.event.NamingExceptionEvent event) {
                    throw new AssertionError(event.getException());
                }
            };
            naming.addListener(listener);
            naming.bind("fc:chronicle/leaf", "事件");
            naming.unbind("fc:chronicle/leaf");
            assert added.get() == 1 : added.get();
            assert removed.get() == 1 : removed.get();

            naming.removeListener(listener);
            naming.bind("fc:chronicle/leaf2", "事件 2");
            naming.unbind("fc:chronicle/leaf2");
            assert added.get() == 1 && removed.get() == 1;
        }
    }

    @FcTest
    public void closeIsolatesAndInvalidatesTheNamespace() throws Exception {
        FcNaming first = FcNaming.open();
        FcNaming second = FcNaming.open();
        try {
            first.bind("fc:service/first", "一号");
            assertThrows(NameNotFoundException.class,
                    () -> second.lookup("fc:service/first"));
        } finally {
            second.close();
        }

        first.close();
        first.close();
        assertThrows(NamingException.class, () -> first.lookup("fc:service/first"));
    }

    private static void assertThrows(Class<? extends Throwable> type, ThrowingCall call)
            throws Exception {
        try {
            call.run();
        } catch (Throwable e) {
            if (type.isInstance(e)) {
                return;
            }
            throw new AssertionError("期望 " + type.getSimpleName() + "，实际 "
                    + e.getClass().getSimpleName() + "：" + e.getMessage(), e);
        }
        throw new AssertionError("期望抛出 " + type.getSimpleName() + "，但没有异常");
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }
}
