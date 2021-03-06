// BootHeap.java, created Tue Dec 10 14:02:01 2002 by joewhaley
// Copyright (C) 2001-3 John Whaley <jwhaley@alum.mit.edu>
// Licensed under the terms of the GNU LGPL; see COPYING for details.
package Memory.Heap;

import Allocator.ObjectLayout;
import Allocator.ObjectLayoutMethods;
import Memory.HeapAddress;
import Util.Assert;

/**
 * @author John Whaley <jwhaley@alum.mit.edu>
 * @version $Id: BootHeap.java,v 1.4 2003/05/12 10:05:19 joewhaley Exp $
 */
public class BootHeap extends Heap {

    /**
     * The range of the data section in the boot image.
     * These are set during the bootstrapping process.     */
    public static final HeapAddress DATA_SECTION_START = HeapAddress.getNull();
    public static final HeapAddress DATA_SECTION_END = HeapAddress.getNull();
    
    public static final BootHeap INSTANCE = new BootHeap();
    
    /**
     * Private constructor.     */
    private BootHeap() {
        super("Boot Image Heap");
    }

    /**
     * Initialize the start and end addresses based on where
     * the data segment was loaded.     */
    static void init() {
        INSTANCE.start = DATA_SECTION_START;
        INSTANCE.end = DATA_SECTION_END;
    }

    /**
     * The current mark value.
     */
    private int markValue;

    /**
     * Allocate size bytes of raw memory.
     * Size is a multiple of wordsize, and the returned memory must be word aligned
     * 
     * @param size Number of bytes to allocate
     * @return Address of allocated storage
     */
    protected HeapAddress allocateZeroedMemory(int size) {
        // Can't allocate anything in the bootheap!
        Assert.UNREACHABLE("allocateZeroedMemory on BootHeap forbidden");
        return null;
    }

    /**
     * Hook to allow heap to perform post-allocation processing of the object.
     * For example, setting the GC state bits in the object header.
     */
    protected void postAllocationProcessing(Object newObj) { 
        // nothing to do in this heap
    }

    /**
     * Mark an object in the boot heap.
     * @param ref the object reference to mark
     * @return whether or not the object was already marked
     */
    public boolean mark(HeapAddress ref) {
        Object obj = ref.asObject();
        return ObjectLayoutMethods.testAndMark(obj, markValue);
    }

    /**
     * Is the object reference live?
     */
    public boolean isLive(HeapAddress ref) {
        Object obj = ref.asObject();
        return ObjectLayoutMethods.testMarkBit(obj, markValue);
    }

    /**
     * Work to do before collection starts.
     */
    public void startCollect() {
        // flip the sense of the mark bit.
        markValue = markValue ^ ObjectLayout.GC_BIT;
    }

}
