// Interface.java, created Fri Apr  5 18:36:41 2002 by joewhaley
// Copyright (C) 2001-3 John Whaley <jwhaley@alum.mit.edu>
// Licensed under the terms of the GNU LGPL; see COPYING for details.
package ClassLib.sun142_win32;

import java.util.Iterator;

import Bootstrap.ObjectTraverser;
import Bootstrap.PrimordialClassLoader;
import ClassLib.ClassLibInterface;
import Clazz.jq_Class;

/*
 * @author  John Whaley <jwhaley@alum.mit.edu>
 * @version $Id: Interface.java,v 1.1 2003/07/05 11:04:17 joewhaley Exp $
 */
public final class Interface extends ClassLib.sun14_win32.Interface {

    /** Creates new Interface */
    public Interface() {}

    public Iterator getImplementationClassDescs(UTF.Utf8 desc) {
        if (ClassLibInterface.USE_JOEQ_CLASSLIB && desc.toString().startsWith("Ljava/")) {
            UTF.Utf8 u = UTF.Utf8.get("LClassLib/sun142_win32/"+desc.toString().substring(1));
            return new Util.Collections.AppendIterator(super.getImplementationClassDescs(desc),
                                            java.util.Collections.singleton(u).iterator());
        }
        return super.getImplementationClassDescs(desc);
    }
    
    public ObjectTraverser getObjectTraverser() {
        return sun142_win32ObjectTraverser.INSTANCE;
    }
    
    public static class sun142_win32ObjectTraverser extends sun14_win32ObjectTraverser {
        public static sun142_win32ObjectTraverser INSTANCE = new sun142_win32ObjectTraverser();
        protected sun142_win32ObjectTraverser() {}
        public void initialize() {
            super.initialize();
            
            jq_Class k = (jq_Class) PrimordialClassLoader.loader.getOrCreateBSType("LClassLib/Common/java/util/zip/DeflaterHuffman;");
            k.load();
            
            // used during bootstrapping.
            k = (jq_Class) PrimordialClassLoader.loader.getOrCreateBSType("Ljava/io/ObjectInputStream$GetFieldImpl;");
            k.load();
            
            // 1.4.2 adds caches to Win32FileSystem, which we should not reflectively inspect.
            k = (jq_Class) PrimordialClassLoader.loader.getOrCreateBSType("Ljava/io/Win32FileSystem;");
            nullInstanceFields.add(k.getOrCreateInstanceField("cache", "Ljava/io/ExpiringCache;"));
            nullInstanceFields.add(k.getOrCreateInstanceField("prefixCache", "Ljava/io/ExpiringCache;"));
            
            // reference these now, so that they are not added during bootimage write.
            k = (jq_Class) PrimordialClassLoader.loader.getOrCreateBSType("Ljava/io/ExpiringCache;");
            k.load();
            k = (jq_Class) PrimordialClassLoader.loader.getOrCreateBSType("Ljava/io/ExpiringCache$Entry;");
        }
    }
}
