/*
 * Copyright 2012 The Netty Project
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

package io.netty.buffer;

import io.netty.util.internal.PlatformDependent;
import org.junit.Assert;
import org.junit.Test;

import java.nio.ByteBuffer;

import static io.netty.util.CharsetUtil.UTF_8;
import static org.junit.Assume.assumeTrue;

public class PoolArenaTest {

    @Test
    public void testNormalizeCapacity() throws Exception {
        PoolArena<ByteBuffer> arena = new PoolArena.DirectArena(null, 0, 0, 9, 999999, 0);
        int[] reqCapacities = {0, 15, 510, 1024, 1023, 1025};
        int[] expectedResult = {0, 16, 512, 1024, 1024, 2048};
        for (int i = 0; i < reqCapacities.length; i++) {
            Assert.assertEquals(expectedResult[i], arena.normalizeCapacity(reqCapacities[i]));
        }
    }

    @Test
    public void testNormalizeAlignedCapacity() throws Exception {
        PoolArena<ByteBuffer> arena = new PoolArena.DirectArena(null, 0, 0, 9, 999999, 64);
        int[] reqCapacities = {0, 15, 510, 1024, 1023, 1025};
        int[] expectedResult = {0, 64, 512, 1024, 1024, 2048};
        for (int i = 0; i < reqCapacities.length; i++) {
            Assert.assertEquals(expectedResult[i], arena.normalizeCapacity(reqCapacities[i]));
        }
    }

    @Test
    public void testDirectArenaOffsetCacheLine() throws Exception {
        assumeTrue(PlatformDependent.hasUnsafe());
        int capacity = 5;
        int alignment = 128;

        for (int i = 0; i < 1000; i++) {
            ByteBuffer bb = PlatformDependent.useDirectBufferNoCleaner()
                    ? PlatformDependent.allocateDirectNoCleaner(capacity + alignment)
                    : ByteBuffer.allocateDirect(capacity + alignment);

            PoolArena.DirectArena arena = new PoolArena.DirectArena(null, 0, 0, 9, 9, alignment);
            int offset = arena.offsetCacheLine(bb);
            long address = PlatformDependent.directBufferAddress(bb);

            Assert.assertEquals(0, (offset + address) & (alignment - 1));
            PlatformDependent.freeDirectBuffer(bb);
        }
    }

    @Test
    public void testAllocationCounter() {
        final PooledByteBufAllocator allocator = new PooledByteBufAllocator(
                true,   // preferDirect
                0,      // nHeapArena
                1,      // nDirectArena
                8192,   // pageSize
                11,     // maxOrder
                0,      // tinyCacheSize
                0,      // smallCacheSize
                0,      // normalCacheSize
                true    // useCacheForAllThreads
        );

        // create tiny buffer
        final ByteBuf b1 = allocator.directBuffer(24);
        // create small buffer
        final ByteBuf b2 = allocator.directBuffer(800);
        // create normal buffer
        final ByteBuf b3 = allocator.directBuffer(8192 * 2);

        Assert.assertNotNull(b1);
        Assert.assertNotNull(b2);
        Assert.assertNotNull(b3);

        // then release buffer to deallocated memory while threadlocal cache has been disabled
        // allocations counter value must equals deallocations counter value
        Assert.assertTrue(b1.release());
        Assert.assertTrue(b2.release());
        Assert.assertTrue(b3.release());

        Assert.assertTrue(allocator.directArenas().size() >= 1);
        final PoolArenaMetric metric = allocator.directArenas().get(0);

        Assert.assertEquals(3, metric.numDeallocations());
        Assert.assertEquals(3, metric.numAllocations());

        Assert.assertEquals(1, metric.numTinyDeallocations());
        Assert.assertEquals(1, metric.numTinyAllocations());
        Assert.assertEquals(1, metric.numSmallDeallocations());
        Assert.assertEquals(1, metric.numSmallAllocations());
        Assert.assertEquals(1, metric.numNormalDeallocations());
        Assert.assertEquals(1, metric.numNormalAllocations());
    }

    @Test
    public void testDirectArenaMemoryCopy() {
        ByteBuf src = PooledByteBufAllocator.DEFAULT.directBuffer(512);
        ByteBuf dst = PooledByteBufAllocator.DEFAULT.directBuffer(512);

        PooledByteBuf<ByteBuffer> pooledSrc = unwrapIfNeeded(src);
        PooledByteBuf<ByteBuffer> pooledDst = unwrapIfNeeded(dst);

        // This causes the internal reused ByteBuffer duplicate limit to be set to 128
        pooledDst.writeBytes(ByteBuffer.allocate(128));
        // Ensure internal ByteBuffer duplicate limit is properly reset (used in memoryCopy non-Unsafe case)
        pooledDst.chunk.arena.memoryCopy(pooledSrc.memory, 0, pooledDst, 512);

        src.release();
        dst.release();
    }

    @SuppressWarnings("unchecked")
    private PooledByteBuf<ByteBuffer> unwrapIfNeeded(ByteBuf buf) {
        return (PooledByteBuf<ByteBuffer>) (buf instanceof PooledByteBuf ? buf : buf.unwrap());
    }


    @Test
    public void maskDemo() {
        for (int i = 31; i >= 0; i--) {
            String name = String.format("%-3s", i);


            System.out.print(name);
        }
        int num = 10;

        toBinary(num);
        toBinary(1 << num);

        int mask = -(1 << num);

        toBinary(mask);
        System.out.println();

    }


    @Test
    public void normalizedCapacityDemo() {
        for (int i = 31; i >= 0; i--) {
            String byteIndex = String.format("%-3s", i);
            System.out.print(byteIndex);
        }
        int normalizedCapacity = 10;
        int temp = 15;
        toBinary(normalizedCapacity);

        //假设为1的最高位n，也就是第 n 位位置确定为 1，具体执行过程描述如下:
        //1. 执行 n |= n >>>1 运算，目的是赋值第 n-1 位的值为 1。
        //2. 那么无符号右移一位后第 n-1 也为 1,  再与原值进行 | 运算后更新第 n-1 的值。
        //  结果，原值的第 n、n-1 都确定为 1，
        temp = normalizedCapacity >>> 1;
        toBinary(temp);
        normalizedCapacity |= normalizedCapacity >>> 1;
        toBinary(normalizedCapacity);

        //因为上一步至少得到了2个1，无符号右移2位，然后进行或操作 ，得到了4个1
        //接下来就可以无符号右移两倍，让 n-2、n-3 赋值为 1。
        temp = normalizedCapacity >>> 2;
        toBinary(temp);
        normalizedCapacity |= normalizedCapacity >>> 2;
        toBinary(normalizedCapacity);

        //因为上一步至少得到了4个1，无符号右移4位，然后进行或操作 ，这样就可以至少得到8个1
        temp = normalizedCapacity >>> 4;
        toBinary(temp);
        normalizedCapacity |= normalizedCapacity >>> 4;
        toBinary(normalizedCapacity);
        //因为上一步至少得到了8个1，无符号右移8位，然后进行或操作 ，这样就可以至少得到16个1
        temp = normalizedCapacity >>> 8;
        toBinary(temp);
        normalizedCapacity |= normalizedCapacity >>> 8;
        toBinary(normalizedCapacity);
        //因为上一步至少得到了16个1，无符号右移16位，然后进行或操作 ，这样就可以至少得到32个1
        temp = normalizedCapacity >>> 16;
        toBinary(temp);
        normalizedCapacity |= normalizedCapacity >>> 16;

        //由于 int 类型有 32 位，所以只需要进行 5 次运算，每次分别无符号右移1、2、4、8、16 就可让小于 i 的所有位都赋值为 1。
        normalizedCapacity++;
        toBinary(normalizedCapacity);

    }

    @Test   //尼恩 分析案例
    public void normalizedCapacityDemo2() {
        for (int i = 31; i >= 0; i--) {
            String byteIndex = String.format("%-3s", i);
            System.out.print(byteIndex);
        }
        int small = 2 * 1024;
        toBinary(15);
        toBinary((small ));
        toBinary((small & 15));

        int reqCapacity = 49;
        toBinary((reqCapacity ));
        toBinary((reqCapacity & 15));
        toBinary(reqCapacity & ~15);
        toBinary(16);
        toBinary( (reqCapacity & ~15) + 16);

    }

    @Test   //尼恩 分析案例
    public void exclusiveORDemo() {
        for (int i = 31; i >= 0; i--) {
            String byteIndex = String.format("%-3s", i);
            System.out.print(byteIndex);
        }
        int num = 2048;

        toBinary(num);
        toBinary(num ^= 1);

        int num1 = 2049;
        toBinary(num1);
        toBinary(num1 ^= 1);
        System.out.println();

    }

    private static void toBinary(int num) {


        System.out.println();
        for (int i = 31; i >= 0; i--) {
            System.out.print((num & 1 << i) == 0 ? "0  " : "1  ");
        }

    }

    @Test  //尼恩 分析案例
    public void testupdateParentsAlloc() {

        System.setProperty("updateParentsAlloc", String.valueOf(true));
        ByteBuf byteBuf1 = PooledByteBufAllocator.DEFAULT.directBuffer(8 * 1024);
        ByteBuf byteBuf2 = PooledByteBufAllocator.DEFAULT.directBuffer(8 * 1024);
        ByteBuf byteBuf3 = PooledByteBufAllocator.DEFAULT.directBuffer(8 * 1024);


        byteBuf1.release();
        byteBuf2.release();
        byteBuf3.release();
    }

    @Test  //尼恩 分析案例
    public void testMemoryTreeAllocateNode() {

        System.setProperty("testMemoryTreeAllocateNode", String.valueOf(true));
        System.out.println("-------------------第一次申请8k ");

        ByteBuf byteBuf1 = PooledByteBufAllocator.DEFAULT.directBuffer(4 * 1024+1);
        System.out.println("-------------------第二次申请8k ");
        ByteBuf byteBuf2 = PooledByteBufAllocator.DEFAULT.directBuffer(8 * 1024);
        System.out.println("-------------------第三次申请8k ");
        ByteBuf byteBuf3 = PooledByteBufAllocator.DEFAULT.directBuffer(8 * 1024);


        byteBuf1.release();
        byteBuf2.release();
        byteBuf3.release();
    }
    @Test  //尼恩 分析案例
    public void testAllocateSubPage() {

        System.setProperty("testAllocateSubPage", String.valueOf(true));
        ByteBuf byteBuf1 = PooledByteBufAllocator.DEFAULT.directBuffer(4 * 1024);
        ByteBuf byteBuf2 = PooledByteBufAllocator.DEFAULT.directBuffer(4 * 1024);
        ByteBuf byteBuf3 = PooledByteBufAllocator.DEFAULT.directBuffer(4 * 1024);
       // PooledUnsafeDirectByteBuf(ridx: 0, widx: 0, cap: 4096)

        byteBuf1.release();
        byteBuf2.release();
        byteBuf3.release();
    }
    @Test  //尼恩 分析案例
    public void testAllocateTinySubPage() {

        System.setProperty("testAllocateSubPage", String.valueOf(true));
        ByteBuf byteBuf1 = PooledByteBufAllocator.DEFAULT.directBuffer(16);
        ByteBuf byteBuf2 = PooledByteBufAllocator.DEFAULT.directBuffer(16);
        ByteBuf byteBuf3 = PooledByteBufAllocator.DEFAULT.directBuffer(16);
       // PooledUnsafeDirectByteBuf(ridx: 0, widx: 0, cap: 4096)

        byteBuf1.release();
        byteBuf2.release();
        byteBuf3.release();
    }
    @Test  //尼恩 分析案例
    public void testAllocateSmallSubPage() {

        System.setProperty("testAllocateSubPage", String.valueOf(true));
        ByteBuf byteBuf1 = PooledByteBufAllocator.DEFAULT.directBuffer(512);
        ByteBuf byteBuf2 = PooledByteBufAllocator.DEFAULT.directBuffer(512);
        ByteBuf byteBuf3 = PooledByteBufAllocator.DEFAULT.directBuffer(512);
       // PooledUnsafeDirectByteBuf(ridx: 0, widx: 0, cap: 4096)

        byteBuf1.release();
        byteBuf2.release();
        byteBuf3.release();
    }

    @Test  //尼恩 分析案例
    public void testFreeSubPage() {



        System.setProperty("io.netty.allocator.useCacheForAllThreads", String.valueOf(false));
        System.setProperty("testFreeSubPage", String.valueOf(true));
        ByteBuf byteBuf1 = PooledByteBufAllocator.DEFAULT.directBuffer(4 * 1024);
        ByteBuf byteBuf2 = PooledByteBufAllocator.DEFAULT.directBuffer(4 * 1024);
        ByteBuf byteBuf3 = PooledByteBufAllocator.DEFAULT.directBuffer(4 * 1024);

        System.out.println("------------------- 释放 = " + byteBuf1);

        byteBuf1.release();

        System.out.println("------------------- 释放 = " + byteBuf2);

        byteBuf2.release();

        System.out.println("------------------- 释放 = " + byteBuf3);

        byteBuf3.release();
    }


    //堆缓冲区
    @Test
    public  void testBuffer() {


        boolean directBufferNoCleaner = PlatformDependent.useDirectBufferNoCleaner();
        System.out.println(" 使用noCleaner策略 directBufferNoCleaner:  "+directBufferNoCleaner);

        //取得堆内存
        //取得堆内存--netty4默认直接buffer，而非堆buffer
        //ByteBuf heapBuf = ByteBufAllocator.DEFAULT.buffer();
        ByteBuf directBuf3 = PooledByteBufAllocator.DEFAULT.directBuffer(5 * 1024);
        ByteBuf directBuf4 = PooledByteBufAllocator.DEFAULT.directBuffer(5 * 1024);
        ByteBuf directBuf = PooledByteBufAllocator.DEFAULT.directBuffer(5 * 1024);

        directBuf.writeBytes("疯狂创客圈:高性能学习社群".getBytes(UTF_8));
        if (!directBuf.hasArray()) {
            int length = directBuf.readableBytes();
            byte[] array = new byte[length];
            int readerIndex = directBuf.readerIndex();
            //读取数据到堆内存
            directBuf.getBytes(readerIndex, array);
            System.out.println(" \n //读取数据到堆内存");

            System.out.println(new String(array, UTF_8));
         }
        directBuf.release();

        ByteBuf directBuf2 = PooledByteBufAllocator.DEFAULT.directBuffer(5 * 1024);

        directBuf2.writeBytes("疯狂创客圈:高性能学习社群".getBytes(UTF_8));
        if (!directBuf2.hasArray()) {
            int length = directBuf2.readableBytes();
            byte[] array = new byte[length];
            int readerIndex = directBuf2.readerIndex();
            //读取数据到堆内存
            directBuf2.getBytes(readerIndex, array);
            System.out.println(" \n //读取数据到堆内存");

            System.out.println(new String(array, UTF_8));
         }
        directBuf2.release();
    }


}
