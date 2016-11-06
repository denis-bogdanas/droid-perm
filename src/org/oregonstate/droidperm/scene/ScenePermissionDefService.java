package org.oregonstate.droidperm.scene;

import org.oregonstate.droidperm.perm.FieldSensitiveDef;
import org.oregonstate.droidperm.perm.IPermissionDefProvider;
import org.oregonstate.droidperm.util.HierarchyUtil;
import soot.Scene;
import soot.SootClass;
import soot.SootField;
import soot.SootMethod;
import soot.jimple.infoflow.android.data.AndroidMethod;
import soot.jimple.infoflow.data.SootMethodAndClass;
import soot.toolkits.scalar.Pair;

import java.util.*;
import java.util.stream.Collectors;

/**
 * All Permission, sensitive and checker-related data structures at scene level.
 *
 * @author Denis Bogdanas <bogdanad@oregonstate.edu> Created on 10/27/2016.
 */
public class ScenePermissionDefService {

    private Set<SootMethodAndClass> permCheckerDefs;
    private Set<AndroidMethod> methodSensitiveDefs;
    private Set<FieldSensitiveDef> fieldSensitiveDefs;
    private Map<Set<String>, Set<SootMethod>> permissionToSensitivesMap;
    private Map<SootMethod, Set<String>> sensitivesToPermissionsMap;
    /**
     * Only sensitive defs that are really found in scene are contained here
     */
    private Map<FieldSensitiveDef, SootField> sceneFieldSensMap;
    private Map<Set<String>, List<SootField>> permissionToFieldSensMap;
    private Map<AndroidMethod, List<SootMethod>>
            sceneSensitivesMap;

    public ScenePermissionDefService(IPermissionDefProvider permissionDefProvider) {
        permCheckerDefs = permissionDefProvider.getPermCheckerDefs();
        methodSensitiveDefs = permissionDefProvider.getMethodSensitiveDefs();
        fieldSensitiveDefs = permissionDefProvider.getFieldSensitiveDefs();
        sceneSensitivesMap = buildSceneSensitivesMap();
        permissionToSensitivesMap = buildPermissionToSensitivesMap();
        sensitivesToPermissionsMap = buildSensitivesToPermissionsMap();
        sceneFieldSensMap = buildSceneFieldSensMap();
        permissionToFieldSensMap = buildPermissionToFieldSensMap();
    }

    public List<SootMethod> getPermCheckers() {
        return HierarchyUtil.resolveAbstractDispatches(permCheckerDefs);
    }

    private Map<AndroidMethod, List<SootMethod>> buildSceneSensitivesMap() {
        return HierarchyUtil.resolveAbstractDispatchesToMap(methodSensitiveDefs);
    }

    private Map<SootMethod, Set<String>> buildSensitivesToPermissionsMap() {
        return permissionToSensitivesMap.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().map(sootMeth -> new Pair<>(sootMeth, entry.getKey())))
                .collect(Collectors.toMap(Pair::getO1, Pair::getO2));
    }

    private Map<Set<String>, Set<SootMethod>> buildPermissionToSensitivesMap() {
        Map<Set<String>, List<AndroidMethod>> permissionToSensitiveDefMap = methodSensitiveDefs
                .stream().collect(Collectors.groupingBy(AndroidMethod::getPermissions));
        return permissionToSensitiveDefMap.keySet().stream()
                .collect(Collectors.toMap(
                        permSet -> permSet,
                        permSet -> permissionToSensitiveDefMap.get(permSet).stream()
                                .flatMap(androMeth -> sceneSensitivesMap.get(androMeth).stream())
                                .collect(Collectors.toCollection(LinkedHashSet::new))
                ));
    }

    public List<SootMethod> getSceneMethodSensitives() {
        return HierarchyUtil.resolveAbstractDispatches(methodSensitiveDefs);
    }

    /**
     * @return all sets of permissions for which there is a sensitive method definition.
     */
    public Set<Set<String>> getMethodPermissionSets() {
        return methodSensitiveDefs.stream().map(AndroidMethod::getPermissions).collect(Collectors.toSet());
    }

    public Set<SootMethod> getMethodSensitivesFor(Set<String> permSet) {
        return permissionToSensitivesMap.get(permSet);
    }

    private Map<FieldSensitiveDef, SootField> buildSceneFieldSensMap() {
        Scene scene = Scene.v();
        return fieldSensitiveDefs.stream().filter(def -> scene.containsClass(def.getClassName()))
                .collect(Collectors.toMap(
                        fieldDef -> fieldDef,
                        fieldDef -> {
                            SootClass clazz = scene.getSootClassUnsafe(fieldDef.getClassName());
                            return clazz.getFieldByName(fieldDef.getName());
                        }
                ));
    }

    private Map<Set<String>, List<SootField>> buildPermissionToFieldSensMap() {
        Map<Set<String>, List<FieldSensitiveDef>> permissionToFieldSensDefMap = fieldSensitiveDefs.stream()
                .collect(Collectors.groupingBy(FieldSensitiveDef::getPermissions));
        return permissionToFieldSensDefMap.keySet().stream()
                .collect(Collectors.toMap(
                        permSet -> permSet,
                        permSet -> permissionToFieldSensDefMap.get(permSet).stream()
                                .map(sceneFieldSensMap::get)
                                .filter(field -> field != null)
                                .collect(Collectors.toList())
                ));
    }

    public Collection<SootField> getSceneFieldSensitives() {
        return sceneFieldSensMap.values();
    }

    public List<SootField> getFieldSensitivesFor(Set<String> permSet) {
        return permissionToFieldSensMap.get(permSet);
    }

    /**
     * @return all sets of permissions for which there is a sensitive field definition.
     */
    public Set<Set<String>> getFieldPermissionSets() {
        return permissionToFieldSensMap.keySet();
    }

    public Set<Set<String>> getAllPermissionSets() {
        Set<Set<String>> result = new HashSet<>();
        result.addAll(getMethodPermissionSets());
        result.addAll(permissionToFieldSensMap.keySet());
        return result;
    }

    public Set<String> getPermissionsFor(SootMethod meth) {
        return sensitivesToPermissionsMap.get(meth);
    }
}
