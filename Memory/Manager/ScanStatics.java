// ScanStatics.java, created Tue Dec 10 14:02:25 2002 by joewhaley
// Copyright (C) 2001-3 John Whaley <jwhaley@alum.mit.edu>
// Licensed under the terms of the GNU LGPL; see COPYING for details.
package Memory.Manager;

import java.util.Collection;
import java.util.Iterator;

import Allocator.DefaultHeapAllocator;
import Bootstrap.PrimordialClassLoader;
import Clazz.jq_Class;
import Clazz.jq_StaticField;
import Memory.HeapAddress;
import Run_Time.Debug;

/**
 * @author John Whaley <jwhaley@alum.mit.edu>
 * @version $Id: ScanStatics.java,v 1.6 2003/05/26 04:03:18 joewhaley Exp $
 */
public class ScanStatics {

    /**
     * Scan static variables for object references.
     */
    static void scanStatics() {
        // todo: other classloaders?
        Collection/*<jq_Type>*/ types = PrimordialClassLoader.loader.getAllTypes();
        for (Iterator i=types.iterator(); i.hasNext(); ) {
            Object o = i.next();
            if (o instanceof jq_Class) {
                jq_Class c = (jq_Class) o;
                jq_StaticField[] sfs = c.getDeclaredStaticFields();
                for (int j=0; j<sfs.length; ++j) {
                    jq_StaticField sf = sfs[j];
                    if (sf.getType().isReferenceType()) {
                        HeapAddress a = sf.getAddress();
                        DefaultHeapAllocator.processPtrField(a);
                    }
                }
            }
        }
    } // scanStatics

    static boolean validateRefs() {
        boolean result = true;
        // todo: other classloaders?
        Collection/*<jq_Type>*/ types = PrimordialClassLoader.loader.getAllTypes();
        for (Iterator i=types.iterator(); i.hasNext(); ) {
            Object o = i.next();
            if (o instanceof jq_Class) {
                jq_Class c = (jq_Class) o;
                jq_StaticField[] sfs = c.getStaticFields();
                for (int j=0; j<sfs.length; ++j) {
                    jq_StaticField sf = sfs[j];
                    if (sf.getType().isReferenceType()) {
                        HeapAddress ref = (HeapAddress) sf.getAddress().peek();
                        if ((!ref.isNull()) && !GCUtil.validRef(ref)) {
                            Debug.write("\nScanStatics.validateRefs:bad ref in ");
                            sf.getName().debugWrite();
                            Debug.write(" ");
                            sf.getDesc().debugWrite();
                            Debug.write("\n");
                            GCUtil.dumpRef(ref);
                            result = false;
                        }
                    }
                }
            }
        }
        return result;
    } // validateRefs

    static boolean validateRefs(int depth) {
        boolean result = true;
        // todo: other classloaders?
        Collection/*<jq_Type>*/ types = PrimordialClassLoader.loader.getAllTypes();
        for (Iterator i=types.iterator(); i.hasNext(); ) {
            Object o = i.next();
            if (o instanceof jq_Class) {
                jq_Class c = (jq_Class) o;
                jq_StaticField[] sfs = c.getStaticFields();
                for (int j=0; j<sfs.length; ++j) {
                    jq_StaticField sf = sfs[j];
                    if (sf.getType().isReferenceType()) {
                        HeapAddress ref = (HeapAddress) sf.getAddress().peek();
                        if (ScanObject.validateRefs(ref, depth)) {
                            Debug.write(
                                "ScanStatics.validateRefs: Bad Ref reached from static ");
                            sf.getName().debugWrite();
                            Debug.write(" ");
                            sf.getDesc().debugWrite();
                            Debug.write("\n");
                            result = false;
                        }
                    }
                }
            }
        }
        return result;
    }

    static void dumpRefs(int start, int count) {
        // todo: other classloaders?
        Collection/*<jq_Type>*/ types = PrimordialClassLoader.loader.getAllTypes();
        for (Iterator i=types.iterator(); i.hasNext(); ) {
            Object o = i.next();
            if (o instanceof jq_Class) {
                jq_Class c = (jq_Class) o;
                jq_StaticField[] sfs = c.getStaticFields();
                for (int j=0; j<sfs.length; ++j) {
                    jq_StaticField sf = sfs[j];
                    if (sf.getType().isReferenceType()) {
                        HeapAddress ref = (HeapAddress) sf.getAddress().peek();
                        if (!ref.isNull()) {
                            sf.getName().debugWrite();
                            Debug.write(" ");
                            sf.getDesc().debugWrite();
                            GCUtil.dumpRef(ref);
                        }
                    }
                }
            }
        }
    } // dumpRefs

}
