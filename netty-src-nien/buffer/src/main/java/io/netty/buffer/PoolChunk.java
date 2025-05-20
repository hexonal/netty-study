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

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Description of algorithm for PageRun/PoolSubpage allocation from PoolChunk
 * <p>
 * Notation: The following terms are important to understand the code
 * > page  - a page is the smallest unit of memory chunk that can be allocated
 * > chunk - a chunk is a collection of pages
 * > in this code chunkSize = 2^{maxOrder} * pageSize
 * <p>
 * To begin we allocate a byte array of size = chunkSize
 * Whenever a ByteBuf of given size needs to be created we search for the first position
 * in the byte array that has enough empty space to accommodate the requested size and
 * return a (long) handle that encodes this offset information, (this memory segment is then
 * marked as reserved so it is always used by exactly one ByteBuf and no more)
 * <p>
 * For simplicity all sizes are normalized according to PoolArena#normalizeCapacity method
 * This ensures that when we request for memory segments of size >= pageSize the normalizedCapacity
 * equals the next nearest power of 2
 * <p>
 * To search for the first offset in chunk that has at least requested size available we construct a
 * complete balanced binary tree and store it in an array (just like heaps) - memoryMap
 * <p>
 * The tree looks like this (the size of each node being mentioned in the parenthesis)
 * <p>
 * depth=0        1 node (chunkSize)
 * depth=1        2 nodes (chunkSize/2)
 * ..
 * ..
 * depth=d        2^d nodes (chunkSize/2^d)
 * ..
 * depth=maxOrder 2^maxOrder nodes (chunkSize/2^{maxOrder} = pageSize)
 * <p>
 * depth=maxOrder is the last level and the leafs consist of pages
 * <p>
 * With this tree available searching in chunkArray translates like this:
 * To allocate a memory segment of size chunkSize/2^k we search for the first node (from left) at height k
 * which is unused
 * <p>
 * Algorithm:
 * ----------
 * Encode the tree in memoryMap with the notation
 * memoryMap[id] = x => in the subtree rooted at id, the first node that is free to be allocated
 * is at depth x (counted from depth=0) i.e., at depths [depth_of_id, x), there is no node that is free
 * <p>
 * As we allocate & free nodes, we update values stored in memoryMap so that the property is maintained
 * <p>
 * Initialization -
 * In the beginning we construct the memoryMap array by storing the depth of a node at each node
 * i.e., memoryMap[id] = depth_of_id
 * <p>
 * Observations:
 * -------------
 * 1) memoryMap[id] = depth_of_id  => it is free / unallocated
 * 2) memoryMap[id] > depth_of_id  => at least one of its child nodes is allocated, so we cannot allocate it, but
 * some of its children can still be allocated based on their availability
 * 3) memoryMap[id] = maxOrder + 1 => the node is fully allocated & thus none of its children can be allocated, it
 * is thus marked as unusable
 * <p>
 * Algorithm: [allocateNode(d) => we want to find the first node (from left) at height h that can be allocated]
 * ----------
 * 1) start at root (i.e., depth = 0 or id = 1)
 * 2) if memoryMap[1] > d => cannot be allocated from this chunk
 * 3) if left node value <= h; we can allocate from left subtree so move to left and repeat until found
 * 4) else try in right subtree
 * <p>
 * Algorithm: [allocateRun(size)]
 * ----------
 * 1) Compute d = log_2(chunkSize/size)
 * 2) Return allocateNode(d)
 * <p>
 * Algorithm: [allocateSubpage(size)]
 * ----------
 * 1) use allocateNode(maxOrder) to find an empty (i.e., unused) leaf (i.e., page)
 * 2) use this handle to construct the PoolSubpage object or if it already exists just call init(normCapacity)
 * note that this PoolSubpage object is added to subpagesPool in the PoolArena when we init() it
 * <p>
 * Note:
 * -----
 * In the implementation for improving cache coherence,
 * we store 2 pieces of information depth_of_id and x as two byte values in memoryMap and depthMap respectively
 * <p>
 * memoryMap[id]= depth_of_id  is defined above
 * depthMap[id]= x  indicates that the first node which is free to be allocated is at depth x (from root)
 */
final class PoolChunk<T> implements PoolChunkMetric {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(PoolChunk.class);

    private static final int INTEGER_SIZE_MINUS_ONE = Integer.SIZE - 1;

    final PoolArena<T> arena;

    //两种形式：堆内存 T 为byte[] , 直接内存 T为 ByteBuffer
    final T memory;

    //huge级别的 unpooled 为true
    final boolean unpooled;

    //偏移量，如果需要内存对齐，那就是这个内存偏移量
    final int offset;
    private final byte[] memoryMap;  //表示完全二叉树,共有4096个
    private final byte[] depthMap;   //表示节点的层高、深度，共有4096个
    private final PoolSubpage<T>[] subpages;
    /**
     * Used to determine if the requested capacity is equal to or greater than pageSize.
     */
    private final int subpageOverflowMask;
    private final int pageSize;//页大小 8k
    private final int pageShifts;  //页位移，也就是pageSize=1<<<pageShifts,8k就是13，即2的13次方是8k
    private final int maxOrder; //最大深度索引，默认11 从0开始的
    private final int chunkSize;//块大小，默认16m
    private final int log2ChunkSize; //ChunkSize取log2的值
    private final int maxSubpageAllocs; //最大子叶数，跟最大深度有关，最大深度上的叶子结点个数就是子页数
    /**
     * Used to mark memory as unusable
     */
    private final byte unusable;

    // Use as cache for ByteBuffer created from the memory. These are just duplicates and so are only a container
    // around the memory itself. These are often needed for operations within the Pooled*ByteBuf and so
    // may produce extra GC, which can be greatly reduced by caching the duplicates.
    //
    // This may be null if the PoolChunk is unpooled as pooling the ByteBuffer instances does not make any sense here.
    private final Deque<ByteBuffer> cachedNioBuffers;

    int freeBytes;

    PoolChunkList<T> parent;
    PoolChunk<T> prev;
    PoolChunk<T> next;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    PoolChunk(PoolArena<T> arena, T memory, int pageSize, int maxOrder, int pageShifts, int chunkSize, int offset) {
        unpooled = false;
        this.arena = arena;
        this.memory = memory;
        this.pageSize = pageSize;
        this.pageShifts = pageShifts;
        this.maxOrder = maxOrder;
        this.chunkSize = chunkSize;
        this.offset = offset;
        unusable = (byte) (maxOrder + 1);
        log2ChunkSize = log2(chunkSize);
        subpageOverflowMask = ~(pageSize - 1);
        freeBytes = chunkSize;


        //maxOrder默认为11，d表示的是每层节点的深度，d的范围是[0,11]
        //不同的深度，还有一个含义：表示不同的内存量，
        // 比如所有的叶子节点的深度都是11，表示每个叶子节点代表的内存大小是8K，
        // 非叶子节点的内存量，为所有叶子节点的和
        // 深度为0的节点，叶子节点的数量为2^(11-0)= 2048个，内存量为  2*1K *8K= 16M
        // 深度为2的节点, 叶子节点的数量为2^(11-2)= 2^9 =512 ，内存量为  512*8K=4M， 表示该节点代表的内存大小是8M
        // 深度为10的节点, 叶子节点的数量为2^(11-10)= 2 ，内存量为  2*8k=16K， 表示该节点代表的内存大小是16K
        assert maxOrder < 30 : "maxOrder should be < 30, but is: " + maxOrder;
        maxSubpageAllocs = 1 << maxOrder; //为2^11次方，2048
        // Generate the memory map.
        // 为2^11次方*2，2048 *2=4096
        memoryMap = new byte[maxSubpageAllocs << 1];
        depthMap = new byte[memoryMap.length];
        int memoryMapIndex = 1;   //满二叉树从第1个元素开始
        //深度的次方从0开始，到11为止
        for (int d = 0; d <= maxOrder; ++d) { // move down the tree one level at a time
            // 这一层的元素个数
            int countInDepth = 1 << d;
            System.out.println();
            System.out.print(d + " => ");
            // int depth = 1 << d;  //depth 用词冲突， 改为 countInDepth
            for (int p = 0; p < countInDepth; ++p) {
                // in each level traverse left to right and set value to the depth of subtree
                memoryMap[memoryMapIndex] = (byte) d;
                depthMap[memoryMapIndex] = (byte) d;
                memoryMapIndex++;
                System.out.print( memoryMapIndex+ "/"+ (byte)d + " , ");

            }
        }
        // maxSubpageAllocs 为2^11次方，2048
        subpages = newSubpageArray(maxSubpageAllocs);
        cachedNioBuffers = new ArrayDeque<ByteBuffer>(8);
    }

    /**
     * Creates a special chunk that is not pooled.
     */
    PoolChunk(PoolArena<T> arena, T memory, int size, int offset) {
        unpooled = true;
        this.arena = arena;
        this.memory = memory;
        this.offset = offset;
        memoryMap = null;
        depthMap = null;
        subpages = null;
        subpageOverflowMask = 0;
        pageSize = 0;
        pageShifts = 0;
        maxOrder = 0;
        unusable = (byte) (maxOrder + 1);
        chunkSize = size;
        log2ChunkSize = log2(chunkSize);
        maxSubpageAllocs = 0;
        cachedNioBuffers = null;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpageArray(int size) {
        return new PoolSubpage[size];
    }

    @Override
    public int usage() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }
        return usage(freeBytes);
    }

    private int usage(int freeBytes) {
        if (freeBytes == 0) {
            return 100;
        }

        int freePercentage = (int) (freeBytes * 100L / chunkSize);
        if (freePercentage == 0) {
            return 99;
        }
        return 100 - freePercentage;
    }

    boolean allocate(PooledByteBuf<T> buf, int reqCapacity, int normCapacity, PoolThreadCache threadCache) {
        final long handle;
        if ((normCapacity & subpageOverflowMask) != 0) { // >= pageSize
            handle = allocateRun(normCapacity);
        } else {
            handle = allocateSubpage(normCapacity);
        }

        if (handle < 0) {
            return false;
        }
        ByteBuffer nioBuffer = cachedNioBuffers != null ? cachedNioBuffers.pollLast() : null;
        initBuf(buf, nioBuffer, handle, reqCapacity, threadCache);
        return true;
    }

    /**
     * Update method used by allocate
     * This is triggered only when a successor is allocated and all its predecessors
     * need to update their state
     * The minimal depth at which subtree rooted at id has some free space
     *
     * @param id id
     */
    private void updateParentsAlloc(int id) {
        //从id开始直到root根节点
        while (id > 1) {
            int parentId = id >>> 1; //无符号右移获取父节点，>>>表示无符号右移,也叫逻辑右移,即若该数为正,则高位补0,而若该数为负数,则右移后高位同样补0
            byte val1 = value(id); //获id节点的深度索引值
            byte val2 = value(id ^ 1); //获取另一个节点的深度索引值，即是左节点就获取右节点，是右节点就获取左节点
            byte val = val1 < val2 ? val1 : val2;//取最小的
            setValue(parentId, val); //设置父节点的深度索引值为子节点最小的那一个
            id = parentId;//继续遍历父节点
        }
    }

    /**
     * Update method used by free
     * This needs to handle the special case when both children are completely free
     * in which case parent be directly allocated on request of size = child-size * 2
     *
     * @param id id
     */
    private void updateParentsFree(int id) {
        int logChild = depth(id) + 1;
        while (id > 1) {
            int parentId = id >>> 1;
            byte val1 = value(id);
            byte val2 = value(id ^ 1);
            logChild -= 1; // in first iteration equals log, subsequently reduce 1 from logChild as we traverse up

            if (val1 == logChild && val2 == logChild) {
                setValue(parentId, (byte) (logChild - 1));
            } else {
                byte val = val1 < val2 ? val1 : val2;
                setValue(parentId, val);
            }

            id = parentId;
        }
    }

    /**
     * Algorithm to allocate an index in memoryMap when we query for a free node
     * at depth d
     *
     * @param d depth
     * @return index in memoryMap
     */
    //在平衡二叉树中, 找到一个可以满足normCapacity的节点，总之：根据所在层数来查找这层可用的内存
    // 参数d为深度，表示从d层开始，查找一个可用的节点的index 下班
    // 返回值为 index，表示： page 在 memoryMap的index 下标
    private int allocateNode(int d) {
        //从第一个元素开始找，也就是树的根元素
        //第二层为左右节点，先看左边节点内存是否够分配，若不够，则选择其兄弟节点（右节点）；若当前左节点够分配，则需要继续向下一层层地查找，
        // 直到找到层级最接近d（分配的内存在二叉树中对应的层级）的节点
        int id = 1;

        //掩码,其作用： 如果(id & initial) > 0,说明id对应的高度>=d
        // 1<<d 表示 2^d 次方,假设 d为11
        // 31 30 29 28 27 26 25 24 23 22 21 20 19 18 17 16 15 14 13 12 11 10 9  8  7  6  5  4  3  2  1  0
        //  1  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  1  0  0  0  0  0  0  0  0  0  0  0
         int initial = -(1 << d); // has last d bits = 0 and rest all = 1

        //获取第1个节点的值  memoryMap[1]
        //换句话说，获取第一个节点在内存映射中的深度值
        byte val = value(id);
        //如果根节点值大于 d，说明分配不了那么大的空间，则直接返回
        if (val > d) { // unusable
            return -1;
        }

        // 如果空间足够，则层层迭代，直到找到层级最接近d层的节点，d为需要分配的内存所在的层级
        // id节点的 val < d 表示这个节点没有分配完，空间足够，剩余空间 大于等于 申请的空间
        // id节点的 val = d 但是 id & initial ==0 , 说明至少 有一个初始值为d的子节点，没有被分配
        while (val < d || (id & initial) == 0) { // id & initial == 1 << d for all ids at depth d, for < d it is 0
            id <<= 1;  //增加一层，先进入左子树，再进入 right 子树
            val = value(id);  // 首先是左子树  ，第一个元素的值
            if (val > d) {   //  如果左节点不满足
                id ^= 1;     // 兄弟节点，通过id 亦或1得到
                val = value(id);
            }

            if (null != System.getProperty("testMemoryTreeAllocateNode"))
                logger.debug("d={}，id={},brother[id] ={}，level={},memoryMap[id]={}，memoryMap[id^1]={}，", d, id, id ^ 1, log2(id), memoryMap[id], memoryMap[id ^ 1]);

        }
        //得的id， val==d,并且没有 初始值为d的子节点
        // val为目标的高度
        byte value = value(id);

        //下面还要断言，如果是=d才是可以用的，>d即被设置了unusable，表示不可用了
        //断言id保存的深度索引值为d 且id所在深度索引为d，否则就会输出错误信息
        assert value == d && (id & initial) == 1 << d :
                String.format("val = %d, id & initial = %d, d = %d", value, id & initial, d);

        //id已经分配，标志位不可用，高度d值为12
        setValue(id, unusable); // mark as unusable

        //更新所有父节点的高度值，更新双亲的 可分配规格
        updateParentsAlloc(id);
        //返回下标id

        if (null != System.getProperty("testMemoryTreeAllocateNode"))
            logger.debug("最终输出的时候：d={}，id={},brother[id] ={}，level={},memoryMap[id]={}，memoryMap[id^1]={}，", d, id, id ^ 1, log2(id), memoryMap[id], memoryMap[id ^ 1]);

        return id;
    }

    /**
     * Allocate a run of pages (>=1)
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    private long allocateRun(int normCapacity) {
        //这里的容量都是pageSize及以上的，
        // log2(normCapacity) - pageShifts 表示容量是页大小的2的多少倍，
        // 最大深度索引maxOrder 再减去这个，刚好是定位到页大小倍数的深度索引

        int d = maxOrder - (log2(normCapacity) - pageShifts);
        logger.debug("d=maxOrder - (log2(normCapacity) - pageShifts , {} = {} - {} + {}", maxOrder, d,log2(normCapacity) ,pageShifts);
        int id = allocateNode(d);
        if (id < 0) {
            return id;
        }
        freeBytes -= runLength(id);
        return id;
    }

    /**
     * Create / initialize a new PoolSubpage of normCapacity
     * Any PoolSubpage created / initialized here is added to subpage pool in the PoolArena that owns this PoolChunk
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    private long allocateSubpage(int normCapacity) {
        // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
        // This is need as we may add it back and so alter the linked-list structure.
        //找到当前规格内存normCapacity在subpagePools数组中索引slot，
        // 进而获取该slot内head节点
        PoolSubpage<T> head = arena.findSubpagePoolHead(normCapacity);
        int d = maxOrder; // subpages are only be allocated from pages i.e., leaves
        //这里涉及修改PoolArena中的PoolSubpage链表，需要同步操作
        synchronized (head) {
            //分配一个page，作为PoolSubpage的归属内存块
            //核心的核心第一步： 分配好了 page的偏移量相关的 索引  memoryMapIdx
            int id = allocateNode(d);
            if (id < 0) {
                return id;
            }
            //this.subpages为初始化PoolChunk时创建得一个大小为2048得数组，分别对应二叉树中2048个叶子节点
            final PoolSubpage<T>[] subpages = this.subpages;
            final int pageSize = this.pageSize;
            freeBytes -= pageSize;
            //id节点(在二叉树中节点)获取到subpages中索引
            int subpageIdx = subpageIdx(id);
            PoolSubpage<T> subpage = subpages[subpageIdx];
            if (subpage == null) {
                //创建一个subpage并放到subpages中
                subpage = new PoolSubpage<T>(head, this, id, runOffset(id), pageSize, normCapacity);
                subpages[subpageIdx] = subpage;
            } else {
                //什么时候subpage不为null？ 之前用过被回收之后
                subpage.init(head, normCapacity);
            }
            //在subpage上进行分配
            return subpage.allocate();
        }
    }

    /**
     * Free a subpage or a run of pages
     * When a subpage is freed from PoolSubpage, it might be added back to subpage pool of the owning PoolArena
     * If the subpage pool in PoolArena has at least one other PoolSubpage of given elemSize, we can
     * completely free the owning Page so it is available for subsequent allocations
     *
     * @param handle handle to free
     */
    void free(long handle, ByteBuffer nioBuffer) {
        //handle的低32位memoryMapIdx，是chunk的memoryMap的Idx
        int memoryMapIdx = memoryMapIdx(handle);
        //handle的高32是subpage的bitmapIdx
        int bitmapIdx = bitmapIdx(handle);
        // bitmapIdx !=0 表明回收的是subpage,否则回收page
        if (bitmapIdx != 0) { // free a subpage
            //根据 memoryMapIdx 获取subpages中的某个subpage
            PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
            assert subpage != null && subpage.doNotDestroy;


            if (null != System.getProperty("testFreeSubPage"))
                logger.debug("start to release：memoryMapIdx={}，bitmapIdx={},subpageIdx(memoryMapIdx) ={}，subpage.elemSize={}，subpages={}",
                        memoryMapIdx, bitmapIdx & 0x3FFFFFFF, subpageIdx(memoryMapIdx), subpage.elemSize, subpages);


            // 获取PoolArena拥有的PoolSubPage池的head，并在其上进行同步synchronize。
            // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
            // This is need as we may add it back and so alter the linked-list structure.
            PoolSubpage<T> head = arena.findSubpagePoolHead(subpage.elemSize);
            // synchronize是必需的，这里需要更改链表的结构
            synchronized (head) {
                //(bitmapIdx & 0x3FFFFFFF)解码出子页真正的bitmapIdx，求handle时候用到的0x4000000000000000L
                // 解码之后，subpages的真正偏移位置，
                if (subpage.free(head, bitmapIdx & 0x3FFFFFFF)) {
                    return;
                }
            }
        }

        //当前chunk的可用字节数增加
        freeBytes += runLength(memoryMapIdx);
        //设置当前page节点为可用
        setValue(memoryMapIdx, depth(memoryMapIdx));
        //设置当前节点的父节点为可用
        updateParentsFree(memoryMapIdx);

        if (nioBuffer != null && cachedNioBuffers != null &&
                cachedNioBuffers.size() < PooledByteBufAllocator.DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK) {
            cachedNioBuffers.offer(nioBuffer);
        }
    }

    void initBuf(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity,
                 PoolThreadCache threadCache) {
        //handle的低32位是 memoryMapIdx，是chunk的memoryMap的Idx, 即内存映射索引
        int memoryMapIdx = memoryMapIdx(handle);
        //handle的高32是subpage的bitmapIdx
        int bitmapIdx = bitmapIdx(handle);
        //这里获取的如果是子页的handle,bitmapIdx不为0，那高32位，并不是真正的位图索引，最高非符号位多了1，
        // 根据bitmapIdx来区分是子页的还是Normal的初始化。
        // 如果是normal的 page，那bitmapIdx就是0
        if (bitmapIdx == 0) {
            byte val = value(memoryMapIdx);
            assert val == unusable : String.valueOf(val);
            buf.init(this, nioBuffer, handle, runOffset(memoryMapIdx) + offset,
                    reqCapacity, runLength(memoryMapIdx), threadCache);
        } else {
            //子页初始化
            initBufWithSubpage(buf, nioBuffer, handle, bitmapIdx, reqCapacity, threadCache);
        }
    }

    void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity,
                            PoolThreadCache threadCache) {
        initBufWithSubpage(buf, nioBuffer, handle, bitmapIdx(handle), reqCapacity, threadCache);
    }

    private void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer,
                                    long handle, int bitmapIdx, int reqCapacity, PoolThreadCache threadCache) {
        assert bitmapIdx != 0;

        //handle的低32位memoryMapIdx，是chunk的memoryMap的Idx, 即内存映射索引
        int memoryMapIdx = memoryMapIdx(handle);

        //获取对应的子页
        PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
        assert subpage.doNotDestroy;//还没销毁
        assert reqCapacity <= subpage.elemSize; //请求容量不会大于规范后的

        int pageOffset = runOffset(memoryMapIdx);
        int tBitmapIdx = (bitmapIdx & 0x3FFFFFFF);
        int subpageOffset = tBitmapIdx * subpage.elemSize;

        buf.init(
                this, nioBuffer, handle,
                runOffset(memoryMapIdx) + (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize + offset,
                reqCapacity, subpage.elemSize, threadCache);

        //runOffset(memoryMapIdx) + (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize + offset
        //runOffset(memoryMapIdx) 通过内存映射memoryMap的下标可以找到page偏移量 比如2048 -》0，2049 -》8K,
        // (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize ： 页内的子页偏移

        if (null != System.getProperty("testAllocateSubPage"))
            logger.debug("最终输出的时候：memoryMapIdx={}，pageOffset={},subpageOffset ={}，tBitmapIdx={},reqCapacity={}，subpage.elemSize={}，subpages={}",
                    memoryMapIdx, pageOffset, subpageOffset, tBitmapIdx, reqCapacity, subpage.elemSize, subpages);


    }

    private byte value(int id) {

        return memoryMap[id];
    }

    private void setValue(int id, byte val) {
        byte old = memoryMap[id];

        memoryMap[id] = val;
        if (null != System.getProperty("updateParentsAlloc"))
            logger.debug("old memoryMap[{}]={}，new memoryMap[{}]={},brother memoryMap[{}]={}", id, old, id, val, id ^ 1, memoryMap[id ^ 1]);


    }

    private byte depth(int id) {
        return depthMap[id];
    }

    // 用位运算，来计算 2的对数，效率比数学运算高多了
    // 首先是用Integer.numberOfLeadingZeros(val)  通过位运算，取出补码表示的最高非0位的前面还有多少个0，
    // 比如16的二进制=00010000 最高非0位是1，前面还有3个0，如果是32位的话，还得加上前面24个0，也就是27个0，
    // 比如8的二进制 =00001000 ，最高非0位是1，前面还有4个0，如果是32位的话，还得加上前面24个0，也就是28个0，
    //然后INTEGER_SIZE_MINUS_ONE =32-1=31，
    // 对于16来说，31-27最后值就为4，也就是16是2的4次方
    // 对于8来说，31-28最后值就为3，也就是8是2的3次方
    private static int log2(int val) {
        // compute the (0-based, with lsb = 0) position of highest set bit i.e, log2
        //用这种位运算代替直接取log，提高性能
        return INTEGER_SIZE_MINUS_ONE - Integer.numberOfLeadingZeros(val);
    }

    // id的当前层级节点的 内存规模大小
    // 其中id的层级越大，返回的值越小
    // 当id为0时，返回16MB，为整个PoolChunk内存的大小；
    // 当id为11时，返回8KB，为page默认的大小。
    private int runLength(int id) {
        // represents the size in #bytes supported by node 'id' in the tree
        // log2ChunkSize=24
        // 假设 id=2048 ，depth=11
        // 返回 8192
        return 1 << log2ChunkSize - depth(id);
    }

    //通过内存映射memoryMap的下标可以找到其偏移量
    //一页是8k，也就是8k的整数倍
    private int runOffset(int id) {
        //偏移量： represents the 0-based offset in #bytes from start of the byte-array chunk
        // id异或id同层级最左边的元素的下标值得到偏移量
        // 首先1 << depth(id) 获得同一层最左边的元素，
        // 然后与id进行亦或
        int shift = id ^ 1 << depth(id);
        return shift * runLength(id); //换算成 字节 偏移量
    }

    //maxSubpageAllocs = 1 << maxOrder; //为2^11次方，2048
    private int subpageIdx(int memoryMapIdx) {
        return memoryMapIdx ^ maxSubpageAllocs; // remove highest set bit, to get offset
    }

    private static int memoryMapIdx(long handle) {
        return (int) handle;
    }

    private static int bitmapIdx(long handle) {
        return (int) (handle >>> Integer.SIZE);
    }

    @Override
    public int chunkSize() {
        return chunkSize;
    }

    @Override
    public int freeBytes() {
        synchronized (arena) {
            return freeBytes;
        }
    }

    @Override
    public String toString() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }

        return new StringBuilder()
                .append("Chunk(")
                .append(Integer.toHexString(System.identityHashCode(this)))
                .append(": ")
                .append(usage(freeBytes))
                .append("%, ")
                .append(chunkSize - freeBytes)
                .append('/')
                .append(chunkSize)
                .append(')')
                .toString();
    }

    void destroy() {
        arena.destroyChunk(this);
    }

}
