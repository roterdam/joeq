// Object.java, created Thu Jul  4  4:50:03 2002 by joewhaley
// Copyright (C) 2001-3 John Whaley <jwhaley@alum.mit.edu>
// Licensed under the terms of the GNU LGPL; see COPYING for details.
package ClassLib.Common.java.lang;

import Allocator.HeapAllocator;
import Clazz.jq_Reference;
import Run_Time.Monitor;
import Run_Time.Reflection;
import Run_Time.Unsafe;
import Scheduler.jq_Thread;

/**
 * Object
 *
 * @author  John Whaley <jwhaley@alum.mit.edu>
 * @version $Id: Object.java,v 1.8 2003/05/12 10:04:53 joewhaley Exp $
 */
public abstract class Object {

    // native method implementations.
    private static void registerNatives() {}
    public final java.lang.Class _getClass() {
        return Reflection.getJDKType(jq_Reference.getTypeOf(this));
    }
    public int hashCode() { return java.lang.System.identityHashCode(this); }
    protected java.lang.Object clone() throws CloneNotSupportedException {
        if (this instanceof Cloneable) {
            return HeapAllocator.clone(this);
        } else throw new CloneNotSupportedException(this.getClass().getName());
    }
    public final void _notify() {
        // TODO
    }
    public final void _notifyAll() {
        // TODO
    }
    public final void _wait(long timeout) throws java.lang.InterruptedException {
        if (timeout < 0L)
            throw new java.lang.IllegalArgumentException(timeout+" < 0");
        // TODO
        int count = Monitor.getLockEntryCount(this);
        int k = count;
        for (;;) {
            Monitor.monitorexit(this);
            if (--k == 0) break;
        }
        jq_Thread t = Unsafe.getThreadBlock();
        java.lang.InterruptedException rethrow;
        try {
            t.sleep(timeout);
            rethrow = null;
        } catch (java.lang.InterruptedException x) {
            rethrow = x;
        }
        for (;;) {
            Monitor.monitorenter(this);
            if (--count == 0) break;
        }
        if (rethrow != null)
            throw rethrow;
    }
    
}
