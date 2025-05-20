/*
 * Copyright 2014 The Netty Project
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

import org.junit.Test;

import java.time.LocalTime;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class RecyclerTest {

    //池化的业务对象
    static final class HandledObject {
        Recycler.Handle<HandledObject> handle;  // 必须包含一个池化的处理器

        HandledObject(Recycler.Handle<HandledObject> handle) {
            this.handle = handle;
        }

        void recycle() {
            handle.recycle(this);
        }
    }

    private static Recycler<HandledObject> newRecycler(int max) {
        return new Recycler<HandledObject>(max) {  //  对象工厂
            @Override
            protected HandledObject newObject(
                    Recycler.Handle<HandledObject> handle) {
                return new HandledObject(handle);  //创建一个新的，持续对象
            }
        };
    }

    //  对象工厂
    private static final Recycler<Pojo> POJO_RECYCLER = new Recycler<Pojo>() {
        @Override
        protected Pojo newObject(Handle<Pojo> handle) {
            return new Pojo(handle);
        }
    };

    //池化的业务对象
    static class Pojo {

        LocalTime time;
        public int id;

        private static volatile int No = 0;  //对象累加编号

        int seq = 0;
        private final Recycler.Handle<Pojo> handle;  // 必须包含一个池化的处理器


        public void doing() {
            time = LocalTime.now();
            System.out.println("biz >>  using object " + id + "  doing sth  " + seq++ + " at  time :" + time);
            this.hashCode();
        }


        public Pojo(Recycler.Handle<Pojo> handle) {
            synchronized (Pojo.class) {
                this.id = ++No;
            }
            this.handle = handle;
        }

        public void recycle() {
            handle.recycle(this);
        }
    }

    @Test
    public void testPOJO_RECYCLER() {
        Pojo pojo1 = POJO_RECYCLER.get();
        pojo1.doing();
        pojo1.recycle();

        Pojo pojo2 = POJO_RECYCLER.get();
        pojo2.doing();
        pojo2.recycle();
        System.out.println(pojo2 == pojo1);
    }

    @Test
    public void demo_Multy_POJO_RECYCLER() {

        System.setProperty("demo_Multy_POJO_RECYCLER", String.valueOf(true));

        Pojo[] pojos = new Pojo[33];
        Pojo[] pojos2 = new Pojo[33];
//        Pojo[] pojos = new Pojo[2];
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch delay_latch = new CountDownLatch(2);

        new Thread(() -> {
            for (int i = 0; i < pojos.length ; i++) {
                pojos[i] = POJO_RECYCLER.get();
                pojos[i].doing();
            }

            latch.countDown();
            try {
                delay_latch.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            for (int i = pojos2.length / 2; i < pojos2.length; i++) {
                pojos2[i] = POJO_RECYCLER.get();  // 此时 回收的对象 ，  queue ， 首先要做  异地迁移
                pojos2[i].doing();
            }

        }, "A线程").start();

        try {
            latch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        new Thread(() -> {
            for (int i = 0; i < pojos.length / 2; i++) {
                pojos[i].doing();
                pojos[i].recycle();
            }
            delay_latch.countDown();


        }, "B线程").start();


        new Thread(() -> {
            for (int i = pojos.length / 2; i < pojos.length; i++) {
                pojos[i].doing();
                pojos[i].recycle();

            }
            delay_latch.countDown();


        }, "C线程").start();






        sleep(1000);




        sleep(Integer.MAX_VALUE);
    }

    void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Test(timeout = 5000L)
    public void testThreadCanBeCollectedEvenIfHandledObjectIsReferenced() throws Exception {
        final Recycler<HandledObject> recycler = newRecycler(1024);
        final AtomicBoolean collected = new AtomicBoolean();
        final AtomicReference<HandledObject> reference = new AtomicReference<HandledObject>();
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                HandledObject object = recycler.get();
                // Store a reference to the HandledObject to ensure it is not collected when the run method finish.
                reference.set(object);
            }
        }) {
            @Override
            protected void finalize() throws Throwable {
                super.finalize();
                collected.set(true);
            }
        };
        assertFalse(collected.get());
        thread.start();
        thread.join();

        // Null out so it can be collected.
        thread = null;

        // Loop until the Thread was collected. If we can not collect it the Test will fail due of a timeout.
        while (!collected.get()) {
            System.gc();
            System.runFinalization();
            Thread.sleep(50);
        }

        // Now call recycle after the Thread was collected to ensure this still works...
        reference.getAndSet(null).recycle();
    }

    @Test(expected = IllegalStateException.class)
    public void testMultipleRecycle() {
        Recycler<HandledObject> recycler = newRecycler(1024);
        HandledObject object = recycler.get();
        object.recycle();
        object.recycle();
    }

    @Test(expected = IllegalStateException.class)
    public void testMultipleRecycleAtDifferentThread() throws InterruptedException {
        Recycler<HandledObject> recycler = newRecycler(1024);
        final HandledObject object = recycler.get();
        final AtomicReference<IllegalStateException> exceptionStore = new AtomicReference<IllegalStateException>();
        final Thread thread1 = new Thread(new Runnable() {
            @Override
            public void run() {
                object.recycle();
            }
        });
        thread1.start();
        thread1.join();

        final Thread thread2 = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    object.recycle();
                } catch (IllegalStateException e) {
                    exceptionStore.set(e);
                }
            }
        });
        thread2.start();
        thread2.join();
        IllegalStateException exception = exceptionStore.get();
        if (exception != null) {
            throw exception;
        }
    }

    @Test
    public void testRecycle() {
        Recycler<HandledObject> recycler = newRecycler(1024);
        HandledObject object = recycler.get();
        object.recycle();
        HandledObject object2 = recycler.get();
        assertSame(object, object2);
        object2.recycle();
    }

    @Test
    public void testRecycleDisable() {
        Recycler<HandledObject> recycler = newRecycler(-1);
        HandledObject object = recycler.get();
        object.recycle();
        HandledObject object2 = recycler.get();
        assertNotSame(object, object2);
        object2.recycle();
    }

    /**
     * Test to make sure bug #2848 never happens again
     * https://github.com/netty/netty/issues/2848
     */
    @Test
    public void testMaxCapacity() {
        testMaxCapacity(300);
        Random rand = new Random();
        for (int i = 0; i < 50; i++) {
            testMaxCapacity(rand.nextInt(1000) + 256); // 256 - 1256
        }
    }

    private static void testMaxCapacity(int maxCapacity) {
        Recycler<HandledObject> recycler = newRecycler(maxCapacity);
        HandledObject[] objects = new HandledObject[maxCapacity * 3];
        for (int i = 0; i < objects.length; i++) {
            objects[i] = recycler.get();
        }

        for (int i = 0; i < objects.length; i++) {
            objects[i].recycle();
            objects[i] = null;
        }

        assertTrue("The threadLocalCapacity (" + recycler.threadLocalCapacity() + ") must be <= maxCapacity ("
                        + maxCapacity + ") as we not pool all new handles internally",
                maxCapacity >= recycler.threadLocalCapacity());
    }

    @Test
    public void testRecycleAtDifferentThread() throws Exception {
        final Recycler<HandledObject> recycler = new Recycler<HandledObject>(256, 10, 2, 10) {
            @Override
            protected HandledObject newObject(Recycler.Handle<HandledObject> handle) {
                return new HandledObject(handle);
            }
        };

        final HandledObject o = recycler.get();
        final HandledObject o2 = recycler.get();

        final Thread thread = new Thread() {
            @Override
            public void run() {
                o.recycle();
                o2.recycle();
            }
        };
        thread.start();
        thread.join();

        assertSame(recycler.get(), o);
        assertNotSame(recycler.get(), o2);
    }

    @Test
    public void testMaxCapacityWithRecycleAtDifferentThread() throws Exception {
        final int maxCapacity = 4; // Choose the number smaller than WeakOrderQueue.LINK_CAPACITY
        final Recycler<HandledObject> recycler = newRecycler(maxCapacity);

        // Borrow 2 * maxCapacity objects.
        // Return the half from the same thread.
        // Return the other half from the different thread.

        final HandledObject[] array = new HandledObject[maxCapacity * 3];
        for (int i = 0; i < array.length; i++) {
            array[i] = recycler.get();
        }

        for (int i = 0; i < maxCapacity; i++) {
            array[i].recycle();
        }

        final Thread thread = new Thread() {
            @Override
            public void run() {
                for (int i = maxCapacity; i < array.length; i++) {
                    array[i].recycle();
                }
            }
        };
        thread.start();
        thread.join();

        assertEquals(maxCapacity, recycler.threadLocalCapacity());
        assertEquals(1, recycler.threadLocalSize());

        for (int i = 0; i < array.length; i++) {
            recycler.get();
        }

        assertEquals(maxCapacity, recycler.threadLocalCapacity());
        assertEquals(0, recycler.threadLocalSize());
    }

    @Test
    public void testDiscardingExceedingElementsWithRecycleAtDifferentThread() throws Exception {
        final int maxCapacity = 32;
        final AtomicInteger instancesCount = new AtomicInteger(0);

        final Recycler<HandledObject> recycler = new Recycler<HandledObject>(maxCapacity, 2) {
            @Override
            protected HandledObject newObject(Recycler.Handle<HandledObject> handle) {
                instancesCount.incrementAndGet();
                return new HandledObject(handle);
            }
        };

        // Borrow 2 * maxCapacity objects.
        final HandledObject[] array = new HandledObject[maxCapacity * 2];
        for (int i = 0; i < array.length; i++) {
            array[i] = recycler.get();
        }

        assertEquals(array.length, instancesCount.get());
        // Reset counter.
        instancesCount.set(0);

        // Recycle from other thread.
        final Thread thread = new Thread() {
            @Override
            public void run() {
                for (HandledObject object : array) {
                    object.recycle();
                }
            }
        };
        thread.start();
        thread.join();

        assertEquals(0, instancesCount.get());

        // Borrow 2 * maxCapacity objects. Half of them should come from
        // the recycler queue, the other half should be freshly allocated.
        for (int i = 0; i < array.length; i++) {
            recycler.get();
        }

        // The implementation uses maxCapacity / 2 as limit per WeakOrderQueue
        assertTrue("The instances count (" + instancesCount.get() + ") must be <= array.length (" + array.length
                + ") - maxCapacity (" + maxCapacity + ") / 2 as we not pool all new handles" +
                " internally", array.length - maxCapacity / 2 <= instancesCount.get());
    }


}
