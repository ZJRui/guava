/*
 * Copyright (C) 2014 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.common.eventbus;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.Queues;

import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Handler for dispatching events to subscribers, providing different event ordering guarantees that
 * make sense for different situations.
 *
 * <p><b>Note:</b> The dispatcher is orthogonal to the subscriber's {@code Executor}. The dispatcher
 * controls the order in which events are dispatched, while the executor controls how (i.e. on which
 * thread) the subscriber is actually called when an event is dispatched to it.
 *
 * @author Colin Decker
 */
@ElementTypesAreNonnullByDefault
abstract class Dispatcher {

    /**
     * Returns a dispatcher that queues events that are posted reentrantly on a thread that is already
     * dispatching an event, guaranteeing that all events posted on a single thread are dispatched to
     * all subscribers in the order they are posted.
     *
     * <p>When all subscribers are dispatched to using a <i>direct</i> executor (which dispatches on
     * the same thread that posts the event), this yields a breadth-first dispatch order on each
     * thread. That is, all subscribers to a single event A will be called before any subscribers to
     * any events B and C that are posted to the event bus by the subscribers to A.
     */
    static Dispatcher perThreadDispatchQueue() {
        return new PerThreadQueuedDispatcher();
    }

    /**
     * Returns a dispatcher that queues events that are posted in a single global queue. This behavior
     * matches the original behavior of AsyncEventBus exactly, but is otherwise not especially useful.
     * For async dispatch, an {@linkplain #immediate() immediate} dispatcher should generally be
     * preferable.
     */
    static Dispatcher legacyAsync() {
        return new LegacyAsyncDispatcher();
    }

    /**
     * Returns a dispatcher that dispatches events to subscribers immediately as they're posted
     * without using an intermediate queue to change the dispatch order. This is effectively a
     * depth-first dispatch order, vs. breadth-first when using a queue.
     */
    static Dispatcher immediate() {
        return ImmediateDispatcher.INSTANCE;
    }

    /**
     * Dispatches the given {@code event} to the given {@code subscribers}.
     */
    abstract void dispatch(Object event, Iterator<Subscriber> subscribers);

    /**
     * Implementation of a {@link #perThreadDispatchQueue()} dispatcher.
     */
    private static final class PerThreadQueuedDispatcher extends Dispatcher {

        // This dispatcher matches the original dispatch behavior of EventBus.

        /**
         * Per-thread queue of events to dispatch.
         */
        private final ThreadLocal<Queue<Event>> queue = new ThreadLocal<Queue<Event>>() {
            @Override
            protected Queue<Event> initialValue() {
                return Queues.newArrayDeque();
            }
        };

        /**
         * Per-thread dispatch state, used to avoid reentrant event dispatching.
         */
        private final ThreadLocal<Boolean> dispatching = new ThreadLocal<Boolean>() {
            @Override
            protected Boolean initialValue() {
                return false;
            }
        };

        @Override
        void dispatch(Object event, Iterator<Subscriber> subscribers) {
            checkNotNull(event);
            checkNotNull(subscribers);
            Queue<Event> queueForThread = queue.get();
            queueForThread.offer(new Event(event, subscribers));

            if (!dispatching.get()) {
                dispatching.set(true);
                try {
                    Event nextEvent;
                    while ((nextEvent = queueForThread.poll()) != null) {
                        while (nextEvent.subscribers.hasNext()) {
                            nextEvent.subscribers.next().dispatchEvent(nextEvent.event);
                        }
                    }
                } finally {
                    dispatching.remove();
                    queue.remove();
                }
            }
        }

        private static final class Event {
            private final Object event;
            private final Iterator<Subscriber> subscribers;

            private Event(Object event, Iterator<Subscriber> subscribers) {
                this.event = event;
                this.subscribers = subscribers;
            }
        }
    }

    /**
     * Implementation of a {@link #legacyAsync()} dispatcher.
     */
    private static final class LegacyAsyncDispatcher extends Dispatcher {
        //legacy:遗产

        // This dispatcher matches the original dispatch behavior of AsyncEventBus.
        // 这个调度程序匹配AsyncEventBus的原始调度行为
        //
        // We can't really make any guarantees about the overall dispatch order for this dispatcher in
        // a multithreaded environment for a couple reasons:
        // 对于这个调度台的整体调度单，我们不能做任何保证
        // 一个多线程环境的原因如下:
        //
        // 1. Subscribers to events posted on different threads can be interleaved with each other
        //    freely. (A event on one thread, B event on another could yield any of
        //    [a1, a2, a3, b1, b2], [a1, b2, a2, a3, b2], [a1, b2, b3, a2, a3], etc.)
        // 1。发布在不同线程上的事件的订阅服务器可以相互交错
        // 自由。(一个线程上的事件，另一个线程上的B事件可以产生任何
        // [a1, a2, a3, b1, b2], [b2 a1, a2、a3, b2], [a1, b2, b3, a2、a3),等等)。


        // 2. It's possible for subscribers to actually be dispatched to in a different order than they
        //    were added to the queue. It's easily possible for one thread to take the head of the
        //    queue, immediately followed by another thread taking the next element in the queue. That
        //    second thread can then dispatch to the subscriber it took before the first thread does.
        //
        // 2。对于订阅者，可能会以不同的顺序实际分派给他们
        // 被添加到队列中。一个线程很容易获得
        // queue，紧跟在后面的另一个线程获取队列中的下一个元素。那
        // 第二个线程可以在第一个线程之前分派给订阅服务器。

        // All this makes me really wonder if there's any value in queueing here at all. A dispatcher
        // that simply loops through the subscribers and dispatches the event to each would actually
        // probably provide a stronger order guarantee, though that order would obviously be different
        // in some cases.
        //所有这些都让我怀疑在这里排队是否有任何价值。一个调度程序
        //简单地循环通过订阅者和分派事件到每个实际上
        //可能会提供更强的顺序保证，尽管顺序会明显不同
        //在某些情况下。

        /**
         * Global event queue.
         */
        private final ConcurrentLinkedQueue<EventWithSubscriber> queue = Queues.newConcurrentLinkedQueue();

        @Override
        void dispatch(Object event, Iterator<Subscriber> subscribers) {
            /**
             * dispatcher用于分发事件给Subscriber。它内部实现了多个分发器用于提供在不同场景下不同的事件顺序性。
             * Dispatcher是一个抽象类.
             * 另外在Dispatcher提供了三个不同的分发器实现：
             *
             * PerThreadQueuedDispatcher
             * 它比较常用，针对每个线程构建一个队列用于暂存事件对象。保证所有的事件都按照他们publish的顺序从单一的线程上发出。保证从单一线程上发出，
             * 没什么特别的地方，主要是在内部定义了一个队列，将其放在ThreadLocal中，用以跟特定的线程关联。
             *
             * LegacyAsyncDispatcher
             * 另一个异步分发器的实现：LegacyAsyncDispatcher，之前在介绍AsyncEventBus的时候提到，它就是用这种实现来分发事件。
             * 它在内部通过一个ConcurrentLinkedQueue<EventWithSubscriber>的全局队列来存储事件。从关键方法：dispatch的实现来看，
             * 它跟PerThreadQueuedDispatcher的区别主要是两个循环上的差异（这里基于队列的缓存事件的方式，肯定会存在两个循环：循环取队列里的事件以及循环发送给Subscriber）。
             * PerThreadQueuedDispatcher：是两层嵌套循环，外层是遍历队列取事件，内存是遍历事件的订阅处理器。
             * LegacyAsyncDispatcher：是一前一后两个循环。前面一个是遍历事件订阅处理器，并构建一个事件实体对象存入队列。
             * 后一个循环是遍历该事件实体对象队列，取出事件实体对象中的事件进行分发。
             * ImmediateDispatcher
             * 其实以上两个基于中间队列的分发实现都可以看做是异步模式，而ImmediateDispatcher则是同步模式：只要有事件发生就会立即分发并被
             * 立即得到处理。ImmediateDispatcher从感官上看类似于线性并顺序执行，而采用队列的方式有多线程汇聚到一个公共队列的由发散到聚合的模
             * 型。因此，ImmediateDispatcher的分发方式是一种深度优先的方式，而使用队列是一种广度优先的方式。
             */
            checkNotNull(event);
            while (subscribers.hasNext()) {
                // 先将所有发布的事件放入队列中
                queue.add(new EventWithSubscriber(event, subscribers.next()));
            }

            EventWithSubscriber e;
            while ((e = queue.poll()) != null) {
                // 消费队列中的消息, 问题： 生产者消费者 异步模式下， 生产者不是仅仅将消息放入队列就可以了吗？
                //然后消费者监听队列，为什么这里   在上面的 while中却要从 queue中取出 Event？ 从队列中取出Event的操作不应该是 消费者的任务吗？
                /**
                 * Guava 中的异步EventBus 是指 消费者 将Event 交给 listener执行，多个Listener可以并行执行。
                 * 他的异步不是说 生产者和消费者的异步。 而是listener的异步。 listener的异步不需要等待listener执行完成。
                 *
                 * 因此生产者 既要讲数据放入队列中，又要 将数据从队列中取出 然后交给每一个listener 异步执行。
                 *
                 *
                 * 注意上面的 队列queue 中 放入的不仅仅是 Event ，EventWithSubscriber 对象中既持有订阅者Listener又持有Event。
                 *
                 * 因此假设针对 String事件 有5个订阅者，那么queue中就会有5个EventWithSubscriber ，在这里的while中 每次poll出
                 * 一个EventWithSubscriber 都是针对当前的 listener（e.subscriber）进行发布事件，发布事件的方式就是将 执行
                 * listener方法的任务提交到线程池中。
                 *
                 */
                e.subscriber.dispatchEvent(e.event);
            }
        }

        private static final class EventWithSubscriber {
            private final Object event;
            private final Subscriber subscriber;

            private EventWithSubscriber(Object event, Subscriber subscriber) {
                this.event = event;
                this.subscriber = subscriber;
            }
        }
    }

    /**
     * Implementation of {@link #immediate()}.
     */
    private static final class ImmediateDispatcher extends Dispatcher {
        private static final ImmediateDispatcher INSTANCE = new ImmediateDispatcher();

        @Override
        void dispatch(Object event, Iterator<Subscriber> subscribers) {
            checkNotNull(event);
            while (subscribers.hasNext()) {
                subscribers.next().dispatchEvent(event);
            }
        }
    }
}
