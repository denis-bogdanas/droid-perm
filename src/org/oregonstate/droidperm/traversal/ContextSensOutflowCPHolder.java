package org.oregonstate.droidperm.traversal;

import com.google.common.collect.Iterators;
import org.oregonstate.droidperm.util.HierarchyUtil;
import org.oregonstate.droidperm.util.PointsToUtil;
import org.oregonstate.droidperm.util.SortUtil;
import org.oregonstate.droidperm.util.StreamUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.InstanceInvokeExpr;
import soot.jimple.Stmt;
import soot.jimple.spark.geom.geomPA.GeomPointsTo;
import soot.jimple.spark.ondemand.genericutil.HashSetMultiMap;
import soot.jimple.spark.ondemand.genericutil.MultiMap;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @author Denis Bogdanas <bogdanad@oregonstate.edu> Created on 3/28/2016.
 */
public class ContextSensOutflowCPHolder {

    private static final Logger logger = LoggerFactory.getLogger(ContextSensOutflowCPHolder.class);

    protected MethodOrMethodContext dummyMainMethod;
    protected Set<MethodOrMethodContext> sensitives;
    /**
     * Methods ignored by the outflow algorithm
     */
    private Set<SootMethod> outflowIgnoreSet;

    protected final PointsToAnalysis pointsToAnalysis;
    protected final CallGraph callGraph = Scene.v().getCallGraph();

    private long time = System.currentTimeMillis();
    private Set<MethodOrMethodContext> uiCallbacks;

    private Set<MethodInContext> sensitivesInContext = new HashSet<>();

    //todo investigate using a true call graph for outflows, instead of these maps.
    //Potentially will improve performance, since callbackToOutflowMap contains a lot of repetition.
    //advantage: efficient navigation both upwards and downwards.
    // Could use MethodOrMethodContext, or downright MethodContext
    // to distinguish between edges in the Soot CG and edges in my outflow CG.
    /**
     * Map from UI callbacks to their outflows, as breadth-first trees in the call graph.
     * <p>
     * 1-st level map: key = callback, value = outflow of that callback.
     * <p>
     * 2-nd level map: key = node in the outflow, value = parent node. Both are context-sensitive.
     */
    private Map<MethodOrMethodContext, Map<MethodInContext, MethodInContext>> callbackToOutflowMap;

    /**
     * From each MethodInContext in the call graph, the set of sensitives it reaches.
     * <p>
     * todo Do not include sensitives checked by try-catch. this would require storing sensitives in context.
     * <p>
     * toperf this collection is likely a huge memory hog.
     */
    private MultiMap<MethodInContext, MethodOrMethodContext> reachableSensitives;

    /**
     * Map from sensitives to sets of callbacks.
     */
    private Map<MethodInContext, Set<MethodOrMethodContext>> sensitiveInCToCallbacksMap;

    /**
     * Elements are unique
     */
    private List<MethodOrMethodContext> reachableCallbacks;

    public ContextSensOutflowCPHolder(MethodOrMethodContext dummyMainMethod, Set<MethodOrMethodContext> sensitives,
                                      Set<SootMethod> outflowIgnoreSet) {
        this.dummyMainMethod = dummyMainMethod;
        this.sensitives = sensitives;
        this.outflowIgnoreSet = outflowIgnoreSet;

        pointsToAnalysis = Scene.v().getPointsToAnalysis();
        if (pointsToAnalysis.getClass() != GeomPointsTo.class) {
            logger.warn("ContextSensOutflowCPHolder is slow with PointsTo algorithms other than GEOM");
        }

        callbackToOutflowMap = buildCallbackToOutflowMap();
        sensitiveInCToCallbacksMap = buildSensitiveInCToCallbacksMap();
        buildReachableSensitives();
        reachableCallbacks = getSensitiveToCallbacksMap().values().stream().flatMap(Collection::stream)
                .distinct().sorted(SortUtil.methodOrMCComparator).collect(Collectors.toList());
    }

    private Map<MethodOrMethodContext, Map<MethodInContext, MethodInContext>> buildCallbackToOutflowMap() {
        Map<MethodOrMethodContext, Map<MethodInContext, MethodInContext>> map = new HashMap<>();
        uiCallbacks = computeUICallbacks();
        logger.info("\n\nTotal callbacks: " + uiCallbacks.size() + "\n");
        for (MethodOrMethodContext callback : uiCallbacks) {
            Map<MethodInContext, MethodInContext> outflow = getBreadthFirstOutflow(callback);

            Collection<MethodOrMethodContext> outflowNodes =
                    outflow.keySet().stream().map(methIC -> methIC.method).collect(Collectors.toList());
            if (!Collections.disjoint(outflowNodes, sensitives)) {
                map.put(callback, outflow);
            }

            long newTime = System.currentTimeMillis();
            logger.info("DP: Callback processed: " + callback + " in " + (newTime - time) / 1E3 + " sec");
            time = newTime;
        }

        return map;
    }

    private Set<MethodOrMethodContext> computeUICallbacks() {
        return StreamUtil.asStream(Scene.v().getCallGraph().edgesOutOf(dummyMainMethod))
                .map(Edge::getTgt).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Produces the outflow tree starting from the root method, by breadth-first traversal.
     *
     * @return A map from nodes in the outflow to their parent.
     */
    private Map<MethodInContext, MethodInContext> getBreadthFirstOutflow(MethodOrMethodContext root) {
        CallGraph cg = Scene.v().getCallGraph();
        Queue<MethodInContext> queue = new ArrayDeque<>();
        Set<MethodInContext> traversed = new HashSet<>();

        Map<MethodInContext, MethodInContext> outflow = new HashMap<>();
        MethodInContext rootInContext = new MethodInContext(root);
        queue.add(rootInContext);
        traversed.add(rootInContext);


        for (MethodInContext meth = queue.poll(); meth != null; meth = queue.poll()) {
            final MethodInContext srcInContext = meth; //to make lambda expressions happy
            MethodOrMethodContext srcMeth = srcInContext.method;
            if (srcMeth.method().hasActiveBody() &&
                    //do not pass through methods in the ignore list
                    !outflowIgnoreSet.contains(srcMeth.method())) {
                srcMeth.method().getActiveBody().getUnits().forEach(
                        (Unit unit) -> getUnitEdgeIterator(unit, srcInContext.getContext(), cg)
                                .forEachRemaining((Edge edge) -> {
                                    MethodInContext tgtInContext = new MethodInContext(edge);

                                    if (!traversed.contains(tgtInContext)) {
                                        traversed.add(tgtInContext);
                                        queue.add(tgtInContext);
                                        outflow.put(tgtInContext, srcInContext);
                                        if (sensitives.contains(edge.getTgt())) {
                                            sensitivesInContext.add(tgtInContext);
                                        }
                                    }
                                }));
            }
        }
        return outflow;
    }

    /**
     * Iterator over outbound edges of a particular unit (likely method call).
     */
    private Iterator<Edge> getUnitEdgeIterator(Unit unit, Stmt context, CallGraph cg) {
        InstanceInvokeExpr virtualInvoke = PointsToUtil.getVirtualInvokeIfPresent((Stmt) unit);
        Iterator<Edge> edgesIterator = cg.edgesOutOf(unit);

        //Points-to is safe to compute only when there is at least one edge present.
        //Also checking for edges presence first is a performance improvement.
        if (edgesIterator.hasNext() && virtualInvoke != null && context != null) {
            PointsToSet pointsToSet = getPointsToForOutflows(unit, context);
            if (pointsToSet == null || pointsToSet.possibleTypes().isEmpty()) {
                //Computing points-to has thrown an exception or has beed disabled.
                //Also if possibleTypes() is empty, this might be a case of points-to inconsistency with valid edges.
                //Disabling points-to refinement for this unit.
                return edgesIterator;
            }

            SootMethod staticTargetMethod;
            try {
                staticTargetMethod = virtualInvoke.getMethod();
            } catch (Exception e) {
                logger.error(e.getMessage());
                return edgesIterator;
            }
            Set<Type> pointsToTargetTypes = pointsToSet.possibleTypes();
            List<SootMethod> pointsToTargetMethods =
                    HierarchyUtil.resolveHybridDispatch(staticTargetMethod, pointsToTargetTypes);

            //todo: more precise support for fake edges - take into account the changed target.
            //Fake edges alter the natural mapping between edge.srcStmt() => edge.tgt()
            //  e.g. the actually invoked method is a different than the one allowed by class hierarchy.
            //Problems with fake edges:
            // 1. They alter the invoked method. Ex: Thread.start() => Thread.run().
            //      edge.srcStmt()...getMethod() != edge.tgt()
            // 2. They might alter invocation target. Ex: executor.submit(r) => r.run()
            //      edge.srcStmt()...getBase()
            //          != actual receiver inside OFCGB.methodToReceivers.get(edge.srcStmt()...getMethod())
            //      How to get it???
            //      v1: Get it correctly from OnFlyCallGraphBuilder.
            //      v2: Hack it for every particular implementation of fake edge.

            //Why context sensitivity works for Thread.start()?
            //  Current algorithm won't distinguish between 2 statements Thread.start() within the same method,
            //  but it doesn't matter for the purpose of DroidPerm.
            //Context sensitivity for Thread is actually achieved by cleaning up unfeasible edges in GeomPointsTo,
            //  not through PointsToSet analysis in this class.
            return StreamUtil.asStream(edgesIterator)
                    .filter(edge -> edgeMatchesPointsTo(edge, pointsToTargetMethods))
                    .iterator();
        }

        //default case, anything except virtual method calls
        return edgesIterator;
    }

    private boolean edgeMatchesPointsTo(Edge edge, List<SootMethod> pointsToTargetMethods) {
        //     This is the main case: real edges
        return pointsToTargetMethods.contains(edge.getTgt().method())
                //2nd case: fake edges
                //Fake edges are a hack in Soot for handling async constructs.
                //If it's a fake edge, include it without comparing to actual targets.
                //With one exception: ExecutorService.execute(). That one is handled better by crafted classpath.
                || (edge.kind().isFake());
                /*Limitation: cannot disable fake edges kinf EXECUTOR, because it would require crafting
                a custom executor.execute() for every implementation of ExecutorService. Those fake edges are still
                needed when executor.execute() is called directly by the app.

                 The only drawback is a bit uglier paths, due to fake edge being logged instead of a nice crafted one.
                */
    }

    private Map<MethodInContext, Set<MethodOrMethodContext>> buildSensitiveInCToCallbacksMap() {
        return sensitivesInContext.stream().collect(Collectors.toMap(
                sensitiveInContext -> sensitiveInContext,
                sensitiveInContext -> callbackToOutflowMap.entrySet().stream()
                        .filter(cbToOutflowEntry -> cbToOutflowEntry.getValue().containsKey(sensitiveInContext))
                        .map(Map.Entry::getKey).collect(Collectors.toSet())
        ));
    }

    private void buildReachableSensitives() {
        reachableSensitives = new HashSetMultiMap<>();
        for (MethodOrMethodContext callback : callbackToOutflowMap.keySet()) {
            for (MethodInContext sensitiveInContext : sensitivesInContext) {
                if (callbackToOutflowMap.get(callback).containsKey(sensitiveInContext)) {
                    collectReachableSensitivesOnPath(callback, sensitiveInContext, callbackToOutflowMap.get(callback));
                }
            }
        }
    }

    private void collectReachableSensitivesOnPath(MethodOrMethodContext src, MethodInContext sensitiveInContext,
                                                  Map<MethodInContext, MethodInContext> outflow) {
        MethodInContext node = sensitiveInContext;
        while (node != null && node.method != src) {
            reachableSensitives.put(node, sensitiveInContext.method);
            node = outflow.get(node);
        }
        if (node != null) {
            reachableSensitives.put(node, sensitiveInContext.method);
        }
    }

    public void printPathsFromCallbackToSensitive() {
        System.out.println("\nPaths from each callback to each sensitive");
        System.out.println("========================================================================\n");

        for (MethodOrMethodContext callback : callbackToOutflowMap.keySet()) {
            for (MethodInContext sensitiveInContext : sensitivesInContext) {
                if (callbackToOutflowMap.get(callback).containsKey(sensitiveInContext)) {
                    printPath(callback, sensitiveInContext, callbackToOutflowMap.get(callback));
                }
            }
        }
    }

    private void printPath(MethodOrMethodContext src, MethodInContext dest,
                           Map<MethodInContext, MethodInContext> outflow) {
        List<MethodInContext> path = computePathFromOutflow(src, dest, outflow);

        System.out.println("From " + src + "\n  to " + dest);
        System.out.println("----------------------------------------------------------------------------------------");
        if (path != null) {
            for (int i = 0; i < path.size(); i++) {
                MethodInContext methodInC = path.get(i);
                MethodInContext child = i < path.size() - 1 ? path.get(i + 1) : null;
                System.out.println(methodInC != null ? pathNodeToString(methodInC, child) : null);
            }
            System.out.println();
        } else {
            System.out.println("Not found!");
        }
        System.out.println();
    }

    /**
     * @param methodInC currently printed method
     * @param child     child of methodInC
     * @return string to print representing methodInC
     */
    private String pathNodeToString(MethodInContext methodInC, MethodInContext child) {
        StringBuilder out = new StringBuilder();

        //parent method
        out.append(methodInC.method);

        //invocation line number in parent method
        if (child != null && child.getContext() != null) {
            out.append(" : ").append(child.getContext().getJavaSourceStartLineNumber());
        }

        //points to of the invocation target
        boolean printPointsTo =
                child != null && PointsToUtil.getVirtualInvokeIfPresent(child.getContext()) != null;
        if (printPointsTo) {
            PointsToSet pointsTo = getPointsToForLogging(child.getContext(), methodInC.getContext());
            out.append("\n                                                                p-to: ");
            if (pointsTo != null) {
                out.append(pointsTo.possibleTypes().stream()
                        .map(type -> type.toString().substring(type.toString().lastIndexOf(".") + 1))
                        .collect(Collectors.toList()));
            } else {
                out.append("exception");
            }
            int edgesCount = Iterators.size(callGraph.edgesOutOf(child.getContext()));
            out.append(", edges: ").append(edgesCount);
        }

        //shortcutted call, if it's a fake edge
        if (child != null && child.edge.kind().isFake()) { //child edge is always != null
            SootMethod shortcuttedMethod;
            try {
                shortcuttedMethod = child.getContext().getInvokeExpr().getMethod();
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
                shortcuttedMethod = null;
            }
            out.append("\n    ");
            out.append(shortcuttedMethod);
            out.append(
                    "\n                                                                FAKE edge: call shortcutted");
        }

        return out.toString();
    }

    private List<MethodInContext> computePathFromOutflow(MethodOrMethodContext src, MethodInContext dest,
                                                         Map<MethodInContext, MethodInContext> outflow) {
        List<MethodInContext> path = new ArrayList<>();
        MethodInContext node = dest;
        while (node != null && node.method != src) {
            path.add(node);
            node = outflow.get(node);
        }
        path.add(node != null ? node : null);
        Collections.reverse(path);
        return path;
    }

    protected PointsToSet getPointsToForOutflows(Unit unit, Stmt context) {
        return PointsToUtil.getPointsToIfVirtualCall(unit, context, pointsToAnalysis);
    }

    protected PointsToSet getPointsToForLogging(Stmt stmt, Stmt context) {
        return PointsToUtil.getPointsToIfVirtualCall(stmt, context, pointsToAnalysis);
    }

    public Set<MethodOrMethodContext> getReacheableSensitives(Edge edge) {
        return reachableSensitives.get(new MethodInContext(edge));
    }

    public Set<Edge> getCallsToSensitiveFor(MethodOrMethodContext callback) {
        //toperf cg.findEdge() is potentially expensive. Better keep edges in the outflow.
        CallGraph cg = Scene.v().getCallGraph();
        return callbackToOutflowMap.get(callback).keySet().stream().filter(sensitivesInContext::contains)
                .map(methInCt -> cg.findEdge(methInCt.getContext(), methInCt.method.method()))
                .collect(Collectors.toSet());
    }

    /**
     * For 1-CFA analysis. Map from call to its parent calls, for each call to sensitive in this calblack.
     */
    public Map<Edge, Set<Edge>> getContextSensCallsToSensitiveFor(MethodOrMethodContext callback) {
        return sensitivesInContext.stream().collect(Collectors.toMap(
                sensInC -> sensInC.edge,
                sensInC -> getParentEdges(sensInC.edge, callback)
        ));
    }

    /**
     * There might be multiple calls to meth in one callback, that's why list is needed.
     */
    public List<Edge> getCallsToMeth(MethodOrMethodContext meth, MethodOrMethodContext callback) {
        CallGraph cg = Scene.v().getCallGraph();
        return callbackToOutflowMap.get(callback).keySet().stream().filter(methInC -> methInC.method == meth)
                .map(methInCt -> cg.findEdge(methInCt.getContext(), methInCt.method.method()))
                .collect(Collectors.toList());
    }

    /**
     * To be used for checkers only. this method does not check points-to consistency between parent edges and child
     * edges.
     * <p>
     * For methods executed directly inside callback, parent will be the edge from dummy main to callback.
     */
    public Set<Edge> getParentEdges(Edge edge, MethodOrMethodContext callback) {
        MethodInContext methInC = new MethodInContext(edge);
        if (!callbackToOutflowMap.get(callback).containsKey(methInC)) {
            return Collections.emptySet();
        }

        Iterator<Edge> allParentEdges = Scene.v().getCallGraph().edgesInto(edge.getSrc());
        return StreamUtil.asStream(allParentEdges)
                .filter(parent -> (callbackToOutflowMap.get(callback).containsKey(new MethodInContext(parent))
                        || edge.getSrc() == callback))
                .collect(Collectors.toSet());
    }

    /**
     * Map from reachable sensitives to sets of callbacks.
     */
    public Map<MethodOrMethodContext, Set<MethodOrMethodContext>> getSensitiveToCallbacksMap() {
        return sensitiveInCToCallbacksMap.keySet().stream().collect(Collectors.toMap(
                sensInC -> sensInC.method,
                sensInC -> sensitiveInCToCallbacksMap.get(sensInC),
                StreamUtil::newObjectUnion
        ));
    }

    public Map<MethodInContext, Set<MethodOrMethodContext>> getSensitiveInCToCallbacksMap() {
        return sensitiveInCToCallbacksMap;
    }

    public Set<MethodOrMethodContext> getReachingCallbacks(MethodInContext meth) {
        return callbackToOutflowMap.keySet().stream()
                .filter(callback -> callbackToOutflowMap.get(callback).containsKey(meth)).collect(Collectors.toSet());
    }

    public Set<MethodOrMethodContext> getUiCallbacks() {
        return uiCallbacks;
    }

    /**
     * We also sort the callbacks by their class name followed by method declaration line number.
     */
    public List<MethodOrMethodContext> getReachableCallbacks() {
        return reachableCallbacks;
    }
}