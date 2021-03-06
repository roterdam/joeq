// Graph.java, created Jun 15, 2003 6:16:17 PM by joewhaley
// Copyright (C) 2003 John Whaley <jwhaley@alum.mit.edu>
// Licensed under the terms of the GNU LGPL; see COPYING for details.
package Util.Graphs;

import java.util.Collection;

/**
 * Graph
 * 
 * @author John Whaley
 * @version $Id: Graph.java,v 1.1 2003/06/16 17:17:04 joewhaley Exp $
 */
public interface Graph {

    Collection getRoots();

    Navigator getNavigator();

}
