/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.util;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.ObjectPool;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.util.internal.MathUtil.safeFindNextPositivePowerOfTwo;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * Light-weight object pool based on a thread-local stack.
 *
 * @param <T> the type of the pooled object
 */
public abstract class Recycler<T> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Recycler.class);


    /**
     * 表示一个不需要回收的包装对象，
     * 用于在禁止使用Recycler功能时, 进行占位, 回收的时候进行空操作
     * 仅当io.netty.recycler.maxCapacityPerThread<=0时用到
     */

    @SuppressWarnings("rawtypes")
    private static final Handle NOOP_HANDLE = new Handle() {
        @Override
        public void recycle(Object object) {
            // NOOP
        }
    };
    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(Integer.MIN_VALUE);
    private static final int OWN_THREAD_ID = ID_GENERATOR.getAndIncrement();
    private static final int DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD = 4 * 1024; // Use 4k instances as default.
    private static final int DEFAULT_MAX_CAPACITY_PER_THREAD;
    private static final int INITIAL_CAPACITY;
    private static final int MAX_SHARED_CAPACITY_FACTOR;
    private static final int MAX_DELAYED_QUEUES_PER_THREAD;
    private static final int LINK_CAPACITY;
    private static final int RATIO;

    static {
        // In the future, we might have different maxCapacity for different object types.
        // e.g. io.netty.recycler.maxCapacity.writeTask
        //      io.netty.recycler.maxCapacity.outboundBuffer
        int maxCapacityPerThread = SystemPropertyUtil.getInt("io.netty.recycler.maxCapacityPerThread",
                SystemPropertyUtil.getInt("io.netty.recycler.maxCapacity", DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD));
        if (maxCapacityPerThread < 0) {
            maxCapacityPerThread = DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD;
        }

        /**
         * 每个线程，对象缓存的最大容量
         */
        DEFAULT_MAX_CAPACITY_PER_THREAD = maxCapacityPerThread;

        MAX_SHARED_CAPACITY_FACTOR = max(2,
                SystemPropertyUtil.getInt("io.netty.recycler.maxSharedCapacityFactor",
                        2));

        MAX_DELAYED_QUEUES_PER_THREAD = max(0,
                SystemPropertyUtil.getInt("io.netty.recycler.maxDelayedQueuesPerThread",
                        // We use the same value as default EventLoop number
                        NettyRuntime.availableProcessors() * 2));

        /**
         * WeakOrderQueue中的Link中的数组DefaultHandle<?>[] elements容量，默认为16，
         * 当一个Link中的DefaultHandle元素达到16个时，会新创建一个Link进行存储，这些Link组成链表，
         * 当然所有的Link加起来的容量要<=最大可共享容量。
         */

        LINK_CAPACITY = safeFindNextPositivePowerOfTwo(
                max(SystemPropertyUtil.getInt("io.netty.recycler.linkCapacity", 16), 16));

        //回收因子，默认为8,即默认每8个对象，允许回收一次，直接扔掉7个，可以让recycler的容量缓慢的增大，避免爆发式的请求
        // By default we allow one push to a Recycler for each 8th try on handles that were never recycled before.
        // This should help to slowly increase the capacity of the recycler while not be too sensitive to allocation
        // bursts.
        RATIO = safeFindNextPositivePowerOfTwo(SystemPropertyUtil.getInt("io.netty.recycler.ratio", 8));

        if (logger.isDebugEnabled()) {
            if (DEFAULT_MAX_CAPACITY_PER_THREAD == 0) {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: disabled");
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: disabled");
                logger.debug("-Dio.netty.recycler.linkCapacity: disabled");
                logger.debug("-Dio.netty.recycler.ratio: disabled");
            } else {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: {}", DEFAULT_MAX_CAPACITY_PER_THREAD);
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: {}", MAX_SHARED_CAPACITY_FACTOR);
                logger.debug("-Dio.netty.recycler.linkCapacity: {}", LINK_CAPACITY);
                logger.debug("-Dio.netty.recycler.ratio: {}", RATIO);
            }
        }

        INITIAL_CAPACITY = min(DEFAULT_MAX_CAPACITY_PER_THREAD, 256);
    }

    private final int maxCapacityPerThread;
    private final int maxSharedCapacityFactor;
    private final int interval;
    private final int maxDelayedQueuesPerThread;
    /**
     * 每一个线程包含一个Stack对象
     * 1、每个Recycler对象都有一个threadLocal
     * 原因：因为一个Stack要指明存储的对象泛型T，而不同的Recycler<T>对象的T可能不同，
     * 所以此处的FastThreadLocal是对象类型级别
     * 2、每条线程都有一个Stack<T>对象
     */

    private final FastThreadLocal<Stack<T>> threadLocal = new FastThreadLocal<Stack<T>>() {
        @Override
        protected Stack<T> initialValue() {
            return new Stack<T>(Recycler.this, Thread.currentThread(), maxCapacityPerThread, maxSharedCapacityFactor,
                    interval, maxDelayedQueuesPerThread);
        }

        @Override
        protected void onRemoval(Stack<T> value) {
            // Let us remove the WeakOrderQueue from the WeakHashMap directly if its safe to remove some overhead
            if (value.threadRef.get() == Thread.currentThread()) {
                if (DELAYED_RECYCLED.isSet()) {
                    DELAYED_RECYCLED.get().remove(value);
                }
            }
        }
    };

    protected Recycler() {
        this(DEFAULT_MAX_CAPACITY_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread) {
        this(maxCapacityPerThread, MAX_SHARED_CAPACITY_FACTOR);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor) {
        this(maxCapacityPerThread, maxSharedCapacityFactor, RATIO, MAX_DELAYED_QUEUES_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                       int ratio, int maxDelayedQueuesPerThread) {
        interval = safeFindNextPositivePowerOfTwo(ratio);
//        interval =20;
        if (maxCapacityPerThread <= 0) {
            this.maxCapacityPerThread = 0;
            this.maxSharedCapacityFactor = 1;
            this.maxDelayedQueuesPerThread = 0;
        } else {
            this.maxCapacityPerThread = maxCapacityPerThread;
            this.maxSharedCapacityFactor = max(1, maxSharedCapacityFactor);
            this.maxDelayedQueuesPerThread = max(0, maxDelayedQueuesPerThread);
        }
    }

    @SuppressWarnings("unchecked")
    public final T get() {
        if (maxCapacityPerThread == 0) {
            return newObject((Handle<T>) NOOP_HANDLE);
        }
        Stack<T> stack = threadLocal.get();  // 线程专用 变量
        DefaultHandle<T> handle = stack.pop();
        if (handle == null) {
            handle = stack.newHandle();
            handle.value = newObject(handle);
        }
        return (T) handle.value;
    }

    /**
     * @deprecated use {@link Handle#recycle(Object)}.
     */
    @Deprecated
    public final boolean recycle(T o, Handle<T> handle) {
        if (handle == NOOP_HANDLE) {
            return false;
        }

        DefaultHandle<T> h = (DefaultHandle<T>) handle;
        if (h.stack.parent != this) {
            return false;
        }

        h.recycle(o);
        return true;
    }

    final int threadLocalCapacity() {
        return threadLocal.get().elements.length;
    }

    final int threadLocalSize() {
        return threadLocal.get().size;
    }

    protected abstract T newObject(Handle<T> handle);

    public interface Handle<T> extends ObjectPool.Handle<T> {
    }

    private static final class DefaultHandle<T> implements Handle<T> {
        int lastRecycledId;
        int recycleId;

        boolean hasBeenRecycled;

        Stack<?> stack;  // 堆栈的引用  ， 线程专属 ，线程隔离的
        Object value;  //业务对象

        DefaultHandle(Stack<?> stack) {
            this.stack = stack;
        }

        @Override
        public void recycle(Object object) {
            if (object != value) {
                throw new IllegalArgumentException("object does not belong to handle");
            }

            Stack<?> stack = this.stack;
            if (lastRecycledId != recycleId || stack == null) {
                throw new IllegalStateException("recycled already");
            }

            stack.push(this);
        }
    }

    /**
     * 每一个线程对象包含一个,为其他线程创建的WeakOrderQueue对象
     * 1、每个Recycler类（而不是每一个Recycler对象）都有一个DELAYED_RECYCLED
     * 原因：可以根据一个Stack<T>对象唯一的找到一个WeakOrderQueue对象，所以此处不需要每个对象建立一个DELAYED_RECYCLED
     * 2、由于DELAYED_RECYCLED是一个类变量，所以需要包容多个T，此处泛型需要使用?
     * 3、WeakHashMap：当Stack没有强引用可达时，整个Entry{Stack<?>, WeakOrderQueue}都会加入相应的弱引用队列等待回收
     */

    private static final FastThreadLocal<Map<Stack<?>, WeakOrderQueue>> DELAYED_RECYCLED =
            new FastThreadLocal<Map<Stack<?>, WeakOrderQueue>>() {
                @Override
                protected Map<Stack<?>, WeakOrderQueue> initialValue() {
                    return new WeakHashMap<Stack<?>, WeakOrderQueue>();
                }
            };

    // a queue that makes only moderate guarantees about visibility: items are seen in the correct order,
    // but we aren't absolutely guaranteed to ever see anything at all, thereby keeping the queue cheap to maintain
    private static final class WeakOrderQueue extends WeakReference<Thread> {

        static final WeakOrderQueue DUMMY = new WeakOrderQueue();

        // Let Link extend AtomicInteger for intrinsics. The Link itself will be used as writerIndex.
        //good job:
        // Link数据结构，继承了AtomicInteger，巧妙的把 AtomicInteger保存的整数当做写指针，写指针通过CAS可以保证线程安全问题，
        // why :
        //    readIndex 读指针为什么是普通的int类型变量呢？
        // reason：

        // 1 读只可能一个线程读，也就是所属stack对应的线程，
        // 2 写有可能被多个线程同时写。回收即写。
        @SuppressWarnings("serial")
        static final class Link extends AtomicInteger {
            // WeakOrderQueue节点内部为一个数组
            final DefaultHandle<?>[] elements = new DefaultHandle[LINK_CAPACITY];

            int readIndex;
            Link next;
        }

        // Its important this does not hold any reference to either Stack or WeakOrderQueue.
        private static final class Head {
            private final AtomicInteger availableSharedCapacity;

            Link link;

            Head(AtomicInteger availableSharedCapacity) {
                this.availableSharedCapacity = availableSharedCapacity;
            }

            /**
             * Reclaim all used space and also unlink the nodes to prevent GC nepotism.
             */
            void reclaimAllSpaceAndUnlink() {
                Link head = link;
                link = null;
                int reclaimSpace = 0;
                while (head != null) {
                    reclaimSpace += LINK_CAPACITY;
                    Link next = head.next;
                    // Unlink to help GC and guard against GC nepotism.
                    head.next = null;
                    head = next;
                }
                if (reclaimSpace > 0) {
                    reclaimSpace(reclaimSpace);
                }
            }

            private void reclaimSpace(int space) {
                availableSharedCapacity.addAndGet(space);
            }

            void relink(Link link) {
                reclaimSpace(LINK_CAPACITY);
                this.link = link;
            }

            /**
             * Creates a new {@link} and returns it if we can reserve enough space for it, otherwise it
             * returns {@code null}.
             */
            //  每个link的元素数量为LINK_CAPACITY(16),
            // 但是：stack中所有link的元素数量不能超过availableSharedCapacity
            Link newLink() {
                return reserveSpaceForLink(availableSharedCapacity) ? new Link() : null;
            }

            static boolean reserveSpaceForLink(AtomicInteger availableSharedCapacity) {
                for (; ; ) {
                    int available = availableSharedCapacity.get();
                    // 如果可用的元素数量小于LINK_CAPACITY,则代表不能再创建link
                    if (available < LINK_CAPACITY) {
                        return false;
                    }
                    //预先扣除新link占用的元素数量LINK_CAPACITY
                    if (availableSharedCapacity.compareAndSet(available, available - LINK_CAPACITY)) {
                        return true;
                    }
                }
            }
        }

        // chain of data items
        private final Head head;
        private Link tail;
        // pointer to another queue of delayed items for the same stack
        private WeakOrderQueue next;
        private final int id = ID_GENERATOR.getAndIncrement();         //  WeakOrderQueue的唯一标记

        private final int interval;
        private int handleRecycleCount;

        private WeakOrderQueue() {
            super(null);
            head = new Head(null);
            interval = 0;
        }

        private WeakOrderQueue(Stack<?> stack, Thread thread) {
            super(thread);

            tail = new Link();

            // Its important that we not store the Stack itself in the WeakOrderQueue as the Stack also is used in
            // the WeakHashMap as key.
            // So just store the enclosed AtomicInteger which should allow to have the Stack itself GCed.
            head = new Head(stack.availableSharedCapacity);
            head.link = tail;
            interval = stack.interval;
            handleRecycleCount = interval; // Start at interval so the first one will be recycled.
        }

        static WeakOrderQueue newQueue(Stack<?> stack, Thread thread) {
            // We allocated a Link so reserve the space
            if (!Head.reserveSpaceForLink(stack.availableSharedCapacity)) {
                return null;
            }
            final WeakOrderQueue queue = new WeakOrderQueue(stack, thread);
            // Done outside of the constructor to ensure WeakOrderQueue.this does not escape the constructor and so
            // may be accessed while its still constructed.
            // 注意这里,
            // 加入了stack的延迟队列，并且设置在head位置
            stack.setHead(queue);

            if (null != System.getProperty("demo_Multy_POJO_RECYCLER"))
                logger.debug("创建queue：stack={}，thread={},queue ={}，",
                        stack, thread, queue);


            return queue;
        }

        WeakOrderQueue getNext() {
            return next;
        }

        void setNext(WeakOrderQueue next) {
            assert next != this;
            this.next = next;
        }

        void reclaimAllSpaceAndUnlink() {
            head.reclaimAllSpaceAndUnlink();
            this.next = null;
        }

        void add(DefaultHandle<?> handle) {
            //设置lastRecycledId
            handle.lastRecycledId = id;

            // While we also enforce the recycling ratio one we transfer objects from the WeakOrderQueue to the Stack
            // we better should enforce it as well early. Missing to do so may let the WeakOrderQueue grow very fast
            // without control if the Stack
            //如果handleRecycleCount < interval则丢弃对象
          /*  if (handleRecycleCount < interval) {
                handleRecycleCount++;
                // Drop the item to prevent recycling to aggressive.
                return;
            }*/
            handleRecycleCount = 0;

            Link tail = this.tail;
            int writeIndex;
            // writeIndex= tail.get() ==LINK_CAPACITY(16)时
            // 需要创建一个link
            if ((writeIndex = tail.get()) == LINK_CAPACITY) {
                Link link = head.newLink();
                if (link == null) {
                    // 如果不能创建link 则丢弃对象
                    // Drop it.
                    return;
                }
                // We allocate a Link so reserve the space
                // 相当于设置tail到 新的link
                // tail.next = link
                // this.tail = link,
                //  this.tail = tail = link,
                this.tail = tail = tail.next = link;

                //重新给writeIndex赋值
                writeIndex = tail.get();
            }
            //将对象添加到数组中的writeIndex位置
            tail.elements[writeIndex] = handle;

            //放进queue里就没有栈了，去除handle对stack的引用
            //将stack置空,表示当前对象已被回收,  并且,并不是由Stack所属线程所回收

            //涉及的场景： 有可能这个Stack会被回收掉，如果这里存在强引用，会导致Stack对象不能回收，
            //后面待Stack做转移时，会重新设置回去
            //                    boolean transfer(Stack<?> dst) {
            //
            //                        ...
            //                        element.stack = dst;
            //                      ...
            //                    }

            // 对象被添加到 Link 之后，handle 的 stack 属性被赋值为 null，
            // 而在取出对象的时候，handle 的 stack 属性又再次被赋值回来，
            // 为什么这么做呢，岂不是很麻烦？
            // 如果 Stack 不再使用，期望被 GC 回收，发现 handle 中还持有 Stack 的引用，那么就无法被 GC 回收，从而造成内存泄漏。

            handle.stack = null;


            // we lazy set to ensure that setting stack to null appears before we unnull it in the owning thread;
            // this also means we guarantee visibility of an element in the queue if we see the index updated

            // lazySet 使用 unsafe.putOrderedInt，保障写入的延迟， 这是volatile高级用法，
            // 延迟更新索引（索引被更新，队列元素才可见）
            //三个优势：
            // 1 如果我们看到index被更新，那么就一定能看到 那个位置上的 ele
            // 2 在owning thread 去设置stack时，或者去判断非空的时候，确保 stack为空
            // 3 多个生产者一个消费者场景，putOrderedObject 性能更好

            // 由于存在多个生产者一个消费者，所以不需要一个WeakOrderQueue的更新立即可见，
            // stack可以从其他WeakOrderQueue队列中获取回收对象。
            // 使用lazySet保持最终可见性，但会存在延迟， 但是 这有利于提升写入时的性能（本质是减少内存屏障的开销）。


            tail.lazySet(writeIndex + 1);

            //lazySet是使用Unsafe.putOrderedObject方法，这个方法在对低延迟代码是很有用的，它能够实现非堵塞的写入，
            //
            //这些写入不会被Java的JIT重新排序指令(instruction reordering)，这样它使用快速的store-store(存储-存储) barrier, 而不是较慢的store-load(存储-加载) barrier,
            //
            // 备注：store-load(存储-加载) barrier总是用在volatile的写操作上，这种性能提升是有代价的，
            //
            //虽然便宜，也就是写后结果并不会被其他线程看到，甚至是自己的线程，通常是几纳秒后被其他线程看到，这个时间比较短，所以代价可以忍受。
            //
            //类似Unsafe.putOrderedObject还有unsafe.putOrderedLong等方法，unsafe.putOrderedLong比使用 unsafe.putIntVolatile要快3倍左右。.
        }

        boolean hasFinalData() {
            return tail.readIndex != tail.get();
        }

        // transfer as many items as we can from this queue to the stack, returning true if any were transferred
        @SuppressWarnings("rawtypes")
        boolean transfer(Stack<?> dst) {
            //拿到第一个Link
            Link head = this.head.link;
            //为空则说明WorkQueue中无对象
            if (head == null) {
                // 如果一个link为空直接退出，去查找下一个线程为本线程创建的WeakOrderQueue
                return false;
            }
            //判断Link中对象是否已被读取,如果读取完了，readIndex就为LINK_CAPACITY
            // 第一次进来readIndex应该为0，不是link的最大容量LINK_CAPACITY
            if (head.readIndex == LINK_CAPACITY) {
                //已被读取,则取下一个Link,下一个Link为空则说明WorkQueue中无对象
                if (head.next == null) {
                    return false;
                }
                //设置Head连接到新的Link,
                //释放上一个Link对象数量操作
                // 交给GC去回收当前link，将WeakOrder.head.link指向下一个link
                head = head.next;
                this.head.relink(head);
            }

            // 走到这一步，
            // 要么 readIndex 不等于LINK_CAPACITY，
            // 要么 head link 等于LINK_CAPACITY，所以进入head.next
            // 如果只要此link 不为null，那么必然有对象可以被回收利用
            // 如果没有进入 head.next , readIndex表示上一次回收的readIndex+1
            // 如果没有进入 head.next , 默认应该是从0开始，因为是一个数组


            //Link中可用对象的起点下标
            final int srcStart = head.readIndex;
            //Link中可用对象的终点下标
            int srcEnd = head.get();
            //Link中可用对象的数量
            final int srcSize = srcEnd - srcStart;
            if (srcSize == 0) {
                return false;
            }

            //stack中对象的数量
            final int dstSize = dst.size;
            //期望对象的数量
            final int expectedCapacity = dstSize + srcSize;
            //如果期望对象的数量大于stack.elements的长度,则stack.elements扩容
            if (expectedCapacity > dst.elements.length) {
                final int actualCapacity = dst.increaseCapacity(expectedCapacity);
                srcEnd = min(srcStart + actualCapacity - dstSize, srcEnd);
            }

            if (srcStart != srcEnd) {
                final DefaultHandle[] srcElems = head.elements;   // 链式 数组结构里边的数组
                final DefaultHandle[] dstElems = dst.elements;  //stack 里边的数组
                int newDstSize = dstSize;
                for (int i = srcStart; i < srcEnd; i++) {
                    DefaultHandle<?> element = srcElems[i];
                    //设置对象的recycleId=lastRecycledId
                    if (element.recycleId == 0) {
                        element.recycleId = element.lastRecycledId;
                    } else if (element.recycleId != element.lastRecycledId) {
                        throw new IllegalStateException("recycled already");
                    }
                    srcElems[i] = null;
                    //尝试丢弃这个对象
                    if (dst.dropHandle(element)) {
                        // Drop the object.
                        continue;
                    }
                    element.stack = dst;

                    dstElems[newDstSize++] = element;  //将对象加入到stack的数组中
                }

                if (srcEnd == LINK_CAPACITY && head.next != null) {
                    // Add capacity back as the Link is GCed.
                    //设置Head连接到新的Link,释放上一个Link对象数量操作
                    this.head.relink(head.next);
                }

                head.readIndex = srcEnd;
                if (dst.size == newDstSize) {
                    return false;
                }
                dst.size = newDstSize;
                return true;
            } else {
                // The destination stack is full already.
                return false;
            }
        }
    }

    private static final class Stack<T> {

        // we keep a queue of per-thread queues, which is appended to once only, each time a new thread other
        // than the stack owner recycles: when we run out of items in our stack we iterate this collection
        // to scavenge those that can be reused. this permits us to incur minimal thread synchronisation whilst
        // still recycling all items.
        final Recycler<T> parent; //指向Reclycer对象

        // We store the Thread in a WeakReference as otherwise we may be the only ones that still hold a strong
        // Reference to the Thread itself after it died because DefaultHandle will hold a reference to the Stack.
        //
        // The biggest issue is if we do not use a WeakReference the Thread may not be able to be collected at all if
        // the user will store a reference to the DefaultHandle somewhere and never clear this reference (or not clear
        // it in a timely manner).
        final WeakReference<Thread> threadRef; //表示当前stack绑定的哪个线程

        //本线程创建的对象, 在其他线程中缓存的最大个数
        final AtomicInteger availableSharedCapacity;

        private final int maxDelayedQueues;

        private final int maxCapacity; //当前stack的最大容量, 表示stack最多能盛放多少个元素

        // 初始值为8 当handleRecycleCount<interval的时候,对象会被直接丢弃
        private final int interval;

        //数组,初始长度为256,最大长度为4*1024
        //stack中存储的对象, 类型为DefaultHandle, handle 可以被外部对象引用, 从而实现回收
        DefaultHandle<?>[] elements;
        int size;

        //初始值为8 每回收一个对象handleRecycleCount被重置为0 每丢弃一个对象handleRecycleCount加1
        private int handleRecycleCount;
        private WeakOrderQueue cursor, prev; //指向WeakOrderQueue
        private volatile WeakOrderQueue head; //指向WeakOrderQueue

        Stack(Recycler<T> parent, Thread thread, int maxCapacity, int maxSharedCapacityFactor,
              int interval, int maxDelayedQueues) {
            this.parent = parent;
            threadRef = new WeakReference<Thread>(thread);
            this.maxCapacity = maxCapacity;
            availableSharedCapacity = new AtomicInteger(max(maxCapacity / maxSharedCapacityFactor, LINK_CAPACITY));
            elements = new DefaultHandle[min(INITIAL_CAPACITY, maxCapacity)];
            this.interval = interval;
            handleRecycleCount = interval; // Start at interval so the first one will be recycled.
            this.maxDelayedQueues = maxDelayedQueues;
        }

        // Marked as synchronized to ensure this is serialized.
        synchronized void setHead(WeakOrderQueue queue) {
            queue.setNext(head);  // 把新的 链式数组结构 ， 插入到 stack 的 专用的   queue 列表 头部
            head = queue;
        }

        int increaseCapacity(int expectedCapacity) {
            int newCapacity = elements.length;
            int maxCapacity = this.maxCapacity;
            do {
                newCapacity <<= 1;
            } while (newCapacity < expectedCapacity && newCapacity < maxCapacity);

            newCapacity = min(newCapacity, maxCapacity);
            if (newCapacity != elements.length) {
                elements = Arrays.copyOf(elements, newCapacity);
            }

            return newCapacity;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        DefaultHandle<T> pop() {   // 拿对象
            int size = this.size;
            if (size == 0) {
                if (!scavenge()) {
                    return null;
                }
                size = this.size;
                if (size <= 0) {
                    // double check, avoid races
                    return null;
                }
            }
            size--;
            DefaultHandle ret = elements[size];  //数组的最后面的那个元素
            elements[size] = null;
            // As we already set the element[size] to null we also need to store the updated size before we do
            // any validation. Otherwise we may see a null value when later try to pop again without a new element
            // added before.
            // 因为我们已经将element [size]设置为null，
            // 所以在进行任何验证之前，我们还需要存储更新的大小。
            // 否则，当以后尝试再次弹出而之前未添加新元素时，我们可能会看到null值。
            this.size = size;

            if (ret.lastRecycledId != ret.recycleId) {
                throw new IllegalStateException("recycled multiple times");
            }
            ret.recycleId = 0;
            ret.lastRecycledId = 0;
            return ret;
        }

        private boolean scavenge() {
            // continue an existing scavenge, if any
            if (scavengeSome()) {
                return true;
            }

            // reset our scavenge cursor
            prev = null;
            cursor = head;
            return false;
        }

        private boolean scavengeSome() {
            WeakOrderQueue prev;
            // cursor表示上一次遍历的WeakOrderQueue的是哪一个
            WeakOrderQueue cursor = this.cursor;
            // 游标cursor为null，说明该线程是第一次遍历WeakOrderQueue
            //如果游标为空,设置游标=head
            if (cursor == null) {
                prev = null;
                cursor = head;
                //如果游标还是空,那么head就为空，那么池中没有对象,返回false
                // 那么head就为空，相当于没有其他线程为当前线程收集了对象
                // Map<Stack<?>, WeakOrderQueue>中key值为当前线程的Stack的value不存在
                // 所以这里返回false
                if (cursor == null) {
                    return false;
                }
            } else {
                // 如果cursor不为空的情况，prev指向this.prev，
                // 也就是上一次遍历到哪个WeakOrderQueue的再上一个WeakOrderQueue
                prev = this.prev;
            }

            boolean success = false;
            do {

                //第一次进来，cursor应该为head,
                //后面进来，cursor表示上一次遍历的WeakOrderQueue的是哪一个，所以这里并不是每次都从头开始，而是从上一次的游标开始
                //curcor的对象迁移到elements数组中
                // WeakOrderQueue.transfer(Stack)返回false表示失败
                if (cursor.transfer(this)) {
                    success = true;
                    break;
                }
                // 走到这里说明迁移失败了，
                // 需要去下一个WeakOrderQueue获取
                WeakOrderQueue next = cursor.getNext();
                if (cursor.get() == null) {
                    // If the thread associated with the queue is gone, unlink it, after
                    // performing a volatile read to confirm there is no data left to collect.
                    // We never unlink the first queue, as we don't want to synchronize on updating the head.

                    //如果与队列相关联的线程不见了，请在执行易失性读取以确认没有剩余数据要收集之后将其取消链接。
                    // 我们永远不会取消链接第一个队列，因为我们不想在更新头部时进行同步。

                    // WeakOrderQueue的线程并不是表示Stack的线程，而是帮忙回收Stack数据的线程，有可能线程已经结束了
                    // 导致上面的owner为null，毕竟是WeakReference
                    // cursor.hasFinalData() 是判断最后一个link的是否有数据

                    if (cursor.hasFinalData()) {
                        for (; ; ) {
                            if (cursor.transfer(this)) {
                                success = true;
                            } else {
                                break;
                            }
                        }
                    }

                    if (prev != null) {
                        // Ensure we reclaim all space before dropping the WeakOrderQueue to be GC'ed.
                        cursor.reclaimAllSpaceAndUnlink();
                        prev.setNext(next);
                    }
                } else {
                    prev = cursor;
                }
                // 从下一个WeakOrderQueue开始重新遍历

                cursor = next;

            } while (cursor != null && !success);

            // prev表示cursor的上一个
            this.prev = prev;
            this.cursor = cursor;
            return success;
        }

        void push(DefaultHandle<?> item) {
            Thread currentThread = Thread.currentThread();
            Thread stackThread = threadRef.get();


            if ( stackThread== currentThread) {
                // The current Thread is the thread that belongs to the Stack, we can try to push the object now.
                pushNow(item);  //当前线程归还对象
            } else {
                // The current Thread is not the one that belongs to the Stack
                // (or the Thread that belonged to the Stack was collected already), we need to signal that the push
                // happens later.
                pushLater(item, currentThread);   //第三方线程归还对象
            }
        }

        private void pushNow(DefaultHandle<?> item) {
            if ((item.recycleId | item.lastRecycledId) != 0) {
                throw new IllegalStateException("recycled already");
            }
            //设置item的recycleId,lastRecycledId 等于 Recycler的OWN_THREAD_ID
            item.recycleId = item.lastRecycledId = OWN_THREAD_ID;

            int size = this.size;

            // 如果 对象数>=最大容量  直接返回
            // 或者成功丢弃对象   直接返回
            if (size >= maxCapacity || dropHandle(item)) {
                // Hit the maximum capacity or should drop - drop the possibly youngest object.
                return;
            }

            //扩容成原elements.length的2倍，
            // 但是不超过  maxCapacity（默认为4k）
            if (size == elements.length) {
                elements = Arrays.copyOf(elements, min(size << 1, maxCapacity));
            }

            elements[size] = item;  //设置对象
            this.size = size + 1;  //对象数+1
        }

        private void pushLater(DefaultHandle<?> item, Thread thread) {
            if (maxDelayedQueues == 0) {
                // 不支持跨线程回收，而是将其直接丢弃。
                // maxDelayedQueues 默认为16 ，更加准确的说，为线程数
                // We don't support recycling across threads and should just drop the item on the floor.
                return;
            }

            // we don't want to have a ref to the queue as the value in our weak map
            // so we null it out; to ensure there are no races with restoring it later
            // we impose a memory ordering here (no-op on x86)
            Map<Stack<?>, WeakOrderQueue> delayedRecycled = DELAYED_RECYCLED.get();  //延迟回收的 对象  stack map
            WeakOrderQueue queue = delayedRecycled.get(this);   //延迟回收对象   的 stack 的 queue  ， 专用存储 异地归还的对象
            if (queue == null) {
                if (delayedRecycled.size() >= maxDelayedQueues) {
                    // Add a dummy queue so we know we should drop the object
                    delayedRecycled.put(this, WeakOrderQueue.DUMMY);
                    return;
                }
                // 创建队列，期间会检查是否已经达到延迟队列的最大数量
                // Check if we already reached the maximum number of delayed queues and if we can allocate at all.
                if ((queue = newWeakOrderQueue(thread)) == null) {
                    // drop object
                    return;
                }
                //将队列关联到本地变量中
                delayedRecycled.put(this, queue);
            } else if (queue == WeakOrderQueue.DUMMY) {
                // drop object
                return;
            }

            queue.add(item);
        }

        /**
         * Allocate a new {@link WeakOrderQueue} or return {@code null} if not possible.
         */
        private WeakOrderQueue newWeakOrderQueue(Thread thread) {
            return WeakOrderQueue.newQueue(this, thread);
        }

        //丢弃对象
        boolean dropHandle(DefaultHandle<?> handle) {
            //条件，对象未被回收过
            if (!handle.hasBeenRecycled) {
                // handleRecycleCount < interval(默认为8) 不回收
                // handleRecycleCount == interval(默认为8) 回收
                // handleRecycleCount的初始值等于interval 所以第一个对象不会被丢弃，后面间隔8个回收1个
                // interval=8 也就是每回收一个对象,之后的8个对象就会被丢弃
                if (handleRecycleCount < interval) {
                    handleRecycleCount++;
                    // Drop the object.
                    return true;
                }
                handleRecycleCount = 0;
                //设置对象为被回收
                handle.hasBeenRecycled = true;
            }
            return false;
        }

        DefaultHandle<T> newHandle() {
            return new DefaultHandle<T>(this);
        }
    }
}
