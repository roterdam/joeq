// DoTypeAnalysis.java, created Fri Jan 11 17:13:17 2002 by joewhaley
// Copyright (C) 2001-3 John Whaley <jwhaley@alum.mit.edu>
// Licensed under the terms of the GNU LGPL; see COPYING for details.
package Main;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Set;

import Bootstrap.PrimordialClassLoader;
import Clazz.jq_Class;
import Clazz.jq_InstanceMethod;
import Clazz.jq_StaticMethod;
import Run_Time.TypeCheck;
import Util.Collections.AppendIterator;

/*
 * @author  John Whaley <jwhaley@alum.mit.edu>
 * @version $Id: DoTypeAnalysis.java,v 1.14 2003/05/12 10:05:18 joewhaley Exp $
 */
public abstract class DoTypeAnalysis {
    
    public static void main(String[] args) throws IOException {
	HostedVM.initialize();
        
        Compil3r.BytecodeAnalysis.TypeAnalysis.classesToAnalyze = new HashSet();
        Iterator i = null; String memberName = null;
        jq_Class interfaceCheck = null;
        for (int x=0; x<args.length; ++x) {
            if (args[x].equals("-trace")) {
                Compil3r.BytecodeAnalysis.TypeAnalysis.TypeAnalysisVisitor.ALWAYS_TRACE = true;
                //Compil3r.BytecodeAnalysis.TypeAnalysis.MethodCallSequence.PRINT_MODEL = true;
                Compil3r.BytecodeAnalysis.TypeAnalysis.TRACE_MAIN = true;
                Compil3r.BytecodeAnalysis.TypeAnalysis.TRACE_ITERATION = true;
                Compil3r.BytecodeAnalysis.TypeAnalysis.AnalysisSummary.TRACE_TRIM = true;
            } else
            if (args[x].equals("-dumpsummaries")) {
                Compil3r.BytecodeAnalysis.TypeAnalysis.DUMP_SUMMARY = true;
            } else
            if (args[x].equals("-i")) {
                String interfaceName = args[++x];
                if (interfaceName.endsWith(".class")) interfaceName = interfaceName.substring(0, interfaceName.length()-6);
                String interfaceDesc = "L"+interfaceName+";";
                interfaceCheck = (jq_Class)PrimordialClassLoader.loader.getOrCreateBSType(interfaceDesc);
                interfaceCheck.prepare();
            } else
            if (args[x].equals("-bypackage")) {
                Compil3r.BytecodeAnalysis.TypeAnalysis.TypeAnalysisVisitor.BY_PACKAGE = true;
            } else
            if (args[x].equals("-maxdepth")) {
                Compil3r.BytecodeAnalysis.TypeAnalysis.TypeAnalysisVisitor.MAX_DEPTH = Integer.parseInt(args[++x]);
            } else
            if (args[x].equals("-full")) {
                Compil3r.BytecodeAnalysis.TypeAnalysis.classesToAnalyze = null;
            } else
            if (args[x].equals("-printmodel")) {
                /*** TEMPORARILY COMMENT OUT UNTIL WE CHECK IN DETOX STUFF. ***
                Compil3r.BytecodeAnalysis.TypeAnalysis.MethodCallSequence.DetoxModelChecker.PRINT_MODEL = true;
                 *** TEMPORARILY COMMENT OUT UNTIL WE CHECK IN DETOX STUFF. ***/
            } else
            if (args[x].equals("-tracemethod")) {
                Compil3r.BytecodeAnalysis.TypeAnalysis.TypeAnalysisVisitor.trace_method_names.add(args[++x]);
            } else
            if (args[x].equals("-buildmodel")) {
                /*** TEMPORARILY COMMENT OUT UNTIL WE CHECK IN DETOX STUFF. ***
                Compil3r.BytecodeAnalysis.TypeAnalysis.MethodCallSequence.modeler =
                    new Compil3r.BytecodeAnalysis.TypeAnalysis.MethodCallSequence.DetoxModelBuilder();
                 *** TEMPORARILY COMMENT OUT UNTIL WE CHECK IN DETOX STUFF. ***/
            } else
            if (args[x].equals("-checkmodel")) {
                /*** TEMPORARILY COMMENT OUT UNTIL WE CHECK IN DETOX STUFF. ***
                Compil3r.BytecodeAnalysis.TypeAnalysis.MethodCallSequence.modeler =
                    new Compil3r.BytecodeAnalysis.TypeAnalysis.MethodCallSequence.DetoxModelChecker();
                 *** TEMPORARILY COMMENT OUT UNTIL WE CHECK IN DETOX STUFF. ***/
            } else
            if (args[x].equals("-file")) {
                BufferedReader br = new BufferedReader(new FileReader(args[++x]));
                LinkedList list = new LinkedList();
                for (;;) {
                    String s = br.readLine();
                    if (s == null) break;
                    if (s.length() == 0) continue;
                    if (s.startsWith("%")) continue;
                    list.add(s);
                }
                i = new AppendIterator(list.iterator(), i);
            } else
            if (args[x].endsWith("*")) {
                i = new AppendIterator(PrimordialClassLoader.loader.listPackage(args[x].substring(0, args[x].length()-1)), i);
            } else {
                int j = args[x].indexOf('.');
                String classname;
                if ((j != -1) && !args[x].endsWith(".class")) {
                    classname = args[x].substring(0, j);
                    memberName = args[x].substring(j+1);
                } else {
                    classname = args[x];
                }
                i = new AppendIterator(Collections.singleton(classname).iterator(), i);
            }
        }
        if (i == null) i = Collections.singleton("jq.class").iterator();
        
        LinkedList classes = new LinkedList();
        while (i.hasNext()) {
            String classname = (String)i.next();
            if (classname.endsWith(".properties")) continue;
            if (classname.endsWith(".class")) classname = classname.substring(0, classname.length()-6);
            String classdesc = "L"+classname+";";
            jq_Class c = (jq_Class)PrimordialClassLoader.loader.getOrCreateBSType(classdesc);
            c.prepare();
            classes.add(c);
        }
        
        Set classes2 = Compil3r.BytecodeAnalysis.TypeAnalysis.classesToAnalyze;
        if (classes2 != null) {
            classes2.addAll(classes);
        }
        System.out.println("Analyzing these "+classes.size()+" classes: "+classes);
        
        i = classes.iterator();
        while (i.hasNext()) {
            jq_Class c = (jq_Class)i.next();
            if (interfaceCheck != null) {
                if (!TypeCheck.isAssignable(c, interfaceCheck)) {
                    System.out.println(c+" does not implement "+interfaceCheck+", skipping.");
                    continue;
                }
            }
            doClass(System.out, c, memberName);
        }
        try {
            Compil3r.BytecodeAnalysis.TypeAnalysis.dump();
        } catch (java.io.IOException x) { x.printStackTrace(); }
    }

    public static void doClass(PrintStream out, jq_Class c, String memberName) {
        System.out.println("Loading "+c+"...");
        c.load();
        System.out.println("Verifying "+c+"...");
        c.verify();
        System.out.println("Preparing "+c+"...");
        c.prepare();
        System.out.println("Initializing static fields of "+c+"...");
        c.sf_initialize();
        //System.out.println("Compiling "+c+"...");
        //c.compile();
        
        Iterator it;
        for(it = Arrays.asList(c.getDeclaredStaticMethods()).iterator();
            it.hasNext(); ) {
            jq_StaticMethod m = (jq_StaticMethod)it.next();
            if (m.getBytecode() == null) continue;
            if (memberName == null || m.getName().toString().equals(memberName))
            {
                out.println(m.toString());
                Compil3r.BytecodeAnalysis.TypeAnalysis.analyze(m);
            }
        }
        for(it = Arrays.asList(c.getDeclaredInstanceMethods()).iterator();
            it.hasNext(); ) {
            jq_InstanceMethod m = (jq_InstanceMethod)it.next();
            if (m.isAbstract()) continue;
            if (m.getBytecode() == null) continue;
            if (memberName == null || m.getName().toString().equals(memberName))
            {
                out.println(m.toString());
                Compil3r.BytecodeAnalysis.TypeAnalysis.analyze(m);
            }
        }
    }
    
}
