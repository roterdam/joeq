// SecurityManager.java, created Sun Nov 17 16:08:31 2002 by asharm2
// Copyright (C) 2001-3 John Whaley <jwhaley@alum.mit.edu>
// Licensed under the terms of the GNU LGPL; see COPYING for details.
package ClassLib.Common.java.lang;

import Clazz.jq_CompiledCode;
import Memory.StackAddress;
import Run_Time.Reflection;
import Run_Time.StackCodeWalker;
import Util.Assert;

/**
 * SecurityManager
 *
 * @author  John Whaley <jwhaley@alum.mit.edu>
 * @version $Id: SecurityManager.java,v 1.4 2003/05/12 10:04:53 joewhaley Exp $
 */
public class SecurityManager {

    protected java.lang.Class[] getClassContext() {
        StackCodeWalker sw = new StackCodeWalker(null, StackAddress.getBasePointer());
        sw.gotoNext();
        int i;
        for (i=0; sw.hasNext(); ++i, sw.gotoNext()) ;
        java.lang.Class[] classes = new java.lang.Class[i];
        sw = new StackCodeWalker(null, StackAddress.getBasePointer());
        sw.gotoNext();
        for (i=0; sw.hasNext(); ++i, sw.gotoNext()) {
            jq_CompiledCode cc = sw.getCode();
            if (cc == null) classes[i] = null;
            else classes[i] = Reflection.getJDKType(cc.getMethod().getDeclaringClass());
        }
        Assert._assert(i == classes.length);
        return classes;
    }

}
