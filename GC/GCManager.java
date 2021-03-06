// GCManager.java, created Wed Sep 25  7:09:24 2002 by laudney
// Copyright (C) 2001-3 laudney <laudney@acm.org>
// Licensed under the terms of the GNU LGPL; see COPYING for details.
package GC;

import java.util.Hashtable;

/**
 * GCManager
 *
 * @author laudney <laudney@acm.org>
 * @version $Id: GCManager.java,v 1.4 2003/05/12 10:05:17 joewhaley Exp $
 */
public class GCManager {
    // Reference Counting GC
    public static final int RC_GC = 1;
    // Tracing GC
    public static final int TR_GC = 2;
    // Mark and Sweep GC
    public static final int MS_GC = 4;
    // Copying GC
    public static final int CP_GC = 8;
    // Conservative GC
    public static final int CNS_GC = 0x10;
    // Incremental GC
    public static final int INC_GC = 0x12;
    // Generational GC
    public static final int GEN_GC = 0x14;
    // Parallel GC
    public static final int PAL_GC = 0x10000;
    // Distributed GC
    public static final int DST_GC = 0x20000;

    private static boolean initialized = false;
    private static Hashtable candidates = new Hashtable();
    private static Runnable defaultTCGC = new TraceMSGC();
    private static Runnable defaultRCGC = new SimpleRCGC();

    public static void initialize() {
        candidates.put(new Integer(TR_GC & MS_GC), new TraceMSGC());
        candidates.put(new Integer(RC_GC), new SimpleRCGC());
        initialized = true;
    }

    public static void reset() {
        initialized = false;
    }

    public static Object getGC(int key) {
        if (!initialized) {
            initialize();
        }
        Object result = candidates.get(new Integer(key));
        if (result != null) {
            return result;
        } else if ((key & TR_GC) != 0) {
            return defaultTCGC;
        } else if ((key & RC_GC) != 0) {
            return defaultRCGC;
        } else {
            return null;
        }
    }
}
