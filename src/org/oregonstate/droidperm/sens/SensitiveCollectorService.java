package org.oregonstate.droidperm.sens;

import com.google.common.collect.Sets;
import org.oregonstate.droidperm.jaxb.JaxbUtil;
import org.oregonstate.droidperm.scene.ClasspathFilter;
import org.oregonstate.droidperm.scene.ScenePermissionDefService;
import org.oregonstate.droidperm.scene.UndetectedItemsUtil;
import org.oregonstate.droidperm.util.MyCollectors;
import org.oregonstate.droidperm.util.PrintUtil;
import org.xmlpull.v1.XmlPullParserException;
import soot.SootField;
import soot.SootMethod;
import soot.jimple.Stmt;
import soot.util.MultiMap;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author Denis Bogdanas <bogdanad@oregonstate.edu> Created on 10/21/2016.
 */
public class SensitiveCollectorService {

    private static final Path DANGEROUS_PERM_FILE = Paths.get("config/DangerousPermissions.txt");

    public static void hierarchySensitivesAnalysis(ScenePermissionDefService scenePermDef,
                                                   ClasspathFilter classpathFilter, File apkFile, File xmlOut)
            throws Exception {
        long startTime = System.currentTimeMillis();

        Map<Set<String>, MultiMap<SootMethod, Stmt>> permToUndetectedMethodSensMap = UndetectedItemsUtil
                .buildPermToUndetectedSensMap(scenePermDef, Collections.emptySet(), classpathFilter);
        UndetectedItemsUtil.printUndetectedSensitives(permToUndetectedMethodSensMap, "Collected method sensitives");

        Map<Set<String>, MultiMap<SootField, Stmt>> permToUndetectedFieldSensMap = UndetectedItemsUtil
                .buildPermToUndetectedFieldSensMap(scenePermDef, classpathFilter);
        UndetectedItemsUtil.printUndetectedSensitives(permToUndetectedFieldSensMap, "Collected field sensitives");

        Set<Set<String>> sensitivePermissionSets = Stream.concat(
                permToUndetectedMethodSensMap.keySet().stream()
                        .filter(permSet -> !permToUndetectedMethodSensMap.get(permSet).isEmpty()),
                permToUndetectedFieldSensMap.keySet().stream()
                        .filter(permSet -> !permToUndetectedFieldSensMap.get(permSet).isEmpty())
        ).collect(Collectors.toCollection(LinkedHashSet::new));
        PrintUtil.printCollection(sensitivePermissionSets, "Permission sets required by sensitives");

        Set<String> declaredPermissions = getDeclaredPermissions(apkFile);
        PrintUtil.printCollection(declaredPermissions, "Permissions declared in manifest");

        Set<Set<String>> undeclaredPermissionSets = sensitivePermissionSets.stream()
                .filter(permSet -> Collections.disjoint(permSet, declaredPermissions))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        PrintUtil.printCollection(undeclaredPermissionSets,
                "Permissions sets used by sensitives but not declared in manifest");

        Set<String> allDangerousPerm = loadDangerousPermissions();
        Set<String> permissionsWithSensitives = sensitivePermissionSets.stream().collect(MyCollectors.toFlatSet());
        Set<String> dangerousPermWithSensitives = Sets.intersection(permissionsWithSensitives, allDangerousPerm);
        Set<String> unusedPermissions = Sets.difference(declaredPermissions, permissionsWithSensitives);
        PrintUtil.printCollection(unusedPermissions, "Permissions declared but not used by sensitives");

        Set<String> declaredDangerousPerm = Sets.intersection(declaredPermissions, allDangerousPerm);
        Set<String> unusedDangerousPerm = Sets.intersection(unusedPermissions, allDangerousPerm);
        PrintUtil.printCollection(declaredDangerousPerm, "Dangerous permissions declared in manifest");
        PrintUtil.printCollection(unusedDangerousPerm, "Dangerous permissions declared but not used by sensitives");

        if (xmlOut != null) {
            SensitiveCollectorJaxbData data =
                    new SensitiveCollectorJaxbData(new ArrayList<>(declaredDangerousPerm), null,
                            new ArrayList<>(dangerousPermWithSensitives));
            JaxbUtil.save(data, SensitiveCollectorJaxbData.class, xmlOut);
        }

        System.out.println("\nTime to collect sensitives: "
                + (System.currentTimeMillis() - startTime) / 1E3 + " seconds");
    }

    private static Set<String> getDeclaredPermissions(File apkFile) throws IOException, XmlPullParserException {
        DPProcessManifest manifest = new DPProcessManifest(apkFile);
        return manifest.getDeclaredPermissions();
    }

    public static Set<String> loadDangerousPermissions() throws IOException {
        return Files.readAllLines(DANGEROUS_PERM_FILE).stream()
                .filter(line -> !(line.trim().isEmpty() || line.startsWith("%")))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
