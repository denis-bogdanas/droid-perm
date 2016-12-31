package org.oregonstate.droidperm.batch;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.google.common.collect.*;
import org.oregonstate.droidperm.jaxb.JaxbCallbackList;
import org.oregonstate.droidperm.jaxb.JaxbUtil;
import org.oregonstate.droidperm.perm.miner.jaxb_out.PermTargetKind;
import org.oregonstate.droidperm.perm.miner.jaxb_out.PermissionDef;
import org.oregonstate.droidperm.perm.miner.jaxb_out.PermissionDefList;
import org.oregonstate.droidperm.sens.SensitiveCollectorJaxbData;
import org.oregonstate.droidperm.sens.SensitiveCollectorService;
import org.oregonstate.droidperm.util.MyCollectors;
import org.oregonstate.droidperm.util.PrintUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.jimple.infoflow.InfoflowConfiguration;

import javax.xml.bind.JAXBException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author George Harder <harderg@oregonstate.edu> Created on 7/5/2016.
 */
public class DPBatchRunner {

    private static Logger logger = LoggerFactory.getLogger(DPBatchRunner.class);

    @Parameter(names = "--apps-dir", description = "Directory with apk files, each in a separate dir", required = true)
    private Path appsDir;

    @Parameter(names = "--droid-perm-home", description = "Path to DroidPerm standalone installation dir",
            required = true)
    private Path droidPermHomeDir;

    @Parameter(names = "--log-dir", description = "Directory where log files will be stored", required = true)
    private Path logDir;

    @Parameter(names = "--cg-algo", description = "The call graph algorithm")
    private InfoflowConfiguration.CallgraphAlgorithm cgAlgo = InfoflowConfiguration.CallgraphAlgorithm.GEOM;

    @Parameter(names = "--use-javadoc-perm", description = "Use Javadoc permission definitions")
    private boolean useJavadocPerm;

    @Parameter(names = "--vm-args", description = "Additional VM arguments, separated by space. "
            + "If more than one, they should be included into quotes (\"\").")
    private String vmArgs;

    @Parameter(names = "--mode", description = "DroidPerm execution mode")
    private Mode mode = Mode.DROID_PERM;

    private enum Mode {
        DROID_PERM, TAINT_ANALYSIS, COLLECT_ANNO, COLLECT_SENSITIVES
    }

    @Parameter(names = "--collect-method-sens-only-apps",
            description = "Collect apps that only use method-based dangerous permissions. "
                    + "Used in conjunction with COLLECT_SENSITIVES mode.")
    private boolean collectMethodSensOnlyApps;

    @Parameter(names = "--collect-method-or-field-sens-only-apps",
            description = "Collect apps that only use method-based or field-based dangerous permissions. "
                    + "No storage permissions. Used in conjunction with COLLECT_SENSITIVES mode.")
    private boolean collectMethodOrFieldSensOnlyApps;

    @Parameter(names = "--help", help = true)
    private boolean help = false;

    /**
     * Essentially the set of all collected permission definitions. Map from definitions to themselves.
     */
    private Map<PermissionDef, PermissionDef> permissionDefs = new LinkedHashMap<>();

    private List<String> appsWithMethodSensOnly = new ArrayList<>();
    private List<String> appsWithMethodOrFieldSensOnly = new ArrayList<>();
    private List<String> appsDeclaringNonStoragePermOnly = new ArrayList<>();

    private enum PermUsage {
        MANIFEST, CODE, SENSITIVE
    }

    private static List<Set<PermUsage>> permDistributions = Arrays.asList(
            ImmutableSet.of(PermUsage.MANIFEST, PermUsage.CODE, PermUsage.SENSITIVE),   //normal usage
            ImmutableSet.of(PermUsage.MANIFEST, PermUsage.CODE),                        //unknown sensitive
            ImmutableSet.of(PermUsage.MANIFEST, PermUsage.SENSITIVE),                   //invalid migration to A6
            ImmutableSet.of(PermUsage.MANIFEST),                                        //overprivileged in A5
            ImmutableSet.of(PermUsage.CODE, PermUsage.SENSITIVE),                       //implausible
            ImmutableSet.of(PermUsage.CODE),                                            //implausible
            ImmutableSet.of(PermUsage.SENSITIVE)                                        //unreaclable sensitive
    );

    private static Set<PermUsage> validDistribution =
            ImmutableSet.of(PermUsage.MANIFEST, PermUsage.CODE, PermUsage.SENSITIVE);

    private Set<String> dangerousPerm = SensitiveCollectorService.getAllDangerousPerm();

    /**
     * Table from (app name, perm discrepancy) to set of permissions in that discrepancy. A discrepancy is a difference
     * between 2 permission sets in the 3 reported permission sets of the app: declared permissions, referred
     * permissions and consumed permissions (e.g. those required by sensitives).
     */
    Table<String, Set<PermUsage>, Set<String>> appToPermDistributionsTable = HashBasedTable.create();

    /**
     * Run DroidPerm on all the apps in the given directory.
     * <p>
     * For help on command line arguments use option --help
     */
    public static void main(final String[] args) throws IOException, InterruptedException, JAXBException {
        DPBatchRunner main = new DPBatchRunner();
        JCommander jCommander = new JCommander(main);
        try {
            jCommander.parse(args);
            if (main.help) {
                jCommander.usage();
                return;
            }
            long startTime = System.currentTimeMillis();
            main.batchRun();
            long endTime = System.currentTimeMillis();
            logger.info("\n\nBatch runner total execution time: " + (endTime - startTime) / 1E3 + " sec");
        } catch (ParameterException e) {
            System.err.println(e.getLocalizedMessage());
            System.err.println();
            StringBuilder sb = new StringBuilder();
            jCommander.usage(sb);
            System.err.println(sb.toString());
            System.exit(1);
        }
    }

    private void batchRun() throws IOException, JAXBException {
        logger.info("DroidPerm Mode: " + mode);
        logger.info("collectMethodSensOnlyApps: " + collectMethodSensOnlyApps);
        logger.info("collectMethodOrFieldSensOnlyApps: " + collectMethodOrFieldSensOnlyApps);
        logger.info("appsDir: " + appsDir);
        logger.info("droidPermHomeDir: " + droidPermHomeDir);
        logger.info("logDir: " + logDir);
        logger.info("cgalgo: " + cgAlgo);
        logger.info("useJavadocPerm: " + useJavadocPerm);
        logger.info("vmArgs: " + vmArgs + "\n");

        Files.createDirectories(logDir);
        ListMultimap<String, Path> appNamesToApksMap = Files.list(appsDir).sorted()
                .filter(path -> Files.isDirectory(path))
                .collect(MyCollectors.toMultimap(
                        LinkedListMultimap::create,
                        path -> path.getFileName().toString(),
                        path -> {
                            try {
                                return Files.walk(path)
                                        .filter(possibleApk -> possibleApk.getFileName().toString().endsWith(".apk"));
                            } catch (IOException e) {
                                //Looks like Java 8 lambdas don't like to throw checked exceptions.
                                throw new RuntimeException(e);
                            }
                        }
                ));

        for (String appName : appNamesToApksMap.keySet()) {
            List<Path> apks = appNamesToApksMap.get(appName);
            if (apks.isEmpty()) {
                logger.warn(appName + ": No files matching \"*.apk\" found.");
                continue;
            }
            if (apks.size() > 1) {
                apks = apks.stream().filter(apk -> apk.getFileName().toString().endsWith("-debug.apk"))
                        .collect(Collectors.toList());
                if (apks.size() > 1) {
                    logger.warn(appName + ": multiple -debug.apk files found: " + apks + "\nPicking the first one.");
                }
            }
            Path apk = apks.get(0);
            analyzeApp(appName, apk);
        }

        switch (mode) {
            case COLLECT_ANNO:
                saveCollectAnnoModeDigest();
                break;
            case COLLECT_SENSITIVES:
                printCollectSensitivesModeDigest();
                break;
        }
    }

    private void analyzeApp(String appName, Path apk) throws IOException, JAXBException {
        String droidPermClassPath = droidPermHomeDir + "/droid-perm.jar";
        String androidClassPath = droidPermHomeDir + "/android-23-cr+util_io.zip";
        Path logFile = Paths.get(logDir.toString(), appName + ".log");
        Path errorFile = Paths.get(logDir.toString(), appName + ".error.log");
        Path xmlOut = Paths.get(logDir.toString(), appName + ".out.xml");

        List<String> processBuilderArgs = new ArrayList<>();
        processBuilderArgs.add("java");
        if (vmArgs != null) {
            processBuilderArgs.addAll(Arrays.asList(vmArgs.split("\\s+")));
        }
        processBuilderArgs.addAll(Arrays.asList(
                "-jar", droidPermClassPath, apk.toAbsolutePath().toString(),
                androidClassPath));
        processBuilderArgs.addAll(Arrays.asList("--cgalgo", cgAlgo.name()));

        String permDefFiles =
                "config/checker-param-sens-def.xml;"
                        + "config/perm-def-API-23.xml;"
                        + "config/perm-def-play-services.xml";
        if (useJavadocPerm) {
            permDefFiles += ";config/javadoc-perm-def-API-23.xml;config/perm-def-manual.xml";
        }
        processBuilderArgs.addAll(Arrays.asList("--perm-def-files", permDefFiles));
        processBuilderArgs.addAll(Arrays.asList("--xml-out", xmlOut.toString()));

        switch (mode) {
            case DROID_PERM:
                break;
            case TAINT_ANALYSIS:
                processBuilderArgs.addAll(Arrays.asList(
                        "--taint-analysis-enabled", "true", "--logsourcesandsinks", "--pathalgo", "CONTEXTSENSITIVE"));
                break;
            case COLLECT_ANNO:
                processBuilderArgs.addAll(Collections.singletonList("--COLLECT-PERM-ANNO-MODE"));
                break;
            case COLLECT_SENSITIVES:
                processBuilderArgs.addAll(Collections.singletonList("--collect-sens-mode"));
                break;
        }

        ProcessBuilder processBuilder = new ProcessBuilder(processBuilderArgs)
                .directory(droidPermHomeDir.toFile())
                .redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()))
                .redirectError(ProcessBuilder.Redirect.to(errorFile.toFile()));

        logger.info(appName + " ... ");

        long time = System.currentTimeMillis();

        Process process = processBuilder.start();
        try {
            int exitCode = process.waitFor();
            long newTime = System.currentTimeMillis();
            logger.info(appName + " analyzed: " + (newTime - time) / 1E3 + " sec");
            if (exitCode != 0) {
                logger.error(appName + " analysis returned exit code " + exitCode);
            } else {
                switch (mode) {
                    case DROID_PERM:
                        droidPermModeFor(xmlOut, appName);
                        break;
                    case COLLECT_ANNO:
                        collectAnnotationsFor(xmlOut);
                        break;
                    case COLLECT_SENSITIVES:
                        collectSensitivesFor(xmlOut, appName);
                        break;
                }
            }
        } catch (InterruptedException e) {
            process.destroy();
            throw new RuntimeException(e);
        }
    }

    private void droidPermModeFor(Path xmlOut, String appName) throws JAXBException {
        JaxbCallbackList data = JaxbUtil.load(JaxbCallbackList.class, xmlOut.toFile());
        if (!data.getUndetectedDangerousPermDefs().isEmpty()) {
            logger.info(
                    appName + " : undetected sensitive defs: " + data.getUndetectedDangerousPermDefs().size());
        }
        if (!data.isCompileApi23Plus()) {
            logger.warn(appName + " : compileSdkVersion is < 23");
        }
        if (data.getTargetSdkVersion() != 23) {
            logger.warn(appName + " : targetSdkVersion = " + data.getTargetSdkVersion());
        }
    }

    private void collectAnnotationsFor(Path annoXmlFile) throws JAXBException {
        List<PermissionDef> newAnnos = JaxbUtil.load(PermissionDefList.class, annoXmlFile.toFile()).getPermissionDefs();
        Set<PermissionDef> existingAnnos = new LinkedHashSet<>(newAnnos);
        existingAnnos.retainAll(permissionDefs.keySet());
        existingAnnos.stream()
                .filter(def -> !new HashSet<>(def.getPermissions())
                        .equals(new HashSet<>(permissionDefs.get(def).getPermissions())))
                .forEach(def -> logger.warn("Different set of permissions found for " + def));

        newAnnos.removeAll(permissionDefs.keySet());
        if (!newAnnos.isEmpty()) {
            logger.info("New annotaions: " + newAnnos.size());
            logger.info("New annotaions: " + newAnnos);
            newAnnos.forEach(newAnno -> permissionDefs.put(newAnno, newAnno));
        }
    }

    private void collectSensitivesFor(Path xmlOut, String appName) throws IOException, JAXBException {
        SensitiveCollectorJaxbData data = JaxbUtil.load(SensitiveCollectorJaxbData.class, xmlOut.toFile());
        if (data.getTargetSdkVersion() != 23) {
            logger.warn(appName + " : targetSdkVersion = " + data.getTargetSdkVersion());
        }

        computePermDistributions(appName, data);

        Set<PermUsage> unusedPermDiscrepancy = ImmutableSet.of(PermUsage.CODE, PermUsage.SENSITIVE);
        boolean noDangerousUnusedPerms = appToPermDistributionsTable.get(appName, unusedPermDiscrepancy) == null;
        boolean referredPermDefsOnlyMethod = data.getReferredPermDefs().stream()
                .allMatch(permDef -> permDef.getTargetKind() == PermTargetKind.Method);
        boolean methodSensOnly = !data.getReferredPermDefs().isEmpty()
                && referredPermDefsOnlyMethod
                && Collections.disjoint(data.getAllDeclaredPerms(), SensitiveCollectorService.storagePerm)
                && noDangerousUnusedPerms;
        boolean methodOrFieldSensOnly = !data.getReferredPermDefs().isEmpty()
                && Collections.disjoint(data.getAllDeclaredPerms(), SensitiveCollectorService.storagePerm)
                && noDangerousUnusedPerms;
        boolean declaresNonStoragePermOnly = !data.getDeclaredDangerousPerms().isEmpty()
                && Collections.disjoint(data.getAllDeclaredPerms(), SensitiveCollectorService.storagePerm);
        if (methodSensOnly) {
            appsWithMethodSensOnly.add(appName);
        }
        if (methodOrFieldSensOnly) {
            appsWithMethodOrFieldSensOnly.add(appName);
        }
        if (declaresNonStoragePermOnly) {
            appsDeclaringNonStoragePermOnly.add(appName);
        }
    }

    private void computePermDistributions(String appName, SensitiveCollectorJaxbData data) {
        //Part 1. Compute initial table for this app.
        Map<PermUsage, Set<String>> permUsagesMap = new HashMap<>();
        permUsagesMap.put(PermUsage.MANIFEST, new LinkedHashSet<>(data.getDeclaredDangerousPerms()));
        permUsagesMap.put(PermUsage.CODE, new LinkedHashSet<>(data.getReferredDangerousPerms()));
        permUsagesMap.put(PermUsage.SENSITIVE, new LinkedHashSet<>(data.getPermsWithSensitives()));
        for (Set<PermUsage> distribution : permDistributions) {
            Set<String> permSet = new LinkedHashSet<>(dangerousPerm);
            for (PermUsage usage : PermUsage.values()) {
                if (distribution.contains(usage)) {
                    permSet.retainAll(permUsagesMap.get(usage));
                } else {
                    permSet.removeAll(permUsagesMap.get(usage));
                }
            }
            //First we put all distributions in the table. The next for will cleanup the empty ones.
            appToPermDistributionsTable.put(appName, distribution, permSet);
        }

        //Part 2, alter the table according to special rules for Sensitives. See DP-379 for details.
        Set<String> validDistPerm = appToPermDistributionsTable.get(appName, validDistribution);
        Set<PermissionDef> unsatisfiedPermDefs = data.getReferredPermDefs().stream()
                .filter(permDef -> Collections.disjoint(permDef.getPermissionNames(), validDistPerm))
                .collect(Collectors.toSet());
        Set<String> unsatisfiedPerm = unsatisfiedPermDefs.stream()
                .flatMap(permDef -> permDef.getPermissionNames().stream()).collect(Collectors.toSet());
        //more exactly those are all dangerous permissions which are "not unsatisfied"
        Set<String> satisfiedOnlyPerm = Sets.difference(dangerousPerm, unsatisfiedPerm);

        //For sensitive only distributions, retain only permissions which are among unsatisfied sensitives and ignore
        // the satisfied ones.
        Set<PermUsage> distSensitiveOnly = ImmutableSet.of(PermUsage.SENSITIVE);
        appToPermDistributionsTable.get(appName, distSensitiveOnly).removeAll(satisfiedOnlyPerm);

        //For distribution Manifest+Sensitive, treat permissions as Manifest only if they are satisfied.
        //E.g. move them from Manifest+Sensitive to Manifest.
        //If unsatisfied, leave them unchanged.
        Set<PermUsage> distManifestAndSensitive = ImmutableSet.of(PermUsage.MANIFEST, PermUsage.SENSITIVE);
        Set<PermUsage> distManifest = ImmutableSet.of(PermUsage.MANIFEST);
        Set<String> manifestAndSensButSatisfied = new LinkedHashSet<>(
                Sets.intersection(appToPermDistributionsTable.get(appName, distManifestAndSensitive),
                        satisfiedOnlyPerm));
        appToPermDistributionsTable.get(appName, distManifestAndSensitive).removeAll(manifestAndSensButSatisfied);
        appToPermDistributionsTable.get(appName, distManifest).addAll(manifestAndSensButSatisfied);

        //Part 3. Cleanup empty elements in the table and log results for the app.
        for (Set<PermUsage> distribution : permDistributions) {
            Set<String> permSet = appToPermDistributionsTable.get(appName, distribution);
            if (permSet.isEmpty()) {
                appToPermDistributionsTable.remove(appName, distribution);
            } else {
                String logString =
                        appName + " : permissions with distribution " + distribution + " : " + permSet.size();
                if (distribution.equals(validDistribution)) {
                    logger.info(logString);
                } else {
                    logger.warn(logString);
                }
            }
        }
    }

    /**
     * Save all collected annotations to a file.
     */
    private void saveCollectAnnoModeDigest() throws JAXBException, IOException {
        Path aggregateAnnoFile = Paths.get(logDir.toString(), "_collected_perm_anno.xml");
        PermissionDefList out = new PermissionDefList();
        out.setPermissionDefs(new ArrayList<>(permissionDefs.keySet()));
        JaxbUtil.save(out, PermissionDefList.class, aggregateAnnoFile.toFile());
    }

    private void printCollectSensitivesModeDigest() {
        //print permission distributions
        for (Set<PermUsage> distribution : permDistributions) {
            Map<String, Set<String>> distributionData = appToPermDistributionsTable.column(distribution);
            Set<String> apps = distributionData.keySet();
            if (apps.isEmpty() || distribution.equals(validDistribution)) {
                System.out.println("\nApps with permission dist " + distribution + " : " + apps.size());
            } else {
                System.out.println("\n\nApps with permission dist " + distribution + " : " + apps.size() + "\n"
                        + "========================================================================");
                for (String app : apps) {
                    Set<String> permSet = distributionData.get(app);
                    System.out.println(app + " : " + permSet.size());
                    for (String perm : permSet) {
                        System.out.println("\t" + perm);
                    }
                }

            }
            if (!apps.isEmpty()) {
                Map<String, Long> permFrequency = distributionData.values().stream().flatMap(Collection::stream)
                        .collect(Collectors.groupingBy(perm -> perm, TreeMap::new, Collectors.counting()));
                long totalInstances = permFrequency.values().stream().mapToLong(l -> l).sum();
                System.out.println(
                        "\n\nTotal permissions " + distribution + " in some apps : " + permFrequency.size());
                System.out.println(
                        "Total <app, " + distribution + " permission> instances : " + totalInstances + "\n"
                                + "------------------------------------------------------------------------");
                for (String perm : permFrequency.keySet()) {
                    System.out.println(perm + " : " + permFrequency.get(perm));
                }
            }
        }

        //print all the rest
        if (collectMethodSensOnlyApps) {
            PrintUtil.printCollection(appsWithMethodSensOnly, "Apps with method sensitives only");
        }
        if (collectMethodOrFieldSensOnlyApps) {
            PrintUtil.printCollection(appsWithMethodOrFieldSensOnly, "Apps with method or field sensitives only");
            PrintUtil.printCollection(appsDeclaringNonStoragePermOnly, "Apps declaring non-storage permissions only");
        }
    }
}
