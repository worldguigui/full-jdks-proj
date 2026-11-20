package fc.sim.event;

import fc.world.GameEvent;

import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tick 之后的事件广播（{@link Flow} 响应式流）。
 *
 * <p>严格边界：本类**只用于报告/遥测/日志**，不参与任何状态写入——
 * 状态写入只发生在 tick 阶段内。这样既保留了流式通知能力，
 * 又不破坏确定性（订阅者晚到或早退都不影响历史）。</p>
 */
public final class EventBus implements AutoCloseable {

    private final SubmissionPublisher<GameEvent> publisher =
            new SubmissionPublisher<>(Runnable::run, Flow.defaultBufferSize());

    /** 订阅事件（用于日志、遥测、UI）。 */
    public void subscribe(Flow.Subscriber<GameEvent> subscriber) {
        publisher.subscribe(subscriber);
    }

    /** Tick 结束后广播本轮事件（顺序即编年史顺序）。 */
    public void publishAll(List<GameEvent> events) {
        for (GameEvent e : events) {
            publisher.submit(e);
        }
    }

    /** 当前订阅者数量（诊断用）。 */
    public int subscriberCount() {
        return publisher.getNumberOfSubscribers();
    }

    @Override
    public void close() {
        publisher.close();
    }

    /**
     * 计数订阅者：只统计收到多少事件，供诊断/测试校验广播链路可用。
     */
    public static final class CountingSubscriber implements Flow.Subscriber<GameEvent> {

        private final AtomicInteger received = new AtomicInteger();
        private Flow.Subscription subscription;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(GameEvent item) {
            received.incrementAndGet();
        }

        @Override
        public void onError(Throwable throwable) {
            // 诊断订阅者忽略错误
        }

        @Override
        public void onComplete() {
            // 无需处理
        }

        public int received() {
            return received.get();
        }

        public boolean isSubscribed() {
            return subscription != null;
        }
    }
}
