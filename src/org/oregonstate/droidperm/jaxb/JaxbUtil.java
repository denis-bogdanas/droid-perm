package org.oregonstate.droidperm.jaxb;

import org.oregonstate.droidperm.consumer.method.MethodPermDetector;
import org.oregonstate.droidperm.util.MyCollectors;
import org.oregonstate.droidperm.util.StreamUtil;
import soot.MethodOrMethodContext;
import soot.Scene;
import soot.Unit;
import soot.jimple.Stmt;
import soot.jimple.toolkits.callgraph.CallGraph;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import java.io.File;
import java.util.Set;

/**
 * @author Denis Bogdanas <bogdanad@oregonstate.edu> Created on 4/26/2016.
 */
public class JaxbUtil {

    /**
     * todo: There's a discrepancy between the way permission checks are matched with sensitives here and in
     * printCoveredCallbacks(). There ANY ONE perm per sensitive should be checked for the whole sensitive to be
     * checked. Here ALL the perms for a statement should be checked for the statement to be checked.
     */
    public static JaxbCallbackList buildJaxbData(MethodPermDetector detector) {
        CallGraph cg = Scene.v().getCallGraph();
        JaxbCallbackList jaxbCallbackList = new JaxbCallbackList();

        for (MethodOrMethodContext callback : detector.getSensitivePathsHolder().getReachableCallbacks()) {
            JaxbCallback jaxbCallback = new JaxbCallback(callback.method());
            for (Unit unit : callback.method().getActiveBody().getUnits()) {
                Set<MethodOrMethodContext> sensitives = StreamUtil.asStream(cg.edgesOutOf(unit))
                        .map(edge -> detector.getSensitivePathsHolder().getReacheableSensitives(edge))
                        .filter(set -> set != null)
                        .collect(MyCollectors.toFlatSet());
                if (!sensitives.isEmpty()) {
                    Set<String> permSet = detector.getPermissionsFor(sensitives);
                    boolean guarded = detector.getPermCheckStatusForAll(permSet, callback) ==
                            MethodPermDetector.PermCheckStatus.DETECTED;
                    JaxbStmt jaxbStmt = new JaxbStmt((Stmt) unit, guarded, permSet);
                    jaxbCallback.addStmt(jaxbStmt);
                }
            }
            jaxbCallbackList.addCallback(jaxbCallback);
        }
        return jaxbCallbackList;
    }

    public static void save(JaxbCallbackList data, File file) throws JAXBException {
        JAXBContext jaxbContext = JAXBContext.newInstance(JaxbCallbackList.class);
        Marshaller jaxbMarshaller = jaxbContext.createMarshaller();

        // output pretty printed
        jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);

        jaxbMarshaller.marshal(data, file);
    }
}