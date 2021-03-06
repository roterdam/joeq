// CSPA.java, created Jun 15, 2003 10:08:38 PM by joewhaley
// Copyright (C) 2003 John Whaley <jwhaley@alum.mit.edu>
// Licensed under the terms of the GNU LGPL; see COPYING for details.
package Compil3r.Quad.IPA;

import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.StringTokenizer;
import java.util.TreeSet;

import org.sf.javabdd.BDD;
import org.sf.javabdd.BDDBitVector;
import org.sf.javabdd.BDDDomain;
import org.sf.javabdd.BDDFactory;
import org.sf.javabdd.BDDPairing;

import Bootstrap.PrimordialClassLoader;
import Clazz.jq_Class;
import Clazz.jq_Field;
import Clazz.jq_Method;
import Clazz.jq_Reference;
import Clazz.jq_StaticMethod;
import Clazz.jq_Type;
import Compil3r.Quad.BDDPointerAnalysis;
import Compil3r.Quad.CachedCallGraph;
import Compil3r.Quad.CallGraph;
import Compil3r.Quad.CodeCache;
import Compil3r.Quad.ControlFlowGraph;
import Compil3r.Quad.LoadedCallGraph;
import Compil3r.Quad.MethodInline;
import Compil3r.Quad.MethodSummary;
import Compil3r.Quad.Operator;
import Compil3r.Quad.ProgramLocation;
import Compil3r.Quad.Quad;
import Compil3r.Quad.QuadIterator;
import Compil3r.Quad.RootedCHACallGraph;
import Compil3r.Quad.MethodSummary.ConcreteObjectNode;
import Compil3r.Quad.MethodSummary.ConcreteTypeNode;
import Compil3r.Quad.MethodSummary.FieldNode;
import Compil3r.Quad.MethodSummary.GlobalNode;
import Compil3r.Quad.MethodSummary.Node;
import Compil3r.Quad.MethodSummary.ParamNode;
import Compil3r.Quad.MethodSummary.PassedParameter;
import Compil3r.Quad.MethodSummary.ReturnValueNode;
import Compil3r.Quad.MethodSummary.ReturnedNode;
import Compil3r.Quad.MethodSummary.ThrownExceptionNode;
import Compil3r.Quad.MethodSummary.UnknownTypeNode;
import Main.HostedVM;
import Run_Time.TypeCheck;
import Util.Assert;
import Util.Strings;
import Util.Collections.Pair;
import Util.Graphs.Navigator;
import Util.Graphs.SCCTopSortedGraph;
import Util.Graphs.SCComponent;
import Util.Graphs.Traversals;

/**
 * CSPA
 * 
 * @author John Whaley
 * @version $Id: CSPA.java,v 1.6 2003/08/09 12:22:05 joewhaley Exp $
 */
public class CSPA {

    /***** FLAGS *****/

    /** Various trace flags. */
    public static final boolean TRACE_ALL = false;
    
    public static final boolean TRACE_MATCHING  = true || TRACE_ALL;
    public static final boolean TRACE_TYPES     = false || TRACE_ALL;
    public static final boolean TRACE_MAPS      = false || TRACE_ALL;
    public static final boolean TRACE_SIZES     = false || TRACE_ALL;
    public static final boolean TRACE_CALLGRAPH = false || TRACE_ALL;
    public static final boolean TRACE_EDGES     = false || TRACE_ALL;
    public static final boolean TRACE_TIMES     = false || TRACE_ALL;
    public static final boolean TRACE_VARORDER  = false || TRACE_ALL;
    public static final boolean TRACE_NUMBERING = false || TRACE_ALL;
    public static final boolean TRACE_RELATIONS = false || TRACE_ALL;
    
    public static final boolean USE_CHA     = false;
    public static final boolean DO_INLINING = false;

    public static boolean LOADED_CALLGRAPH = false;
    public static final boolean TEST_CALLGRAPH = false;
    
    public static boolean BREAK_RECURSION = false;
    
    public static final boolean CONTEXT_SENSITIVE = true;
    public static final boolean CONTEXT_SENSITIVE_HEAP = true;
    
    public static void main(String[] args) throws IOException {
        // We use bytecode maps.
        CodeCache.AlwaysMap = true;
        HostedVM.initialize();
        
        jq_Class c = (jq_Class) jq_Type.parseType(args[0]);
        c.prepare();
        Collection roots = Arrays.asList(c.getDeclaredStaticMethods());
        if (args.length > 1) {
            for (Iterator i=roots.iterator(); i.hasNext(); ) {
                jq_StaticMethod sm = (jq_StaticMethod) i.next();
                if (args[1].equals(sm.getName().toString())) {
                    roots = Collections.singleton(sm);
                    break;
                }
            }
        }
        
        String callgraphfilename = System.getProperty("callgraph", "callgraph");
        
        CallGraph cg = null;
        if (new File(callgraphfilename).exists()) {
            try {
                System.out.print("Loading initial call graph...");
                long time = System.currentTimeMillis();
                cg = new LoadedCallGraph("callgraph");
                time = System.currentTimeMillis() - time;
                System.out.println("done. ("+time/1000.+" seconds)");
                roots = cg.getRoots();
                LOADED_CALLGRAPH = true;
            } catch (IOException x) {
                x.printStackTrace();
            }
        }
        if (cg == null) {
            System.out.print("Setting up initial call graph...");
            long time = System.currentTimeMillis();
            if (USE_CHA) {
                cg = new RootedCHACallGraph();
                cg = new CachedCallGraph(cg);
                cg.setRoots(roots);
            } else {
                BDDPointerAnalysis dis = new BDDPointerAnalysis("java", 1000000, 100000);
                cg = dis.goIncremental(roots);
                cg = new CachedCallGraph(cg);
                // BDD pointer analysis changes the root set by adding class initializers,
                // thread entry points, etc.
                roots = cg.getRoots();
            }
            time = System.currentTimeMillis() - time;
            System.out.println("done. ("+time/1000.+" seconds)");
        
            System.out.print("Calculating reachable methods...");
            time = System.currentTimeMillis();
            /* Calculate the reachable methods once to touch each method,
               so that the set of types are stable. */
            cg.calculateReachableMethods(roots);
            time = System.currentTimeMillis() - time;
            System.out.println("done. ("+time/1000.+" seconds)");
        
            try {
                java.io.FileWriter fw = new java.io.FileWriter("callgraph");
                java.io.PrintWriter pw = new java.io.PrintWriter(fw);
                LoadedCallGraph.write(cg, pw);
                pw.close();
            } catch (java.io.IOException x) {
                x.printStackTrace();
            }
            
        }
        
        if (DO_INLINING) {
            System.out.print("Doing inlining on call graph...");
            CachedCallGraph ccg;
            if (cg instanceof CachedCallGraph)
                ccg = (CachedCallGraph) cg;
            else
                ccg = new CachedCallGraph(cg);
            long time = System.currentTimeMillis();
            // pre-initialize all classes so that we can inline more.
            for (Iterator i=ccg.getAllMethods().iterator(); i.hasNext(); ) {
                jq_Method m = (jq_Method) i.next();
                m.getDeclaringClass().cls_initialize();
            }
            MethodInline mi = new MethodInline(ccg);
            Navigator navigator = ccg.getNavigator();
            for (Iterator i=Traversals.postOrder(navigator, roots).iterator(); i.hasNext(); ) {
                jq_Method m = (jq_Method) i.next();
                if (m.getBytecode() == null) continue;
                ControlFlowGraph cfg = CodeCache.getCode(m);
                //MethodSummary ms = MethodSummary.getSummary(cfg);
                mi.visitCFG(cfg);
            }
            time = System.currentTimeMillis() - time;
            System.out.println("done. ("+time/1000.+" seconds)");
            
            System.out.print("Rebuilding call graph...");
            time = System.currentTimeMillis();
            ccg.invalidateCache();
            time = System.currentTimeMillis() - time;
            System.out.println("done. ("+time/1000.+" seconds)");
            cg = ccg;
        }

        long time;
        
        if (TEST_CALLGRAPH) {
            RootedCHACallGraph.test(cg);
        }
        
        System.out.print("Counting size of call graph...");
        time = System.currentTimeMillis();
        countCallGraph(cg);
        time = System.currentTimeMillis() - time;
        System.out.println("done. ("+time/1000.+" seconds)");
        
        // Allocate CSPA object.
        CSPA dis = new CSPA(cg);
        dis.roots = roots;
        
        // Initialize BDD package.
        dis.initializeBDD(DEFAULT_NODE_COUNT, DEFAULT_CACHE_SIZE);
        
        // Add edges for existing globals.
        dis.addGlobals();
        
        System.out.print("Generating BDD summaries without context...");
        time = System.currentTimeMillis();
        dis.generateBDDSummaries();
        time = System.currentTimeMillis() - time;
        System.out.println("done. ("+time/1000.+" seconds)");
        
        System.out.print("Counting paths...");
        time = System.currentTimeMillis();
        long paths = dis.countPaths2();
        time = System.currentTimeMillis() - time;
        System.out.println("done. ("+time/1000.+" seconds)");
        System.out.println(paths+" paths");
        
        System.out.print("Initializing relations and adding call graph edges...");
        time = System.currentTimeMillis();
        dis.goForIt();
        time = System.currentTimeMillis() - time;
        System.out.println("done. ("+time/1000.+" seconds)");
        
        System.out.print("Solving pointers...");
        time = System.currentTimeMillis();
        dis.solveIncremental();
        time = System.currentTimeMillis() - time;
        System.out.println("done. ("+time/1000.+" seconds)");
        
        dis.printHistogram();
        dis.escapeAnalysis();
        
        String dumpfilename = System.getProperty("cspa.dumpfile", "cspa");
        dis.dumpResults(dumpfilename);
    }
    
    /**
     * @param dumpfilename
     */
    void dumpResults(String dumpfilename) throws IOException {
        bdd.save(dumpfilename+".bdd", g_pointsTo);
        
        DataOutputStream dos;
        dos = new DataOutputStream(new FileOutputStream(dumpfilename+".config"));
        dumpConfig(dos);
        dos.close();
        dos = new DataOutputStream(new FileOutputStream(dumpfilename+".vars"));
        dumpVarIndexMap(dos);
        dos.close();
        dos = new DataOutputStream(new FileOutputStream(dumpfilename+".heap"));
        dumpHeapIndexMap(dos);
        dos.close();
    }

    private void dumpConfig(DataOutput out) throws IOException {
        out.writeBytes(VARBITS+" "+HEAPBITS+" "+FIELDBITS+" "+CLASSBITS+" "+CONTEXTBITS+"\n");
        String ordering = System.getProperty("bddordering", "FD_H2cxH2o_V2cxV1cxV2oxV1o_H1cxH1o");
        out.writeBytes(ordering+"\n");
    }

    private void dumpVarIndexMap(DataOutput out) throws IOException {
        int n = variableIndexMap.size();
        out.writeBytes(n+"\n");
        int j;
        for (j=0; j<=globalVarHighIndex; ++j) {
            Node node = (Node) variableIndexMap.get(j);
            node.write(null, out);
            out.writeByte('\n');
        }
        for (Iterator i=bddSummaryList.iterator(); i.hasNext(); ) {
            BDDMethodSummary s = (BDDMethodSummary) i.next();
            Assert._assert(s.lowVarIndex == j);
            for ( ; j<=s.highVarIndex; ++j) {
                Node node = (Node) variableIndexMap.get(j);
                node.write(s.ms, out);
                out.writeByte('\n');
            }
        }
        while (j < variableIndexMap.size()) {
            UnknownTypeNode node = (UnknownTypeNode) variableIndexMap.get(j);
            node.write(null, out);
            out.writeByte('\n');
            ++j;
        }
    }

    private void dumpHeapIndexMap(DataOutput out) throws IOException {
        int n = heapobjIndexMap.size();
        out.writeBytes(n+"\n");
        int j;
        for (j=0; j<=globalHeapHighIndex; ++j) {
            ConcreteObjectNode node = (ConcreteObjectNode) heapobjIndexMap.get(j);
            if (node == null) out.writeBytes("null");
            else node.write(null, out);
            out.writeByte('\n');
        }
        for (Iterator i=bddSummaryList.iterator(); i.hasNext(); ) {
            BDDMethodSummary s = (BDDMethodSummary) i.next();
            Assert._assert(s.lowHeapIndex == j);
            for ( ; j<=s.highHeapIndex; ++j) {
                Node node = (Node) heapobjIndexMap.get(j);
                node.write(s.ms, out);
                out.writeByte('\n');
            }
        }
        while (j < heapobjIndexMap.size()) {
            UnknownTypeNode node = (UnknownTypeNode) heapobjIndexMap.get(j);
            node.write(null, out);
            out.writeByte('\n');
            ++j;
        }
    }
    
    void printHistogram() {
        BDD pointsTo = g_pointsTo.exist(V1c.set());
        int[] histogram = new int[64];
        for (int i=0; i<variableIndexMap.size(); i++) {
            BDD a = pointsTo.restrict(V1o.ithVar(i));
            BDD b = a.exist(H1c.set());
            long size = (long) b.satCount(H1o.set());
            int index;
            if (size >= histogram.length) index = histogram.length - 1;
            else index = (int) size;
            histogram[index]++;
            //System.out.println(variableIndexMap.get(i)+" points to "+size+" objects");
        }
        for (int i=0; i<histogram.length; ++i) {
            if (histogram[i] != 0) {
                if (i==histogram.length-1) System.out.print(">=");
                System.out.println(i+" = "+histogram[i]);
            }
        }
    }
    
    int globalVarLowIndex, globalVarHighIndex;
    int globalHeapLowIndex, globalHeapHighIndex;
    
    public void addGlobals() {
        Assert._assert(variableIndexMap.size() == 0);
        globalVarLowIndex = 0; globalHeapLowIndex = 0;
        GlobalNode.GLOBAL.addDefaultStatics();
        addGlobalObjectAllocation(GlobalNode.GLOBAL, null);
        addAllocType(null, PrimordialClassLoader.getJavaLangObject());
        addVarType(GlobalNode.GLOBAL, PrimordialClassLoader.getJavaLangObject());
        handleGlobalNode(GlobalNode.GLOBAL);
        for (Iterator i=ConcreteObjectNode.getAll().iterator(); i.hasNext(); ) {
            handleGlobalNode((ConcreteObjectNode) i.next());
        }
        globalVarHighIndex = variableIndexMap.size() - 1;
        globalHeapHighIndex = heapobjIndexMap.size() - 1;
    }
    
    public void addGlobalV1Context(BDD b) {
        if (CONTEXT_SENSITIVE)
            b.andWith(V1c.domain());
        else
            b.andWith(V1c.ithVar(0));
    }
    public void addGlobalV2Context(BDD b) {
        if (CONTEXT_SENSITIVE)
            b.andWith(V2c.domain());
        else
            b.andWith(V2c.ithVar(0));
    }
    public void addGlobalH1Context(BDD b) {
        if (CONTEXT_SENSITIVE)
            b.andWith(H1c.domain());
        else
            b.andWith(H1c.ithVar(0));
    }
    public BDD getV1Context(long lo, long hi) {
        if (CONTEXT_SENSITIVE)
            return V1c.varRange(lo, hi);
        else
            return V1c.ithVar(0);
    }
    public BDD getV2Context(long lo, long hi) {
        if (CONTEXT_SENSITIVE)
            return V2c.varRange(lo, hi);
        else
            return V2c.ithVar(0);
    }
    public BDD getH1Context(long lo, long hi) {
        if (CONTEXT_SENSITIVE)
            return H1c.varRange(lo, hi);
        else
            return H1c.ithVar(0);
    }
    
    public void addGlobalObjectAllocation(Node dest, Node site) {
        int dest_i = getVariableIndex(dest);
        int site_i = getHeapobjIndex(site);
        BDD dest_bdd = V1o.ithVar(dest_i);
        addGlobalV1Context(dest_bdd);
        BDD site_bdd = H1o.ithVar(site_i);
        addGlobalH1Context(site_bdd);
        dest_bdd.andWith(site_bdd);
        g_pointsTo.orWith(dest_bdd);
    }
    
    public void addGlobalLoad(Set dests, Node base, jq_Field f) {
        int base_i = getVariableIndex(base);
        int f_i = getFieldIndex(f);
        BDD base_bdd = V1o.ithVar(base_i);
        addGlobalV1Context(base_bdd);
        BDD f_bdd = FD.ithVar(f_i);
        for (Iterator i=dests.iterator(); i.hasNext(); ) {
            FieldNode dest = (FieldNode) i.next();
            int dest_i = getVariableIndex(dest);
            BDD dest_bdd = V2o.ithVar(dest_i);
            addGlobalV2Context(dest_bdd);
            dest_bdd.andWith(f_bdd.id());
            dest_bdd.andWith(base_bdd.id());
            g_loads.orWith(dest_bdd);
        }
        base_bdd.free(); f_bdd.free();
    }
    
    public void addGlobalStore(Node base, jq_Field f, Set srcs) {
        int base_i = getVariableIndex(base);
        int f_i = getFieldIndex(f);
        BDD base_bdd = V2o.ithVar(base_i);
        addGlobalV2Context(base_bdd);
        BDD f_bdd = FD.ithVar(f_i);
        for (Iterator i=srcs.iterator(); i.hasNext(); ) {
            Node src = (Node) i.next();
            int src_i = getVariableIndex(src);
            BDD src_bdd = V1o.ithVar(src_i);
            addGlobalV1Context(src_bdd);
            src_bdd.andWith(f_bdd.id());
            src_bdd.andWith(base_bdd.id());
            g_stores.orWith(src_bdd);
        }
        base_bdd.free(); f_bdd.free();
    }
    
    // v2 = v1;
    public void addGlobalEdge(Node dest, Collection srcs) {
        int dest_i = getVariableIndex(dest);
        BDD dest_bdd = V2o.ithVar(dest_i);
        addGlobalV2Context(dest_bdd);
        for (Iterator i=srcs.iterator(); i.hasNext(); ) {
            Node src = (Node) i.next();
            int src_i = getVariableIndex(src);
            BDD src_bdd = V1o.ithVar(src_i);
            addGlobalV1Context(src_bdd);
            src_bdd.andWith(dest_bdd.id());
            g_edgeSet.orWith(src_bdd);
        }
        dest_bdd.free();
    }
    
    public void handleGlobalNode(Node n) {
        
        Iterator j;
        j = n.getEdges().iterator();
        while (j.hasNext()) {
            Map.Entry e = (Map.Entry) j.next();
            jq_Field f = (jq_Field) e.getKey();
            Object o = e.getValue();
            // n.f = o
            if (o instanceof Set) {
                addGlobalStore(n, f, (Set) o);
            } else {
                addGlobalStore(n, f, Collections.singleton(o));
            }
        }
        j = n.getAccessPathEdges().iterator();
        while (j.hasNext()) {
            Map.Entry e = (Map.Entry)j.next();
            jq_Field f = (jq_Field)e.getKey();
            Object o = e.getValue();
            // o = n.f
            if (o instanceof Set) {
                addGlobalLoad((Set) o, n, f);
            } else {
                addGlobalLoad(Collections.singleton(o), n, f);
            }
        }
        if (n instanceof ConcreteTypeNode ||
            n instanceof UnknownTypeNode ||
            n instanceof ConcreteObjectNode) {
            addGlobalObjectAllocation(n, n);
            addAllocType(n, (jq_Reference) n.getDeclaredType());
        }
        if (n instanceof GlobalNode) {
            addGlobalEdge(GlobalNode.GLOBAL, Collections.singleton(n));
            addGlobalEdge(n, Collections.singleton(GlobalNode.GLOBAL));
            addVarType(n, PrimordialClassLoader.getJavaLangObject());
        } else {
            addVarType(n, (jq_Reference) n.getDeclaredType());
        }
    }
    
    /**
     * The default initial node count.  Smaller values save memory for
     * smaller problems, larger values save the time to grow the node tables
     * on larger problems.
     */
    public static final int DEFAULT_NODE_COUNT = Integer.parseInt(System.getProperty("bddnodes", "1000000"));

    /**
     * The size of the BDD operator cache.
     */
    public static final int DEFAULT_CACHE_SIZE = Integer.parseInt(System.getProperty("bddcache", "100000"));

    /**
     * Singleton BDD object that provides access to BDD functions.
     */
    private BDDFactory bdd;
    
    public static int VARBITS = 18;
    public static int HEAPBITS = 15;
    public static int FIELDBITS = 14;
    public static int CLASSBITS = 14;
    public static int CONTEXTBITS = 38;
    
    // the size of domains, can be changed to reflect the size of inputs
    int domainBits[];
    // to be computed in sysInit function
    int domainSpos[]; 
    
    // V1 V2 are domains for variables 
    // H1 H2 are domains for heap objects
    // FD is a domain for field signature
    BDDDomain V1o, V2o, H1o, H2o;
    BDDDomain V1c, V2c, H1c, H2c;
    BDDDomain FD;
    // T1 and T2 are used to compute typeFilter
    // T1 = V2, and T2 = V1
    BDDDomain T1, T2; 
    BDDDomain[] bdd_domains;

    // domain pairs for bdd_replace
    BDDPairing V1ToV2;
    BDDPairing V2ToV1;
    BDDPairing H1ToH2;
    BDDPairing H2ToH1;
    BDDPairing T2ToT1;
    
    // domain sets
    BDD V1set, V2set, V3set, FDset, H1set, H2set, T1set, T2set;
    BDD H1andFDset;

    // global BDDs
    BDD aC; // H1 x T2
    BDD vC; // V1 x T1
    BDD cC; // T1 x T2
    BDD typeFilter; // V1 x H1

    public static void countCallGraph(CallGraph cg) {
        Set fields = new HashSet();
        Set classes = new HashSet();
        int vars = 0, heaps = 0, bcodes = 0, methods = 0, calls = 0;
        for (Iterator i=cg.getAllMethods().iterator(); i.hasNext(); ) {
            jq_Method m = (jq_Method) i.next();
            ++methods;
            if (m.getBytecode() == null) continue;
            bcodes += m.getBytecode().length;
            ControlFlowGraph cfg = CodeCache.getCode(m);
            MethodSummary ms = MethodSummary.getSummary(cfg);
            for (Iterator j=ms.nodeIterator(); j.hasNext(); ) {
                Node n = (Node) j.next();
                ++vars;
                if (n instanceof ConcreteTypeNode ||
                    n instanceof UnknownTypeNode ||
                    n instanceof ConcreteObjectNode)
                    ++heaps;
                fields.addAll(n.getAccessPathEdgeFields());
                fields.addAll(n.getEdgeFields());
                if (n instanceof GlobalNode) continue;
                jq_Reference r = (jq_Reference) n.getDeclaredType();
                classes.add(r);
            }
            calls += ms.getCalls().size();
        }
        System.out.println();
        System.out.println("Methods="+methods+" Bytecodes="+bcodes+" Call sites="+calls);
        //long paths = Util.Graphs.CountPaths.countPaths(cg);
        long paths = countPaths3(cg);
        System.out.println("Vars="+vars+" Heaps="+heaps+" Classes="+classes.size()+" Fields="+fields.size()+" Paths="+paths);
        double log2 = Math.log(2);
        VARBITS = (int) (Math.log(vars+256)/log2 + 1.0);
        HEAPBITS = (int) (Math.log(heaps+256)/log2 + 1.0);
        FIELDBITS = (int) (Math.log(fields.size()+64)/log2 + 2.0);
        CLASSBITS = (int) (Math.log(classes.size()+64)/log2 + 2.0);
        CONTEXTBITS = (int) (Math.log(paths)/log2 + 1.0);
        CONTEXTBITS = Math.min(60, CONTEXTBITS);
        System.out.println("Var bits="+VARBITS+" Heap bits="+HEAPBITS+" Class bits="+CLASSBITS+" Field bits="+FIELDBITS+" Context bits="+CONTEXTBITS);
    }

    public CSPA(CallGraph cg) {
        this.cg = cg;
    }
    
    CallGraph cg;
    Collection roots;
    
    public void initializeBDD(int nodeCount, int cacheSize) {
        bdd = BDDFactory.init(nodeCount, cacheSize);
        
        bdd.setCacheRatio(8);
        bdd.setMaxIncrease(Math.min(nodeCount/4, 2500000));
        bdd.setMaxNodeNum(0);
        
        variableIndexMap = new IndexMap("Variable", 1 << VARBITS);
        heapobjIndexMap = new IndexMap("HeapObj", 1 << HEAPBITS);
        fieldIndexMap = new IndexMap("Field", 1 << FIELDBITS);
        typeIndexMap = new IndexMap("Class", 1 << CLASSBITS);

        domainBits = new int[] {VARBITS, CONTEXTBITS,
                                VARBITS, CONTEXTBITS,
                                FIELDBITS,
                                HEAPBITS, CONTEXTBITS,
                                HEAPBITS, CONTEXTBITS};
        domainSpos = new int[domainBits.length];
        
        long[] domains = new long[domainBits.length];
        for (int i=0; i<domainBits.length; ++i) {
            domains[i] = (1L << domainBits[i]);
        }
        bdd_domains = bdd.extDomain(domains);
        V1o = bdd_domains[0];
        V1c = bdd_domains[1];
        V2o = bdd_domains[2];
        V2c = bdd_domains[3];
        FD = bdd_domains[4];
        H1o = bdd_domains[5];
        H1c = bdd_domains[6];
        H2o = bdd_domains[7];
        H2c = bdd_domains[8];
        T1 = V2o;
        T2 = V1o;
        for (int i=0; i<domainBits.length; ++i) {
            Assert._assert(bdd_domains[i].varNum() == domainBits[i]);
        }
        
        boolean reverseLocal = System.getProperty("bddreverse", "true").equals("true");
        String ordering = System.getProperty("bddordering", "FD_H2cxH2o_V2cxV1cxV2oxV1o_H1cxH1o");
        
        int[] varorder = makeVarOrdering(bdd, domainBits, domainSpos, reverseLocal, ordering);
        if (TRACE_VARORDER) {
            for (int i=0; i<varorder.length; ++i) {
                if (i != 0) 
                    System.out.print(",");
                System.out.print(varorder[i]);
            }
            System.out.println();
        }
        bdd.setVarOrder(varorder);
        bdd.enableReorder();
        
        V1ToV2 = bdd.makePair();
        V1ToV2.set(new BDDDomain[] {V1o, V1c},
                   new BDDDomain[] {V2o, V2c});
        V2ToV1 = bdd.makePair();
        V2ToV1.set(new BDDDomain[] {V2o, V2c},
                   new BDDDomain[] {V1o, V1c});
        H1ToH2 = bdd.makePair();
        H1ToH2.set(new BDDDomain[] {H1o, H1c},
                   new BDDDomain[] {H2o, H2c});
        H2ToH1 = bdd.makePair();
        H2ToH1.set(new BDDDomain[] {H2o, H2c},
                   new BDDDomain[] {H1o, H1c});
        T2ToT1 = bdd.makePair(T2, T1);
        
        V1set = V1o.set();
        //if (CONTEXT_SENSITIVE)
            V1set.andWith(V1c.set());
        V2set = V2o.set();
        //if (CONTEXT_SENSITIVE)
            V2set.andWith(V2c.set());
        FDset = FD.set();
        H1set = H1o.set();
        //if (CONTEXT_SENSITIVE)
            H1set.andWith(H1c.set());
        H2set = H2o.set();
        //if (CONTEXT_SENSITIVE)
            H2set.andWith(H2c.set());
        T1set = T1.set();
        T2set = T2.set();
        H1andFDset = H1set.and(FDset);
        
        reset();
    }

    void reset() {
        aC = bdd.zero();
        vC = bdd.zero();
        cC = bdd.zero();
        typeFilter = bdd.zero();
        g_pointsTo = bdd.zero();
        g_loads = bdd.zero();
        g_stores = bdd.zero();
        g_edgeSet = bdd.zero();
    }

    static int[] makeVarOrdering(BDDFactory bdd, int[] domainBits, int[] domainSpos,
                                 boolean reverseLocal, String ordering) {
        
        int varnum = bdd.varNum();
        
        int[][] localOrders = new int[domainBits.length][];
        for (int i=0; i<localOrders.length; ++i) {
            localOrders[i] = new int[domainBits[i]];
        }
        
        for (int i=0, pos=0; i<domainBits.length; ++i) {
            domainSpos[i] = pos;
            pos += domainBits[i];
            for (int j=0; j<domainBits[i]; ++j) {
                if (reverseLocal) {
                    localOrders[i][j] = domainBits[i] - j - 1;
                } else {
                    localOrders[i][j] = j;
                }
            }
        }
        
        BDDDomain[] doms = new BDDDomain[domainBits.length];
        
        int[] varorder = new int[varnum];
        
        System.out.println("Ordering: "+ordering);
        StringTokenizer st = new StringTokenizer(ordering, "x_", true);
        int numberOfDomains = 0, bitIndex = 0;
        for (int i=0; ; ++i) {
            String s = st.nextToken();
            BDDDomain d;
            if (s.equals("V1o")) d = bdd.getDomain(0);
            else if (s.equals("V1c")) d = bdd.getDomain(1);
            else if (s.equals("V2o")) d = bdd.getDomain(2);
            else if (s.equals("V2c")) d = bdd.getDomain(3);
            else if (s.equals("FD")) d = bdd.getDomain(4);
            else if (s.equals("H1o")) d = bdd.getDomain(5);
            else if (s.equals("H1c")) d = bdd.getDomain(6);
            else if (s.equals("H2o")) d = bdd.getDomain(7);
            else if (s.equals("H2c")) d = bdd.getDomain(8);
            else {
                Assert.UNREACHABLE("bad domain: "+s);
                return null;
            }
            doms[i] = d;
            if (st.hasMoreTokens()) {
                s = st.nextToken();
                if (s.equals("x")) {
                    ++numberOfDomains;
                    continue;
                }
            }
            bitIndex = fillInVarIndices(domainBits, domainSpos,
                                        doms, i-numberOfDomains, numberOfDomains+1,
                                        localOrders, bitIndex, varorder);
            if (!st.hasMoreTokens()) {
                //Collection not_done = new ArrayList(Arrays.asList(bdd_domains));
                //not_done.removeAll(Arrays.asList(doms));
                //Assert._assert(not_done.isEmpty(), not_done.toString());
                break;
            }
            if (s.equals("_")) {
                numberOfDomains = 0;
            } else {
                Assert.UNREACHABLE("bad token: "+s);
                return null;
            }
        }
        
        for (int i=0; i<doms.length; ++i) {
            doms[i] = bdd.getDomain(i);
        }
        int[] outside2inside = new int[varnum];
        getVariableMap(outside2inside, doms);
        
        remapping(varorder, outside2inside);
        
        return varorder;
    }
    
    static int fillInVarIndices(int[] domainBits, int[] domainSpos,
                         BDDDomain[] doms, int domainIndex, int numDomains,
                         int[][] localOrders, int bitIndex, int[] varorder) {
        int maxBits = 0;
        for (int i=0; i<numDomains; ++i) {
            BDDDomain d = doms[domainIndex+i];
            int di = d.getIndex();
            maxBits = Math.max(maxBits, domainBits[di]);
        }
        for (int bitNumber=0; bitNumber<maxBits; ++bitNumber) {
            for (int i=0; i<numDomains; ++i) {
                BDDDomain d = doms[domainIndex+i];
                int di = d.getIndex();
                if (bitNumber < domainBits[di]) {
                    varorder[bitIndex++] = domainSpos[di] + localOrders[di][bitNumber];
                }
            }
        }
        return bitIndex;
    }
    
    static void getVariableMap(int[] map, BDDDomain[] doms) {
        int idx = 0;
        for (int var = 0; var < doms.length; var++) {
            int[] vars = doms[var].vars();
            for (int i = 0; i < vars.length; i++) {
                map[idx++] = vars[i];
            }
        }
    }
    
    /* remap according to a map */
    static void remapping(int[] varorder, int[] maps) {
        int[] varorder2 = new int[varorder.length];
        for (int i = 0; i < varorder.length; i++) {
            varorder2[i] = maps[varorder[i]];
        }
        System.arraycopy(varorder2, 0, varorder, 0, varorder.length);
    }
    
    IndexMap/* Node->index */ variableIndexMap;
    IndexMap/* Node->index */ heapobjIndexMap;
    IndexMap/* jq_Field->index */ fieldIndexMap;
    IndexMap/* jq_Reference->index */ typeIndexMap;

    int getVariableIndex(Node dest) {
        return variableIndexMap.get(dest);
    }
    int getHeapobjIndex(Node site) {
        return heapobjIndexMap.get(site);
    }
    int getFieldIndex(jq_Field f) {
        return fieldIndexMap.get(f);
    }
    int getTypeIndex(jq_Reference f) {
        return typeIndexMap.get(f);
    }
    Node getVariable(int index) {
        return (Node) variableIndexMap.get(index);
    }
    Node getHeapobj(int index) {
        return (Node) heapobjIndexMap.get(index);
    }
    jq_Field getField(int index) {
        return (jq_Field) fieldIndexMap.get(index);
    }
    jq_Reference getType(int index) {
        return (jq_Reference) typeIndexMap.get(index);
    }

    public void addClassType(jq_Reference type) {
        if (type == null) return;
        if (typeIndexMap.contains(type)) return;
        int type_i = getTypeIndex(type);
        if (type instanceof jq_Class) {
            jq_Class k = (jq_Class) type;
            k.prepare();
            jq_Class[] interfaces = k.getInterfaces();
            for (int i=0; i<interfaces.length; ++i) {
                addClassType(interfaces[i]);
            }
            addClassType(k.getSuperclass());
        }
    }

    public void addAllocType(Node site, jq_Reference type) {
        addClassType(type);
        int site_i = getHeapobjIndex(site);
        int type_i = getTypeIndex(type);
        BDD site_bdd = H1o.ithVar(site_i);
        BDD type_bdd = T2.ithVar(type_i);
        type_bdd.andWith(site_bdd);
        if (TRACE_TYPES) System.out.println("Adding alloc type: "+type_bdd.toStringWithDomains());
        aC.orWith(type_bdd);
    }

    public void addVarType(Node var, jq_Reference type) {
        addClassType(type);
        int var_i = getVariableIndex(var);
        int type_i = getTypeIndex(type);
        BDD var_bdd = V1o.ithVar(var_i);
        BDD type_bdd = T1.ithVar(type_i);
        type_bdd.andWith(var_bdd);
        if (TRACE_TYPES) System.out.println("Adding var type: "+type_bdd.toStringWithDomains());
        vC.orWith(type_bdd);
    }
    
    int last_typeIndex;
    
    void calculateTypeHierarchy() {
        int n1=typeIndexMap.size();
        if (TRACE_TYPES) System.out.println(n1-last_typeIndex + " new types");
        for (int i1=0; i1<n1; ++i1) {
            jq_Type t1 = (jq_Type) typeIndexMap.get(i1);
            if (t1 == null) {
                BDD type1_bdd = T1.ithVar(i1);
                BDD type2_bdd = T2.domain();
                type1_bdd.andWith(type2_bdd);
                cC.orWith(type1_bdd);
                continue;
            }
            t1.prepare();
            int i2 = (i1 < last_typeIndex) ? last_typeIndex : 0;
            for ( ; i2<n1; ++i2) {
                jq_Type t2 = (jq_Type) typeIndexMap.get(i2);
                if (t2 == null) {
                    BDD type1_bdd = T1.domain();
                    BDD type2_bdd = T2.ithVar(i2);
                    type1_bdd.andWith(type2_bdd);
                    cC.orWith(type1_bdd);
                    continue;
                }
                t2.prepare();
                if (TypeCheck.isAssignable(t2, t1)) {
                    BDD type1_bdd = T1.ithVar(i1);
                    BDD type2_bdd = T2.ithVar(i2);
                    type1_bdd.andWith(type2_bdd);
                    cC.orWith(type1_bdd);
                }
            }
        }
        last_typeIndex = n1;
    }
    
    public void calculateTypeFilter() {
        calculateTypeHierarchy();
        
        // (T1 x T2) * (H1 x T2) => (T1 x H1)
        BDD assignableTypes = cC.relprod(aC, T2set);
        // (T1 x H1) * (V1 x T1) => (V1 x H1)
        typeFilter = assignableTypes.relprod(vC, T1set);
        assignableTypes.free();
        //cC.free(); vC.free(); aC.free();

        if (false) typeFilter = bdd.one();
    }

    BDDMethodSummary getOrCreateBDDSummary(jq_Method m) {
        if (m.getBytecode() == null) return null;
        ControlFlowGraph cfg = CodeCache.getCode(m);
        MethodSummary ms = MethodSummary.getSummary(cfg);
        BDDMethodSummary bms = getBDDSummary(ms);
        if (bms == null) {
            bddSummaries.put(ms, bms = new BDDMethodSummary(ms));
            bddSummaryList.add(bms);
        }
        return bms;
    }
    
    Map bddSummaries = new HashMap();
    List bddSummaryList = new LinkedList();
    BDDMethodSummary getBDDSummary(MethodSummary ms) {
        BDDMethodSummary result = (BDDMethodSummary) bddSummaries.get(ms);
        return result;
    }
    
    BDDMethodSummary getBDDSummary(jq_Method m) {
        if (m.getBytecode() == null) return null;
        ControlFlowGraph cfg = CodeCache.getCode(m);
        MethodSummary ms = MethodSummary.getSummary(cfg);
        return getBDDSummary(ms);
    }
    
    public ProgramLocation mapCall(ProgramLocation callSite) {
        if (LOADED_CALLGRAPH && callSite instanceof ProgramLocation.QuadProgramLocation) {
            jq_Method m = (jq_Method) callSite.getMethod();
            Map map = CodeCache.getBCMap(m);
            Quad q = ((ProgramLocation.QuadProgramLocation) callSite).getQuad();
            if (q == null) {
                Assert.UNREACHABLE("Error: cannot find call site "+callSite);
            }
            Integer i = (Integer) map.get(q);
            if (i == null) {
                Assert.UNREACHABLE("Error: no mapping for quad "+q);
            }
            int bcIndex = i.intValue();
            callSite = new ProgramLocation.BCProgramLocation(m, bcIndex);
        }
        return callSite;
    }
    
    public Collection getTargetMethods(ProgramLocation callSite) {
        return cg.getTargetMethods(mapCall(callSite));
    }

    BDD g_pointsTo;
    BDD g_edgeSet;
    BDD g_stores;
    BDD g_loads;

    static boolean USE_REPLACE_V2 = true;
    static boolean USE_REPLACE_H1 = false;
    static BDDPairing V1cToV2c, V1cToH1c;

    public void addRelations(MethodSummary ms) {
        BDDMethodSummary bms = this.getBDDSummary(ms);

        if (TRACE_RELATIONS)
            System.out.println("Adding relations for "+ms.getMethod());
        
        long time = System.currentTimeMillis();
        BDD v1c, v2c, h1c;
        v1c = getV1Context(0, bms.n_paths);
        if (USE_REPLACE_V2) {
            if (V1cToV2c == null) V1cToV2c = bdd.makePair(V1c, V2c);
            v2c = v1c.replace(V1cToV2c);
        } else {
            v2c = getV2Context(0, bms.n_paths);
        }
        if (USE_REPLACE_H1) {
            if (V1cToH1c == null) V1cToH1c = bdd.makePair(V1c, H1c);
            h1c = v1c.replace(V1cToH1c);
        } else {
            h1c = getH1Context(0, bms.n_paths);
        }
        time = System.currentTimeMillis() - time;
        if (TRACE_TIMES || time > 500)
            System.out.println("Building context BDD: "+(time/1000.));
        
        time = System.currentTimeMillis();
        
        BDD t1 = bms.m_pointsTo.id();
        t1.andWith(v1c.id());
        t1.andWith(h1c);
        g_pointsTo.orWith(t1);

        if (false) {
            t1 = bms.m_loads.id();
            t1.andWith(v1c.id());
            t1.andWith(v2c.id());
            g_loads.orWith(t1);
            t1 = bms.m_stores.id();
            t1.andWith(v1c);
            t1.andWith(v2c);
            g_stores.orWith(t1);
        } else {
            v1c.andWith(v2c);
            t1 = bms.m_loads.id();
            t1.andWith(v1c.id());
            g_loads.orWith(t1);
            t1 = bms.m_stores.id();
            t1.andWith(v1c);
            g_stores.orWith(t1);
        }
        time = System.currentTimeMillis() - time;
        if (TRACE_TIMES || time > 500)
            System.out.println("Adding relations to global: "+(time/1000.));
        
        bms.dispose();
    }
    
    public void bindCallEdges(MethodSummary caller) {
        if (TRACE_CALLGRAPH) System.out.println("Adding call graph edges for "+caller.getMethod());
        for (Iterator i=caller.getCalls().iterator(); i.hasNext(); ) {
            ProgramLocation mc = (ProgramLocation) i.next();
            for (Iterator j=getTargetMethods(mc).iterator(); j.hasNext(); ) {
                jq_Method target = (jq_Method) j.next();
                if (target.getBytecode() == null) {
                    bindParameters_native(caller, mc);
                    continue;
                }
                ControlFlowGraph cfg = CodeCache.getCode(target);
                MethodSummary callee = MethodSummary.getSummary(cfg);
                bindParameters(caller, mc, callee);
            }
        }
    }
    
    public void bindParameters_native(MethodSummary caller, ProgramLocation mc) {
        // only handle return value for now.
        Object t = caller.getMethod().and_getReturnType();
        if (t instanceof jq_Reference) {
            ReturnValueNode rvn = caller.getRVN(mc);
            if (rvn != null) {
                jq_Reference r = (jq_Reference) t;
                UnknownTypeNode utn = UnknownTypeNode.get(r);
                addGlobalObjectAllocation(utn, utn);
                addAllocType(utn, r);
                addVarType(utn, r);
                addGlobalEdge(rvn, Collections.singleton(utn));
            }
        }
        ThrownExceptionNode ten = caller.getTEN(mc);
        if (ten != null) {
            jq_Reference r = PrimordialClassLoader.getJavaLangThrowable();
            UnknownTypeNode utn = UnknownTypeNode.get(r);
            addGlobalObjectAllocation(utn, utn);
            addAllocType(utn, r);
            addVarType(utn, r);
            addGlobalEdge(ten, Collections.singleton(utn));
        }
    }
    
    public void bindParameters(MethodSummary caller, ProgramLocation mc, MethodSummary callee) {
        if (TRACE_CALLGRAPH)
            System.out.println("Adding call graph edge "+caller.getMethod()+"->"+callee.getMethod());
        BDDMethodSummary caller_s = this.getBDDSummary(caller);
        BDDMethodSummary callee_s = this.getBDDSummary(callee);
        Pair p = new Pair(mapCall(mc), callee.getMethod());
        Range r = (Range) callGraphEdges.get(p);
        if (backEdges.contains(p))
            System.out.println("Back edge: "+p+"="+r);
        if (TRACE_CALLGRAPH)
            System.out.println("Context range "+r);
        BDD context_map;
        // for parameters: V1 in caller matches V2 in callee
        context_map = buildVarContextMap(0, caller_s.n_paths - 1, r.low, r.high);
        for (int i=0; i<mc.getNumParams(); ++i) {
            if (i >= callee.getNumOfParams()) break;
            ParamNode pn = callee.getParamNode(i);
            if (pn == null) continue;
            PassedParameter pp = new PassedParameter(mc, i);
            Set s = caller.getNodesThatCall(pp);
            if (TRACE_EDGES) System.out.println("Adding edges for "+pn);
            addEdge(context_map, pn, s);
        }
        context_map.free();
        ReturnValueNode rvn = caller.getRVN(mc);
        if (rvn != null) {
            Set s = callee.getReturned();
            // for returns: V1 in callee matches V2 in caller
            context_map = buildVarContextMap(r.low, r.high, 0, caller_s.n_paths - 1);
            if (TRACE_EDGES) System.out.println("Adding edges for "+rvn);
            addEdge(context_map, rvn, s);
            context_map.free();
        }
        ThrownExceptionNode ten = caller.getTEN(mc);
        if (ten != null) {
            Set s = callee.getThrown();
            context_map = buildVarContextMap(r.low, r.high, 0, caller_s.n_paths - 1);
            if (TRACE_EDGES) System.out.println("Adding edges for "+ten);
            addEdge(context_map, ten, s);
            context_map.free();
        }
    }

    public void dumpGlobalNodes() {
        System.out.print("g_pointsTo="+g_pointsTo.nodeCount());
        System.out.print(", g_edgeSet="+g_edgeSet.nodeCount());
        System.out.print(", g_loads="+g_loads.nodeCount());
        System.out.println(", g_stores="+g_stores.nodeCount());
    }
    
    public void dumpContextInsensitive() {
        BDD t = g_pointsTo.exist(V1c.set().and(H1c.set()));
        System.out.print("pointsTo (context-insensitive) = ");
        report(t, V1o.set().and(H1o.set()));
        t.free();
    }
    
    public void dumpGlobalSizes() {
        System.out.print("g_pointsTo = ");
        report(g_pointsTo, V1set.and(H1set));
        System.out.print("g_edgeSet = ");
        report(g_edgeSet, V1set.and(V2set));
        System.out.print("g_loads = ");
        report(g_loads, V1set.and(V2set).and(FDset));
        System.out.print("g_stores = ");
        report(g_stores, V1set.and(V2set).and(FDset));
    }
    
    static final void report(BDD bdd, BDD d) {
        System.out.print(bdd.satCount(d));
        System.out.println(" ("+bdd.nodeCount()+" nodes)");
    }
    
    public BDD buildRecursiveMap(long sizeV1, long sizeV2) {
        if (!CONTEXT_SENSITIVE) {
            return bdd.one();
        }
        BDD r;
        if (sizeV1 == sizeV2) {
            r = buildVarContextMap(0, sizeV1 - 1, 0, sizeV1 - 1);
        } else {
            System.out.print("buildRecursiveMap: "+sizeV1+", "+sizeV2+" = ");
            if (sizeV1 > sizeV2) {
                r = buildMod(V1c, V2c, sizeV2);
                r.andWith(V1c.varRange(0, sizeV1));
                if (sizeV1 < 256L) System.out.println(r.toStringWithDomains());
            } else {
                r = buildMod(V2c, V1c, sizeV1);
                r.andWith(V2c.varRange(0, sizeV2));
                if (sizeV2 < 256L) System.out.println(r.toStringWithDomains());
            }
        }
        return r;
    }
    
    public static final boolean MASK = true;
    
    public BDD buildVarContextMap(long startV1, long endV1, long startV2, long endV2) {
        if (!CONTEXT_SENSITIVE) {
            return bdd.one();
        }
        BDD r;
        long sizeV1 = endV1 - startV1;
        long sizeV2 = endV2 - startV2;
        if (sizeV1 < 0L) {
            if (BREAK_RECURSION) {
                r = bdd.zero();
            } else {
                r = V2c.varRange(startV2, endV2);
                r.andWith(V1c.ithVar(0));
            }
        } else if (sizeV2 < 0L) {
            if (BREAK_RECURSION) {
                r = bdd.zero();
            } else {
                r = V1c.varRange(startV1, endV1);
                r.andWith(V2c.ithVar(0));
            }
        } else {
            if (sizeV1 >= sizeV2) {
                r = V1c.buildAdd(V2c, startV2 - startV1);
                if (MASK)
                    r.andWith(V1c.varRange(startV1, endV1));
            } else {
                r = V1c.buildAdd(V2c, startV2 - startV1);
                if (MASK)
                    r.andWith(V2c.varRange(startV2, endV2));
            }
        }
        return r;
    }

    public static BDD buildMod(BDDDomain d1, BDDDomain d2, long val) {
        Assert._assert(d1.varNum() == d2.varNum());

        BDDFactory bdd = d1.getFactory();
        
        BDDBitVector y = bdd.buildVector(d1);
        BDDBitVector z = y.divmod(val, false);
        
        BDDBitVector x = bdd.buildVector(d2);
        BDD result = bdd.one();
        for (int n = 0; n < x.size(); n++) {
            result.andWith(x.getBit(n).biimp(z.getBit(n)));
        }
        x.free(); y.free(); z.free();
        return result;
    }
    
    public void addEdge(BDD context_map, Node dest, Set srcs) {
        //if (TRACE_EDGES) System.out.println(" Context map: "+context_map.toStringWithDomains());
        int dest_i = getVariableIndex(dest);
        BDD dest_bdd = V2o.ithVar(dest_i);
        for (Iterator i=srcs.iterator(); i.hasNext(); ) {
            Node src = (Node) i.next();
            int src_i = getVariableIndex(src);
            BDD src_bdd = V1o.ithVar(src_i);
            src_bdd.andWith(context_map.id());
            src_bdd.andWith(dest_bdd.id());
            if (TRACE_EDGES) System.out.println("Dest="+dest_i+" Src="+src_i);
            //if (TRACE_EDGES) System.out.println(" Adding edge: "+src_bdd.toStringWithDomains());
            g_edgeSet.orWith(src_bdd);
        }
        dest_bdd.free();
    }

    public void solveIncremental() {

        calculateTypeFilter();
        
        BDD oldPointsTo = bdd.zero();
        BDD newPointsTo = g_pointsTo.id();

        BDD fieldPt = bdd.zero();
        BDD storePt = bdd.zero();
        BDD loadAss = bdd.zero();

        // start solving 
        for (int x = 1; ; ++x) {

            if (TRACE_MATCHING) {
                System.out.println("Outer iteration "+x+": ");
            }
            
            // repeat rule (1) in the inner loop
            for (int y = 1; ; ++y) {
                if (TRACE_MATCHING) {
                    System.out.println("Inner iteration "+y+": ");
                }
                if (TRACE_SIZES) {
                    System.out.print("g_pointsTo = ");
                    report(g_pointsTo, V1set.and(V2set));
                    dumpContextInsensitive();
                }
                BDD newPt1 = g_edgeSet.relprod(newPointsTo, V1set);
                newPointsTo.free();
                if (TRACE_SIZES) {
                    System.out.print("newPt1 = ");
                    report(newPt1, V2set.and(H1set));
                }
                BDD newPt2 = newPt1.replace(V2ToV1);
                newPt1.free();
                if (TRACE_SIZES) {
                    System.out.print("newPt2 = ");
                    report(newPt2, V1set.and(H1set));
                }
                newPt2.applyWith(g_pointsTo.id(), BDDFactory.diff);
                if (TRACE_SIZES) {
                    System.out.print("newPt2 (really) = ");
                    report(newPt2, V1set.and(H1set));
                }
                newPt2.andWith(typeFilter.id());
                newPointsTo = newPt2;
                if (TRACE_SIZES) {
                    System.out.print("newPointsTo = ");
                    report(newPointsTo, V1set.and(H1set));
                }
                if (newPointsTo.isZero()) break;
                g_pointsTo.orWith(newPointsTo.id());
            }
            newPointsTo.free();
            newPointsTo = g_pointsTo.apply(oldPointsTo, BDDFactory.diff);

            // apply rule (2)
            BDD tmpRel1 = g_stores.relprod(newPointsTo, V1set); // time-consuming!
            if (TRACE_SIZES) {
                System.out.print("tmpRel1 = ");
                report(tmpRel1, V2set.and(FDset).and(H1set));
            }
            // (V2xFD)xH1
            BDD tmpRel2 = tmpRel1.replace(V2ToV1);
            tmpRel1.free();
            if (TRACE_SIZES) {
                System.out.print("tmpRel2 = ");
                report(tmpRel2, V1set.and(FDset).and(H1set));
            }
            BDD tmpRel3 = tmpRel2.replace(H1ToH2);
            tmpRel2.free();
            if (TRACE_SIZES) {
                System.out.print("tmpRel3 = ");
                report(tmpRel3, V1set.and(FDset).and(H2set));
            }
            // (V1xFD)xH2
            tmpRel3.applyWith(storePt.id(), BDDFactory.diff);
            BDD newStorePt = tmpRel3;
            if (TRACE_SIZES) {
                System.out.print("newStorePt = ");
                report(newStorePt, V1set.and(FDset).and(H2set));
            }
            // cache storePt
            storePt.orWith(newStorePt.id()); // (V1xFD)xH2
            if (TRACE_SIZES) {
                System.out.print("storePt = ");
                report(storePt, V1set.and(FDset).and(H2set));
            }

            BDD newFieldPt = storePt.relprod(newPointsTo, V1set); // time-consuming!
            // (H1xFD)xH2
            newFieldPt.orWith(newStorePt.relprod(oldPointsTo, V1set));
            newStorePt.free();
            oldPointsTo.free();
            // (H1xFD)xH2
            newFieldPt.applyWith(fieldPt.id(), BDDFactory.diff);
            // cache fieldPt
            fieldPt.orWith(newFieldPt.id()); // (H1xFD)xH2
            if (TRACE_SIZES) {
                System.out.print("fieldPt = ");
                report(fieldPt, H1andFDset.and(H2set));
            }

            // apply rule (3)
            BDD tmpRel4 = g_loads.relprod(newPointsTo, V1set); // time-consuming!
            newPointsTo.free();
            // (H1xFD)xV2
            BDD newLoadAss = tmpRel4.apply(loadAss, BDDFactory.diff);
            tmpRel4.free();
            BDD newLoadPt = loadAss.relprod(newFieldPt, H1andFDset);
            newFieldPt.free();
            // V2xH2
            newLoadPt.orWith(newLoadAss.relprod(fieldPt, H1andFDset));
            // V2xH2
            // cache loadAss
            loadAss.orWith(newLoadAss);
            if (TRACE_SIZES) {
                System.out.print("loadAss = ");
                report(loadAss, V2set.and(H2set));
            }

            // update oldPointsTo
            oldPointsTo = g_pointsTo.id();

            // convert new points-to relation to normal type
            BDD tmpRel5 = newLoadPt.replace(V2ToV1);
            newPointsTo = tmpRel5.replace(H2ToH1);
            tmpRel5.free();
            newPointsTo.applyWith(g_pointsTo.id(), BDDFactory.diff);

            // apply typeFilter
            newPointsTo.andWith(typeFilter.id());
            if (newPointsTo.isZero()) break;
            g_pointsTo.orWith(newPointsTo.id());
        }
        
        newPointsTo.free();
        fieldPt.free();
        storePt.free();
        loadAss.free();
    }
    
    public static class Range {
        public long low, high;
        public Range(long l, long h) {
            this.low = l; this.high = h;
        }
        public String toString() {
            return "<"+low+','+high+'>';
        }
    }
    
    HashMap callGraphEdges = new HashMap();
    
    static long max_paths = 0L;
    
    public long countPaths() {
        if (TRACE_NUMBERING) System.out.print("Building and sorting SCCs...");
        Navigator navigator = cg.getNavigator();
        Set sccs = SCComponent.buildSCC(roots, navigator);
        SCCTopSortedGraph graph = SCCTopSortedGraph.topSort(sccs);
        if (TRACE_NUMBERING) System.out.print("done.");
        
        List list = Traversals.reversePostOrder(cg.getNavigator(), cg.getRoots());
        boolean again;
        do {
            again = false;
            for (Iterator i=list.iterator(); i.hasNext(); ) {
                jq_Method m = (jq_Method) i.next();
                SCComponent scc = getSCC(graph, m);
                if (countPaths_helper(m, scc)) {
                    if (TRACE_NUMBERING) System.out.println(m+" changed.");
                    again = true;
                }
            }
        } while (again);
        return max_paths;
    }
    
    SCComponent getSCC(SCCTopSortedGraph graph, Object o) {
        SCComponent scc = graph.getFirst();
        while (scc != null) {
            if (scc.contains(o)) return scc;
            scc = scc.nextTopSort();
        }
        return null;
    }
    
    public void generateBDDSummaries() {
        for (Iterator i=cg.getAllMethods().iterator(); i.hasNext(); ) {
            jq_Method m = (jq_Method) i.next();
            getOrCreateBDDSummary(m);
        }
    }
    
    public long countPaths2() {
        max_paths = 0L;
        
        if (TRACE_NUMBERING) System.out.print("Building and sorting SCCs...");
        Navigator navigator = cg.getNavigator();
        Set sccs = SCComponent.buildSCC(roots, navigator);
        SCCTopSortedGraph graph = SCCTopSortedGraph.topSort(sccs);
        if (TRACE_NUMBERING) System.out.print("done.");
        
        SCComponent scc = graph.getFirst();
        while (scc != null) {
            initializeSccMap(scc);
            scc = scc.nextTopSort();
        }
            
        /* Walk through SCCs in forward order. */
        scc = graph.getFirst();
        Assert._assert(scc.prevLength() == 0);
        while (scc != null) {
            /* Assign a number for each SCC. */
            if (TRACE_NUMBERING)
                System.out.println("Visiting SCC"+scc.getId()+(scc.isLoop()?" (loop)":" (non-loop)"));
            long total = 0L;
            countNumberOfEdges(scc);
            for (Iterator i=Arrays.asList(scc.prev()).iterator(); i.hasNext(); ) {
                SCComponent pred = (SCComponent) i.next();
                Pair edge = new Pair(pred, scc);
                //System.out.println("Visiting edge SCC"+pred.getId()+" to SCC"+scc.getId());
                long nedges = ((long[]) sccEdgeCounts.get(edge))[0];
                Range r = (Range) sccNumbering.get(pred);
                long newtotal = total + nedges * (r.high+1L);
                if (newtotal < (1L << CONTEXTBITS) && newtotal >= 0L) {
                    total = newtotal;
                } else {
                    // overflow of bits for context.
                    System.out.println("Overflow of bits for SCC"+pred.getId()+" to SCC"+scc.getId()+": "+newtotal);
                }
            }
            if (total == 0L) total = 1L;
            Range r = new Range(total, total-1L);
            if (TRACE_NUMBERING)
                System.out.println("Paths to SCC"+scc.getId()+(scc.isLoop()?" (loop)":" (non-loop)")+"="+total);
            sccNumbering.put(scc, r);
            max_paths = Math.max(max_paths, total);
            scc = scc.nextTopSort();
        }
        scc = graph.getFirst();
        while (scc != null) {
            addEdges(scc);
            scc = scc.nextTopSort();
        }
        scc = graph.getFirst();
        while (scc != null) {
            Range r = (Range) sccNumbering.get(scc);
            if (TRACE_NUMBERING) System.out.println("Range for SCC"+scc.getId()+(scc.isLoop()?" (loop)":" (non-loop)")+"="+r);
            if (r.low != 0L) {
                Assert.UNREACHABLE("SCC"+scc.getId()+" Range="+r);
            }
            scc = scc.nextTopSort();
        }
        return max_paths;
    }
        
    HashSet backEdges = new HashSet();
    
    static HashMap sccNumbering = new HashMap();
    static HashMap methodToScc = new HashMap();
    
    public static void initializeSccMap(SCComponent scc1) {
        Object[] nodes1 = scc1.nodes();
        for (int i=0; i<nodes1.length; ++i) {
            jq_Method caller = (jq_Method) nodes1[i];
            methodToScc.put(caller, scc1);
        }
    }
    
    static Map sccEdges = new HashMap();
    static Map sccEdgeCounts = new HashMap();
    
    public void countNumberOfEdges(SCComponent scc1) {
        Object[] nodes1 = scc1.nodes();
        long total = 0L;
        for (int i=0; i<nodes1.length; ++i) {
            jq_Method caller = (jq_Method) nodes1[i];
            BDDMethodSummary ms2 = getOrCreateBDDSummary(caller);
            for (Iterator k=cg.getCallSites(caller).iterator(); k.hasNext(); ) {
                ProgramLocation mc = (ProgramLocation) k.next();
                Assert._assert(mc == mapCall(mc));
                Collection targetMethods = getTargetMethods(mc);
                for (Iterator j=targetMethods.iterator(); j.hasNext(); ) {
                    jq_Method callee = (jq_Method) j.next();
                    SCComponent scc2 = (SCComponent) methodToScc.get(callee);
                    Pair edge = new Pair(scc1, scc2);
                    if (TRACE_NUMBERING) System.out.println("Edge SCC"+scc1.getId()+" to SCC"+scc2.getId()+": "+mc);
                    long[] value = (long[]) sccEdgeCounts.get(edge);
                    if (value == null) sccEdgeCounts.put(edge, value = new long[] {1L});
                    else value[0]++;
                    HashSet calls = (HashSet) sccEdges.get(edge);
                    if (calls == null) sccEdges.put(edge, calls = new HashSet());
                    calls.add(new Pair(mc, callee));
                }
            }
        }
    }
    
    public static void countNumberOfEdges2(CallGraph cg, SCComponent scc1) {
        Object[] nodes1 = scc1.nodes();
        long total = 0L;
        for (int i=0; i<nodes1.length; ++i) {
            jq_Method caller = (jq_Method) nodes1[i];
            for (Iterator k=cg.getCallSites(caller).iterator(); k.hasNext(); ) {
                ProgramLocation mc = (ProgramLocation) k.next();
                Collection targetMethods = cg.getTargetMethods(mc);
                for (Iterator j=targetMethods.iterator(); j.hasNext(); ) {
                    jq_Method callee = (jq_Method) j.next();
                    SCComponent scc2 = (SCComponent) methodToScc.get(callee);
                    Pair edge = new Pair(scc1, scc2);
                    if (TRACE_NUMBERING) System.out.println("Edge SCC"+scc1.getId()+" to SCC"+scc2.getId()+": "+mc);
                    long[] value = (long[]) sccEdgeCounts.get(edge);
                    if (value == null) sccEdgeCounts.put(edge, value = new long[] {1L});
                    else value[0]++;
                    HashSet calls = (HashSet) sccEdges.get(edge);
                    if (calls == null) sccEdges.put(edge, calls = new HashSet());
                    calls.add(new Pair(mc, callee));
                }
            }
        }
    }
    
    public void addEdges(SCComponent scc1) {
        if (TRACE_NUMBERING) System.out.println("Adding edges SCC"+scc1.getId());
        Object[] nodes1 = scc1.nodes();
        Range r1 = (Range) sccNumbering.get(scc1);
        if (scc1.prevLength() == 0) {
            if (TRACE_NUMBERING) System.out.println("SCC"+scc1.getId()+" is in the root set");
            Assert._assert(r1.low == 1L && r1.high == 0L);
            r1.low = 0L;
        }
        for (int i=0; i<nodes1.length; ++i) {
            jq_Method caller = (jq_Method) nodes1[i];
            BDDMethodSummary ms1 = getOrCreateBDDSummary(caller);
            if (ms1 != null) {
                ms1.n_paths = r1.high+1L;
                if (TRACE_NUMBERING) System.out.println("Paths to SCC"+scc1.getId()+" "+caller+"="+ms1.n_paths);
            }
        }
        if (scc1.isLoop()) {
            Set internalCalls = (Set) sccEdges.get(new Pair(scc1, scc1));
            for (Iterator i=internalCalls.iterator(); i.hasNext(); ) {
                Pair p = (Pair) i.next();
                ProgramLocation mc = (ProgramLocation) p.left;
                Assert._assert(mc == mapCall(mc));
                jq_Method callee = (jq_Method) p.right;
                BDDMethodSummary ms2 = getOrCreateBDDSummary(callee);
                if (ms2 != null) {
                    ms2.n_paths = r1.high+1L;
                    if (TRACE_NUMBERING) System.out.println("Paths to SCC"+scc1.getId()+" "+callee+"="+ms2.n_paths);
                }
                Assert._assert(scc1.contains(callee));
                Pair edge = new Pair(mc, callee);
                if (TRACE_NUMBERING) System.out.println("Range for "+edge+" = "+r1+" "+Strings.hex(r1));
                callGraphEdges.put(edge, r1);
            }
        }
        for (Iterator i=Arrays.asList(scc1.next()).iterator(); i.hasNext(); ) {
            SCComponent scc2 = (SCComponent) i.next();
            Range r2 = (Range) sccNumbering.get(scc2);
            Set calls = (Set) sccEdges.get(new Pair(scc1, scc2));
            for (Iterator k=calls.iterator(); k.hasNext(); ) {
                Pair p = (Pair) k.next();
                ProgramLocation mc = (ProgramLocation) p.left;
                Assert._assert(mc == mapCall(mc));
                jq_Method callee = (jq_Method) p.right;
                BDDMethodSummary ms2 = getOrCreateBDDSummary(callee);
                if (ms2 != null) {
                    ms2.n_paths = r2.high+1L;
                    if (TRACE_NUMBERING) System.out.println("Paths to SCC"+scc2.getId()+" "+callee+"="+ms2.n_paths);
                }
                Assert._assert(scc2.contains(callee));
                // external call. update internal object and make new object.
                long newlow = r2.low - (r1.high + 1L);
                if (newlow >= 0L) {
                    r2.low = newlow;
                } else {
                    // loss of context due to not enough bits.
                    if (TRACE_NUMBERING) System.out.println("Loss of context between SCC"+scc1.getId()+" = "+r1+" and SCC"+scc2.getId()+" = "+r2+", "+newlow);
                    r2.low = newlow = 0L;
                }
                if (TRACE_NUMBERING) System.out.println("External call!  New range for SCC"+scc2.getId()+" = "+r2);
                Range r3 = new Range(newlow, newlow + r1.high);
                Pair edge = new Pair(mc, callee);
                if (TRACE_NUMBERING) System.out.println("Range for "+edge+" = "+r3+" "+Strings.hex(r3));
                callGraphEdges.put(edge, r3);
            }
        }
    }
    
    public static long countPaths3(CallGraph cg) {
        max_paths = 0L;
        
        int max_scc = 0;
        int num_scc = 0;
        
        if (TRACE_NUMBERING) System.out.print("Building and sorting SCCs...");
        Navigator navigator = cg.getNavigator();
        Set sccs = SCComponent.buildSCC(cg.getRoots(), navigator);
        SCCTopSortedGraph graph = SCCTopSortedGraph.topSort(sccs);
        if (TRACE_NUMBERING) System.out.print("done.");
        
        SCComponent scc = graph.getFirst();
        while (scc != null) {
            initializeSccMap(scc);
            max_scc = Math.max(scc.getId(), max_scc);
            scc = scc.nextTopSort();
            ++num_scc;
        }
        System.out.println("Max SCC="+max_scc+", Num SCC="+num_scc);
        
        /* Walk through SCCs in forward order. */
        scc = graph.getFirst();
        Assert._assert(scc.prevLength() == 0);
        while (scc != null) {
            /* Assign a number for each SCC. */
            if (TRACE_NUMBERING)
                System.out.println("Visiting SCC"+scc.getId()+(scc.isLoop()?" (loop)":" (non-loop)"));
            long total = 0L;
            countNumberOfEdges2(cg, scc);
            for (Iterator i=Arrays.asList(scc.prev()).iterator(); i.hasNext(); ) {
                SCComponent pred = (SCComponent) i.next();
                Pair edge = new Pair(pred, scc);
                //System.out.println("Visiting edge SCC"+pred.getId()+" to SCC"+scc.getId());
                long nedges = ((long[]) sccEdgeCounts.get(edge))[0];
                Range r = (Range) sccNumbering.get(pred);
                long newtotal = total + nedges * (r.high+1L);
                total = newtotal;
            }
            if (total == 0L) total = 1L;
            Range r = new Range(total, total-1L);
            if (TRACE_NUMBERING || total > max_paths)
                System.out.println("Paths to SCC"+scc.getId()+(scc.isLoop()?" (loop)":" (non-loop)")+"="+total);
            sccNumbering.put(scc, r);
            max_paths = Math.max(max_paths, total);
            scc = scc.nextTopSort();
        }
        
        return max_paths;
    }
    
    public boolean countPaths_helper(jq_Method callee, SCComponent scc) {
        BDDMethodSummary ms = getOrCreateBDDSummary(callee);
        if (ms == null) {
            return false;
        }
        boolean already_visited = ms.n_paths != 0L;
        long myPaths = 0L, maxPaths = 0L;
        boolean change = false;
        // iterate over the callers to find the number of paths to this method.
        for (Iterator j=cg.getCallers(callee).iterator(); j.hasNext(); ) {
            jq_Method caller = (jq_Method) j.next();
            BDDMethodSummary ms2 = getOrCreateBDDSummary(caller);
            if (ms2 == null) {
                continue;
            }
            for (Iterator k=cg.getCallSites(caller).iterator(); k.hasNext(); ) {
                ProgramLocation mc = (ProgramLocation) k.next();
                Assert._assert(mc == mapCall(mc));
                if (getTargetMethods(mc).contains(callee)) {
                    Pair edge = new Pair(mc, callee);
                    Range r = (Range) callGraphEdges.get(edge);
                    if (r == null) {
                        // never visited this edge before.
                        Assert._assert(!already_visited);
                        if (scc.isLoop()) {
                            r = (Range) sccNumbering.get(scc);
                            if (r == null) r = new Range(0, -1L);
                            if (!scc.contains(caller)) {
                                // external edge.
                                Assert._assert(ms2.n_paths > 0L);
                                r.high += ms2.n_paths;
                            } else {
                                // internal edge, use the shared range.
                            }
                        } else {
                            if (isCallInteresting(edge, myPaths, ms2.n_paths)) {
                                r = new Range(myPaths, myPaths + ms2.n_paths - 1L);
                                myPaths = r.high + 1;
                            } else {
                                r = new Range(0, ms2.n_paths - 1L);
                            }
                        }
                        callGraphEdges.put(edge, r);
                        if (ms2.n_paths == 0L) {
                            //System.out.println("Back edge "+edge);
                            backEdges.add(edge);
                            change = true;
                        }
                        maxPaths = Math.max(maxPaths, r.high + 1);
                    } else {
                        if (false) {
                            // edge has been visited before (loop)
                            long new_paths = Math.max(r.high, ms2.n_paths-1);
                            change |= r.high != new_paths;
                            if (TRACE_NUMBERING && r.high != new_paths)
                                System.out.println(edge+" updated, old high = "+r.high+" new high = "+new_paths);
                            Assert._assert(new_paths <= max_paths);
                            r.high = new_paths;
                        }
                    }
                    if (TRACE_NUMBERING)
                        System.out.println(edge+": "+r);
                }
            }
        }
        if (maxPaths == 0L) maxPaths = 1L;
        ms.n_paths = maxPaths;
        if (maxPaths > max_paths) {
            System.out.println("New max paths: "+callee+"="+maxPaths);
        }
        max_paths = Math.max(max_paths, maxPaths);
        return change;
    }
    
    public boolean isCallInteresting(Pair p, long callee_paths, long caller_paths) {
        if (callee_paths + caller_paths >= (1L << CONTEXTBITS))
            return false;
        return true;
    }
    
    public class BDDMethodSummary {
        
        /** The method summary that we correspond to. */
        MethodSummary ms;
        
        /** The number of paths that reach this method. */
        long n_paths;
        
        /** BDD representing all of the variables in this method and its callees.  For escape analysis. */
        BDD vars; // V1c
        int lowVarIndex, highVarIndex;
        int lowHeapIndex, highHeapIndex;
        
        BDD m_pointsTo;     // V1 x H1
        BDD m_stores;       // V1 x (V2 x FD) 
        BDD m_loads;        // (V1 x FD) x V2
        
        BDDMethodSummary(MethodSummary ms) {
            this.ms = ms;
            lowVarIndex = variableIndexMap.size();
            lowHeapIndex = heapobjIndexMap.size();
            reset();
            computeInitial();
            highVarIndex = variableIndexMap.size() - 1;
            highHeapIndex = heapobjIndexMap.size() - 1;
        }
        
        void reset() {
            // initialize relations to zero.
            m_pointsTo = bdd.zero();
            m_stores = bdd.zero();
            m_loads = bdd.zero();
        }
        
        void reportSize() {
            System.out.print("pointsTo = ");
            report(m_pointsTo, V1o.set().and(H1o.set()));
            System.out.print("stores = ");
            report(m_stores, V1o.set().and(FDset).and(V2o.set()));
            System.out.print("loads = ");
            report(m_loads, V1o.set().and(FDset).and(V2o.set()));
        }

        void dispose() {
            m_pointsTo.free();
            m_stores.free();
            m_loads.free();
        }
        
        void computeInitial() {
            long time;
            
            time = System.currentTimeMillis();
            // add edges for all local stuff.
            for (Iterator i=ms.nodeIterator(); i.hasNext(); ) {
                Node n = (Node) i.next();
                handleNode(n);
            }
            time = System.currentTimeMillis() - time;
            if (TRACE_TIMES || time > 400) System.out.println("Converting "+ms.getMethod().getName()+"() to BDD sets: "+(time/1000.));
            
        }
        
        public void handleNode(Node n) {
            
            Iterator j;
            j = n.getEdges().iterator();
            while (j.hasNext()) {
                Map.Entry e = (Map.Entry) j.next();
                jq_Field f = (jq_Field) e.getKey();
                Object o = e.getValue();
                // n.f = o
                if (o instanceof Set) {
                    addStore(n, f, (Set) o);
                } else {
                    addStore(n, f, Collections.singleton(o));
                }
            }
            j = n.getAccessPathEdges().iterator();
            while (j.hasNext()) {
                Map.Entry e = (Map.Entry)j.next();
                jq_Field f = (jq_Field)e.getKey();
                Object o = e.getValue();
                // o = n.f
                if (o instanceof Set) {
                    addLoad((Set) o, n, f);
                } else {
                    addLoad(Collections.singleton(o), n, f);
                }
            }
            if (n instanceof ConcreteTypeNode) {
                ConcreteTypeNode ctn = (ConcreteTypeNode) n;
                addObjectAllocation(ctn, ctn);
                addAllocType(ctn, (jq_Reference) ctn.getDeclaredType());
            } else if (n instanceof UnknownTypeNode) {
                UnknownTypeNode utn = (UnknownTypeNode) n;
                addObjectAllocation(utn, utn);
                addAllocType(utn, (jq_Reference) utn.getDeclaredType());
            }
            if (n instanceof ParamNode ||
                ms.getReturned().contains(n) ||
                ms.getThrown().contains(n)) {
                addUpwardEscapeNode(n);
            }
            if (n instanceof ReturnedNode ||
                n.getPassedParameters() != null) {
                addDownwardEscapeNode(n);
            }
            if (n instanceof GlobalNode) {
                addGlobalEdge(GlobalNode.GLOBAL, Collections.singleton(n));
                addGlobalEdge(n, Collections.singleton(GlobalNode.GLOBAL));
                addVarType(n, PrimordialClassLoader.getJavaLangObject());
            } else {
                addVarType(n, (jq_Reference) n.getDeclaredType());
            }
        }
        
        public void addObjectAllocation(Node dest, Node site) {
            int dest_i = getVariableIndex(dest);
            int site_i = getHeapobjIndex(site);
            BDD dest_bdd = V1o.ithVar(dest_i);
            BDD site_bdd = H1o.ithVar(site_i);
            dest_bdd.andWith(site_bdd);
            m_pointsTo.orWith(dest_bdd);
        }

        public void addLoad(Set dests, Node base, jq_Field f) {
            int base_i = getVariableIndex(base);
            int f_i = getFieldIndex(f);
            BDD base_bdd = V1o.ithVar(base_i);
            BDD f_bdd = FD.ithVar(f_i);
            for (Iterator i=dests.iterator(); i.hasNext(); ) {
                FieldNode dest = (FieldNode) i.next();
                int dest_i = getVariableIndex(dest);
                BDD dest_bdd = V2o.ithVar(dest_i);
                dest_bdd.andWith(f_bdd.id());
                dest_bdd.andWith(base_bdd.id());
                m_loads.orWith(dest_bdd);
            }
            base_bdd.free(); f_bdd.free();
        }
    
        public void addStore(Node base, jq_Field f, Set srcs) {
            int base_i = getVariableIndex(base);
            int f_i = getFieldIndex(f);
            BDD base_bdd = V2o.ithVar(base_i);
            BDD f_bdd = FD.ithVar(f_i);
            for (Iterator i=srcs.iterator(); i.hasNext(); ) {
                Node src = (Node) i.next();
                int src_i = getVariableIndex(src);
                BDD src_bdd = V1o.ithVar(src_i);
                src_bdd.andWith(f_bdd.id());
                src_bdd.andWith(base_bdd.id());
                m_stores.orWith(src_bdd);
            }
            base_bdd.free(); f_bdd.free();
        }
        
        public void addUpwardEscapeNode(Node n) {
            int n_i = getVariableIndex(n);
        }
        
        public void addDownwardEscapeNode(Node n) {
            int n_i = getVariableIndex(n);
        }
        
        public String toString() {
            StringBuffer sb = new StringBuffer();
            sb.append("BDD Summary for ");
            sb.append(ms.getMethod());
            sb.append(':');
            sb.append(Strings.lineSep);
            sb.append("Loads=");
            sb.append(m_loads.toStringWithDomains());
            sb.append(Strings.lineSep);
            sb.append("Stores=");
            sb.append(m_stores.toStringWithDomains());
            sb.append(Strings.lineSep);
            sb.append("Points-to=");
            sb.append(m_pointsTo.toStringWithDomains());
            sb.append(Strings.lineSep);
            return sb.toString();
        }
    }
    
    public static class IndexMap {
        private final String name;
        private final HashMap hash;
        private final Object[] list;
        private int index;
        
        public IndexMap(String name, int maxIndex) {
            this.name = name;
            hash = new HashMap();
            list = new Object[maxIndex];
            index = -1;
        }
        
        public int get(Object o) {
            Integer i = (Integer) hash.get(o);
            if (i == null) {
                int j = ++index;
                while (list[j] != null)
                    ++j;
                list[j] = o;
                hash.put(o, i = new Integer(j));
                if (TRACE_MAPS) System.out.println(this+"["+j+"] = "+o);
            }
            return i.intValue();
        }
        
        public Object get(int i) {
            return list[i];
        }
        
        public boolean contains(Object o) {
            return hash.containsKey(o);
        }
        
        public int size() {
            return index+1;
        }
        
        public String toString() {
            return name;
        }
        
        public Iterator iterator() {
            return new Iterator() {
                int foo = -1;

                public void remove() {
                    throw new UnsupportedOperationException();
                }

                public boolean hasNext() {
                    return foo < index;
                }

                public Object next() {
                    if (!hasNext()) throw new java.util.NoSuchElementException();
                    return list[++foo];
                }
            };
        }
        
    }

    public void goForIt() {
        List list = Traversals.reversePostOrder(cg.getNavigator(), cg.getRoots());
        for (Iterator i=list.iterator(); i.hasNext(); ) {
            jq_Method o = (jq_Method) i.next();
            if (o.getBytecode() == null) {
                continue;
            }
            ControlFlowGraph cfg = CodeCache.getCode(o);
            MethodSummary ms = MethodSummary.getSummary(cfg);
            addRelations(ms);
            bindCallEdges(ms);
        }
    }

    public static boolean TRACE_ESCAPE = false;

    public void escapeAnalysis() {
        
        BDD escapingLocations = bdd.zero();
        
        BDD myPointsTo;
        myPointsTo = g_pointsTo.exist(V1c.set().and(H1c.set()));
        
        List order = Traversals.postOrder(cg.getNavigator(), cg.getRoots());
        for (Iterator i=order.iterator(); i.hasNext(); ) {
            jq_Method m = (jq_Method) i.next();
            BDDMethodSummary bms = getOrCreateBDDSummary(m);
            if (bms == null) continue;
            BDD range;
            SCComponent scc = (SCComponent) methodToScc.get(m);
            if (scc.isLoop()) {
                bms.vars = bdd.zero();
            } else {
                bms.vars = V1o.varRange(bms.lowVarIndex, bms.highVarIndex);
                for (Iterator j=cg.getCallees(m).iterator(); j.hasNext(); ) {
                    jq_Method callee = (jq_Method) j.next();
                    BDDMethodSummary bms2 = getOrCreateBDDSummary(callee);
                    if (bms2 == null) continue;
                    bms.vars.orWith(bms2.vars.id());
                }
            }
            HashMap concreteNodes = new HashMap();
            MethodSummary ms = bms.ms;
            for (Iterator j=ms.nodeIterator(); j.hasNext(); ) {
                Node o = (Node) j.next();
                if (o instanceof ConcreteTypeNode) {
                    ConcreteTypeNode ctn = (ConcreteTypeNode) o;
                    concreteNodes.put(ctn.getQuad(), ctn);
                }
                boolean bad = false;
                if (o.getEscapes()) {
                    if (TRACE_ESCAPE) System.out.println(o+" escapes, bad");
                    bad = true;
                } else if (cg.getRoots().contains(m) && ms.getThrown().contains(o)) {
                    if (TRACE_ESCAPE) System.out.println(o+" is thrown from root set, bad");
                    bad = true;
                } else {
                    Set passedParams = o.getPassedParameters();
                    if (passedParams != null) {
                        outer:
                        for (Iterator k=passedParams.iterator(); k.hasNext(); ) {
                            PassedParameter pp = (PassedParameter) k.next();
                            ProgramLocation mc = pp.getCall();
                            for (Iterator a=getTargetMethods(mc).iterator(); a.hasNext(); ) {
                                jq_Method m2 = (jq_Method) a.next();
                                if (m2.getBytecode() == null) {
                                    if (TRACE_ESCAPE) System.out.println(o+" is passed into a native method, bad");
                                    bad = true;
                                    break outer;
                                }
                            }
                        }
                    }
                }
                if (bad) {
                    int v_i = getVariableIndex(o);
                    bms.vars.and(V1o.ithVar(v_i).not());
                }
            }
            if (TRACE_ESCAPE) System.out.println("Non-escaping locations for "+m+" = "+bms.vars.toStringWithDomains());
            ControlFlowGraph cfg = CodeCache.getCode(m);
            boolean trivial = false;
            for (QuadIterator j=new QuadIterator(cfg); j.hasNext(); ) {
                Quad q = j.nextQuad();
                if (q.getOperator() instanceof Operator.New ||
                    q.getOperator() instanceof Operator.NewArray) {
                    ConcreteTypeNode ctn = (ConcreteTypeNode) concreteNodes.get(q);
                    if (ctn == null) {
                        //trivial = true;
                        trivial = q.getOperator() instanceof Operator.New;
                        System.out.println(cfg.getMethod()+": "+q+" trivially doesn't escape.");
                    } else {
                        int h_i = getHeapobjIndex(ctn);
                        BDD h = H1o.ithVar(h_i);
                        if (TRACE_ESCAPE) {
                            System.out.println("Heap location: "+h.toStringWithDomains()+" = "+ctn);
                            System.out.println("Pointed to by: "+myPointsTo.restrict(h).toStringWithDomains());
                        }
                        h.andWith(bms.vars.not());
                        escapingLocations.orWith(h);
                    }
                }
            }
            if (trivial) {
                System.out.println(cfg.fullDump());
            }
        }
        BDD escapingHeap = escapingLocations.relprod(myPointsTo, V1set);
        System.out.println("Escaping heap: "+escapingHeap.satCount(H1o.set()));
        //System.out.println("Escaping heap: "+escapingHeap.toStringWithDomains());
        BDD capturedHeap = escapingHeap.not();
        capturedHeap.andWith(H1o.varRange(0, heapobjIndexMap.size()-1));
        System.out.println("Captured heap: "+capturedHeap.satCount(H1o.set()));
        
        int capturedSites = 0;
        int escapedSites = 0;
        long capturedSize = 0L;
        long escapedSize = 0L;
        
        for (Iterator i=heapobjIndexMap.iterator(); i.hasNext(); ) {
            Node n = (Node) i.next();
            int ndex = heapobjIndexMap.get(n);
            if (n instanceof ConcreteTypeNode) {
                ConcreteTypeNode ctn = (ConcreteTypeNode) n;
                jq_Reference t = (jq_Reference) ctn.getDeclaredType();
                int size = 0;
                if (t instanceof jq_Class)
                    size = ((jq_Class) t).getInstanceSize();
                else
                    continue;
                BDD bdd = capturedHeap.and(H1o.ithVar(ndex));
                if (capturedHeap.and(H1o.ithVar(ndex)).isZero()) {
                    // not captured.
                    if (TRACE_ESCAPE) System.out.println("Escaped: "+n);
                    escapedSites ++;
                    escapedSize += size;
                } else {
                    // captured.
                    if (TRACE_ESCAPE) System.out.println("Captured: "+n);
                    capturedSites ++;
                    capturedSize += size;
                }
            }
        }
        System.out.println("Captured sites = "+capturedSites+", "+capturedSize+" bytes.");
        System.out.println("Escaped sites = "+escapedSites+", "+escapedSize+" bytes.");
    }

    /*
    void dumpResults(String filename) throws IOException {
        PrintWriter out = new PrintWriter(new FileWriter(filename));
        int j=0;
        for (Iterator i=this.variableIndexMap.iterator(); i.hasNext(); ++j) {
            Node n = (Node) i.next();
            Assert._assert(this.variableIndexMap.get(n) == j);
            out.print("VARIABLE ");
            n.print(out);
            out.println();
        }
        j=0;
        for (Iterator i=this.heapobjIndexMap.iterator(); i.hasNext(); ++j) {
            Node n = (Node) i.next();
            Assert._assert(this.heapobjIndexMap.get(n) == j);
            out.print("HEAPOBJ ");
            n.print(out);
            out.println();
        }
    }
    */

    BDD getAllHeapOfType(jq_Reference type) {
        if (false) {
            int j=0;
            BDD result = bdd.zero();
            for (Iterator i=heapobjIndexMap.iterator(); i.hasNext(); ++j) {
                Node n = (Node) i.next();
                Assert._assert(this.heapobjIndexMap.get(n) == j);
                if (n.getDeclaredType() == type)
                    result.orWith(V1o.ithVar(j));
            }
            return result;
        } else {
            int i = typeIndexMap.get(type);
            BDD a = T2.ithVar(i);
            BDD result = aC.restrict(a);
            a.free();
            return result;
        }
    }

    BDD getTypesOf(Node variable) {
        BDD context = V1c.set();
        context.andWith(H1c.set());
        BDD ci_pointsTo = g_pointsTo.exist(context);
        context.free();
        int i = variableIndexMap.get(variable);
        BDD a = V1o.ithVar(i);
        BDD heapObjs = ci_pointsTo.restrict(a);
        a.free();
        BDD result = ci_pointsTo.relprod(heapObjs, H1o.set());
        heapObjs.free();
        return result;
    }
    
    Map postOrderNumbering;
    
    void numberPostOrder() {
        Navigator navigator = cg.getNavigator();
        Set sccs = SCComponent.buildSCC(roots, navigator);
        SCCTopSortedGraph graph = SCCTopSortedGraph.topSort(sccs);
        
        SCComponent scc = graph.getFirst();
        int j = 0;
        while (scc != null) {
            postOrderNumbering.put(scc, new Integer(j++));
        }
    }
    
    final PostOrderComparator po_comparator = new PostOrderComparator();
    
    public class PostOrderComparator implements Comparator {

        private PostOrderComparator() {}
        
        /* (non-Javadoc)
         * @see java.util.Comparator#compare(java.lang.Object, java.lang.Object)
         */
        public int compare(Object arg0, Object arg1) {
            if (arg0 == arg1) return 0;
            int a = ((Integer) postOrderNumbering.get(arg0)).intValue();
            int b = ((Integer) postOrderNumbering.get(arg1)).intValue();
            if (a < b) return -1;
            Assert._assert(a > b);
            return 1;
        }
    }
    
    public static class CallString {
        
    }
    
    List getContext(jq_Method callee, BDD context) {
        // visit methods in post order.
        numberPostOrder();
        SortedSet worklist = new TreeSet(po_comparator);
        worklist.add(callee);
        
        Map contexts = new HashMap();
        contexts.put(callee, context);
        
        while (!worklist.isEmpty()) {
            
            
            
            BDDMethodSummary callee_s = this.getBDDSummary(callee);
            for (Iterator i=cg.getCallees(callee).iterator(); i.hasNext(); ) {
                jq_Method caller = (jq_Method) i.next();
                BDDMethodSummary caller_s = this.getBDDSummary(caller);
                for (Iterator j=cg.getCallSites(caller).iterator(); j.hasNext(); ) {
                    ProgramLocation call = (ProgramLocation) j.next();
                    Pair p = new Pair(mapCall(call), callee);
                    Range r = (Range) callGraphEdges.get(p);
                    // V1 in callee matches V2 in caller
                    BDD context_map = buildVarContextMap(r.low, r.high, 0, caller_s.n_paths - 1);
                
                }
            }
        }
        return null;
    }

}
