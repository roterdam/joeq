// PriorityQueueSolver.java, created Thu Apr 25 16:32:26 2002 by joewhaley
// Copyright (C) 2001-3 John Whaley <jwhaley@alum.mit.edu>
// Licensed under the terms of the GNU LGPL; see COPYING for details.
package Compil3r.Dataflow;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import Util.Collections.BinHeapPriorityQueue;
import Util.Collections.MapFactory;
import Util.Collections.MaxPriorityQueue;
import Util.Graphs.Graph;
import Util.Graphs.Traversals;

/**
 * PriorityQueueSolver
 * 
 * @author John Whaley
 * @version $Id: PriorityQueueSolver.java,v 1.1 2003/06/17 02:37:51 joewhaley Exp $
 */
public class PriorityQueueSolver extends WorklistSolver {

    /** Map from nodes to their (integer) priorities. */
    protected Map nodesToPriorities;
    /** Priority-queue implementation of the worklist. */
    protected MaxPriorityQueue worklist;

    public PriorityQueueSolver(MapFactory f) {
        super(f);
    }
    public PriorityQueueSolver() {
        super();
    }

    /* (non-Javadoc)
     * @see Compil3r.Dataflow.Solver#initialize(Compil3r.Dataflow.Problem, Util.Graphs.Graph)
     */
    public void initialize(Problem p, Graph graph) {
        this.initialize(p, graph, Traversals.reversePostOrder(graph.getNavigator(), graph.getRoots()));
    }
    
    /** Initializes this solver with the given dataflow problem, graph, and
     * traversal order.
     * 
     * @see Compil3r.Dataflow.Solver#initialize(Compil3r.Dataflow.Problem, Util.Graphs.Graph)
     */
    public void initialize(Problem p, Graph graph, List traversalOrder) {
        super.initialize(p, graph);
        initializeTraversalOrder(traversalOrder);
    }
    
    protected void initializeTraversalOrder(List order) {
        int n = order.size();
        this.nodesToPriorities = new HashMap();
        for (Iterator i = order.iterator(); i.hasNext(); ) {
            Object o = i.next();
            nodesToPriorities.put(o, new Integer(n));
            --n;
        }
    }

    /* (non-Javadoc)
     * @see Compil3r.Dataflow.Solver#allLocations()
     */
    public Iterator allLocations() {
        return nodesToPriorities.keySet().iterator();
    }

    /* (non-Javadoc)
     * @see Compil3r.Dataflow.WorklistSolver#initializeWorklist()
     */
    protected void initializeWorklist() {
        worklist = new BinHeapPriorityQueue(nodesToPriorities.size());
        for (Iterator i = nodesToPriorities.entrySet().iterator(); i.hasNext(); ) {
            Map.Entry e = (Map.Entry) i.next();
            Object o = e.getKey();
            if (boundaries.contains(o)) continue;
            int v = ((Integer) e.getValue()).intValue();
            worklist.insert(o, v);
        }
    }

    /* (non-Javadoc)
     * @see Compil3r.Dataflow.WorklistSolver#hasNext()
     */
    protected boolean hasNext() {
        return !worklist.isEmpty();
    }

    /* (non-Javadoc)
     * @see Compil3r.Dataflow.WorklistSolver#pull()
     */
    protected Object pull() {
        return worklist.deleteMax();
    }

    /* (non-Javadoc)
     * @see Compil3r.Dataflow.WorklistSolver#pushAll(java.util.Collection)
     */
    protected void pushAll(Collection c) {
        for (Iterator i = c.iterator(); i.hasNext(); ) {
            Object o = i.next();
            int v = ((Integer) nodesToPriorities.get(o)).intValue();
            worklist.insert(o, v);
        }
    }
    
}
