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

import com.google.common.annotations.VisibleForTesting;
import com.google.j2objc.annotations.Weak;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import javax.annotation.CheckForNull;

/**
 * A subscriber method on a specific object, plus the executor that should be used for dispatching
 * events to it.
 *
 * <p>Two subscribers are equivalent when they refer to the same method on the same object (not
 * class). This property is used to ensure that no subscriber method is registered more than once.
 *
 * @author Colin Decker
 */
@ElementTypesAreNonnullByDefault
class Subscriber {

  /** Creates a {@code Subscriber} for {@code method} on {@code listener}. */
  static Subscriber create(EventBus bus, Object listener, Method method) {
    return isDeclaredThreadSafe(method)
        ? new Subscriber(bus, listener, method)
        : new SynchronizedSubscriber(bus, listener, method);
  }

  /** The event bus this subscriber belongs to. */
  @Weak private EventBus bus;

  /** The object with the subscriber method. */
  @VisibleForTesting final Object target;

  /** Subscriber method. */
  private final Method method;

  /** Executor to use for dispatching events to this subscriber. */
  private final Executor executor;

  private Subscriber(EventBus bus, Object target, Method method) {
    this.bus = bus;
    this.target = checkNotNull(target);
    this.method = method;
    method.setAccessible(true);

    this.executor = bus.executor();
  }

  /** Dispatches {@code event} to this subscriber using the proper executor. */
  final void dispatchEvent(Object event) {
    /**
     *
     * Subscriber的create方法在实现中，它会先判断该处理器方法上是否被标注有@AllowConcurrentEvents注解，
     * 如果有，则实例化Subscriber类的一个实例；如果没有，则不允许eventbus在多线程的环境中调用处理器方法（），
     * 所以这里专门为此提供了一个同步的订阅者对象：SynchronizedSubscriber来保证线程安全。
     *
     * 什么意思呢？ 如果线程A发布一个事件，线程B 也发布一个事件,同一个Subcirber1 接收到两个事件 。 这两个事件
     *
     * （1）线程A 通过Eventbug.post -->
     * 都会通过Eventbug.post -->LegacyAsyncDispatcher#dispatch(java.lang.Object, java.util.Iterator)，注意这里 会先找到事件的订阅者Subcirber1 --->这里的dispatchEvent
     * 然后dispatchEvent 中又将 Subcirber1 的监听方法 保证成任务 提交到线程池中。
     *
     * （2）线程B 也 类似的发布另外一个事件，将 Subcirber1 的监听方法 包装成任务提交到线程池中。
     *
     * 那么线程池中 我们是否允许 多个线程 并发 执行 同一个Subcirber1 对象的监听方法呢？ 普通的Subscriber 是允许的， 在SynchronieSubscriber 中会对当前的Subscriber对象加锁，也就是
     * 不允许多个线程同时使用同一个Subscriber并发消费多个事件对象。
     * ————————————————
     *
     *
     * 它调用一个多线程执行器来执行事件处理器方法。
     * 另一个方法：invokeSubscriberMethod以反射的方式调用事件处理器方法。
     * 另外，该类对Object的equals方法进行了override并标识为final。主要是为了避免同一个对象对某个事件进行重复订阅，
     * 在SubscriberRegistry中会有相应的判等操作。当然这里Subscriber也override并final了hashCode方法。这是最佳实践，
     * 不必多谈，如果不了解的可以去看看《Effective Java》。
     * 该类还有个内部类，就是我们上面谈到的SynchronizedSubscriber，它继承了Subscriber，与Subscriber唯一的不同就是
     * 在invokeSubscriberMethod的执行上做了同步。
     *
     */
    executor.execute(
        () -> {
          try {
            invokeSubscriberMethod(event);
          } catch (InvocationTargetException e) {
            bus.handleSubscriberException(e.getCause(), context(event));
          }
        });
  }

  /**
   * Invokes the subscriber method. This method can be overridden to make the invocation
   * synchronized.
   */
  @VisibleForTesting
  void invokeSubscriberMethod(Object event) throws InvocationTargetException {
    try {
      //    // 通过反射直接执行订阅者中方法
      //还有就是如果没有添加注解 AllowConcurrentEvents，就会走SynchronizedSubscriber中invokeSubscriberMethod()逻辑，
      // SynchronizedSubscriber 添加了synchronized关键字，不支持并发执行。
      method.invoke(target, checkNotNull(event));
    } catch (IllegalArgumentException e) {
      throw new Error("Method rejected target/argument: " + event, e);
    } catch (IllegalAccessException e) {
      throw new Error("Method became inaccessible: " + event, e);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof Error) {
        throw (Error) e.getCause();
      }
      throw e;
    }
  }

  /** Gets the context for the given event. */
  private SubscriberExceptionContext context(Object event) {
    return new SubscriberExceptionContext(bus, event, target, method);
  }

  @Override
  public final int hashCode() {
    return (31 + method.hashCode()) * 31 + System.identityHashCode(target);
  }

  @Override
  public final boolean equals(@CheckForNull Object obj) {
    if (obj instanceof Subscriber) {
      Subscriber that = (Subscriber) obj;
      // Use == so that different equal instances will still receive events.
      // We only guard against the case that the same object is registered
      // multiple times
      return target == that.target && method.equals(that.method);
    }
    return false;
  }

  /**
   * Checks whether {@code method} is thread-safe, as indicated by the presence of the {@link
   * AllowConcurrentEvents} annotation.
   */
  private static boolean isDeclaredThreadSafe(Method method) {
    //    // 如果有AllowConcurrentEvents注解，则返回true
    return method.getAnnotation(AllowConcurrentEvents.class) != null;
  }

  /**
   * Subscriber that synchronizes invocations of a method to ensure that only one thread may enter
   * the method at a time.
   */
  @VisibleForTesting
  static final class SynchronizedSubscriber extends Subscriber {

    private SynchronizedSubscriber(EventBus bus, Object target, Method method) {
      super(bus, target, method);
    }

    @Override
    void invokeSubscriberMethod(Object event) throws InvocationTargetException {
      synchronized (this) {
        // SynchronizedSubscriber不支持并发，这里用synchronized修饰，所有执行都串行化执行
        //还有就是如果没有添加注解 AllowConcurrentEvents，就会走SynchronizedSubscriber中invokeSubscriberMethod()逻辑，
        // SynchronizedSubscriber 添加了synchronized关键字，不支持并发执行。
        super.invokeSubscriberMethod(event);
      }
    }
  }
}
