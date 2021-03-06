// BlockControl.java, created Tue Dec 10 14:02:01 2002 by joewhaley
// Copyright (C) 2001-3 John Whaley <jwhaley@alum.mit.edu>
// Licensed under the terms of the GNU LGPL; see COPYING for details.
package Memory.Heap;

import Bootstrap.PrimordialClassLoader;
import Clazz.jq_Array;
import Clazz.jq_Class;
import Memory.HeapAddress;

/**
 * @author John Whaley <jwhaley@alum.mit.edu>
 * @version $Id: BlockControl.java,v 1.2 2003/05/12 10:05:19 joewhaley Exp $
 */
public class BlockControl {
    HeapAddress baseAddr;
    int slotsize;   // slotsize
    byte[] mark;
    byte[] alloc;
    int nextblock;
    byte[] Alloc1;
    byte[] Alloc2;
    boolean live;
    boolean sticky;
    int alloc_size; // allocated length of mark and alloc arrays

    public static final jq_Class _class = (jq_Class) PrimordialClassLoader.loader.getOrCreateBSType("LMemory/Heap/BlockControl;");
    public static final jq_Array _array = _class.getArrayTypeForElementType();
}
