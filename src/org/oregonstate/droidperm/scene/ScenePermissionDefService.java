package org.oregonstate.droidperm.scene;

import com.google.common.collect.*;
import org.oregonstate.droidperm.perm.FieldSensitiveDef;
import org.oregonstate.droidperm.perm.IPermissionDefProvider;
import org.oregonstate.droidperm.perm.PermissionDefConverter;
import org.oregonstate.droidperm.perm.miner.jaxb_out.PermissionDef;
import org.oregonstate.droidperm.util.HierarchyUtil;
import org.oregonstate.droidperm.util.MyCollectors;
import org.oregonstate.droidperm.util.StreamUtil;
import soot.*;
import soot.jimple.infoflow.android.data.AndroidMethod;
import soot.jimple.infoflow.data.SootMethodAndClass;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * All Permission, sensitive and checker-related data structures at scene level.
 *
 * @author Denis Bogdanas <bogdanad@oregonstate.edu> Created on 10/27/2016.
 */
public class ScenePermissionDefService {

    private IPermissionDefProvider permissionDefProvider;
    private ListMultimap<AndroidMethod, SootMethod> sceneMethodSensMap;
    private SetMultimap<Set<String>, SootMethod> permissionToSensitivesMap;
    private SetMultimap<SootMethod, String> sensitivesToPermissionsMap;
    /**
     * Only sensitive defs that are really found in scene are contained here
     */
    private Map<FieldSensitiveDef, SootField> sceneFieldSensMap;
    private ListMultimap<Set<String>, SootField> permissionToFieldSensMap;
    private final SetMultimap<SootField, String> fieldSensToPermissionsMap;
    private final Map<String, SootField> stringConstantFieldsMap;
    private final Map<Integer, SootField> intConstantFieldsMap;
    private List<SootMethod> sceneMethodSensitives;
    private List<SootMethod> sceneParametricSensitives;
    /**
     * Specifies the index of the sensitive argument for parametric sensitives.
     */
    private Map<SootMethod, Integer> parametricSensToArgIndexMap;

    public ScenePermissionDefService(IPermissionDefProvider permissionDefProvider) {
        this.permissionDefProvider = permissionDefProvider;

        sceneMethodSensMap =
                HierarchyUtil.resolveAbstractDispatchesToMap(permissionDefProvider.getMethodSensitiveDefs());
        permissionToSensitivesMap = buildPermissionToSensitivesMap();
        sensitivesToPermissionsMap = permissionToSensitivesMap.entries().stream()
                .collect(MyCollectors.toMultimapForCollection(Map.Entry::getValue, Map.Entry::getKey));
        sceneFieldSensMap = buildSceneFieldSensMap();
        permissionToFieldSensMap = buildPermissionToFieldSensMap();
        fieldSensToPermissionsMap = permissionToFieldSensMap.entries().stream()
                .collect(MyCollectors.toMultimapForCollection(Map.Entry::getValue, Map.Entry::getKey));
        stringConstantFieldsMap = SceneUtil.buildStringConstantFieldsMap(fieldSensToPermissionsMap.keySet());
        intConstantFieldsMap = SceneUtil.buildIntConstantFieldsMap(fieldSensToPermissionsMap.keySet());
    }

    public List<SootMethod> getPermCheckers() {
        return HierarchyUtil.resolveAbstractDispatches(permissionDefProvider.getPermCheckerDefs(), false);
    }

    public List<SootMethod> getPermRequesters() {
        return HierarchyUtil.resolveAbstractDispatches(permissionDefProvider.getPermRequesterDefs(), false);
    }

    private SetMultimap<Set<String>, SootMethod> buildPermissionToSensitivesMap() {
        Map<Set<String>, List<AndroidMethod>> permissionToSensitiveDefMap =
                permissionDefProvider.getMethodSensitiveDefs()
                        .stream().collect(Collectors.groupingBy(AndroidMethod::getPermissions));
        return permissionToSensitiveDefMap.keySet().stream()
                .collect(MyCollectors.toMultimap(
                        LinkedHashMultimap::create,
                        permSet -> permSet,
                        permSet -> permissionToSensitiveDefMap.get(permSet).stream()
                                .flatMap(androMeth -> sceneMethodSensMap.get(androMeth).stream())
                ));
    }

    private Map<FieldSensitiveDef, SootField> buildSceneFieldSensMap() {
        Scene scene = Scene.v();
        return permissionDefProvider.getFieldSensitiveDefs().stream()
                .filter(def -> scene.containsClass(def.getClassName())
                        && scene.getSootClass(def.getClassName()).getFieldByNameUnsafe(def.getName()) != null)
                .collect(Collectors.toMap(
                        fieldDef -> fieldDef,
                        fieldDef -> scene.getSootClass(fieldDef.getClassName()).getFieldByName(fieldDef.getName()),
                        StreamUtil.throwingMerger(),
                        LinkedHashMap::new
                ));
    }

    private ListMultimap<Set<String>, SootField> buildPermissionToFieldSensMap() {
        Map<Set<String>, List<FieldSensitiveDef>> permissionToFieldSensDefMap =
                permissionDefProvider.getFieldSensitiveDefs().stream()
                        .collect(Collectors.groupingBy(FieldSensitiveDef::getPermissions));
        return permissionToFieldSensDefMap.keySet().stream()
                .collect(MyCollectors.toMultimap(
                        ArrayListMultimap::create,
                        permSet -> permSet,
                        permSet -> permissionToFieldSensDefMap.get(permSet).stream()
                                .map(sceneFieldSensMap::get)
                                .filter(Objects::nonNull)
                ));
    }

    public List<SootMethod> getSceneMethodSensitives() {
        if (sceneMethodSensitives == null) {
            sceneMethodSensitives = sceneMethodSensMap.values().stream().distinct().collect(Collectors.toList());
        }
        return sceneMethodSensitives;
    }

    public SootField getFieldFor(String actionString) {
        return stringConstantFieldsMap.get(actionString);
    }

    public List<SootField> getFieldsFor(int phoneStateListenerArg) {
        return intConstantFieldsMap.keySet().stream().filter(key -> (key & phoneStateListenerArg) != 0)
                .map(intConstantFieldsMap::get).collect(Collectors.toList());
    }

    public List<SootMethod> getSceneParametricSensitives() {
        if (sceneParametricSensitives == null) {
            sceneParametricSensitives =
                    HierarchyUtil.resolveAbstractDispatches(permissionDefProvider.getParametricSensDefs(), false);
        }
        return sceneParametricSensitives;
    }

    /**
     * @return The index of the sensitive argument for the given parametric sensitive. Or null if the given method is
     * not a parametric sensitive.
     */
    public Integer getSensitiveArgumentIndex(SootMethod parametricSens) {
        if (parametricSensToArgIndexMap == null) {
            parametricSensToArgIndexMap = getSceneParametricSensitives().stream()
                    .collect(Collectors.toMap(
                            paramSens -> paramSens,
                            this::computeSensitiveArgIndex
                    ));
        }
        return parametricSensToArgIndexMap.get(parametricSens);
    }

    private static final Set<String> SENSITIVE_PARAM_TYPES =
            ImmutableSet.of("java.lang.String", "android.net.Uri", "int");

    /**
     * @return the index of the first parameter whose type is String or Uri.
     */
    private int computeSensitiveArgIndex(SootMethod parametricSens) {
        for (int i = 0; i < parametricSens.getParameterTypes().size(); i++) {
            Type type = parametricSens.getParameterType(i);
            if (SENSITIVE_PARAM_TYPES.contains(type.toString())) {
                return i;
            }
        }
        throw new RuntimeException("Unsupported parametric sensitive: " + parametricSens);
    }

    public Collection<SootField> getSceneFieldSensitives() {
        return sceneFieldSensMap.values();
    }

    public Set<SootMethod> getMethodSensitivesFor(Set<String> permSet) {
        return permissionToSensitivesMap.get(permSet);
    }

    public List<SootField> getFieldSensitivesFor(Set<String> permSet) {
        return permissionToFieldSensMap.get(permSet);
    }

    /**
     * @return all sets of permissions for which there is a sensitive method definition.
     */
    public Set<Set<String>> getMethodPermissionSets() {
        return permissionDefProvider.getMethodSensitiveDefs().stream()
                .map(AndroidMethod::getPermissions).collect(Collectors.toSet());
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
        result.addAll(getFieldPermissionSets());
        return result;
    }

    /**
     * Returns the permissions for the given sensitive method. If the method is not a sensitive, returns an empty set.
     */
    public Set<String> getPermissionsFor(SootMethod meth) {
        return sensitivesToPermissionsMap.get(meth);
    }

    /**
     * Returns the permissions for the given sensitive field. If the field is not a sensitive, returns an empty set.
     */
    public Set<String> getPermissionsFor(SootField field) {
        return fieldSensToPermissionsMap.get(field);
    }

    public boolean isParametric(MethodOrMethodContext sens) {
        return getSceneParametricSensitives().contains(sens.method());
    }

    public AndroidMethod getPermDefFor(SootMethod meth) {
        return sceneMethodSensMap.keySet().stream().filter(sensDef -> sceneMethodSensMap.get(sensDef).contains(meth))
                .findAny().orElse(null);
    }

    public FieldSensitiveDef getPermDefFor(SootField field) {
        return sceneFieldSensMap.keySet().stream().filter(sensDef -> sceneFieldSensMap.get(sensDef).equals(field))
                .findAny().orElse(null);
    }

    public List<PermissionDef> getPermDefsFor(Collection<SootMethod> methodSens, Collection<SootField> fieldSens) {
        if (methodSens == null) {
            methodSens = Collections.emptyList();
        }
        if (fieldSens == null) {
            fieldSens = Collections.emptyList();
        }
        return Stream.concat(
                methodSens.stream().map(meth -> PermissionDefConverter.forMethod(getPermDefFor(meth))),
                fieldSens.stream().map(field -> PermissionDefConverter.forField(getPermDefFor(field)))
        ).collect(Collectors.toList());
    }

    public boolean isCompileSdkVersion_23_OrMore() {
        SootMethodAndClass contextCheckSelfPerm = permissionDefProvider.getPermCheckerDefs().stream()
                .filter(permDef -> permDef.getClassName().equals("android.content.Context")
                        && permDef.getMethodName().equals("checkSelfPermission")).findAny().orElse(null);
        return !HierarchyUtil.resolveAbstractDispatch(contextCheckSelfPerm, false).isEmpty();
    }
}
