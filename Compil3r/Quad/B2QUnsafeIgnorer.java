// B2QUnsafeIgnorer.java, created Mon Dec 23 23:00:34 2002 by mcmartin
// Copyright (C) 2001-3 mcmartin
// Licensed under the terms of the GNU LGPL; see COPYING for details.
package Compil3r.Quad;

import Clazz.jq_Method;

/*
 * @author  Michael Martin <mcmartin@stanford.edu>
 * @version $Id: B2QUnsafeIgnorer.java,v 1.2 2003/05/12 10:05:14 joewhaley Exp $
 */
class B2QUnsafeIgnorer implements BytecodeToQuad.UnsafeHelper {
    public boolean isUnsafe(jq_Method m) {
	return false;
    }
    public boolean endsBB(jq_Method m) {
	return false;
    }
    public boolean handleMethod(BytecodeToQuad b2q, ControlFlowGraph quad_cfg, BytecodeToQuad.AbstractState current_state, jq_Method m, Operator.Invoke oper) {
	return false;
    }
}
