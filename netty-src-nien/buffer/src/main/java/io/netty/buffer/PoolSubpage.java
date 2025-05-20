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

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
// 管理一个需要进行 切分的page
final class PoolSubpage<T> implements PoolSubpageMetric {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(PoolSubpage.class);

    final PoolChunk<T> chunk;
    private final int memoryMapIdx; //该子页对应的 chunk 内存页二叉树节点坐标。  page 在 memoryMap的index 下标
    private final int runOffset;// chunk中的偏移，偏移量： represents the 0-based offset in #bytes from start of the byte-array chunk
    private final int pageSize;  //子页大小
    private final long[] bitmap;//位图数组，描述某一尺寸内存的状态 每一位都可以表示是该内存否可用了 1表示不可用 0表示可用

    PoolSubpage<T> prev;
    PoolSubpage<T> next;

    boolean doNotDestroy;
    int elemSize;  // slibsize /slice 切片规格
    private int maxNumElems;  //最大可分配的内存数
    private int bitmapLength; //实际用到的位图数
    private int nextAvail; //下一个可用的位图索引
    private int numAvail; //可用内存的个数  、可以用的 切片数量

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /** Special constructor that creates a linked list head */
    PoolSubpage(int pageSize) {
        chunk = null;
        memoryMapIdx = -1;
        runOffset = -1;
        elemSize = -1;
        this.pageSize = pageSize;
        bitmap = null;
    }

    //memoryMapIdx:  page 在 memoryMap的index 下标
    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int memoryMapIdx, int runOffset, int pageSize, int elemSize) {
        this.chunk = chunk;
        this.memoryMapIdx = memoryMapIdx;
        this.runOffset = runOffset;
        this.pageSize = pageSize;

        // pageSize / 16（2^4） / 64  （2^6）
        bitmap = new long[pageSize >>> 10]; // pageSize / 16 / 64

        // 这里为何是16,64两个数字呢，
        // elemSize是通过normCapacity处理的数字，最小值为16；
        // 因此一个page最多可能被分成pageSize/16段，而一个long有64个位，固能够表示64个内存段的状态；
        // 所以最多须要pageSize/16/64个long ，才能保证全部段的状态均可以管理
        init(head, elemSize);
    }

    void init(PoolSubpage<T> head, int elemSize) {
        doNotDestroy = true;
        this.elemSize = elemSize;
        if (elemSize != 0) {
            //初始subpage的最大elem 数量maxNumElems、可用数量numAvail
            maxNumElems = numAvail = pageSize / elemSize;


            //初始化下一个可用subpage的索引
            nextAvail = 0;
            //计算bitmap的有效数量，bitmapLength = maxNumElems >>> 6 = maxNumElems / 64
            bitmapLength = maxNumElems >>> 6;
            //如果maxNumElems不是64的整数倍，bitmapLength需要额外加1
            if ((maxNumElems & 63) != 0) {
                bitmapLength ++;
            }

            //有效长度的bitmap值都设置成0
            for (int i = 0; i < bitmapLength; i ++) {
                bitmap[i] = 0;
            }

            if (null != System.getProperty("testFreeSubPage"))
                logger.debug("init：PoolSubpage object={},maxNumElems={}，pageSize={},elemSize ={}，memoryMapIdx={},elemSize={}，numAvail={}，maxNumElems={}",
                       this, maxNumElems, pageSize,elemSize,memoryMapIdx,elemSize,numAvail,maxNumElems);

        }
        //把当前PoolSubpage节点添加到head后面。
        addToPool(head);
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     */
    // slab 的分配： 分配一个可用的element并标记 bitmap
    long allocate() {
        if (elemSize == 0) {
            return toHandle(0);
        }
        // 没有可用的内存或者已经被销毁
        //numAvail:可用 slab 数量，
        // doNotDestroy：未被销毁，free时改为true
        if (numAvail == 0 || !doNotDestroy) {
            return -1;
        }
       // 找到当前page中可分配内存段的bitmapIdx
        //bitmapIdx高26位表示在bitmap数组中位置索引，低6位表示64位long类型内位数索引(2^6 = 64)
        final int bitmapIdx = getNextAvail();
        // 算出对应index的标志位在数组中的位置q，bitmap数组下标
        int q = bitmapIdx >>> 6;   //  区段  编号
        // 将>=64的那一部分二进制抹掉，获得一个小于64的数
        int r = bitmapIdx & 63;   //  段内 的 编号
        assert (bitmap[q] >>> r & 1) == 0;
        // 对应位置值设置为1表示当前element已经被分配，
        // 这几句，转换成咱们常见的BitSet，其实就是bitSet.set(q, true)
        bitmap[q] |= 1L << r;
        // 若是当前page没有可用的subpage，则从arena的pool链表中移除
        if (-- numAvail == 0) {
            removeFromPool();
        }
        //  核心的核心的第二步： 分配好了 slab indx 之后， 合并 两级偏移  ，形成句柄：
        // 把两个int(32位)的索引 bitmapIdx 和 memoryMapIdx，合并成一个long类型
        return toHandle(bitmapIdx);
    }

    /**
     * @return {@code true} if this subpage is in use.
     *         {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     */
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        if (elemSize == 0) {
            return true;
        }
        //计算bitmap下标
        int q = bitmapIdx >>> 6;   //计算区段的编号
        //计算在位图中的下标
        int r = bitmapIdx & 63;  // 计算 区段内的编号
        assert (bitmap[q] >>> r & 1) != 0;

        bitmap[q] ^= 1L << r;    //将位图中对应的位置为0
        //设置为一个可用的subpage
        setNextAvail(bitmapIdx);

        if (null != System.getProperty("testFreeSubPage"))
            logger.debug("释放前：bitmapIdx={}，q={},r ={}，numAvail={},elemSize={}，numAvail={}，maxNumElems={}",
                    bitmapIdx, q,r,numAvail,elemSize,numAvail,maxNumElems);


        // 可用数量为0的时候subpage已经不在链表中,需要将其加入链表中
        // 分配的时候，会-- numAvail，若是0当前page没有可用的subpage，则从arena的pool链表中移除
        //注意，这里是后自增
        if (numAvail ++ == 0) {
            addToPool(head);

            if (null != System.getProperty("testFreeSubPage"))
                logger.debug("加入后：bitmapIdx={}，q={},r ={}，numAvail={},elemSize={}，numAvail={}，maxNumElems={}",
                        bitmapIdx, q,r,numAvail,elemSize,numAvail,maxNumElems);

            return true;
        }

        if (null != System.getProperty("testFreeSubPage"))
            logger.debug("后：bitmapIdx={}，q={},r ={}，numAvail={},elemSize={}，numAvail={}，maxNumElems={}",
                    bitmapIdx, q,r,numAvail,elemSize,numAvail,maxNumElems);


        if (numAvail != maxNumElems) {
            return true;
        } else {
            // Subpage 未被使用 (numAvail == maxNumElems)
            // Subpage not in use (numAvail == maxNumElems)
            if (prev == next) {
                // 如果链表中只有一个subpage,那么不要删除它
                // Do not remove if this subpage is the only one left in the pool.
                return true;
            }

            // 如果链表中还有其它subpage,那么从链表中删除当前subpage
            // Remove this subpage from the pool if there are other subpages left in the pool.
            doNotDestroy = false;
            removeFromPool();

            if (null != System.getProperty("testFreeSubPage"))
                logger.debug("删除后：bitmapIdx={}，q={},r ={}，numAvail={},elemSize={}，numAvail={}，maxNumElems={}",
                        bitmapIdx, q,r,numAvail,elemSize,numAvail,maxNumElems);

            return false;
        }
    }

    private void addToPool(PoolSubpage<T> head) {
        if (null != System.getProperty("testFreeSubPage"))
        logger.debug("--  addToPool：numAvail={}，maxNumElems={}，pageSize={},elemSize ={}，memoryMapIdx={},elemSize={}，maxNumElems={}，PoolSubpage object={},",
                numAvail,  maxNumElems, pageSize,elemSize,memoryMapIdx,elemSize,maxNumElems,this);


        assert prev == null && next == null;
        prev = head;
        next = head.next;
        next.prev = this;
        head.next = this;
    }

    private void removeFromPool() {  // 把page （subpage） 从 槽位list  删除
        if (null != System.getProperty("testFreeSubPage"))
            logger.debug("--  removeFromPool：numAvail={}，maxNumElems={}，pageSize={},elemSize ={}，memoryMapIdx={},elemSize={}，maxNumElems={}，PoolSubpage object={},",
                    numAvail,  maxNumElems, pageSize,elemSize,memoryMapIdx,elemSize,maxNumElems,this);


        assert prev != null && next != null;
        prev.next = next;
        next.prev = prev;
        next = null;
        prev = null;
    }

    private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }

    private int getNextAvail() {
        // nextAvail>=0时，表示明确的知道这个element未被分配，此时直接返回就能够了
        // >=0 有两种状况：
        // 一、刚初始化；
        // 二、有element被释放且还未被分配
        // 每次分配完成nextAvail就被置为-1，由于这个时候除非计算一次，不然没法知道下一个可用位置在哪

        int nextAvail = this.nextAvail;
        if (nextAvail >= 0) {
            this.nextAvail = -1;
            return nextAvail;
        }
        // 没有明确的可用位置时则挨个查找
        return findNextAvail();
    }

    private int findNextAvail() {
        //bitmap数组是个long类型节点
        final long[] bitmap = this.bitmap;
        final int bitmapLength = this.bitmapLength;
        // bitmapLength表示bitmap可用长度，
        // eg:elemnt规模为16B时需要8个long，bitmapLength=8；elemnt规模为32B时只需要4个long，bitmapLength=4
        for (int i = 0; i < bitmapLength; i ++) {
            //bits: bitmap数组i位置的long
            long bits = bitmap[i];
            // 说明这个分段中还有能够分配的element
            //bits 不是每一位都全是1，表示还有为0的可用段
            if (~bits != 0) {
                return findNextAvail0(i, bits);
            }
        }
        return -1;
    }

    private int findNextAvail0(int i, long bits) {
        // 该page包含得段数量
        final int maxNumElems = this.maxNumElems;
        // 要分配的起始索引，根据第i个位图，
        // 如果是0表示第一个long类型表示即0-63进行分配 1表示第二个long类型表示64-127分配
        //例如：i = 00000000000000000000000000000000; baseVal = 00000000000000000000000000000000      十进制：0
        //     i = 00000000000000000000000000000001; baseVal = 00000000000000000000000001000000      十进制：64
        //     i = 00000000000000000000000000000010; baseVal = 00000000000000000000000010000000      十进制：128
        final int baseVal = i << 6;  // 起始编号

        for (int j = 0; j < 64; j ++) {
            // 取出最低位
            // 若是该位置的值为0，表示还未分配
            if ((bits & 1) == 0) {
                //相当于baseVal + j， baseVal相当于由于bitmap中索引值，j 为位图的内部索引
                int val = baseVal | j;   // 起始编号 baseVal  + 当前段的偏移 j  = 总的bit  偏移量
                if (val < maxNumElems) {
                    return val;
                } else {
                    break;
                }
            }
            //位图右移，即从低位往高位
            bits >>>= 1;
        }
        return -1;
    }
  //两个偏移量  的   拼接 成 handler
    private long toHandle(int bitmapIdx) {
        return 0x4000000000000000L | (long) bitmapIdx << 32 | memoryMapIdx;
    }

    @Override
    public String toString() {
        final boolean doNotDestroy;
        final int maxNumElems;
        final int numAvail;
        final int elemSize;
        if (chunk == null) {
            // This is the head so there is no need to synchronize at all as these never change.
            doNotDestroy = true;
            maxNumElems = 0;
            numAvail = 0;
            elemSize = -1;
        } else {
            synchronized (chunk.arena) {
                if (!this.doNotDestroy) {
                    doNotDestroy = false;
                    // Not used for creating the String.
                    maxNumElems = numAvail = elemSize = -1;
                } else {
                    doNotDestroy = true;
                    maxNumElems = this.maxNumElems;
                    numAvail = this.numAvail;
                    elemSize = this.elemSize;
                }
            }
        }

        if (!doNotDestroy) {
            return "(" + memoryMapIdx + ": not in use)";
        }

        return "(" + memoryMapIdx + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
                ", offset: " + runOffset + ", length: " + pageSize + ", elemSize: " + elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        if (chunk == null) {
            // It's the head.
            return 0;
        }

        synchronized (chunk.arena) {
            return maxNumElems;
        }
    }

    @Override
    public int numAvailable() {
        if (chunk == null) {
            // It's the head.
            return 0;
        }

        synchronized (chunk.arena) {
            return numAvail;
        }
    }

    @Override
    public int elementSize() {
        if (chunk == null) {
            // It's the head.
            return -1;
        }

        synchronized (chunk.arena) {
            return elemSize;
        }
    }

    @Override
    public int pageSize() {
        return pageSize;
    }

    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }
}
