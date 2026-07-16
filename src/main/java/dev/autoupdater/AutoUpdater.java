package dev.autoupdater;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class AutoUpdater {
    private static final String APP_NAME = "Auto-Updater";
    private static final String VERSION = "0.3.0";
    private static final String DEFAULT_CONFIG = "updater.yml";
    private static final String SOURCE_NOT_FOUND = "Not Found";
    private static final String SOURCE_ORIGIN_MANUAL = "manual";
    private static final String SOURCE_ORIGIN_DISCOVERED = "discovered";
    private static final String SOURCE_ORIGIN_DISCOVERED_UNVERIFIED = "discovered-unverified";
    private static final String SOURCE_ORIGIN_UNRESOLVED = "unresolved";
    private static final String MANAGED_MAVEN_VERSION = "3.9.11";
    private static final String MANAGED_GRADLE_VERSION = "9.2.1";
    private static final List<Integer> MANAGED_BUILD_JAVA_FALLBACKS = List.of(21, 18);
    private static final List<String> DEFAULT_DISCOVERY_SOURCE_PRIORITY = List.of("github-release", "hangar", "modrinth");
    private static final Duration GITHUB_CACHE_FRESH = Duration.ofMinutes(30);
    private static final Duration GITHUB_CACHE_STALE = Duration.ofHours(24);
    private static final Duration DISCOVERY_NOT_FOUND_BACKOFF = Duration.ofHours(1);
    private static final Duration DISCOVERY_HTTP_TIMEOUT = Duration.ofSeconds(25);
    private static final Duration DISCOVERY_RAW_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration DISCOVERY_ARCHIVE_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DISCOVERY_JAR_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration CACHE_STAGING_MAX_AGE = Duration.ofHours(1);
    private static final Duration CACHE_DISCOVERY_JAR_MAX_AGE = Duration.ofDays(7);
    private static final Duration CACHE_DISCOVERY_METADATA_MAX_AGE = Duration.ofDays(14);
    private static final Duration CACHE_SOURCE_FAILURE_MAX_AGE = Duration.ofDays(14);
    private static final long CACHE_DISCOVERY_JAR_MAX_BYTES = 96L * 1024L * 1024L;
    private static final int GITHUB_CORE_RESERVE_REMAINING = 5;
    private static final int GITHUB_SEARCH_RESERVE_REMAINING = 1;
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public static void main(String[] args) {
        int code;
        try {
            code = new AutoUpdater().run(args);
        } catch (Exception ex) {
            Log.error("Fatal error: " + safeExceptionMessage(ex));
            if (Boolean.getBoolean("autoUpdater.debug") || Boolean.getBoolean("velocityUpdater.debug")) {
                ex.printStackTrace(System.err);
            }
            code = 1;
        }
        if (code != 0) {
            System.exit(code);
        }
    }

    private int run(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("apply-self-update")) {
            return SelfUpdater.applyReplacement(args);
        }

        Cli cli = Cli.parse(args);
        if (cli.command.equals("help") || cli.command.equals("-h") || cli.command.equals("--help")) {
            printHelp();
            return 0;
        }

        Path configPath = cli.configPath.toAbsolutePath().normalize();
        if (cli.command.equals("init")) {
            return initConfig(configPath);
        }

        if (!Files.exists(configPath)) {
            Log.warn("No " + configPath.getFileName() + " found. Creating a starter config.");
            writeExampleConfig(configPath);
            Log.warn("Edit " + configPath + ", then run this jar again.");
            return 2;
        }

        AppConfig config = ConfigParser.parse(configPath);
        config.configPath = configPath;
        config.baseDir = configPath.getParent() == null ? Paths.get(".").toAbsolutePath().normalize() : configPath.getParent();
        config.validate();
        ConfigRewriter.refreshConfigSchema(config);
        CacheMaintenance.run(config);

        Updater updater = new Updater(config);
        if (cli.command.equals("run") || cli.command.equals("update")) {
            try {
                if (updater.relaunchForSelfUpdateIfNeeded(args, cli.command.equals("run"))) {
                    return 0;
                }
            } catch (Exception ex) {
                Log.warn("Self-update check failed; continuing with the current Auto-Updater jar: " + safeExceptionMessage(ex));
            }
        }
        switch (cli.command) {
            case "check":
                updater.printPlan();
                updater.pathTraceEnd("check", "plan printed");
                return 0;
            case "discover":
                updater.discover();
                updater.pathTraceEnd("discover", "discovery command complete");
                return 0;
            case "update":
                List<InstalledUpdate> updateOnly = updater.updateAll();
                updater.pathTraceEnd("update", "installedUpdates=" + updateOnly.size());
                return 0;
            case "run":
                List<InstalledUpdate> startupUpdates = updater.updateAll();
                return new ServerRunner(config, updater).runServerLoop(startupUpdates);
            default:
                Log.error("Unknown command: " + cli.command);
                printHelp();
                return 1;
        }
    }

    private int initConfig(Path configPath) throws IOException {
        if (Files.exists(configPath)) {
            Log.warn(configPath + " already exists; leaving it alone.");
            return 0;
        }
        writeExampleConfig(configPath);
        Log.info("Created " + configPath);
        return 0;
    }

    private static void writeExampleConfig(Path configPath) throws IOException {
        Path parent = configPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(configPath, ExampleConfig.text(VERSION), StandardCharsets.UTF_8);
    }

    private static void printHelp() {
        System.out.println(APP_NAME + " " + VERSION);
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -jar auto-updater.jar init [--config updater.yml]");
        System.out.println("  java -jar auto-updater.jar check [--config updater.yml]");
        System.out.println("  java -jar auto-updater.jar discover [--config updater.yml]");
        System.out.println("  java -jar auto-updater.jar update [--config updater.yml]");
        System.out.println("  java -jar auto-updater.jar run [--config updater.yml]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  init    Create a starter updater.yml");
        System.out.println("  check   Parse config and show detected update sources");
        System.out.println("  discover  Suggest/update-source discovery plan without changing jars");
        System.out.println("  update  Download/update configured jars, then exit");
        System.out.println("  run     Update jars, start the configured server, and manage scheduled restarts");
    }

    private static final class Cli {
        final String command;
        final Path configPath;

        private Cli(String command, Path configPath) {
            this.command = command;
            this.configPath = configPath;
        }

        static Cli parse(String[] args) {
            String command = args.length == 0 ? "run" : args[0].trim();
            Path config = Paths.get(DEFAULT_CONFIG);
            for (int i = 1; i < args.length; i++) {
                String arg = args[i];
                if (arg.equals("--config") || arg.equals("-c")) {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--config needs a path");
                    }
                    config = Paths.get(args[++i]);
                } else {
                    throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }
            return new Cli(command, config);
        }
    }

    private static final class AppConfig {
        Path baseDir = Paths.get(".").toAbsolutePath().normalize();
        Path configPath;
        String mode = "hosted-safe";
        String onFailure = "keep-current";
        String userAgent = APP_NAME + "/" + VERSION + " (contact: your-email@example.com)";
        String githubToken = "";
        Path cacheDir = Paths.get("cache");
        boolean cacheDirConfigured = false;
        Path backupDir = Paths.get("backups");
        Path diagnosticsFile = Paths.get("updater.diagnostics.log");
        TargetConfig server = new TargetConfig(null, true);
        List<TargetConfig> plugins = new ArrayList<>();
        List<TargetConfig> prunedMissingPlugins = new ArrayList<>();
        List<String> newPluginLinks = new ArrayList<>();
        List<String> consumedNewPluginLinks = new ArrayList<>();
        DiscoveryConfig discovery = new DiscoveryConfig();
        BuildFromSourceConfig buildFromSource = new BuildFromSourceConfig();
        FailureMemoryConfig failureMemory = new FailureMemoryConfig();
        ValidationConfig validation = new ValidationConfig();
        DuplicateConfig duplicates = new DuplicateConfig();
        RestartConfig restart = new RestartConfig();
        SelfUpdateConfig selfUpdate = new SelfUpdateConfig();
        List<SourceHint> sourceHints = defaultSourceHints();
        GithubRateLimitState githubRateLimit = new GithubRateLimitState();
        GithubApiBudget githubBudget = new GithubApiBudget();
        boolean githubTokenDisabled = false;
        boolean githubTokenRejectedLogged = false;

        void validate() {
            applyCacheDefaults();
            mode = lower(mode);
            onFailure = lower(onFailure);
            if (!mode.equals("hosted-safe") && !mode.equals("auto")) {
                throw new IllegalArgumentException("This build supports mode: hosted-safe or auto. Found: " + mode);
            }
            if (!onFailure.equals("keep-current") && !onFailure.equals("stop")) {
                throw new IllegalArgumentException("onFailure must be keep-current or stop");
            }
            if (server.changeVersion == null) {
                server.changeVersion = false;
            }
            applyServerAutoDefaults(server);
            if (server.source == null || server.source.isBlank()) {
                Log.warn("No server.source configured. The updater will only launch the existing " + server.installAs + ".");
            }
            autoAddInstalledPlugins();
            processNewPluginLinks();
            for (TargetConfig plugin : plugins) {
                if (plugin.changeVersion == null) {
                    plugin.changeVersion = true;
                }
                if (plugin.installAs == null || plugin.installAs.isBlank()) {
                    if (plugin.name != null && !plugin.name.isBlank()) {
                        plugin.installAs = "plugins/" + safeName(plugin.name) + ".jar";
                    } else {
                        throw new IllegalArgumentException("Each plugin needs installAs when name is missing");
                    }
                }
                enrichPluginFromInstalledJar(plugin);
            }
            pruneMissingInstalledPluginEntries();
            restart.warnings.sort(Comparator.comparing((RestartWarning w) -> w.before).reversed());
            String rollbackPolicy = lower(restart.startupRollbackPolicy);
            if (!rollbackPolicy.equals("rollbackbatch") && !rollbackPolicy.equals("rollbackmatchedonly")) {
                throw new IllegalArgumentException("restart.startupRollbackPolicy must be rollbackBatch or rollbackMatchedOnly");
            }
            restart.startupRollbackPolicy = rollbackPolicy.equals("rollbackmatchedonly") ? "rollbackMatchedOnly" : "rollbackBatch";
        }

        private void applyCacheDefaults() {
            if (cacheDirConfigured || cacheDir == null || cacheDir.isAbsolute()) {
                return;
            }
            if (!normalizedConfigPath(cacheDir.toString()).equals("cache")) {
                return;
            }
            if (!isLikelySyncedPath(baseDir)) {
                return;
            }
            String localAppData = firstNonBlank(System.getenv("LOCALAPPDATA"), "");
            if (localAppData.isBlank()) {
                return;
            }
            String serverKey = safeName(baseDir.getFileName() == null ? "server" : baseDir.getFileName().toString())
                + "-" + sha256Text(baseDir.toString()).substring(0, 12);
            cacheDir = Paths.get(localAppData).resolve("AutoUpdater").resolve("cache").resolve(serverKey);
            Log.info("Default cacheDir is inside a synced folder, so using local cache instead: " + cacheDir
                + ". Set cacheDir explicitly to override this.");
        }

        private void applyServerAutoDefaults(TargetConfig server) {
            ServerJarDetection detected = detectExistingServerJar(baseDir, server);
            boolean sourceWasAuto = isAutoValue(server.source);
            if ((server.project == null || server.project.isBlank()) && sourceWasAuto && detected.hasProject()) {
                server.project = detected.project;
            }
            if (sourceWasAuto) {
                if (detected.hasProject()) {
                    server.source = paperMcDownloadSource(detected.project);
                    Log.info("Auto-detected server source: " + server.source);
                } else {
                    server.source = "";
                    Log.warn("server.source is auto, but no known PaperMC server jar filename was detected. Set server.source manually to enable server jar updates.");
                }
            }
            String project = inferPaperMcProject(server);
            if (project.isBlank() && detected.hasProject()) {
                project = detected.project;
            }
            if (server.name == null || server.name.isBlank() || lower(server.name).equals("auto")) {
                server.name = project.isBlank() ? "Server" : title(project);
                Log.info("Auto-detected server name: " + server.name);
            }
            if (server.installAs == null || server.installAs.isBlank() || lower(server.installAs).equals("auto")) {
                server.installAs = sourceWasAuto && detected.path != null ? relativeConfigPath(baseDir, detected.path) : (project.isBlank() ? "server.jar" : project + ".jar");
                Log.info("Auto-detected server jar filename: " + server.installAs);
            }
            if (server.gameVersion != null && lower(server.gameVersion).equals("auto")) {
                Log.info("Server gameVersion is auto. The updater will use updater.lock.yml if present, otherwise it will lock the latest available PaperMC version on the next update.");
            }
        }

        private void autoAddInstalledPlugins() {
            if (!discovery.enabled || !discovery.scanInstalledPlugins) {
                return;
            }
            Path pluginDir = resolve(Paths.get("plugins"));
            if (!Files.isDirectory(pluginDir)) {
                return;
            }
            Set<String> configured = new HashSet<>();
            Map<String, TargetConfig> configuredByIdentity = new LinkedHashMap<>();
            for (TargetConfig plugin : plugins) {
                if (plugin.installAs != null && !plugin.installAs.isBlank()) {
                    configured.add(normalizedConfigPath(plugin.installAs));
                } else if (plugin.name != null && !plugin.name.isBlank()) {
                    configured.add(normalizedConfigPath("plugins/" + safeName(plugin.name) + ".jar"));
                }
                for (String identity : pluginIdentityKeys(plugin)) {
                    configuredByIdentity.putIfAbsent(identity, plugin);
                }
            }
            List<Path> jars = listJarFiles(pluginDir);
            for (Path jar : jars) {
                String installAs = relativeConfigPath(baseDir, jar);
                if (configured.contains(normalizedConfigPath(installAs))) {
                    continue;
                }
                PluginJarInfo info = readPluginJarInfo(jar);
                TargetConfig existing = firstConfiguredPluginByIdentity(configuredByIdentity, info);
                if (existing != null) {
                    Path existingPath = existing.installAs == null || existing.installAs.isBlank()
                        ? null
                        : resolve(Paths.get(existing.installAs));
                    if (existingPath == null || !Files.isRegularFile(existingPath)) {
                        existing.installAs = installAs;
                        existing.sourceDiscoveredThisRun = true;
                        Log.info("Matched configured plugin " + existing.displayName()
                            + " to installed jar " + installAs + "; will persist installAs correction.");
                    }
                    configured.add(normalizedConfigPath(installAs));
                    continue;
                }
                TargetConfig plugin = new TargetConfig(info.name, false);
                plugin.installAs = installAs;
                plugin.source = SOURCE_NOT_FOUND;
                plugin.sourceOrigin = SOURCE_ORIGIN_UNRESOLVED;
                plugin.type = "auto";
                plugin.required = false;
                plugin.platform = inferredPluginPlatform(server);
                plugin.autoDiscovered = true;
                plugin.sourceDiscoveredThisRun = true;
                plugin.detectedPluginId = info.id;
                plugin.detectedVersion = info.version;
                plugin.detectedWebsite = info.website;
                plugin.detectedMainClass = info.mainClass;
                plugin.detectedAuthors = info.authors;
                plugins.add(plugin);
                configured.add(normalizedConfigPath(installAs));
                for (String identity : pluginIdentityKeys(plugin)) {
                    configuredByIdentity.putIfAbsent(identity, plugin);
                }
                Log.info("Auto-discovered installed plugin: " + plugin.name + " -> " + plugin.installAs
                    + " (source pending; will persist as " + SOURCE_NOT_FOUND + " if discovery does not fill it).");
            }
        }

        private void pruneMissingInstalledPluginEntries() {
            if (!discovery.enabled || !discovery.scanInstalledPlugins || !discovery.pruneMissingInstalledPlugins) {
                return;
            }
            if (!Files.isDirectory(resolve(Paths.get("plugins")))) {
                return;
            }
            Iterator<TargetConfig> iterator = plugins.iterator();
            while (iterator.hasNext()) {
                TargetConfig plugin = iterator.next();
                if (!shouldPruneMissingInstalledPlugin(plugin)) {
                    continue;
                }
                plugin.prunedMissingThisRun = true;
                prunedMissingPlugins.add(plugin);
                iterator.remove();
                Log.info("Removed missing auto-managed plugin entry from active config: "
                    + plugin.displayName() + " -> " + plugin.installAs);
            }
        }

        private boolean shouldPruneMissingInstalledPlugin(TargetConfig plugin) {
            if (plugin == null || plugin.server || plugin.installAs == null || plugin.installAs.isBlank()) {
                return false;
            }
            String installAs = normalizedConfigPath(plugin.installAs);
            if (!installAs.startsWith("plugins/")) {
                return false;
            }
            if (Files.isRegularFile(resolve(Paths.get(plugin.installAs)))) {
                return false;
            }
            String origin = lower(firstNonBlank(plugin.sourceOrigin, ""));
            return origin.equals(SOURCE_ORIGIN_DISCOVERED)
                || origin.equals(SOURCE_ORIGIN_DISCOVERED_UNVERIFIED)
                || origin.equals(SOURCE_ORIGIN_UNRESOLVED);
        }

        private TargetConfig firstConfiguredPluginByIdentity(Map<String, TargetConfig> configuredByIdentity, PluginJarInfo info) {
            for (String identity : pluginIdentityKeys(info)) {
                TargetConfig configured = configuredByIdentity.get(identity);
                if (configured != null) {
                    return configured;
                }
            }
            return null;
        }

        private void enrichPluginFromInstalledJar(TargetConfig plugin) {
            if (plugin.installAs == null || plugin.installAs.isBlank()) {
                return;
            }
            Path jar = resolve(Paths.get(plugin.installAs));
            if (!Files.isRegularFile(jar)) {
                return;
            }
            PluginJarInfo info = readPluginJarInfo(jar);
            if ((plugin.name == null || plugin.name.isBlank() || isAutoValue(plugin.name)) && !info.name.isBlank()) {
                plugin.name = info.name;
            }
            if (plugin.detectedPluginId == null || plugin.detectedPluginId.isBlank()) {
                plugin.detectedPluginId = info.id;
            }
            if (plugin.detectedVersion == null || plugin.detectedVersion.isBlank()) {
                plugin.detectedVersion = info.version;
            }
            if (plugin.detectedWebsite == null || plugin.detectedWebsite.isBlank()) {
                plugin.detectedWebsite = info.website;
            }
            if (plugin.detectedMainClass == null || plugin.detectedMainClass.isBlank()) {
                plugin.detectedMainClass = info.mainClass;
            }
            if (plugin.detectedAuthors == null || plugin.detectedAuthors.isBlank()) {
                plugin.detectedAuthors = info.authors;
            }
            syncDiscoveredGithubRepoHint(plugin);
        }

        private void syncDiscoveredGithubRepoHint(TargetConfig plugin) {
            if (plugin == null || plugin.source == null || plugin.source.isBlank()) {
                return;
            }
            String origin = lower(firstNonBlank(plugin.sourceOrigin, ""));
            if (!origin.equals(SOURCE_ORIGIN_DISCOVERED) && !origin.equals(SOURCE_ORIGIN_DISCOVERED_UNVERIFIED)) {
                return;
            }
            String repo = githubRepoFromSourceText(plugin.source);
            if (repo.isBlank()) {
                return;
            }
            if (!repo.equalsIgnoreCase(firstNonBlank(plugin.githubRepo, ""))) {
                plugin.githubRepo = repo;
                plugin.sourceDiscoveredThisRun = true;
            }
        }

        private String githubRepoFromSourceText(String source) {
            String value = firstNonBlank(source, "").trim();
            int marker = lower(value).indexOf("github.com/");
            if (marker >= 0) {
                String rest = value.substring(marker + "github.com/".length()).split("[?#]", 2)[0];
                List<String> parts = Arrays.stream(rest.split("/"))
                    .filter(part -> !part.isBlank())
                    .toList();
                if (parts.size() >= 2) {
                    return cleanSimpleGithubPart(parts.get(0)) + "/"
                        + cleanSimpleGithubPart(parts.get(1).replace(".git", ""));
                }
                return "";
            }
            if (value.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
                String[] parts = value.split("/", 2);
                return cleanSimpleGithubPart(parts[0]) + "/"
                    + cleanSimpleGithubPart(parts[1].replace(".git", ""));
            }
            return "";
        }

        private String cleanSimpleGithubPart(String value) {
            return firstNonBlank(value, "").replaceAll("[^A-Za-z0-9_.-]", "");
        }
        private void processNewPluginLinks() {
            if (newPluginLinks.isEmpty()) {
                return;
            }
            Set<String> consumedKeys = new HashSet<>();
            for (String rawLink : newPluginLinks) {
                String link = cleanNewPluginLink(rawLink);
                if (link.isBlank()) {
                    continue;
                }
                String consumedKey = lower(link);
                if (!consumedKeys.add(consumedKey)) {
                    consumedNewPluginLinks.add(rawLink);
                    continue;
                }
                TargetConfig incoming = targetFromNewPluginLink(link);
                TargetConfig existing = findExistingPluginForNewPluginLink(incoming);
                if (existing == null) {
                    incoming.name = uniquePluginName(incoming.name);
                    incoming.installAs = "plugins/" + safeName(incoming.name) + ".jar";
                    incoming.platform = inferredPluginPlatform(server);
                    incoming.manualCatalogUpdatedThisRun = true;
                    plugins.add(incoming);
                    Log.info("Added manual plugin catalog entry from newPluginLinks: "
                        + incoming.displayName() + " -> " + incoming.source + ".");
                } else {
                    applyNewPluginLink(existing, incoming);
                    Log.info("Updated manual plugin catalog entry from newPluginLinks: "
                        + existing.displayName() + " -> " + existing.source + ".");
                }
                consumedNewPluginLinks.add(rawLink);
            }
        }

        private String cleanNewPluginLink(String rawLink) {
            String link = ConfigParser.unquote(firstNonBlank(rawLink, "").trim());
            link = link.replaceFirst("^\\d+[.)]\\s+", "").trim();
            return link;
        }

        private TargetConfig targetFromNewPluginLink(String link) {
            String source = canonicalNewPluginSource(link);
            String type = newPluginLinkType(source);
            String project = newPluginLinkProject(type, source);
            String name = inferredNewPluginName(type, source, project);
            if (name.isBlank()) {
                name = nextUnknownPluginName();
            }
            TargetConfig target = new TargetConfig(name, false);
            target.source = source;
            target.sourceOrigin = SOURCE_ORIGIN_MANUAL;
            target.type = type;
            target.project = project;
            target.githubRepo = type.equals("github-source") || type.equals("github-release") ? project : "";
            target.required = false;
            target.autoUpdate = true;
            return target;
        }

        private void applyNewPluginLink(TargetConfig existing, TargetConfig incoming) {
            existing.source = incoming.source;
            existing.sourceOrigin = SOURCE_ORIGIN_MANUAL;
            existing.type = incoming.type;
            existing.project = incoming.project;
            existing.githubRepo = incoming.githubRepo;
            if ((existing.name == null || existing.name.isBlank() || isAutoValue(existing.name))
                && incoming.name != null && !incoming.name.isBlank()) {
                existing.name = incoming.name;
            }
            if ((existing.platform == null || existing.platform.isBlank()) && !firstNonBlank(incoming.platform, "").isBlank()) {
                existing.platform = incoming.platform;
            }
            existing.manualCatalogUpdatedThisRun = true;
            existing.sourceOriginUpdatedThisRun = false;
            existing.sourceDiscoveredThisRun = false;
        }

        private TargetConfig findExistingPluginForNewPluginLink(TargetConfig incoming) {
            String incomingSourceKey = canonicalSourceMatchKey(incoming.source);
            String incomingGithubRepo = firstNonBlank(incoming.githubRepo, githubRepoFromSourceText(incoming.source));
            String incomingName = normalizeIdentity(incoming.name);
            boolean incomingUnknown = incomingName.isBlank() || incomingName.startsWith("unknownplugin");
            for (TargetConfig plugin : plugins) {
                if (plugin.server) {
                    continue;
                }
                if (!incomingSourceKey.isBlank() && sourcesMatchLoosely(plugin.source, incoming.source)) {
                    return plugin;
                }
                for (String fallback : plugin.fallbackSources) {
                    if (!incomingSourceKey.isBlank() && sourcesMatchLoosely(fallback, incoming.source)) {
                        return plugin;
                    }
                }
                String pluginGithubRepo = firstNonBlank(plugin.githubRepo, githubRepoFromSourceText(plugin.source));
                if (!incomingGithubRepo.isBlank() && incomingGithubRepo.equalsIgnoreCase(pluginGithubRepo)) {
                    return plugin;
                }
                if (!incomingUnknown && incomingName.equals(normalizeIdentity(plugin.displayName()))) {
                    return plugin;
                }
            }
            return null;
        }

        private String canonicalNewPluginSource(String link) {
            String value = firstNonBlank(link, "").trim();
            try {
                URI uri = URI.create(value);
                String host = lower(firstNonBlank(uri.getHost(), "")).replaceFirst("^www\\.", "");
                List<String> parts = pathParts(uri);
                if (host.equals("github.com") && parts.size() >= 2) {
                    String owner = cleanSimpleGithubPart(parts.get(0));
                    String repo = cleanSimpleGithubPart(parts.get(1).replace(".git", ""));
                    if (lower(value).contains("/releases/download/") || lower(value).endsWith(".jar")) {
                        return value.split("[?#]", 2)[0];
                    }
                    return "https://github.com/" + owner + "/" + repo;
                }
                if (host.equals("modrinth.com") && parts.size() >= 2
                    && (parts.get(0).equalsIgnoreCase("plugin") || parts.get(0).equalsIgnoreCase("mod"))) {
                    return "https://modrinth.com/plugin/" + parts.get(1) + "/versions";
                }
                if (host.equals("hangar.papermc.io") && parts.size() >= 2) {
                    return "https://hangar.papermc.io/" + cleanSimpleGithubPart(parts.get(0))
                        + "/" + cleanSimpleGithubPart(parts.get(1)) + "/versions";
                }
            } catch (RuntimeException ignored) {
                // Keep the user's text as the manual source when it is not a normal URL.
            }
            if (value.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
                String[] parts = value.split("/", 2);
                return "https://github.com/" + cleanSimpleGithubPart(parts[0])
                    + "/" + cleanSimpleGithubPart(parts[1].replace(".git", ""));
            }
            return value;
        }

        private String newPluginLinkType(String source) {
            String lowerSource = lower(source);
            if (lowerSource.contains("github.com/")
                && !lowerSource.contains("/releases/download/")
                && !lowerSource.endsWith(".jar")) {
                return "github-source";
            }
            if (lowerSource.contains("modrinth.com/")) {
                return "modrinth";
            }
            if (lowerSource.contains("hangar.papermc.io/")) {
                return "hangar";
            }
            if (lowerSource.contains("spigotmc.org/resources") || lowerSource.contains("api.spiget.org/")) {
                return "spigot";
            }
            if (lowerSource.contains("/job/") && !lowerSource.endsWith(".jar")) {
                return "jenkins";
            }
            return "direct";
        }

        private String newPluginLinkProject(String type, String source) {
            if (type.equals("github-source") || type.equals("github-release")) {
                return githubRepoFromSourceText(source);
            }
            try {
                URI uri = URI.create(source);
                List<String> parts = pathParts(uri);
                if (type.equals("modrinth") && parts.size() >= 2) {
                    return parts.get(1);
                }
                if (type.equals("hangar") && parts.size() >= 2) {
                    return cleanSimpleGithubPart(parts.get(0)) + "/" + cleanSimpleGithubPart(parts.get(1));
                }
            } catch (RuntimeException ignored) {
                // Unknown project is acceptable for manual catalog entries.
            }
            return "";
        }

        private String inferredNewPluginName(String type, String source, String project) {
            if (type.equals("github-source") || type.equals("github-release")) {
                String repo = githubRepoFromSourceText(source);
                if (!repo.isBlank()) {
                    return humanPluginName(repo.substring(repo.indexOf('/') + 1));
                }
            }
            if (type.equals("modrinth") && !project.isBlank()) {
                return humanPluginName(project);
            }
            if (type.equals("hangar") && project.contains("/")) {
                return humanPluginName(project.substring(project.indexOf('/') + 1));
            }
            try {
                URI uri = URI.create(source);
                List<String> parts = pathParts(uri);
                if (!parts.isEmpty() && lower(parts.get(parts.size() - 1)).endsWith(".jar")) {
                    return humanPluginName(parts.get(parts.size() - 1).replaceFirst("(?i)\\.jar$", ""));
                }
            } catch (RuntimeException ignored) {
                // Fall through to UnknownPlugin.
            }
            return "";
        }

        private String humanPluginName(String value) {
            String cleaned = firstNonBlank(value, "")
                .replace(".git", "")
                .replaceAll("(?i)-?folia$", "")
                .replaceAll("[^A-Za-z0-9_.-]+", "")
                .trim();
            return cleaned.isBlank() ? "" : cleaned;
        }

        private String nextUnknownPluginName() {
            return uniquePluginName("UnknownPlugin");
        }

        private String uniquePluginName(String wanted) {
            String base = firstNonBlank(wanted, "UnknownPlugin");
            Set<String> used = plugins.stream()
                .map(TargetConfig::displayName)
                .map(AutoUpdater::normalizeIdentity)
                .collect(Collectors.toSet());
            if (!used.contains(normalizeIdentity(base))) {
                return base;
            }
            for (int i = 2; i < 10_000; i++) {
                String candidate = base + i;
                if (!used.contains(normalizeIdentity(candidate))) {
                    return candidate;
                }
            }
            return base + System.currentTimeMillis();
        }

        Path resolve(Path path) {
            if (path.isAbsolute()) {
                return path.normalize();
            }
            return baseDir.resolve(path).normalize();
        }
    }

    private static final class DiscoveryConfig {
        boolean enabled = false;
        String mode = "suggest";
        List<String> sourcePriority = new ArrayList<>(List.of("github-release", "hangar", "modrinth"));
        boolean checkAlternateSourcesWhenOutdated = true;
        int outdatedThresholdDays = 14;
        boolean autoSwitchSource = true;
        boolean saveDiscoveredSources = true;
        boolean scanInstalledPlugins = true;
        boolean pruneMissingInstalledPlugins = true;
        boolean retryDeferredAfterStartup = true;
        List<String> preferredOwners = new ArrayList<>();
        boolean pathfindingDebug = false;
        String pathfindingDebugPlugin = "";
        String pathfindingDebugFile = "architecture-pathfinding.debug";
    }

    private static final class GithubRateLimitState {
        boolean paused;
        Instant resetAt = Instant.EPOCH;
        boolean pauseLogged;
        boolean skipLogged;

        boolean isPaused() {
            if (!paused) {
                return false;
            }
            if (resetAt != null && resetAt.isAfter(Instant.now())) {
                return true;
            }
            paused = false;
            pauseLogged = false;
            skipLogged = false;
            resetAt = Instant.EPOCH;
            return false;
        }

        void pauseUntil(Instant reset) {
            paused = true;
            if (reset != null && (resetAt == null || reset.isAfter(resetAt))) {
                resetAt = reset;
            }
        }

        String resetText() {
            return resetAt == null || resetAt.equals(Instant.EPOCH) ? "the rate limit resets" : resetAt.toString();
        }
    }

    private static final class GithubApiBudget {
        final int maxSearchPerRun = 6;
        final int maxCorePerRun = 28;
        final int maxSearchPerPlugin = 1;
        final int maxCorePerPlugin = 4;
        int searchUsed;
        int coreUsed;
        final Map<String, GithubPluginBudget> pluginBudgets = new HashMap<>();
        boolean warnedRunSearch;
        boolean warnedRunCore;

        void beginPlugin(TargetConfig target) {
            pluginBudgets.put(pluginKey(target), new GithubPluginBudget());
        }

        void reset() {
            searchUsed = 0;
            coreUsed = 0;
            pluginBudgets.clear();
            warnedRunSearch = false;
            warnedRunCore = false;
        }

        boolean tryUse(TargetConfig target, String resource) {
            if (resource.equals("search")) {
                GithubPluginBudget plugin = pluginBudgets.computeIfAbsent(pluginKey(target), ignored -> new GithubPluginBudget());
                if (searchUsed >= maxSearchPerRun) {
                    return false;
                }
                if (plugin.searchUsed >= maxSearchPerPlugin) {
                    return false;
                }
                searchUsed++;
                plugin.searchUsed++;
                return true;
            }
            GithubPluginBudget plugin = pluginBudgets.computeIfAbsent(pluginKey(target), ignored -> new GithubPluginBudget());
            if (coreUsed >= maxCorePerRun) {
                return false;
            }
            if (plugin.coreUsed >= maxCorePerPlugin) {
                return false;
            }
            coreUsed++;
            plugin.coreUsed++;
            return true;
        }

        String status() {
            return "search " + searchUsed + "/" + maxSearchPerRun + ", core " + coreUsed + "/" + maxCorePerRun;
        }

        String status(TargetConfig target) {
            GithubPluginBudget plugin = pluginBudgets.get(pluginKey(target));
            String pluginStatus = plugin == null
                ? "plugin search 0/" + maxSearchPerPlugin + ", plugin core 0/" + maxCorePerPlugin
                : "plugin search " + plugin.searchUsed + "/" + maxSearchPerPlugin
                    + ", plugin core " + plugin.coreUsed + "/" + maxCorePerPlugin;
            return status() + ", " + pluginStatus;
        }

        boolean runBudgetReached(String resource) {
            return resource.equals("search") ? searchUsed >= maxSearchPerRun : coreUsed >= maxCorePerRun;
        }

        String limits() {
            return "search " + maxSearchPerRun + "/run and " + maxSearchPerPlugin + "/plugin, core "
                + maxCorePerRun + "/run and " + maxCorePerPlugin + "/plugin";
        }

        private String pluginKey(TargetConfig target) {
            return normalizedConfigPath(target == null ? "" : firstNonBlank(target.installAs, target.displayName()));
        }
    }

    private static final class GithubPluginBudget {
        int searchUsed;
        int coreUsed;
    }

    private static final class GithubTokenStatus {
        final String value;
        final String source;
        final String warning;
        final boolean configured;

        GithubTokenStatus(String value, String source, String warning, boolean configured) {
            this.value = firstNonBlank(value, "");
            this.source = firstNonBlank(source, "");
            this.warning = firstNonBlank(warning, "");
            this.configured = configured;
        }

        boolean hasToken() {
            return !value.isBlank();
        }

        String display() {
            if (hasToken()) {
                return "configured (" + source + ")";
            }
            return configured ? "configured but not visible to this Java process" : "not visible to this Java process";
        }
    }

    private static final class SourceHint {
        List<String> match = new ArrayList<>();
        String type = "auto";
        String source = "";
        String project = "";
        String githubRepo = "";
        String label = "";
        String reason = "";
        int score = 110;
        int priority = -8;

        boolean matches(TargetConfig target) {
            if (source.isBlank() || match.isEmpty()) {
                return false;
            }
            List<String> tokens = match.stream()
                .filter(token -> token != null && !token.isBlank())
                .toList();
            if (tokens.isEmpty()) {
                return false;
            }
            Set<String> identityTokens = new HashSet<>();
            for (String value : List.of(
                firstNonBlank(target.name, ""),
                firstNonBlank(target.detectedPluginId, ""),
                jarIdentityHint(target.installAs)
            )) {
                String normalized = normalizeIdentity(value);
                if (!normalized.isBlank()) {
                    identityTokens.add(normalized);
                }
            }
            if (!identityTokens.contains(normalizeIdentity(tokens.get(0)))) {
                return false;
            }
            String fingerprint = lower(String.join(" ",
                firstNonBlank(target.name, ""),
                firstNonBlank(target.detectedPluginId, ""),
                firstNonBlank(target.installAs, ""),
                firstNonBlank(target.detectedWebsite, ""),
                firstNonBlank(target.detectedMainClass, ""),
                firstNonBlank(target.detectedAuthors, "")
            ));
            for (String token : tokens.subList(1, tokens.size())) {
                if (!token.isBlank() && !fingerprint.contains(lower(token))) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class DescriptorRankingSignal {
        final int scoreDelta;
        final String reason;

        DescriptorRankingSignal(int scoreDelta, String reason) {
            this.scoreDelta = scoreDelta;
            this.reason = firstNonBlank(reason, "");
        }
    }

    private static List<SourceHint> defaultSourceHints() {
        List<SourceHint> hints = new ArrayList<>();
        hints.add(sourceHint(
            List.of("antipopup", "com.github.kaspiandev.antipopup"),
            "modrinth",
            "https://modrinth.com/plugin/antipopup/versions",
            "antipopup",
            "",
            "AntiPopup official Modrinth listing",
            "installed jar fingerprint: Kaspian AntiPopup; prefer Modrinth official listing",
            122,
            -12
        ));
        hints.add(sourceHint(
            List.of("autopay", "com.tcoded.autopay"),
            "github-source",
            "https://github.com/TechnicallyCoded/AutoPay",
            "TechnicallyCoded/AutoPay",
            "TechnicallyCoded/AutoPay",
            "TechnicallyCoded AutoPay GitHub source",
            "installed jar fingerprint: AutoPay main package com.tcoded.autopay",
            122,
            -12
        ));
        hints.add(sourceHint(
            List.of("betterboard", "hsgamer"),
            "github-release",
            "https://github.com/HSGamer/BetterBoard",
            "HSGamer/BetterBoard",
            "HSGamer/BetterBoard",
            "HSGamer BetterBoard GitHub releases",
            "installed jar fingerprint: HSGamer BetterBoard",
            122,
            -12
        ));
        hints.add(sourceHint(
            List.of("bettereconomy", "me.hsgamer.bettereconomy"),
            "github-release",
            "https://github.com/HSGamer/BetterEconomy",
            "HSGamer/BetterEconomy",
            "HSGamer/BetterEconomy",
            "HSGamer BetterEconomy GitHub releases",
            "installed jar fingerprint: HSGamer BetterEconomy",
            122,
            -12
        ));
        hints.add(sourceHint(
            List.of("betterrtp", "superronancraft"),
            "github-source",
            "https://github.com/RonanPlugins/BetterRTP",
            "RonanPlugins/BetterRTP",
            "RonanPlugins/BetterRTP",
            "RonanPlugins BetterRTP GitHub source",
            "installed jar fingerprint: SuperRonanCraft BetterRTP",
            122,
            -12
        ));
        hints.add(sourceHint(
            List.of("grimcheckup", "me.hsgamer.grimcheckup"),
            "github-source",
            "https://github.com/Folia-Inquisitors/GrimCheckup",
            "Folia-Inquisitors/GrimCheckup",
            "Folia-Inquisitors/GrimCheckup",
            "Folia-Inquisitors GrimCheckup GitHub source",
            "installed jar fingerprint: GrimCheckup main package me.hsgamer.grimcheckup",
            122,
            -12
        ));
        hints.add(sourceHint(
            List.of("grimyatpa", "me.hsgamer.grimyatpa"),
            "github-source",
            "https://github.com/Folia-Inquisitors/GrimYATPA/tree/master",
            "Folia-Inquisitors/GrimYATPA",
            "Folia-Inquisitors/GrimYATPA",
            "Folia-Inquisitors GrimYATPA GitHub source",
            "installed jar fingerprint: GrimYATPA main package me.hsgamer.grimyatpa",
            122,
            -12
        ));
        hints.add(sourceHint(
            List.of("imageframe", "loohp"),
            "github-release",
            "https://github.com/LOOHP/ImageFrame",
            "LOOHP/ImageFrame",
            "LOOHP/ImageFrame",
            "LOOHP ImageFrame GitHub releases",
            "installed jar fingerprint: LOOHP ImageFrame",
            122,
            -12
        ));
        hints.add(sourceHint(
            List.of("keepinv", "me.leonrobi.keepinv"),
            "github-source",
            "https://github.com/Folia-Inquisitors/KeepInv",
            "Folia-Inquisitors/KeepInv",
            "Folia-Inquisitors/KeepInv",
            "Folia-Inquisitors KeepInv GitHub source",
            "installed jar fingerprint: KeepInv main package me.leonrobi.keepinv",
            122,
            -12
        ));
        hints.add(sourceHint(
            List.of("luckperms", "luckperms.net"),
            "github-release",
            "https://github.com/LuckPerms/LuckPerms",
            "LuckPerms/LuckPerms",
            "LuckPerms/LuckPerms",
            "LuckPerms GitHub releases",
            "installed jar fingerprint: LuckPerms official metadata",
            122,
            -12
        ));
        hints.add(sourceHint(
            List.of("modularmob", "me.hsgamer.modularmob"),
            "github-source",
            "https://github.com/Folia-Inquisitors/ModularMob",
            "Folia-Inquisitors/ModularMob",
            "Folia-Inquisitors/ModularMob",
            "Folia-Inquisitors ModularMob GitHub source",
            "installed jar fingerprint: ModularMob main package me.hsgamer.modularmob",
            122,
            -12
        ));
        hints.add(sourceHint(
            List.of("fastasyncworldedit", "com.sk89q.worldedit.bukkit"),
            "github-release",
            "https://github.com/IntellectualSites/FastAsyncWorldEdit",
            "IntellectualSites/FastAsyncWorldEdit",
            "IntellectualSites/FastAsyncWorldEdit",
            "IntellectualSites FastAsyncWorldEdit GitHub releases",
            "installed jar fingerprint: FastAsyncWorldEdit main package com.sk89q.worldedit.bukkit",
            122,
            -12
        ));
        hints.add(sourceHint(
            List.of("globaltrackedmaps", "com.loohp.globaltrackedmaps"),
            "github-source",
            "https://github.com/Folia-Inquisitors/GlobalTrackedMaps",
            "Folia-Inquisitors/GlobalTrackedMaps",
            "Folia-Inquisitors/GlobalTrackedMaps",
            "Folia-Inquisitors GlobalTrackedMaps GitHub source",
            "installed jar fingerprint: GlobalTrackedMaps main package com.loohp.globaltrackedmaps",
            122,
            -12
        ));
        hints.add(sourceHint(
            List.of("grimac", "ac.grim.grimac"),
            "github-release",
            "https://github.com/GrimAnticheat/Grim",
            "GrimAnticheat/Grim",
            "GrimAnticheat/Grim",
            "GrimAnticheat Grim GitHub releases",
            "installed jar fingerprint: GrimAC main package ac.grim.grimac",
            122,
            -12
        ));
        hints.add(sourceHint(
            List.of("illegalstack", "main.java.me.dniym"),
            "github-source",
            "https://github.com/dniym/IllegalStack",
            "dniym/IllegalStack",
            "dniym/IllegalStack",
            "dNiym IllegalStack GitHub source",
            "installed jar fingerprint: IllegalStack main package main.java.me.dniym",
            122,
            -12
        ));
        hints.add(sourceHint(
            List.of("floodgate", "geysermc"),
            "geysermc",
            "https://geysermc.org/download/?project=floodgate",
            "floodgate",
            "",
            "GeyserMC floodgate official download",
            "installed jar fingerprint: GeyserMC Floodgate; prefer current GeyserMC downloads over mirrored hosts",
            120,
            -10
        ));
        hints.add(sourceHint(
            List.of("votifier", "nuvotifier", "com.vexsoftware.votifier"),
            "github-source",
            "https://github.com/NuVotifier/NuVotifier",
            "NuVotifier/NuVotifier",
            "NuVotifier/NuVotifier",
            "NuVotifier official GitHub source",
            "installed jar fingerprint: NuVotifier Bukkit plugin",
            124,
            -14
        ));
        hints.add(sourceHint(
            List.of("clanslite", "loving11ish"),
            "hangar",
            "https://hangar.papermc.io/Loving11ish/ClansLite/versions",
            "Loving11ish/ClansLite",
            "",
            "Loving11ish ClansLite Hangar releases",
            "installed jar fingerprint: Loving11ish ClansLite",
            122,
            -12
        ));
        hints.add(sourceHint(
            List.of("chatfilter", "a4.papers.chatfilter"),
            "github-source",
            "https://github.com/A4Papers/ChatFilter",
            "A4Papers/ChatFilter",
            "A4Papers/ChatFilter",
            "A4Papers ChatFilter GitHub source",
            "installed jar fingerprint: A4Papers/ChatFilter",
            122,
            -12
        ));
        return hints;
    }

    private static SourceHint sourceHint(List<String> match, String type, String source, String project,
                                         String githubRepo, String label, String reason, int score, int priority) {
        SourceHint hint = new SourceHint();
        hint.match = new ArrayList<>(match);
        hint.type = type;
        hint.source = source;
        hint.project = project;
        hint.githubRepo = githubRepo;
        hint.label = label;
        hint.reason = reason;
        hint.score = score;
        hint.priority = priority;
        return hint;
    }

    private static final class BuildFromSourceConfig {
        String enabled = "false";
        boolean onlyTrusted = true;
        boolean preferHostedIfSameVersion = true;
        List<String> trustedGithubOrgs = new ArrayList<>();
        List<String> trustedGithubRepos = new ArrayList<>();

        boolean allowsBuild() {
            String value = lower(enabled);
            return value.equals("true") || value.equals("yes") || value.equals("on") || value.equals("1") || value.equals("auto");
        }

        boolean autoFallback() {
            return lower(enabled).equals("auto");
        }
    }

    private static final class FailureMemoryConfig {
        boolean enabled = true;
        String retryBadAfter = "never";
    }

    private static final class ValidationConfig {
        boolean enabled = true;
        int minAutoInstallScore = 90;
        int minTrustedSourceScore = 85;
        boolean rejectOnPluginNameMismatch = true;
        boolean rejectOnPluginFingerprintMismatch = true;
        boolean rejectWrongPlatform = true;
    }

    private static final class DuplicateConfig {
        boolean enabled = true;
        String action = "quarantine";
        Path directory = Paths.get("backups", "duplicates");
    }

    private static final class RestartConfig {
        boolean enabled = false;
        Duration interval = Duration.ofDays(7);
        String stopCommand = "shutdown";
        int gracefulStopSeconds = 60;
        String startupRollbackPolicy = "rollbackBatch";
        List<RestartWarning> warnings = new ArrayList<>();
    }

    private static final class RestartWarning {
        Duration before = Duration.ZERO;
        String command;
    }

    private static final class SelfUpdateConfig {
        boolean enabled = false;
        String source = "";
        String type = "auto";
        String githubRepo = "";
        String installAs = "auto";
    }

    private static final class ConfigParser {
        static AppConfig parse(Path path) throws IOException {
            AppConfig config = new AppConfig();
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            stripUtf8Bom(lines);
            String section = "";
            String restartSubsection = "";
            String discoverySubsection = "";
            TargetConfig currentPlugin = null;
            SourceHint currentSourceHint = null;
            RestartWarning currentWarning = null;

            for (int lineNo = 1; lineNo <= lines.size(); lineNo++) {
                String raw = lines.get(lineNo - 1);
                String noComment = stripComment(raw);
                if (noComment.trim().isEmpty()) {
                    continue;
                }
                int indent = countIndent(noComment);
                String line = noComment.trim();

                if (indent == 0) {
                    currentPlugin = null;
                    currentSourceHint = null;
                    currentWarning = null;
                    restartSubsection = "";
                    discoverySubsection = "";
                    KeyValue kv = keyValue(line, lineNo);
                    if (kv.value.isEmpty()) {
                        section = lower(kv.key);
                    } else {
                        section = "";
                        applyTopLevel(config, kv);
                    }
                    continue;
                }

                switch (section) {
                    case "server":
                        applyTarget(config.server, keyValue(line, lineNo));
                        break;
                    case "discovery":
                        if (line.equals("preferredOwners:") || line.equals("preferred_owners:")) {
                            discoverySubsection = "preferredowners";
                        } else if (discoverySubsection.equals("preferredowners")) {
                            if (indent <= 2 && !line.startsWith("- ")) {
                                discoverySubsection = "";
                                applyDiscovery(config.discovery, keyValue(line, lineNo));
                            } else if (line.startsWith("- ")) {
                                String owner = ConfigParser.unquote(line.substring(2).trim());
                                if (!owner.isBlank()) {
                                    config.discovery.preferredOwners.add(owner);
                                }
                            } else {
                                throw new IllegalArgumentException("Discovery preferredOwners entry before '-' at line " + lineNo);
                            }
                        } else {
                            applyDiscovery(config.discovery, keyValue(line, lineNo));
                        }
                        break;
                    case "buildfromsource":
                    case "build_from_source":
                        applyBuildFromSource(config.buildFromSource, keyValue(line, lineNo));
                        break;
                    case "failurememory":
                    case "failure_memory":
                        applyFailureMemory(config.failureMemory, keyValue(line, lineNo));
                        break;
                    case "validation":
                        applyValidation(config.validation, keyValue(line, lineNo));
                        break;
                    case "duplicates":
                        applyDuplicates(config.duplicates, keyValue(line, lineNo));
                        break;
                    case "selfupdate":
                    case "self_update":
                        applySelfUpdate(config.selfUpdate, keyValue(line, lineNo));
                        break;
                    case "newpluginlinks":
                    case "new_plugin_links":
                    case "pluginlinkinbox":
                    case "plugin_link_inbox":
                        if (line.startsWith("- ")) {
                            String link = parseNewPluginLinkItem(line.substring(2).trim(), lineNo);
                            if (!link.isBlank()) {
                                config.newPluginLinks.add(link);
                            }
                        } else {
                            throw new IllegalArgumentException("newPluginLinks entry before '-' at line " + lineNo);
                        }
                        break;
                    case "plugins":
                        if (line.startsWith("- ")) {
                            currentPlugin = new TargetConfig(null, false);
                            config.plugins.add(currentPlugin);
                            String rest = line.substring(2).trim();
                            if (!rest.isEmpty()) {
                                applyTarget(currentPlugin, keyValue(rest, lineNo));
                            }
                        } else if (currentPlugin != null) {
                            applyTarget(currentPlugin, keyValue(line, lineNo));
                        } else {
                            throw new IllegalArgumentException("Plugin entry before '-' at line " + lineNo);
                        }
                        break;
                    case "sourcehints":
                    case "source_hints":
                        if (line.startsWith("- ")) {
                            currentSourceHint = new SourceHint();
                            config.sourceHints.add(currentSourceHint);
                            String rest = line.substring(2).trim();
                            if (!rest.isEmpty()) {
                                applySourceHint(currentSourceHint, keyValue(rest, lineNo));
                            }
                        } else if (currentSourceHint != null) {
                            applySourceHint(currentSourceHint, keyValue(line, lineNo));
                        } else {
                            throw new IllegalArgumentException("Source hint entry before '-' at line " + lineNo);
                        }
                        break;
                    case "restart":
                        if (line.equals("warnings:")) {
                            restartSubsection = "warnings";
                            currentWarning = null;
                        } else if (restartSubsection.equals("warnings")) {
                            if (indent <= 2 && !line.startsWith("- ")) {
                                restartSubsection = "";
                                currentWarning = null;
                                applyRestart(config.restart, keyValue(line, lineNo));
                            } else if (line.startsWith("- ")) {
                                currentWarning = new RestartWarning();
                                config.restart.warnings.add(currentWarning);
                                String rest = line.substring(2).trim();
                                if (!rest.isEmpty()) {
                                    applyWarning(currentWarning, keyValue(rest, lineNo));
                                }
                            } else if (currentWarning != null) {
                                applyWarning(currentWarning, keyValue(line, lineNo));
                            } else {
                                throw new IllegalArgumentException("Restart warning before '-' at line " + lineNo);
                            }
                        } else {
                            applyRestart(config.restart, keyValue(line, lineNo));
                        }
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown section '" + section + "' at line " + lineNo);
                }
            }
            return config;
        }

        private static void stripUtf8Bom(List<String> lines) {
            if (lines.isEmpty()) {
                return;
            }
            String first = lines.get(0);
            if (first != null && !first.isEmpty() && first.charAt(0) == '\uFEFF') {
                lines.set(0, first.substring(1));
            }
        }


        private static String parseNewPluginLinkItem(String item, int lineNo) {
            String value = item.trim();
            if (value.isBlank()) {
                return "";
            }
            try {
                KeyValue kv = keyValue(value, lineNo);
                String key = lower(kv.key);
                if (key.equals("url") || key.equals("link") || key.equals("source")) {
                    return ConfigParser.unquote(kv.value.trim());
                }
            } catch (IllegalArgumentException ignored) {
                // Plain list items are the common path.
            }
            return ConfigParser.unquote(value);
        }
        private static void applyTopLevel(AppConfig config, KeyValue kv) {
            switch (lower(kv.key)) {
                case "mode":
                    config.mode = kv.value;
                    break;
                case "onfailure":
                case "on_failure":
                    config.onFailure = kv.value;
                    break;
                case "useragent":
                case "user_agent":
                    config.userAgent = kv.value;
                    break;
                case "githubtoken":
                case "github_token":
                    config.githubToken = kv.value;
                    break;
                case "cachedir":
                case "cache_dir":
                    config.cacheDir = Paths.get(kv.value);
                    config.cacheDirConfigured = true;
                    break;
                case "backupdir":
                case "backup_dir":
                    config.backupDir = Paths.get(kv.value);
                    break;
                case "diagnosticsfile":
                case "diagnostics_file":
                    config.diagnosticsFile = Paths.get(kv.value);
                    break;
                case "discoversources":
                case "discover_sources":
                    config.discovery.enabled = parseBooleanStrict(kv.key, kv.value);
                    break;
                case "newpluginlinks":
                case "new_plugin_links":
                case "pluginlinkinbox":
                case "plugin_link_inbox":
                    config.newPluginLinks.addAll(parseList(kv.value));
                    break;
                default:
                    throw new IllegalArgumentException("Unknown config key: " + kv.key);
            }
        }

        private static void applyTarget(TargetConfig target, KeyValue kv) {
            switch (lower(kv.key)) {
                case "name":
                    target.name = kv.value;
                    break;
                case "enabled":
                    target.enabled = parseBooleanStrict(kv.key, kv.value);
                    break;
                case "autoupdate":
                case "auto_update":
                    target.autoUpdate = parseBooleanStrict(kv.key, kv.value);
                    break;
                case "required":
                    target.required = parseBooleanStrict(kv.key, kv.value);
                    break;
                case "source":
                    target.source = kv.value;
                    break;
                case "sourceorigin":
                case "source_origin":
                    target.sourceOrigin = parseSourceOriginStrict(kv.value);
                    break;
                case "fallbacksources":
                case "fallback_sources":
                    target.fallbackSources = parseList(kv.value);
                    break;
                case "type":
                    target.type = kv.value;
                    break;
                case "project":
                    target.project = kv.value;
                    break;
                case "githubrepo":
                case "github_repo":
                    target.githubRepo = kv.value;
                    break;
                case "platform":
                    target.platform = kv.value;
                    break;
                case "loader":
                    target.loader = kv.value;
                    break;
                case "gameversion":
                case "game_version":
                    target.gameVersion = kv.value;
                    break;
                case "versiontype":
                case "version_type":
                    target.versionType = kv.value;
                    break;
                case "changeversion":
                case "change_version":
                    target.changeVersion = parseBooleanStrict(kv.key, kv.value);
                    break;
                case "channel":
                    target.channel = kv.value;
                    break;
                case "pinbuild":
                case "pin_build":
                case "build":
                    target.pinBuild = kv.value;
                    break;
                case "installas":
                case "install_as":
                    target.installAs = kv.value;
                    break;
                case "java":
                    target.java = kv.value;
                    break;
                case "javaargs":
                case "java_args":
                    target.javaArgs = kv.value;
                    break;
                case "args":
                    target.args = kv.value;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown target key: " + kv.key);
            }
        }

        private static void applyDiscovery(DiscoveryConfig discovery, KeyValue kv) {
            switch (lower(kv.key)) {
                case "enabled":
                    discovery.enabled = parseBooleanStrict(kv.key, kv.value);
                    break;
                case "mode":
                    discovery.mode = kv.value;
                    break;
                case "sourcepriority":
                case "source_priority":
                    discovery.sourcePriority = parseList(kv.value);
                    break;
                case "checkalternatesourceswhenoutdated":
                case "check_alternate_sources_when_outdated":
                    discovery.checkAlternateSourcesWhenOutdated = parseBooleanStrict(kv.key, kv.value);
                    break;
                case "outdatedthresholddays":
                case "outdated_threshold_days":
                    discovery.outdatedThresholdDays = Integer.parseInt(kv.value);
                    break;
                case "autoswitchsource":
                case "auto_switch_source":
                    discovery.autoSwitchSource = parseBooleanStrict(kv.key, kv.value);
                    break;
                case "savediscoveredsources":
                case "save_discovered_sources":
                    discovery.saveDiscoveredSources = parseBooleanStrict(kv.key, kv.value);
                    break;
                case "scaninstalledplugins":
                case "scan_installed_plugins":
                    discovery.scanInstalledPlugins = parseBooleanStrict(kv.key, kv.value);
                    break;
                case "prunemissinginstalledplugins":
                case "prune_missing_installed_plugins":
                    discovery.pruneMissingInstalledPlugins = parseBooleanStrict(kv.key, kv.value);
                    break;
                case "retrydeferredafterstartup":
                case "retry_deferred_after_startup":
                    discovery.retryDeferredAfterStartup = parseBooleanStrict(kv.key, kv.value);
                    break;
                case "preferredowners":
                case "preferred_owners":
                    discovery.preferredOwners = parseList(kv.value);
                    break;
                case "pathfindingdebug":
                case "pathfinding_debug":
                case "architecturepathfindingdebug":
                case "architecture_pathfinding_debug":
                case "archestecturepathfindingdebug":
                case "archestecture_pathfinding_debug":
                    discovery.pathfindingDebug = parseBooleanStrict(kv.key, kv.value);
                    break;
                case "pathfindingdebugplugin":
                case "pathfinding_debug_plugin":
                case "architecturepathfindingdebugplugin":
                case "architecture_pathfinding_debug_plugin":
                    discovery.pathfindingDebugPlugin = kv.value;
                    break;
                case "pathfindingdebugfile":
                case "pathfinding_debug_file":
                case "architecturepathfindingdebugfile":
                case "architecture_pathfinding_debug_file":
                    discovery.pathfindingDebugFile = kv.value;
                    break;
                case "allowspigotdiscovery":
                case "allow_spigot_discovery":
                    // Deprecated no-op. Spigot/Spiget update sources are no longer supported.
                    break;
                case "allowspigotsources":
                case "allow_spigot_sources":
                    // Deprecated no-op. Spigot/Spiget update sources are no longer supported.
                    break;
                default:
                    throw new IllegalArgumentException("Unknown discovery key: " + kv.key);
            }
        }

        private static void applySourceHint(SourceHint hint, KeyValue kv) {
            switch (lower(kv.key)) {
                case "match":
                case "matches":
                    hint.match = parseList(kv.value);
                    break;
                case "type":
                    hint.type = kv.value;
                    break;
                case "source":
                    hint.source = kv.value;
                    break;
                case "project":
                case "projecthint":
                case "project_hint":
                    hint.project = kv.value;
                    break;
                case "githubrepo":
                case "github_repo":
                    hint.githubRepo = kv.value;
                    break;
                case "label":
                    hint.label = kv.value;
                    break;
                case "reason":
                    hint.reason = kv.value;
                    break;
                case "score":
                    hint.score = Integer.parseInt(kv.value);
                    break;
                case "priority":
                    hint.priority = Integer.parseInt(kv.value);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown sourceHint key: " + kv.key);
            }
        }

        private static void applyBuildFromSource(BuildFromSourceConfig build, KeyValue kv) {
            switch (lower(kv.key)) {
                case "enabled":
                    build.enabled = parseBuildFromSourceEnabledStrict(kv.value);
                    break;
                case "onlytrusted":
                case "only_trusted":
                    build.onlyTrusted = parseBooleanStrict(kv.key, kv.value);
                    break;
                case "preferhostedifsameversion":
                case "prefer_hosted_if_same_version":
                    build.preferHostedIfSameVersion = parseBooleanStrict(kv.key, kv.value);
                    break;
                case "trustedgithuborgs":
                case "trusted_github_orgs":
                    build.trustedGithubOrgs = parseList(kv.value);
                    break;
                case "trustedgithubrepos":
                case "trusted_github_repos":
                    build.trustedGithubRepos = parseList(kv.value);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown buildFromSource key: " + kv.key);
            }
        }

        private static void applyFailureMemory(FailureMemoryConfig failureMemory, KeyValue kv) {
            switch (lower(kv.key)) {
                case "enabled":
                    failureMemory.enabled = parseBooleanStrict(kv.key, kv.value);
                    break;
                case "retrybadafter":
                case "retry_bad_after":
                    failureMemory.retryBadAfter = kv.value;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown failureMemory key: " + kv.key);
            }
        }

        private static void applyValidation(ValidationConfig validation, KeyValue kv) {
            switch (lower(kv.key)) {
                case "enabled":
                    validation.enabled = parseBooleanStrict(kv.key, kv.value);
                    break;
                case "minautoinstallscore":
                case "min_auto_install_score":
                    validation.minAutoInstallScore = Integer.parseInt(kv.value);
                    break;
                case "mintrustedsourcescore":
                case "min_trusted_source_score":
                    validation.minTrustedSourceScore = Integer.parseInt(kv.value);
                    break;
                case "rejectonpluginnamemismatch":
                case "reject_on_plugin_name_mismatch":
                    validation.rejectOnPluginNameMismatch = parseBooleanStrict(kv.key, kv.value);
                    break;
                case "rejectonpluginfingerprintmismatch":
                case "reject_on_plugin_fingerprint_mismatch":
                    validation.rejectOnPluginFingerprintMismatch = parseBooleanStrict(kv.key, kv.value);
                    break;
                case "rejectwrongplatform":
                case "reject_wrong_platform":
                    validation.rejectWrongPlatform = parseBooleanStrict(kv.key, kv.value);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown validation key: " + kv.key);
            }
        }

        private static void applyDuplicates(DuplicateConfig duplicates, KeyValue kv) {
            switch (lower(kv.key)) {
                case "enabled":
                    duplicates.enabled = parseBooleanStrict(kv.key, kv.value);
                    break;
                case "action":
                    duplicates.action = kv.value;
                    break;
                case "directory":
                    duplicates.directory = Paths.get(kv.value);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown duplicates key: " + kv.key);
            }
        }

        private static void applyRestart(RestartConfig restart, KeyValue kv) {
            switch (lower(kv.key)) {
                case "enabled":
                    restart.enabled = parseBooleanStrict(kv.key, kv.value);
                    break;
                case "interval":
                    restart.interval = parseDuration(kv.value);
                    break;
                case "stopcommand":
                case "stop_command":
                    restart.stopCommand = kv.value;
                    break;
                case "gracefulstopseconds":
                case "graceful_stop_seconds":
                    restart.gracefulStopSeconds = Integer.parseInt(kv.value);
                    break;
                case "startuprollbackpolicy":
                case "startup_rollback_policy":
                    restart.startupRollbackPolicy = kv.value;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown restart key: " + kv.key);
            }
        }

        private static void applySelfUpdate(SelfUpdateConfig selfUpdate, KeyValue kv) {
            switch (lower(kv.key)) {
                case "enabled":
                    selfUpdate.enabled = parseBooleanStrict(kv.key, kv.value);
                    break;
                case "source":
                    selfUpdate.source = kv.value;
                    break;
                case "type":
                    selfUpdate.type = kv.value;
                    break;
                case "githubrepo":
                case "github_repo":
                    selfUpdate.githubRepo = kv.value;
                    break;
                case "installas":
                case "install_as":
                    selfUpdate.installAs = kv.value;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown selfUpdate key: " + kv.key);
            }
        }

        private static void applyWarning(RestartWarning warning, KeyValue kv) {
            switch (lower(kv.key)) {
                case "before":
                    warning.before = parseDuration(kv.value);
                    break;
                case "command":
                    warning.command = kv.value;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown warning key: " + kv.key);
            }
        }

        private static boolean parseBooleanStrict(String fieldName, String value) {
            String v = lower(value);
            if (v.equals("true") || v.equals("yes") || v.equals("on") || v.equals("1")) {
                return true;
            }
            if (v.equals("false") || v.equals("no") || v.equals("off") || v.equals("0")) {
                return false;
            }
            throw new IllegalArgumentException(fieldName + " must be a boolean (true/false/yes/no/on/off/1/0), found: " + value);
        }

        private static String parseBuildFromSourceEnabledStrict(String value) {
            String v = lower(value);
            if (v.equals("auto")) {
                return "auto";
            }
            parseBooleanStrict("buildFromSource.enabled", value);
            return v;
        }

        private static String parseSourceOriginStrict(String value) {
            String origin = lower(firstNonBlank(value, ""));
            if (origin.isBlank()
                || origin.equals(SOURCE_ORIGIN_MANUAL)
                || origin.equals(SOURCE_ORIGIN_DISCOVERED)
                || origin.equals(SOURCE_ORIGIN_DISCOVERED_UNVERIFIED)
                || origin.equals(SOURCE_ORIGIN_UNRESOLVED)) {
                return origin;
            }
            throw new IllegalArgumentException("sourceOrigin must be manual, discovered, discovered-unverified, or unresolved; found: " + value);
        }

        private static KeyValue keyValue(String line, int lineNo) {
            int colon = findColon(line);
            if (colon < 0) {
                throw new IllegalArgumentException("Expected key: value at line " + lineNo + ": " + line);
            }
            String key = line.substring(0, colon).trim();
            String value = unquote(line.substring(colon + 1).trim());
            return new KeyValue(key, value);
        }

        private static int findColon(String line) {
            boolean single = false;
            boolean dbl = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '\'' && !dbl) {
                    single = !single;
                } else if (c == '"' && !single) {
                    dbl = !dbl;
                } else if (c == ':' && !single && !dbl) {
                    return i;
                }
            }
            return -1;
        }

        private static String stripComment(String line) {
            boolean single = false;
            boolean dbl = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '\'' && !dbl) {
                    single = !single;
                } else if (c == '"' && !single) {
                    dbl = !dbl;
                } else if (c == '#' && !single && !dbl) {
                    return line.substring(0, i);
                }
            }
            return line;
        }

        private static int countIndent(String line) {
            int count = 0;
            while (count < line.length() && line.charAt(count) == ' ') {
                count++;
            }
            return count;
        }

        private static String unquote(String value) {
            if (value.length() >= 2) {
                char first = value.charAt(0);
                char last = value.charAt(value.length() - 1);
                if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                    return value.substring(1, value.length() - 1);
                }
            }
            return value;
        }
    }

    private static final class ConfigRewriter {
        static void refreshConfigSchema(AppConfig config) throws IOException {
            if (config.configPath == null || !Files.exists(config.configPath)) {
                return;
            }
            List<String> lines = Files.readAllLines(config.configPath, StandardCharsets.UTF_8);
            boolean changed = normalizeEditableConfigSettings(lines, config);
            if (!changed) {
                return;
            }
            writeConfigLines(config.configPath, lines);
            Log.info("Updated config schema defaults in " + config.configPath.getFileName() + ".");
        }
        static void saveDiscoveredPluginSources(AppConfig config, List<TargetConfig> targets) throws IOException {
            if (config.configPath == null) {
                Log.warn("Config path is unknown; discovered sources were not saved.");
                return;
            }
            List<String> lines = Files.exists(config.configPath)
                ? Files.readAllLines(config.configPath, StandardCharsets.UTF_8)
                : new ArrayList<>();
            boolean changed = false;
            boolean settingsChanged = normalizeEditableConfigSettings(lines, config);
            changed |= settingsChanged;
            int consumedNewPluginLinks = removeConsumedNewPluginLinks(lines, config);
            changed |= consumedNewPluginLinks > 0;
            int savedDiscoveredSources = 0;
            int savedSourceOriginCleanups = 0;
            int prunedMissingPlugins = 0;
            for (TargetConfig target : config.prunedMissingPlugins) {
                PluginBlock block = findPluginBlock(lines, target);
                if (block != null) {
                    lines.subList(block.start, block.end).clear();
                    changed = true;
                    prunedMissingPlugins++;
                }
            }
            for (TargetConfig target : targets) {
                PluginBlock block = findPluginBlock(lines, target);
                if (block == null) {
                    appendPluginBlock(lines, target);
                    changed = true;
                    if (target.sourceDiscoveredThisRun) {
                        savedDiscoveredSources++;
                    } else if (target.sourceOriginUpdatedThisRun) {
                        savedSourceOriginCleanups++;
                    }
                } else if (target.sourceOriginUpdatedThisRun && !target.sourceDiscoveredThisRun) {
                    if (updatePluginSourceOrigin(lines, block, target)) {
                        changed = true;
                        savedSourceOriginCleanups++;
                    }
                } else if (updatePluginBlock(lines, block, target)) {
                    changed = true;
                    savedDiscoveredSources++;
                }
            }
            // Sorting also normalizes hand-edited configs, so run it even when discovery had nothing new to save.
            List<String> beforeSort = new ArrayList<>(lines);
            sortPluginBlocks(lines);
            changed |= !lines.equals(beforeSort);
            if (!changed) {
                return;
            }

            writeConfigLines(config.configPath, lines);
            if (consumedNewPluginLinks > 0) {
                Log.info("Imported " + consumedNewPluginLinks + " newPluginLinks item"
                    + (consumedNewPluginLinks == 1 ? "" : "s") + " into the manual plugin catalog in "
                    + config.configPath.getFileName() + ".");
            } else if (prunedMissingPlugins > 0) {
                Log.info("Pruned missing auto-managed plugin entr"
                    + (prunedMissingPlugins == 1 ? "y" : "ies") + " from " + config.configPath.getFileName() + ".");
            } else if (savedDiscoveredSources > 0 && savedSourceOriginCleanups > 0) {
                Log.info("Saved discovered source" + (savedDiscoveredSources == 1 ? "" : "s")
                    + " and manual source-origin cleanup" + (savedSourceOriginCleanups == 1 ? "" : "s")
                    + " to " + config.configPath.getFileName() + ".");
            } else if (savedDiscoveredSources > 0) {
                Log.info("Saved discovered source" + (savedDiscoveredSources == 1 ? "" : "s")
                    + " to " + config.configPath.getFileName() + ".");
            } else if (savedSourceOriginCleanups > 0) {
                Log.info("Saved manual source-origin cleanup" + (savedSourceOriginCleanups == 1 ? "" : "s")
                    + " to " + config.configPath.getFileName() + ".");
            } else if (settingsChanged) {
                Log.info("Updated config settings in " + config.configPath.getFileName() + ".");
            } else {
                Log.info("Organized plugin entries in " + config.configPath.getFileName() + ".");
            }
        }

        private static int removeConsumedNewPluginLinks(List<String> lines, AppConfig config) {
            if (config.consumedNewPluginLinks.isEmpty()) {
                return 0;
            }
            Set<String> consumed = config.consumedNewPluginLinks.stream()
                .map(ConfigRewriter::normalizedNewPluginInboxLink)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toSet());
            if (consumed.isEmpty()) {
                return 0;
            }
            int sectionStart = findTopLevelSectionByNormalized(lines, Set.of("newpluginlinks", "pluginlinkinbox"));
            if (sectionStart < 0) {
                return 0;
            }
            String noComment = ConfigParser.stripComment(lines.get(sectionStart));
            KeyValue header = ConfigParser.keyValue(noComment.trim(), sectionStart + 1);
            String renderedKey = firstNonBlank(header.key, "newPluginLinks");
            if (!header.value.isBlank()) {
                List<String> kept = new ArrayList<>();
                int removed = 0;
                for (String item : parseList(header.value)) {
                    if (consumed.contains(normalizedNewPluginInboxLink(item))) {
                        removed++;
                    } else if (!item.isBlank()) {
                        kept.add(item);
                    }
                }
                if (removed > 0) {
                    lines.set(sectionStart, renderedKey + ": " + (kept.isEmpty() ? "[]" : quoteYaml(String.join(", ", kept))));
                }
                return removed;
            }
            int sectionEnd = findNextTopLevel(lines, sectionStart + 1);
            int removed = 0;
            for (int i = sectionStart + 1; i < sectionEnd;) {
                String itemNoComment = ConfigParser.stripComment(lines.get(i));
                String item = itemNoComment.trim();
                if (item.startsWith("- ")) {
                    String link = ConfigParser.parseNewPluginLinkItem(item.substring(2).trim(), i + 1);
                    if (consumed.contains(normalizedNewPluginInboxLink(link))) {
                        lines.remove(i);
                        sectionEnd--;
                        removed++;
                        continue;
                    }
                }
                i++;
            }
            if (removed > 0 && !hasListItems(lines, sectionStart + 1, sectionEnd)) {
                lines.subList(sectionStart + 1, sectionEnd).clear();
                lines.set(sectionStart, renderedKey + ": []");
            }
            return removed;
        }

        private static boolean hasListItems(List<String> lines, int start, int end) {
            for (int i = start; i < end; i++) {
                String noComment = ConfigParser.stripComment(lines.get(i));
                if (noComment.trim().startsWith("- ")) {
                    return true;
                }
            }
            return false;
        }

        private static int findTopLevelSectionByNormalized(List<String> lines, Set<String> normalizedNames) {
            for (int i = 0; i < lines.size(); i++) {
                String noComment = ConfigParser.stripComment(lines.get(i));
                if (noComment.trim().isEmpty() || ConfigParser.countIndent(noComment) != 0) {
                    continue;
                }
                try {
                    KeyValue kv = ConfigParser.keyValue(noComment.trim(), i + 1);
                    if (kv.value.isEmpty() && normalizedNames.contains(normalizedKey(kv.key))) {
                        return i;
                    }
                    if (!kv.value.isEmpty() && normalizedNames.contains(normalizedKey(kv.key))) {
                        return i;
                    }
                } catch (IllegalArgumentException ignored) {
                    // Ignore lines that are not key/value entries.
                }
            }
            return -1;
        }

        private static String normalizedNewPluginInboxLink(String value) {
            String link = ConfigParser.unquote(firstNonBlank(value, "").trim())
                .replaceFirst("^\\d+[.)]\\s+", "")
                .trim();
            return lower(link);
        }
        private static void writeConfigLines(Path configPath, List<String> lines) throws IOException {
            String newline = detectNewline(configPath);
            String text = String.join(newline, lines) + newline;
            Path temp = configPath.resolveSibling(configPath.getFileName() + ".tmp");
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try {
                Files.move(temp, configPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        private static boolean normalizeEditableConfigSettings(List<String> lines, AppConfig config) {
            boolean changed = false;
            changed |= ensureTopLevelGithubToken(lines, config);
            changed |= ensureNewPluginLinksInbox(lines);
            changed |= normalizeDiscoverySourcePriority(lines, config);
            changed |= ensureDiscoverySchemaDefaults(lines);
            changed |= refreshDiscoveryComments(lines);
            return changed;
        }

        private static boolean ensureNewPluginLinksInbox(List<String> lines) {
            if (findTopLevelSectionByNormalized(lines, Set.of("newpluginlinks", "pluginlinkinbox")) >= 0) {
                return false;
            }
            int insertAt = findTopLevelSection(lines, "discovery");
            if (insertAt < 0) {
                insertAt = findTopLevelSection(lines, "plugins");
            }
            if (insertAt < 0) {
                insertAt = lines.size();
            }
            if (insertAt > 0 && !lines.get(insertAt - 1).isBlank()) {
                lines.add(insertAt, "");
                insertAt++;
            }
            lines.add(insertAt, "newPluginLinks: []");
            insertAt++;
            if (insertAt < lines.size() && !lines.get(insertAt).isBlank()) {
                lines.add(insertAt, "");
            }
            return true;
        }

        private static boolean ensureDiscoverySchemaDefaults(List<String> lines) {
            boolean changed = false;
            changed |= ensureDiscoveryKey(lines, "checkAlternateSourcesWhenOutdated", "true");
            changed |= ensureDiscoveryKey(lines, "outdatedThresholdDays", "14");
            changed |= ensureDiscoveryKey(lines, "autoSwitchSource", "true");
            changed |= ensureDiscoveryKey(lines, "saveDiscoveredSources", "true");
            changed |= ensureDiscoveryKey(lines, "scanInstalledPlugins", "true");
            changed |= ensureDiscoveryKey(lines, "pruneMissingInstalledPlugins", "true");
            changed |= ensureDiscoveryKey(lines, "retryDeferredAfterStartup", "true");
            changed |= ensureDiscoveryKey(lines, "pathfindingDebug", "false");
            changed |= ensureDiscoveryKey(lines, "pathfindingDebugPlugin", quoteYaml(""));
            changed |= ensureDiscoveryKey(lines, "pathfindingDebugFile", "architecture-pathfinding.debug");
            return changed;
        }

        private static boolean ensureDiscoveryKey(List<String> lines, String key, String value) {
            int discoveryStart = findTopLevelSection(lines, "discovery");
            if (discoveryStart < 0) {
                return false;
            }
            int discoveryEnd = findNextTopLevel(lines, discoveryStart + 1);
            String normalized = normalizedKey(key);
            for (int i = discoveryStart + 1; i < discoveryEnd; i++) {
                String noComment = ConfigParser.stripComment(lines.get(i));
                if (noComment.trim().isEmpty() || ConfigParser.countIndent(noComment) <= 0) {
                    continue;
                }
                try {
                    KeyValue kv = ConfigParser.keyValue(noComment.trim(), ConfigParser.countIndent(noComment));
                    if (normalizedKey(kv.key).equals(normalized)) {
                        return false;
                    }
                } catch (IllegalArgumentException ignored) {
                    // Keep scanning discovery entries.
                }
            }
            lines.add(discoveryEnd, "  " + key + ": " + value);
            return true;
        }
        private static boolean ensureTopLevelGithubToken(List<String> lines, AppConfig config) {
            if (topLevelKeyIndex(lines, "githubToken") >= 0 || topLevelKeyIndex(lines, "github_token") >= 0) {
                return normalizeExistingGithubToken(lines, config);
            }
            if (config.githubToken != null && !config.githubToken.isBlank()) {
                return false;
            }
            int insertAt = topLevelKeyIndex(lines, "userAgent");
            if (insertAt >= 0) {
                lines.add(insertAt + 1, "githubToken: env:GITHUB_TOKEN");
                config.githubToken = "env:GITHUB_TOKEN";
                return true;
            }
            int discoveryStart = findTopLevelSection(lines, "discovery");
            if (discoveryStart >= 0) {
                lines.add(discoveryStart, "githubToken: env:GITHUB_TOKEN");
                lines.add(discoveryStart + 1, "");
                config.githubToken = "env:GITHUB_TOKEN";
                return true;
            }
            int pluginsStart = findTopLevelSection(lines, "plugins");
            int targetIndex = pluginsStart >= 0 ? pluginsStart : lines.size();
            if (targetIndex > 0 && !lines.get(targetIndex - 1).isBlank()) {
                lines.add(targetIndex, "");
                targetIndex++;
            }
            lines.add(targetIndex, "githubToken: env:GITHUB_TOKEN");
            config.githubToken = "env:GITHUB_TOKEN";
            return true;
        }

        private static boolean normalizeExistingGithubToken(List<String> lines, AppConfig config) {
            for (int i = 0; i < lines.size(); i++) {
                String noComment = ConfigParser.stripComment(lines.get(i));
                if (noComment.trim().isEmpty() || ConfigParser.countIndent(noComment) != 0) {
                    continue;
                }
                try {
                    KeyValue kv = ConfigParser.keyValue(noComment.trim(), 0);
                    String normalized = normalizedKey(kv.key);
                    if (!normalized.equals("githubtoken")) {
                        continue;
                    }
                    String value = ConfigParser.unquote(firstNonBlank(kv.value, "").trim());
                    if (!lower(value).startsWith("env:")) {
                        return false;
                    }
                    String envName = value.substring(4).trim();
                    if (!looksLikeGithubToken(envName)) {
                        return false;
                    }
                    String replacement = kv.key + ": env:GITHUB_TOKEN";
                    if (!lines.get(i).equals(replacement)) {
                        lines.set(i, replacement);
                        config.githubToken = "env:GITHUB_TOKEN";
                        Log.warn("Rewrote githubToken to env:GITHUB_TOKEN because the config had a literal token after env:. Rotate that token in GitHub.");
                        return true;
                    }
                    config.githubToken = "env:GITHUB_TOKEN";
                    return false;
                } catch (IllegalArgumentException ignored) {
                    // Keep scanning top-level keys.
                }
            }
            return false;
        }

        private static boolean normalizeDiscoverySourcePriority(List<String> lines, AppConfig config) {
            int discoveryStart = findTopLevelSection(lines, "discovery");
            if (discoveryStart < 0) {
                return false;
            }
            int discoveryEnd = findNextTopLevel(lines, discoveryStart + 1);
            for (int i = discoveryStart + 1; i < discoveryEnd; i++) {
                String noComment = ConfigParser.stripComment(lines.get(i));
                if (noComment.trim().isEmpty() || ConfigParser.countIndent(noComment) <= 0) {
                    continue;
                }
                try {
                    KeyValue kv = ConfigParser.keyValue(noComment.trim(), ConfigParser.countIndent(noComment));
                    if (!normalizedKey(kv.key).equals("sourcepriority")) {
                        continue;
                    }
                    List<String> normalized = normalizedDiscoveryPriorityValues(parseList(kv.value));
                    if (normalized.isEmpty()) {
                        normalized = DEFAULT_DISCOVERY_SOURCE_PRIORITY;
                    }
                    config.discovery.sourcePriority = new ArrayList<>(normalized);
                    String replacement = spaces(ConfigParser.countIndent(noComment)) + "sourcePriority: " + String.join(", ", normalized);
                    if (lines.get(i).equals(replacement)) {
                        return false;
                    }
                    lines.set(i, replacement);
                    return true;
                } catch (IllegalArgumentException ignored) {
                    // Keep scanning the discovery block.
                }
            }
            List<String> normalized = normalizedDiscoveryPriorityValues(config.discovery.sourcePriority);
            if (normalized.isEmpty()) {
                normalized = DEFAULT_DISCOVERY_SOURCE_PRIORITY;
            }
            config.discovery.sourcePriority = new ArrayList<>(normalized);
            lines.add(discoveryStart + 1, "  sourcePriority: " + String.join(", ", normalized));
            return true;
        }

        private static List<String> normalizedDiscoveryPriorityValues(List<String> values) {
            List<String> normalized = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String value : values) {
                String type = lower(value);
                if (type.equals("github")) {
                    type = "github-release";
                }
                if (type.equals("spiget")) {
                    type = "spigot";
                }
                if (type.equals("spigot") || type.equals("jenkins")) {
                    continue;
                }
                if ((type.equals("github-release") || type.equals("hangar") || type.equals("modrinth"))
                    && seen.add(type)) {
                    normalized.add(type);
                }
            }
            return normalized;
        }

        private static boolean refreshDiscoveryComments(List<String> lines) {
            boolean changed = false;
            for (int i = 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).trim();
                if (trimmed.equals("#   Supported source families: github-release, hangar, modrinth, spigot.")) {
                    lines.set(i, indentOf(lines.get(i)) + "#   Supported source families: github-release, hangar, modrinth.");
                    changed = true;
                    continue;
                }
                if (trimmed.equals("#   It also searches GitHub, Hangar, Modrinth, and Spigot for likely update")) {
                    lines.set(i, indentOf(lines.get(i)) + "#   It also searches GitHub, Hangar, and Modrinth for likely update");
                    changed = true;
                    continue;
                }
                if (trimmed.equals("#     Download from Spigot through Spiget when available without login.")) {
                    lines.set(i, indentOf(lines.get(i)) + "#     Manual-only download from Spigot through Spiget when available without login.");
                    changed = true;
                }
            }
            return changed;
        }

        private static String indentOf(String line) {
            int end = 0;
            while (end < line.length() && Character.isWhitespace(line.charAt(end))) {
                end++;
            }
            return line.substring(0, end);
        }

        private static int topLevelKeyIndex(List<String> lines, String key) {
            String normalized = normalizedKey(key);
            for (int i = 0; i < lines.size(); i++) {
                String noComment = ConfigParser.stripComment(lines.get(i));
                if (noComment.trim().isEmpty() || ConfigParser.countIndent(noComment) != 0) {
                    continue;
                }
                try {
                    KeyValue kv = ConfigParser.keyValue(noComment.trim(), 0);
                    if (normalizedKey(kv.key).equals(normalized)) {
                        return i;
                    }
                } catch (IllegalArgumentException ignored) {
                    // Ignore non key/value lines.
                }
            }
            return -1;
        }

        private static void sortPluginBlocks(List<String> lines) {
            int pluginsStart = findTopLevelSection(lines, "plugins");
            if (pluginsStart < 0) {
                return;
            }
            int pluginsEnd = findNextTopLevel(lines, pluginsStart + 1);
            List<String> prelude = new ArrayList<>();
            List<List<String>> blocks = new ArrayList<>();
            int cursor = pluginsStart + 1;
            while (cursor < pluginsEnd) {
                String noComment = ConfigParser.stripComment(lines.get(cursor));
                String trimmed = noComment.trim();
                if (trimmed.isEmpty() || !trimmed.startsWith("- ")) {
                    prelude.add(lines.get(cursor));
                    cursor++;
                    continue;
                }
                int start = cursor;
                int indent = ConfigParser.countIndent(noComment);
                int end = cursor + 1;
                while (end < pluginsEnd) {
                    String candidateNoComment = ConfigParser.stripComment(lines.get(end));
                    String candidateTrimmed = candidateNoComment.trim();
                    if (!candidateTrimmed.isEmpty()) {
                        int candidateIndent = ConfigParser.countIndent(candidateNoComment);
                        if (candidateIndent <= indent && candidateTrimmed.startsWith("- ")) {
                            break;
                        }
                    }
                    end++;
                }
                blocks.add(new ArrayList<>(lines.subList(start, end)));
                cursor = end;
            }
            if (blocks.isEmpty()) {
                return;
            }
            List<List<String>> manual = new ArrayList<>();
            List<List<String>> discovered = new ArrayList<>();
            List<List<String>> unresolved = new ArrayList<>();
            for (List<String> block : blocks) {
                List<String> cleaned = cleanedPluginBlock(block);
                int group = pluginBlockSourceGroup(cleaned);
                if (group == 2) {
                    unresolved.add(cleaned);
                } else if (group == 1) {
                    discovered.add(cleaned);
                } else {
                    manual.add(cleaned);
                }
            }
            Comparator<List<String>> byName = Comparator.comparing(ConfigRewriter::pluginBlockSortName, String.CASE_INSENSITIVE_ORDER);
            manual.sort(byName);
            discovered.sort(byName);
            unresolved.sort(byName);

            List<String> replacement = cleanedPluginPrelude(prelude);
            appendPluginGroup(replacement, "Manual sources", manual);
            appendPluginGroup(replacement, "Discovered sources", discovered);
            appendPluginGroup(replacement, "Unresolved sources", unresolved);
            lines.subList(pluginsStart + 1, pluginsEnd).clear();
            lines.addAll(pluginsStart + 1, replacement);
        }

        private static List<String> cleanedPluginPrelude(List<String> prelude) {
            List<String> cleaned = new ArrayList<>();
            for (String line : prelude) {
                if (isPluginGroupComment(line)) {
                    continue;
                }
                cleaned.add(line);
            }
            trimTrailingBlankLines(cleaned);
            return cleaned;
        }

        private static List<String> cleanedPluginBlock(List<String> block) {
            List<String> cleaned = new ArrayList<>(block);
            relocatePluginKeysAfterDecorativeTail(cleaned);
            while (!cleaned.isEmpty()) {
                String line = cleaned.get(cleaned.size() - 1);
                String trimmed = line.trim();
                if (line.isBlank() || trimmed.startsWith("#")) {
                    cleaned.remove(cleaned.size() - 1);
                    continue;
                }
                break;
            }
            return cleaned;
        }

        private static void relocatePluginKeysAfterDecorativeTail(List<String> block) {
            int firstDecoration = -1;
            for (int i = 1; i < block.size(); i++) {
                String trimmed = block.get(i).trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    firstDecoration = i;
                    break;
                }
            }
            if (firstDecoration < 0) {
                return;
            }
            List<String> misplacedKeys = new ArrayList<>();
            for (int i = firstDecoration + 1; i < block.size();) {
                String trimmed = block.get(i).trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    misplacedKeys.add(block.remove(i));
                    continue;
                }
                i++;
            }
            if (misplacedKeys.isEmpty()) {
                return;
            }
            block.addAll(firstDecoration, misplacedKeys);
        }

        private static void appendPluginGroup(List<String> replacement, String label, List<List<String>> blocks) {
            if (blocks.isEmpty()) {
                return;
            }
            trimTrailingBlankLines(replacement);
            if (!replacement.isEmpty()) {
                replacement.add("");
            }
            replacement.add("  # " + label);
            for (List<String> block : blocks) {
                replacement.addAll(block);
                replacement.add("");
            }
            trimTrailingBlankLines(replacement);
        }

        private static boolean isPluginGroupComment(String line) {
            String trimmed = line == null ? "" : line.trim();
            return trimmed.equalsIgnoreCase("# Manual sources")
                || trimmed.equalsIgnoreCase("# Discovered sources")
                || trimmed.equalsIgnoreCase("# Unresolved sources");
        }

        private static void trimTrailingBlankLines(List<String> lines) {
            while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
                lines.remove(lines.size() - 1);
            }
        }

        private static boolean pluginBlockSourceMissing(List<String> block) {
            String source = pluginBlockField(block, "source");
            return isMissingSourceValue(source);
        }

        private static int pluginBlockSourceGroup(List<String> block) {
            if (pluginBlockSourceMissing(block)) {
                return 2;
            }
            String origin = lower(pluginBlockField(block, "sourceOrigin"));
            if (origin.equals(SOURCE_ORIGIN_UNRESOLVED)) {
                return 2;
            }
            if (origin.equals(SOURCE_ORIGIN_DISCOVERED)) {
                return 1;
            }
            if (origin.equals(SOURCE_ORIGIN_DISCOVERED_UNVERIFIED)) {
                return 1;
            }
            return 0;
        }

        private static String pluginBlockSortName(List<String> block) {
            return firstNonBlank(pluginBlockField(block, "name"), pluginBlockField(block, "installAs"), "zzzz");
        }

        private static String pluginBlockField(List<String> block, String key) {
            String normalized = normalizedKey(key);
            for (int i = 0; i < block.size(); i++) {
                String line = ConfigParser.stripComment(block.get(i)).trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (i == 0 && line.startsWith("- ")) {
                    line = line.substring(2).trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                } else if (line.startsWith("- ")) {
                    continue;
                }
                try {
                    KeyValue kv = ConfigParser.keyValue(line, 0);
                    if (normalizedKey(kv.key).equals(normalized)) {
                        return ConfigParser.unquote(kv.value);
                    }
                } catch (IllegalArgumentException ignored) {
                    // Keep scanning this block.
                }
            }
            return "";
        }

        private static String detectNewline(Path path) {
            try {
                String text = Files.readString(path, StandardCharsets.UTF_8);
                int lf = text.indexOf('\n');
                if (lf > 0 && text.charAt(lf - 1) == '\r') {
                    return "\r\n";
                }
            } catch (IOException ignored) {
                // Fall back to the platform newline.
            }
            return System.lineSeparator();
        }

        private static PluginBlock findPluginBlock(List<String> lines, TargetConfig target) {
            int pluginsStart = findTopLevelSection(lines, "plugins");
            if (pluginsStart < 0) {
                return null;
            }
            int pluginsEnd = findNextTopLevel(lines, pluginsStart + 1);
            for (int i = pluginsStart + 1; i < pluginsEnd; i++) {
                String noComment = ConfigParser.stripComment(lines.get(i));
                if (noComment.trim().isEmpty()) {
                    continue;
                }
                int indent = ConfigParser.countIndent(noComment);
                String line = noComment.trim();
                if (!line.startsWith("- ")) {
                    continue;
                }
                int end = i + 1;
                while (end < pluginsEnd) {
                    String candidateNoComment = ConfigParser.stripComment(lines.get(end));
                    String candidateTrimmed = candidateNoComment.trim();
                    if (!candidateTrimmed.isEmpty()) {
                        int candidateIndent = ConfigParser.countIndent(candidateNoComment);
                        if (candidateIndent <= indent && candidateTrimmed.startsWith("- ")) {
                            break;
                        }
                    }
                    end++;
                }
                PluginBlock block = new PluginBlock(i, end, indent, Math.max(indent + 2, 4));
                Map<String, String> fields = parsePluginFields(lines, block);
                if (matchesPluginBlock(fields, target)) {
                    return block;
                }
                i = end - 1;
            }
            return null;
        }

        private static int findTopLevelSection(List<String> lines, String section) {
            for (int i = 0; i < lines.size(); i++) {
                String noComment = ConfigParser.stripComment(lines.get(i));
                if (noComment.trim().isEmpty() || ConfigParser.countIndent(noComment) != 0) {
                    continue;
                }
                try {
                    KeyValue kv = ConfigParser.keyValue(noComment.trim(), 0);
                    if (kv.value.isEmpty() && lower(kv.key).equals(section)) {
                        return i;
                    }
                } catch (IllegalArgumentException ignored) {
                    // Ignore lines that are not simple key/value entries.
                }
            }
            return -1;
        }

        private static int findNextTopLevel(List<String> lines, int start) {
            for (int i = start; i < lines.size(); i++) {
                String noComment = ConfigParser.stripComment(lines.get(i));
                if (!noComment.trim().isEmpty() && ConfigParser.countIndent(noComment) == 0) {
                    return i;
                }
            }
            return lines.size();
        }

        private static Map<String, String> parsePluginFields(List<String> lines, PluginBlock block) {
            Map<String, String> fields = new HashMap<>();
            for (int i = block.start; i < block.end; i++) {
                String line = ConfigParser.stripComment(lines.get(i)).trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (i == block.start && line.startsWith("- ")) {
                    line = line.substring(2).trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                } else if (line.startsWith("- ")) {
                    continue;
                }
                try {
                    KeyValue kv = ConfigParser.keyValue(line, 0);
                    fields.put(normalizedKey(kv.key), kv.value);
                } catch (IllegalArgumentException ignored) {
                    // Ignore nested or unsupported YAML syntax.
                }
            }
            return fields;
        }

        private static boolean matchesPluginBlock(Map<String, String> fields, TargetConfig target) {
            String installAs = fields.get("installas");
            if (installAs != null && target.installAs != null
                && normalizedConfigPath(installAs).equals(normalizedConfigPath(target.installAs))) {
                return true;
            }
            String name = fields.get("name");
            return name != null && target.name != null && lower(name).equals(lower(target.name));
        }

        private static boolean updatePluginBlock(List<String> lines, PluginBlock block, TargetConfig target) {
            boolean changed = false;
            changed |= upsertPluginKey(lines, block, "source", target.source);
            if (target.sourceOrigin != null && !target.sourceOrigin.isBlank()) {
                changed |= upsertPluginKey(lines, block, "sourceOrigin", target.sourceOrigin);
            }
            changed |= upsertPluginKey(lines, block, "type", firstNonBlank(target.type, "auto"));
            if (target.githubRepo != null && !target.githubRepo.isBlank()) {
                changed |= upsertPluginKey(lines, block, "githubRepo", target.githubRepo);
            } else {
                changed |= removePluginKey(lines, block, "githubRepo");
            }
            if (target.platform != null && !target.platform.isBlank()) {
                changed |= upsertPluginKey(lines, block, "platform", target.platform);
            }
            if (!target.fallbackSources.isEmpty()) {
                changed |= upsertPluginKey(lines, block, "fallbackSources", String.join(", ", target.fallbackSources));
            } else {
                changed |= removePluginKey(lines, block, "fallbackSources");
            }
            if (target.installAs != null && !target.installAs.isBlank()) {
                changed |= upsertPluginKey(lines, block, "installAs", target.installAs);
            }
            changed |= upsertPluginKey(lines, block, "required", Boolean.toString(target.required));
            return changed;
        }

        private static boolean updatePluginSourceOrigin(List<String> lines, PluginBlock block, TargetConfig target) {
            if (target.sourceOrigin == null || target.sourceOrigin.isBlank()) {
                return false;
            }
            return upsertPluginKey(lines, block, "sourceOrigin", target.sourceOrigin);
        }

        private static boolean upsertPluginKey(List<String> lines, PluginBlock block, String key, String value) {
            String normalized = normalizedKey(key);
            String rendered = spaces(block.propertyIndent) + key + ": " + quoteYaml(value);
            for (int i = block.start; i < block.end; i++) {
                String noComment = ConfigParser.stripComment(lines.get(i));
                String line = noComment.trim();
                boolean firstInlineKey = false;
                if (i == block.start && line.startsWith("- ")) {
                    firstInlineKey = true;
                    line = line.substring(2).trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                } else if (line.startsWith("- ")) {
                    continue;
                }
                try {
                    KeyValue kv = ConfigParser.keyValue(line, 0);
                    if (normalizedKey(kv.key).equals(normalized)) {
                        String replacement = firstInlineKey
                            ? spaces(block.itemIndent) + "- " + key + ": " + quoteYaml(value)
                            : rendered;
                        if (!firstInlineKey && pluginKeyAppearsAfterDecorativeTail(lines, block, i)) {
                            lines.remove(i);
                            block.end--;
                            int insertAt = pluginBlockTailInsertionIndex(lines, block);
                            lines.add(insertAt, rendered);
                            block.end++;
                            return true;
                        }
                        if (lines.get(i).equals(replacement)) {
                            return false;
                        }
                        lines.set(i, replacement);
                        return true;
                    }
                } catch (IllegalArgumentException ignored) {
                    // Keep looking.
                }
            }
            int insertAt = block.end;
            insertAt = pluginBlockTailInsertionIndex(lines, block);
            lines.add(insertAt, rendered);
            block.end++;
            return true;
        }

        private static int pluginBlockTailInsertionIndex(List<String> lines, PluginBlock block) {
            int insertAt = block.end;
            while (insertAt > block.start + 1 && isTrailingPluginBlockDecoration(lines.get(insertAt - 1))) {
                insertAt--;
            }
            return insertAt;
        }

        private static boolean isTrailingPluginBlockDecoration(String line) {
            String trimmed = line == null ? "" : line.trim();
            return trimmed.isEmpty() || trimmed.startsWith("#");
        }

        private static boolean pluginKeyAppearsAfterDecorativeTail(List<String> lines, PluginBlock block, int keyIndex) {
            for (int i = block.start + 1; i < keyIndex; i++) {
                if (isTrailingPluginBlockDecoration(lines.get(i))) {
                    return true;
                }
            }
            return false;
        }

        private static boolean removePluginKey(List<String> lines, PluginBlock block, String key) {
            String normalized = normalizedKey(key);
            for (int i = block.start; i < block.end; i++) {
                String noComment = ConfigParser.stripComment(lines.get(i));
                String line = noComment.trim();
                if (i == block.start && line.startsWith("- ")) {
                    line = line.substring(2).trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                } else if (line.startsWith("- ")) {
                    continue;
                }
                try {
                    KeyValue kv = ConfigParser.keyValue(line, 0);
                    if (normalizedKey(kv.key).equals(normalized)) {
                        lines.remove(i);
                        block.end--;
                        return true;
                    }
                } catch (IllegalArgumentException ignored) {
                    // Keep looking.
                }
            }
            return false;
        }

        private static void appendPluginBlock(List<String> lines, TargetConfig target) {
            int pluginsStart = findTopLevelSection(lines, "plugins");
            if (pluginsStart < 0) {
                if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
                    lines.add("");
                }
                lines.add("plugins:");
                pluginsStart = lines.size() - 1;
            }
            int insertAt = findNextTopLevel(lines, pluginsStart + 1);
            List<String> entry = pluginEntryLines(target);
            if (insertAt > 0 && !lines.get(insertAt - 1).isBlank()) {
                entry.add(0, "");
            }
            if (insertAt < lines.size() && !entry.get(entry.size() - 1).isBlank()) {
                entry.add("");
            }
            lines.addAll(insertAt, entry);
        }

        private static List<String> pluginEntryLines(TargetConfig target) {
            List<String> entry = new ArrayList<>();
            entry.add("  - name: " + quoteYaml(target.displayName()));
            entry.add("    source: " + quoteYaml(target.source));
            if (target.sourceOrigin != null && !target.sourceOrigin.isBlank()) {
                entry.add("    sourceOrigin: " + quoteYaml(target.sourceOrigin));
            }
            entry.add("    type: " + quoteYaml(firstNonBlank(target.type, "auto")));
            entry.add("    autoUpdate: " + target.autoUpdate);
            if (target.githubRepo != null && !target.githubRepo.isBlank()) {
                entry.add("    githubRepo: " + quoteYaml(target.githubRepo));
            }
            if (target.platform != null && !target.platform.isBlank()) {
                entry.add("    platform: " + quoteYaml(target.platform));
            }
            if (!target.fallbackSources.isEmpty()) {
                entry.add("    fallbackSources: " + quoteYaml(String.join(", ", target.fallbackSources)));
            }
            entry.add("    installAs: " + quoteYaml(target.installAs));
            entry.add("    required: " + target.required);
            return entry;
        }

        private static String normalizedKey(String key) {
            return lower(key).replace("_", "");
        }

        private static String spaces(int count) {
            return " ".repeat(Math.max(0, count));
        }

        private static final class PluginBlock {
            final int start;
            int end;
            final int itemIndent;
            final int propertyIndent;

            PluginBlock(int start, int end, int itemIndent, int propertyIndent) {
                this.start = start;
                this.end = end;
                this.itemIndent = itemIndent;
                this.propertyIndent = propertyIndent;
            }
        }
    }

    private static final class LockState {
        String serverProject = "";
        String serverGameVersion = "";
        String serverBuild = "";
        final Map<String, BadServerBuild> badServerBuilds = new LinkedHashMap<>();
        final Map<String, BadPluginVersion> badPluginVersions = new LinkedHashMap<>();
        final Map<String, BadSourceBuild> badSourceBuilds = new LinkedHashMap<>();
        final Map<String, DiscoveryState> discoveryStates = new LinkedHashMap<>();
        final Map<String, SourceProof> sourceProofs = new LinkedHashMap<>();
        final Map<String, RejectedSourceProof> rejectedSourceProofs = new LinkedHashMap<>();

        static LockState read(AppConfig config) {
            LockState state = new LockState();
            Path lock = lockPath(config);
            if (!Files.exists(lock)) {
                return state;
            }
            try {
                List<String> lines = Files.readAllLines(lock, StandardCharsets.UTF_8);
                String section = "";
                BadServerBuild currentBadServer = null;
                BadPluginVersion currentBad = null;
                BadSourceBuild currentBadBuild = null;
                DiscoveryState currentDiscovery = null;
                SourceProof currentProof = null;
                RejectedSourceProof currentRejectedProof = null;
                for (String raw : lines) {
                    String noComment = ConfigParser.stripComment(raw);
                    String line = noComment.trim();
                    if (line.isEmpty() || !line.contains(":")) {
                        continue;
                    }
                    int indent = ConfigParser.countIndent(noComment);
                    if (indent == 0) {
                        currentBadServer = null;
                        currentBad = null;
                        currentBadBuild = null;
                        currentDiscovery = null;
                        currentProof = null;
                        currentRejectedProof = null;
                        KeyValue kv = ConfigParser.keyValue(line, 0);
                        String key = lower(kv.key);
                        if (kv.value.isEmpty()) {
                            section = key;
                            continue;
                        }
                        section = "";
                        switch (key) {
                            case "serverproject":
                                state.serverProject = kv.value;
                                break;
                            case "servergameversion":
                                state.serverGameVersion = kv.value;
                                break;
                            case "serverbuild":
                                state.serverBuild = kv.value;
                                break;
                            default:
                                break;
                        }
                        continue;
                    }

                    if (section.equals("badserverbuilds") && indent == 2) {
                        KeyValue kv = ConfigParser.keyValue(line, 0);
                        if (kv.value.isEmpty()) {
                            String key = ConfigParser.unquote(kv.key);
                            currentBadServer = new BadServerBuild(key);
                            state.badServerBuilds.put(serverBuildLockKey(currentBadServer.project, currentBadServer.gameVersion, currentBadServer.build), currentBadServer);
                        }
                        continue;
                    }
                    if (section.equals("badserverbuilds") && indent >= 4 && currentBadServer != null) {
                        KeyValue kv = ConfigParser.keyValue(line, 0);
                        currentBadServer.apply(kv.key, kv.value);
                        continue;
                    }
                    if (section.equals("badpluginversions") && indent == 2) {
                        KeyValue kv = ConfigParser.keyValue(line, 0);
                        if (kv.value.isEmpty()) {
                            String installAs = ConfigParser.unquote(kv.key);
                            currentBad = new BadPluginVersion(installAs);
                            state.badPluginVersions.put(lockKey(installAs), currentBad);
                        }
                        continue;
                    }
                    if (section.equals("badpluginversions") && indent >= 4 && currentBad != null) {
                        KeyValue kv = ConfigParser.keyValue(line, 0);
                        currentBad.apply(kv.key, kv.value);
                        continue;
                    }
                    if (section.equals("badsourcebuilds") && indent == 2) {
                        KeyValue kv = ConfigParser.keyValue(line, 0);
                        if (kv.value.isEmpty()) {
                            String repo = ConfigParser.unquote(kv.key);
                            currentBadBuild = new BadSourceBuild(repo);
                            state.badSourceBuilds.put(sourceBuildLockKey(repo), currentBadBuild);
                        }
                        continue;
                    }
                    if (section.equals("badsourcebuilds") && indent >= 4 && currentBadBuild != null) {
                        KeyValue kv = ConfigParser.keyValue(line, 0);
                        currentBadBuild.apply(kv.key, kv.value);
                        continue;
                    }
                    if (section.equals("discoverystates") && indent == 2) {
                        KeyValue kv = ConfigParser.keyValue(line, 0);
                        if (kv.value.isEmpty()) {
                            String installAs = ConfigParser.unquote(kv.key);
                            currentDiscovery = new DiscoveryState(installAs);
                            state.discoveryStates.put(lockKey(installAs), currentDiscovery);
                        }
                        continue;
                    }
                    if (section.equals("discoverystates") && indent >= 4 && currentDiscovery != null) {
                        KeyValue kv = ConfigParser.keyValue(line, 0);
                        currentDiscovery.apply(kv.key, kv.value);
                        continue;
                    }
                    if (section.equals("sourceproofs") && indent == 2) {
                        KeyValue kv = ConfigParser.keyValue(line, 0);
                        if (kv.value.isEmpty()) {
                            String installAs = ConfigParser.unquote(kv.key);
                            currentProof = new SourceProof(installAs);
                            state.sourceProofs.put(lockKey(installAs), currentProof);
                        }
                        continue;
                    }
                    if (section.equals("sourceproofs") && indent >= 4 && currentProof != null) {
                        KeyValue kv = ConfigParser.keyValue(line, 0);
                        currentProof.apply(kv.key, kv.value);
                        continue;
                    }
                    if (section.equals("rejectedsourceproofs") && indent == 2) {
                        KeyValue kv = ConfigParser.keyValue(line, 0);
                        if (kv.value.isEmpty()) {
                            String key = ConfigParser.unquote(kv.key);
                            currentRejectedProof = new RejectedSourceProof(key);
                            state.rejectedSourceProofs.put(rejectedSourceKeyRaw(key), currentRejectedProof);
                        }
                        continue;
                    }
                    if (section.equals("rejectedsourceproofs") && indent >= 4 && currentRejectedProof != null) {
                        KeyValue kv = ConfigParser.keyValue(line, 0);
                        currentRejectedProof.apply(kv.key, kv.value);
                    }
                }
            } catch (Exception ex) {
                Log.warn("Could not read updater.lock.yml: " + ex.getMessage());
            }
            return state;
        }

        void write(AppConfig config) throws IOException {
            Path lock = lockPath(config);
            List<String> lines = new ArrayList<>();
            lines.add("# Auto-generated by " + APP_NAME + ".");
            lines.add("# Keep this file so Auto-Updater can remember locked server versions and known-bad plugin jars.");
            if (!serverProject.isBlank()) {
                lines.add("serverProject: " + quoteYaml(serverProject));
            }
            if (!serverGameVersion.isBlank()) {
                lines.add("serverGameVersion: " + quoteYaml(serverGameVersion));
            }
            if (!serverBuild.isBlank()) {
                lines.add("serverBuild: " + quoteYaml(serverBuild));
            }
            if (!badServerBuilds.isEmpty()) {
                lines.add("badServerBuilds:");
                for (BadServerBuild bad : badServerBuilds.values()) {
                    lines.add("  " + quoteYamlKey(serverBuildLockKey(bad.project, bad.gameVersion, bad.build)) + ":");
                    lines.add("    project: " + quoteYaml(bad.project));
                    lines.add("    gameVersion: " + quoteYaml(bad.gameVersion));
                    lines.add("    build: " + quoteYaml(bad.build));
                    lines.add("    reason: " + quoteYaml(bad.reason));
                    lines.add("    failedAt: " + quoteYaml(bad.failedAt));
                }
            }
            if (!badPluginVersions.isEmpty()) {
                lines.add("badPluginVersions:");
                for (BadPluginVersion bad : badPluginVersions.values()) {
                    lines.add("  " + quoteYamlKey(bad.installAs) + ":");
                    lines.add("    source: " + quoteYaml(bad.source));
                    lines.add("    version: " + quoteYaml(bad.version));
                    lines.add("    sha256: " + quoteYaml(bad.sha256));
                    lines.add("    reason: " + quoteYaml(bad.reason));
                    lines.add("    failedAt: " + quoteYaml(bad.failedAt));
                }
            }
            if (!badSourceBuilds.isEmpty()) {
                lines.add("badSourceBuilds:");
                for (BadSourceBuild bad : badSourceBuilds.values()) {
                    lines.add("  " + quoteYamlKey(bad.repo) + ":");
                    lines.add("    commit: " + quoteYaml(bad.commit));
                    lines.add("    summary: " + quoteYaml(bad.summary));
                    lines.add("    reason: " + quoteYaml(bad.reason));
                    lines.add("    logFile: " + quoteYaml(bad.logFile));
                    lines.add("    failedAt: " + quoteYaml(bad.failedAt));
                }
            }
            if (!discoveryStates.isEmpty()) {
                lines.add("discoveryStates:");
                for (DiscoveryState state : discoveryStates.values()) {
                    lines.add("  " + quoteYamlKey(state.installAs) + ":");
                    lines.add("    status: " + quoteYaml(state.status));
                    lines.add("    reason: " + quoteYaml(state.reason));
                    lines.add("    lastTried: " + quoteYaml(state.lastTried));
                    lines.add("    nextRetryAfter: " + quoteYaml(state.nextRetryAfter));
                }
            }
            if (!sourceProofs.isEmpty()) {
                lines.add("sourceProofs:");
                for (SourceProof proof : sourceProofs.values()) {
                    lines.add("  " + quoteYamlKey(proof.installAs) + ":");
                    lines.add("    source: " + quoteYaml(proof.source));
                    lines.add("    type: " + quoteYaml(proof.type));
                    lines.add("    repo: " + quoteYaml(proof.repo));
                    lines.add("    proof: " + quoteYaml(proof.proof));
                    lines.add("    descriptorPath: " + quoteYaml(proof.descriptorPath));
                    lines.add("    pluginId: " + quoteYaml(proof.pluginId));
                    lines.add("    mainClass: " + quoteYaml(proof.mainClass));
                    if (!proof.forkRepo.isBlank()) {
                        lines.add("    forkRepo: " + quoteYaml(proof.forkRepo));
                    }
                    if (!proof.upstreamRepo.isBlank()) {
                        lines.add("    upstreamRepo: " + quoteYaml(proof.upstreamRepo));
                    }
                    if (proof.foliaSupported != null) {
                        lines.add("    foliaSupported: " + quoteYaml(Boolean.toString(proof.foliaSupported)));
                    }
                    if (!proof.proofReason.isBlank()) {
                        lines.add("    proofReason: " + quoteYaml(proof.proofReason));
                    }
                    lines.add("    verifiedAt: " + quoteYaml(proof.verifiedAt));
                }
            }
            if (!rejectedSourceProofs.isEmpty()) {
                lines.add("rejectedSourceProofs:");
                for (RejectedSourceProof proof : rejectedSourceProofs.values()) {
                    lines.add("  " + quoteYamlKey(proof.key) + ":");
                    lines.add("    installAs: " + quoteYaml(proof.installAs));
                    lines.add("    source: " + quoteYaml(proof.source));
                    lines.add("    type: " + quoteYaml(proof.type));
                    lines.add("    repo: " + quoteYaml(proof.repo));
                    lines.add("    reason: " + quoteYaml(proof.reason));
                    lines.add("    pluginId: " + quoteYaml(proof.pluginId));
                    lines.add("    mainClass: " + quoteYaml(proof.mainClass));
                    lines.add("    rejectedAt: " + quoteYaml(proof.rejectedAt));
                }
            }
            Files.write(lock, lines, StandardCharsets.UTF_8);
        }

        Optional<BadServerBuild> activeBadServerBuild(AppConfig config, String project, String gameVersion, String build) {
            if (!config.failureMemory.enabled || project.isBlank() || gameVersion.isBlank() || build.isBlank()) {
                return Optional.empty();
            }
            BadServerBuild bad = badServerBuilds.get(serverBuildLockKey(project, gameVersion, build));
            if (bad != null && !retryWindowExpired(config, bad.failedAt)) {
                return Optional.of(bad);
            }
            for (BadServerBuild remembered : badServerBuilds.values()) {
                if (remembered.matches(project, gameVersion, build) && !retryWindowExpired(config, remembered.failedAt)) {
                    return Optional.of(remembered);
                }
            }
            return Optional.empty();
        }

        void rememberBadServerBuild(InstalledUpdate update, String reason) {
            if (update == null || !update.target.server || update.serverBuild.isBlank()) {
                return;
            }
            String project = firstNonBlank(update.serverProject, update.target.project, inferPaperMcProject(update.target));
            String gameVersion = firstNonBlank(update.serverGameVersion, update.version, update.target.gameVersion);
            BadServerBuild bad = new BadServerBuild(project, gameVersion, update.serverBuild);
            bad.reason = reason;
            bad.failedAt = Instant.now().toString();
            badServerBuilds.put(serverBuildLockKey(bad.project, bad.gameVersion, bad.build), bad);
        }

        Optional<BadPluginVersion> activeBadPlugin(AppConfig config, TargetConfig target, ResolvedDownload download, String sha256) {
            if (!config.failureMemory.enabled || target.server) {
                return Optional.empty();
            }
            BadPluginVersion bad = badPluginVersions.get(lockKey(target.installAs));
            if (bad != null && !retryWindowExpired(config, bad)) {
                if (!bad.sha256.isBlank() && bad.sha256.equalsIgnoreCase(sha256)) {
                    return Optional.of(bad);
                }
                if (bad.sha256.isBlank()
                    && !bad.version.isBlank()
                    && !download.version.isBlank()
                    && bad.version.equalsIgnoreCase(download.version)
                    && sourcesMatch(bad.source, target.source)) {
                    return Optional.of(bad);
                }
            }
            for (BadPluginVersion remembered : badPluginVersions.values()) {
                if (remembered == bad || retryWindowExpired(config, remembered)) {
                    continue;
                }
                if (!remembered.sha256.isBlank() && remembered.sha256.equalsIgnoreCase(sha256)) {
                    return Optional.of(remembered);
                }
            }
            return Optional.empty();
        }

        void rememberBadPlugin(InstalledUpdate update, String reason) {
            if (update == null || update.target.server || update.sha256.isBlank()) {
                return;
            }
            BadPluginVersion bad = new BadPluginVersion(update.target.installAs);
            bad.source = firstNonBlank(update.source, update.target.source, "");
            bad.version = update.version;
            bad.sha256 = update.sha256;
            bad.reason = reason;
            bad.failedAt = Instant.now().toString();
            badPluginVersions.put(lockKey(update.target.installAs), bad);
        }

        Optional<BadSourceBuild> activeBadSourceBuild(AppConfig config, String repo, String commit) {
            if (!config.failureMemory.enabled || repo.isBlank() || commit.isBlank()) {
                return Optional.empty();
            }
            BadSourceBuild bad = badSourceBuilds.get(sourceBuildLockKey(repo));
            if (bad == null || retryWindowExpired(config, bad.failedAt)) {
                return Optional.empty();
            }
            return bad.commit.equalsIgnoreCase(commit) ? Optional.of(bad) : Optional.empty();
        }

        void rememberBadSourceBuild(String repo, String commit, String reason) {
            rememberBadSourceBuild(repo, commit, summarizeFailure(reason), reason, "");
        }

        void rememberBadSourceBuild(String repo, String commit, String summary, String reason, String logFile) {
            if (repo.isBlank() || commit.isBlank()) {
                return;
            }
            BadSourceBuild bad = new BadSourceBuild(repo);
            bad.commit = commit;
            bad.summary = lockText(firstNonBlank(summary, "source-build-failed"), 300);
            bad.reason = lockText(firstNonBlank(reason, bad.summary), 1000);
            bad.logFile = firstNonBlank(logFile, "");
            bad.failedAt = Instant.now().toString();
            badSourceBuilds.put(sourceBuildLockKey(repo), bad);
        }

        Optional<DiscoveryState> activeDiscoveryDeferral(TargetConfig target) {
            DiscoveryState state = discoveryStates.get(lockKey(target.installAs));
            if (state == null || state.nextRetryAfter.isBlank()) {
                return Optional.empty();
            }
            try {
                Instant retry = Instant.parse(state.nextRetryAfter);
                return Instant.now().isBefore(retry) ? Optional.of(state) : Optional.empty();
            } catch (Exception ex) {
                return Optional.empty();
            }
        }

        void rememberDiscoveryDeferred(TargetConfig target, String reason, Instant nextRetryAfter) {
            if (target == null || target.server) {
                return;
            }
            DiscoveryState state = new DiscoveryState(firstNonBlank(target.installAs, target.displayName()));
            state.status = "deferred";
            state.reason = firstNonBlank(reason, "discovery-deferred");
            state.lastTried = Instant.now().toString();
            state.nextRetryAfter = (nextRetryAfter == null ? Instant.now().plus(DISCOVERY_NOT_FOUND_BACKOFF) : nextRetryAfter).toString();
            discoveryStates.put(lockKey(state.installAs), state);
        }

        void rememberDiscoveryNotFound(TargetConfig target, String reason) {
            rememberDiscoveryDeferred(target, firstNonBlank(reason, "no reliable source found"), Instant.now().plus(DISCOVERY_NOT_FOUND_BACKOFF));
        }

        void clearDiscoveryState(TargetConfig target) {
            if (target != null) {
                discoveryStates.remove(lockKey(target.installAs));
            }
        }

        Optional<SourceProof> activeSourceProof(TargetConfig target) {
            SourceProof proof = target == null ? null : sourceProofs.get(lockKey(target.installAs));
            if (proof == null || proof.source.isBlank()) {
                return Optional.empty();
            }
            if (!proofMatchesTarget(proof, target)) {
                return Optional.empty();
            }
            return Optional.of(proof);
        }

        Optional<SourceProof> activeSourceProof(TargetConfig target, GithubRepo repo) {
            Optional<SourceProof> proof = activeSourceProof(target);
            if (proof.isEmpty() || repo == null) {
                return Optional.empty();
            }
            String expected = lower(repo.owner + "/" + repo.name);
            return lower(proof.get().repo).equals(expected) ? proof : Optional.empty();
        }

        void rememberSourceProof(TargetConfig target, String source, String type, GithubRepo repo,
                                 PluginJarInfo descriptor, String proofKind) {
            rememberSourceProof(target, source, type, repo == null ? "" : repo.owner + "/" + repo.name, descriptor, proofKind);
        }

        void rememberSourceProof(TargetConfig target, String source, String type, String project,
                                 PluginJarInfo descriptor, String proofKind) {
            if (target == null || target.server || source.isBlank() || descriptor == null || !descriptor.hasDescriptor) {
                return;
            }
            SourceProof proof = new SourceProof(firstNonBlank(target.installAs, target.displayName()));
            proof.source = source;
            proof.type = firstNonBlank(type, "auto");
            proof.repo = project;
            proof.proof = firstNonBlank(proofKind, "descriptor-match");
            proof.descriptorPath = descriptor.descriptorPath;
            proof.pluginId = firstNonBlank(descriptor.id, descriptor.name);
            proof.mainClass = descriptor.mainClass;
            proof.foliaSupported = descriptor.foliaSupported;
            proof.verifiedAt = Instant.now().toString();
            sourceProofs.put(lockKey(proof.installAs), proof);
            clearDiscoveryState(target);
        }

        void enrichSourceProof(TargetConfig target, ForkLineage lineage, String reason) {
            if (target == null || lineage == null || !lineage.isFork()) {
                return;
            }
            SourceProof proof = sourceProofs.get(lockKey(target.installAs));
            if (proof == null) {
                return;
            }
            proof.forkRepo = lineage.repo;
            proof.upstreamRepo = firstNonBlank(lineage.sourceRepo, lineage.parentRepo);
            proof.proofReason = firstNonBlank(reason, lineage.describe());
            if (proof.proof.equals("descriptor-match") || proof.proof.endsWith("jar-descriptor-match")
                || proof.proof.equals("raw-descriptor-match")) {
                proof.proof = "descriptor-matched-fork";
            }
            sourceProofs.put(lockKey(proof.installAs), proof);
        }

        void forgetSourceProof(TargetConfig target) {
            if (target == null) {
                return;
            }
            sourceProofs.remove(lockKey(target.installAs));
        }

        Optional<RejectedSourceProof> activeRejectedSourceProof(TargetConfig target, String source, String type, String project) {
            if (target == null || source.isBlank()) {
                return Optional.empty();
            }
            RejectedSourceProof proof = rejectedSourceProofs.get(rejectedSourceKey(target, source, type, project));
            if (proof == null || !proofMatchesTarget(proof, target)) {
                return Optional.empty();
            }
            return Optional.of(proof);
        }

        void rememberRejectedSourceProof(TargetConfig target, String source, String type, String project,
                                         PluginJarInfo descriptor, String reason) {
            if (target == null || target.server || source.isBlank() || descriptor == null || !descriptor.hasDescriptor) {
                return;
            }
            String key = rejectedSourceKey(target, source, type, project);
            RejectedSourceProof proof = new RejectedSourceProof(key);
            proof.installAs = firstNonBlank(target.installAs, target.displayName());
            proof.source = source;
            proof.type = firstNonBlank(type, "auto");
            proof.repo = firstNonBlank(project, "");
            proof.reason = firstNonBlank(reason, "source descriptor did not match installed plugin");
            proof.pluginId = firstNonBlank(descriptor.id, descriptor.name);
            proof.mainClass = descriptor.mainClass;
            proof.rejectedAt = Instant.now().toString();
            rejectedSourceProofs.put(key, proof);
        }

        void applyGithubRetryPause(AppConfig config) {
            Instant next = discoveryStates.values().stream()
                .filter(state -> lower(state.reason).contains("github"))
                .map(state -> parseInstantOptional(state.nextRetryAfter))
                .flatMap(Optional::stream)
                .filter(instant -> instant.isAfter(Instant.now()))
                .max(Comparator.naturalOrder())
                .orElse(null);
            if (next != null) {
                config.githubRateLimit.pauseUntil(next);
            }
        }

        private boolean retryWindowExpired(AppConfig config, BadPluginVersion bad) {
            return retryWindowExpired(config, bad.failedAt);
        }

        private boolean retryWindowExpired(AppConfig config, String failedAt) {
            String retry = lower(config.failureMemory.retryBadAfter).trim();
            if (retry.isBlank() || retry.equals("never") || retry.equals("false") || retry.equals("off") || retry.equals("no")) {
                return false;
            }
            try {
                Duration delay = parseDuration(retry);
                Instant failed = Instant.parse(failedAt);
                return Instant.now().isAfter(failed.plus(delay));
            } catch (Exception ex) {
                return false;
            }
        }

        private static boolean sourcesMatch(String a, String b) {
            return normalizeSlashes(firstNonBlank(a, "")).equalsIgnoreCase(normalizeSlashes(firstNonBlank(b, "")));
        }

        private static boolean sourcesMatchLoosely(String a, String b) {
            return AutoUpdater.sourcesMatchLoosely(a, b);
        }

        private static Path lockPath(AppConfig config) {
            return config.resolve(Paths.get("updater.lock.yml"));
        }

        private static String lockKey(String installAs) {
            return normalizedConfigPath(firstNonBlank(installAs, ""));
        }

        private static String sourceBuildLockKey(String repo) {
            return lower(firstNonBlank(repo, "")).replace("\\", "/");
        }

        private static String serverBuildLockKey(String project, String gameVersion, String build) {
            return lower(firstNonBlank(project, "") + "/" + firstNonBlank(gameVersion, "") + "/" + firstNonBlank(build, ""));
        }
    }

    private static final class SourceProof {
        final String installAs;
        String source = "";
        String type = "";
        String repo = "";
        String proof = "";
        String descriptorPath = "";
        String pluginId = "";
        String mainClass = "";
        String forkRepo = "";
        String upstreamRepo = "";
        Boolean foliaSupported = null;
        String proofReason = "";
        String verifiedAt = "";

        SourceProof(String installAs) {
            this.installAs = firstNonBlank(installAs, "plugins/unknown.jar");
        }

        void apply(String key, String value) {
            value = ConfigParser.unquote(value);
            switch (lower(key)) {
                case "source":
                    source = value;
                    break;
                case "type":
                    type = value;
                    break;
                case "repo":
                    repo = value;
                    break;
                case "proof":
                    proof = value;
                    break;
                case "descriptorpath":
                case "descriptor_path":
                    descriptorPath = value;
                    break;
                case "pluginid":
                case "plugin_id":
                    pluginId = value;
                    break;
                case "mainclass":
                case "main_class":
                    mainClass = value;
                    break;
                case "forkrepo":
                case "fork_repo":
                    forkRepo = value;
                    break;
                case "upstreamrepo":
                case "upstream_repo":
                    upstreamRepo = value;
                    break;
                case "foliasupported":
                case "folia_supported":
                    if (!value.isBlank()) {
                        foliaSupported = parseOptionalBoolean(value);
                    }
                    break;
                case "proofreason":
                case "proof_reason":
                    proofReason = value;
                    break;
                case "verifiedat":
                case "verified_at":
                    verifiedAt = value;
                    break;
                default:
                    break;
            }
        }
    }

    private static final class RejectedSourceProof {
        final String key;
        String installAs = "";
        String source = "";
        String type = "";
        String repo = "";
        String reason = "";
        String pluginId = "";
        String mainClass = "";
        String rejectedAt = "";

        RejectedSourceProof(String key) {
            this.key = firstNonBlank(key, "unknown");
        }

        void apply(String key, String value) {
            value = ConfigParser.unquote(value);
            switch (lower(key)) {
                case "installas":
                case "install_as":
                    installAs = value;
                    break;
                case "source":
                    source = value;
                    break;
                case "type":
                    type = value;
                    break;
                case "repo":
                    repo = value;
                    break;
                case "reason":
                    reason = value;
                    break;
                case "pluginid":
                case "plugin_id":
                    pluginId = value;
                    break;
                case "mainclass":
                case "main_class":
                    mainClass = value;
                    break;
                case "rejectedat":
                case "rejected_at":
                    rejectedAt = value;
                    break;
                default:
                    break;
            }
        }
    }

    private static final class DiscoveryState {
        final String installAs;
        String status = "";
        String reason = "";
        String lastTried = "";
        String nextRetryAfter = "";

        DiscoveryState(String installAs) {
            this.installAs = firstNonBlank(installAs, "plugins/unknown.jar");
        }

        void apply(String key, String value) {
            switch (lower(key)) {
                case "status":
                    status = value;
                    break;
                case "reason":
                    reason = value;
                    break;
                case "lasttried":
                case "last_tried":
                    lastTried = value;
                    break;
                case "nextretryafter":
                case "next_retry_after":
                    nextRetryAfter = value;
                    break;
                default:
                    break;
            }
        }
    }

    private static final class BadPluginVersion {
        final String installAs;
        String source = "";
        String version = "";
        String sha256 = "";
        String reason = "";
        String failedAt = "";

        BadPluginVersion(String installAs) {
            this.installAs = firstNonBlank(installAs, "plugins/unknown.jar");
        }

        void apply(String key, String value) {
            switch (lower(key)) {
                case "source":
                    source = value;
                    break;
                case "version":
                    version = value;
                    break;
                case "sha256":
                    sha256 = value;
                    break;
                case "reason":
                    reason = value;
                    break;
                case "failedat":
                case "failed_at":
                    failedAt = value;
                    break;
                default:
                    break;
            }
        }
    }

    private static final class BadServerBuild {
        String project = "";
        String gameVersion = "";
        String build = "";
        String reason = "";
        String failedAt = "";

        BadServerBuild(String key) {
            String[] parts = firstNonBlank(key, "").split("/", 3);
            if (parts.length == 3) {
                project = parts[0];
                gameVersion = parts[1];
                build = parts[2];
            }
        }

        BadServerBuild(String project, String gameVersion, String build) {
            this.project = firstNonBlank(project, "");
            this.gameVersion = firstNonBlank(gameVersion, "");
            this.build = firstNonBlank(build, "");
        }

        void apply(String key, String value) {
            switch (lower(key)) {
                case "project":
                    project = value;
                    break;
                case "gameversion":
                case "game_version":
                    gameVersion = value;
                    break;
                case "build":
                    build = value;
                    break;
                case "reason":
                    reason = value;
                    break;
                case "failedat":
                case "failed_at":
                    failedAt = value;
                    break;
                default:
                    break;
            }
        }

        boolean matches(String project, String gameVersion, String build) {
            return this.project.equalsIgnoreCase(project)
                && this.gameVersion.equalsIgnoreCase(gameVersion)
                && this.build.equalsIgnoreCase(build);
        }
    }

    private static final class BadSourceBuild {
        final String repo;
        String commit = "";
        String summary = "";
        String reason = "";
        String logFile = "";
        String failedAt = "";

        BadSourceBuild(String repo) {
            this.repo = firstNonBlank(repo, "unknown/repo");
        }

        void apply(String key, String value) {
            switch (lower(key)) {
                case "commit":
                case "sha":
                    commit = value;
                    break;
                case "summary":
                    summary = value;
                    break;
                case "reason":
                    reason = value;
                    break;
                case "logfile":
                case "log_file":
                    logFile = value;
                    break;
                case "failedat":
                case "failed_at":
                    failedAt = value;
                    break;
                default:
                    break;
            }
        }
    }

    private static final class ServerJarDetection {
        final Path path;
        final String project;

        ServerJarDetection(Path path, String project) {
            this.path = path;
            this.project = project == null ? "" : project;
        }

        boolean hasProject() {
            return !project.isBlank();
        }
    }

    private static final class PluginJarInfo {
        final String id;
        final String name;
        final String version;
        final String website;
        final String description;
        final String mainClass;
        final String authors;
        final String dependencies;
        final Set<String> descriptorTypes;
        final Boolean foliaSupported;
        final boolean hasDescriptor;
        final String descriptorPath;

        PluginJarInfo(String id, String name, String version, String website) {
            this(id, name, version, website, "", "", "", Set.of(), null, false, "", "");
        }

        PluginJarInfo(String id, String name, String version, String website, String mainClass, String authors,
                      String dependencies, Set<String> descriptorTypes, Boolean foliaSupported, boolean hasDescriptor) {
            this(id, name, version, website, mainClass, authors, dependencies, descriptorTypes, foliaSupported, hasDescriptor, "", "");
        }

        PluginJarInfo(String id, String name, String version, String website, String mainClass, String authors,
                      String dependencies, Set<String> descriptorTypes, Boolean foliaSupported, boolean hasDescriptor,
                      String descriptorPath) {
            this(id, name, version, website, mainClass, authors, dependencies, descriptorTypes, foliaSupported, hasDescriptor, descriptorPath, "");
        }

        PluginJarInfo(String id, String name, String version, String website, String mainClass, String authors,
                      String dependencies, Set<String> descriptorTypes, Boolean foliaSupported, boolean hasDescriptor,
                      String descriptorPath, String description) {
            this.id = firstNonBlank(id, "");
            this.name = firstNonBlank(name, "");
            this.version = firstNonBlank(version, "");
            this.website = firstNonBlank(website, "");
            this.description = firstNonBlank(description, "");
            this.mainClass = firstNonBlank(mainClass, "");
            this.authors = firstNonBlank(authors, "");
            this.dependencies = firstNonBlank(dependencies, "");
            this.descriptorTypes = Set.copyOf(descriptorTypes);
            this.foliaSupported = foliaSupported;
            this.hasDescriptor = hasDescriptor;
            this.descriptorPath = firstNonBlank(descriptorPath, "");
        }

        PluginJarInfo withDescriptorPath(String path) {
            return new PluginJarInfo(id, name, version, website, mainClass, authors, dependencies,
                descriptorTypes, foliaSupported, hasDescriptor, path, description);
        }

        boolean supportsPlatform(String expectedPlatform) {
            String expected = lower(expectedPlatform);
            if (expected.isBlank()) {
                return true;
            }
            if (expected.equals("paper")) {
                return descriptorTypes.contains("paper") || descriptorTypes.contains("bukkit");
            }
            if (expected.equals("folia")) {
                return descriptorTypes.contains("paper") || descriptorTypes.contains("bukkit");
            }
            if (expected.equals("velocity")) {
                return descriptorTypes.contains("velocity");
            }
            if (expected.equals("waterfall") || expected.equals("bungee")) {
                return descriptorTypes.contains("bungee");
            }
            return true;
        }

        boolean isVelocityOnly() {
            return descriptorTypes.size() == 1 && descriptorTypes.contains("velocity");
        }

        String descriptorSummary() {
            return descriptorTypes.isEmpty() ? "none" : String.join("/", descriptorTypes);
        }
    }

    private static final class DiscoveryCandidate {
        final String type;
        final String source;
        final String projectHint;
        final String latestVersion;
        final String label;
        final String reason;
        final int score;
        final int priority;

        DiscoveryCandidate(String type, String source, String projectHint, String latestVersion, String label, String reason, int score, int priority) {
            this.type = type;
            this.source = source;
            this.projectHint = projectHint;
            this.latestVersion = latestVersion;
            this.label = label;
            this.reason = reason;
            this.score = score;
            this.priority = priority;
        }
    }

    private static final class ForkLineage {
        final String repo;
        final String parentRepo;
        final String sourceRepo;
        final boolean fork;

        ForkLineage(String repo, String parentRepo, String sourceRepo, boolean fork) {
            this.repo = firstNonBlank(repo, "");
            this.parentRepo = firstNonBlank(parentRepo, "");
            this.sourceRepo = firstNonBlank(sourceRepo, "");
            this.fork = fork;
        }

        boolean isFork() {
            return fork && (!parentRepo.isBlank() || !sourceRepo.isBlank());
        }

        String describe() {
            if (!isFork()) {
                return "";
            }
            List<String> parts = new ArrayList<>();
            parts.add(repo);
            if (!parentRepo.isBlank() && !parts.get(parts.size() - 1).equalsIgnoreCase(parentRepo)) {
                parts.add(parentRepo);
            }
            if (!sourceRepo.isBlank() && !parts.get(parts.size() - 1).equalsIgnoreCase(sourceRepo)) {
                parts.add(sourceRepo);
            }
            return "GitHub fork lineage: " + String.join(" -> ", parts);
        }
    }

    private static final class PluginJarCandidate {
        final Path path;
        final PluginJarInfo info;

        PluginJarCandidate(Path path, PluginJarInfo info) {
            this.path = path;
            this.info = info;
        }
    }

    private static final class PendingSourceProof {
        final String installAs;
        final String source;
        final String type;
        final String project;
        final PluginJarInfo descriptor;
        final String proofKind;
        ForkLineage lineage;
        String lineageReason = "";

        PendingSourceProof(TargetConfig target, String source, String type, String project,
                           PluginJarInfo descriptor, String proofKind) {
            this.installAs = firstNonBlank(target == null ? "" : target.installAs, target == null ? "" : target.displayName());
            this.source = firstNonBlank(source, "");
            this.type = firstNonBlank(type, "auto");
            this.project = firstNonBlank(project, "");
            this.descriptor = descriptor;
            this.proofKind = firstNonBlank(proofKind, "descriptor-match");
        }

        boolean matches(TargetConfig target, String source) {
            return target != null
                && normalizedConfigPath(installAs).equals(normalizedConfigPath(target.installAs))
                && sourcesMatchLoosely(this.source, source);
        }
    }

    private static final class PendingRejectedSourceProof {
        final TargetConfig target;
        final String source;
        final String type;
        final String project;
        final PluginJarInfo descriptor;
        final String reason;

        PendingRejectedSourceProof(TargetConfig target, String source, String type, String project,
                                   PluginJarInfo descriptor, String reason) {
            this.target = target;
            this.source = source;
            this.type = type;
            this.project = project;
            this.descriptor = descriptor;
            this.reason = reason;
        }
    }

    private enum SourceDescriptorEvidence {
        MATCH,
        MISMATCH,
        UNKNOWN
    }

    private static final class MissingBuildToolException extends IOException {
        MissingBuildToolException(String message) {
            super(message);
        }
    }

    private static final class KnownBadPluginUpdateException extends IOException {
        KnownBadPluginUpdateException(String message) {
            super(message);
        }
    }

    private static final class InstalledUpdate {
        final TargetConfig target;
        final Path targetPath;
        final Path backupPath;
        final String source;
        final String version;
        final String sha256;
        final String serverProject;
        final String serverGameVersion;
        final String serverBuild;
        final ServerLockSnapshot previousServerLock;

        InstalledUpdate(TargetConfig target, Path targetPath, Path backupPath, String source, String version, String sha256) {
            this(target, targetPath, backupPath, source, version, sha256, null);
        }

        InstalledUpdate(TargetConfig target, Path targetPath, Path backupPath, String source, String version,
                        String sha256, ServerLockSnapshot previousServerLock) {
            this(target, targetPath, backupPath, source, version, sha256, "", "", "", previousServerLock);
        }

        InstalledUpdate(TargetConfig target, Path targetPath, Path backupPath, String source, String version,
                        String sha256, String serverProject, String serverGameVersion, String serverBuild,
                        ServerLockSnapshot previousServerLock) {
            this.target = target;
            this.targetPath = targetPath;
            this.backupPath = backupPath;
            this.source = firstNonBlank(source, "");
            this.version = firstNonBlank(version, "");
            this.sha256 = firstNonBlank(sha256, "");
            this.serverProject = firstNonBlank(serverProject, "");
            this.serverGameVersion = firstNonBlank(serverGameVersion, "");
            this.serverBuild = firstNonBlank(serverBuild, "");
            this.previousServerLock = previousServerLock;
        }

        boolean hasBackup() {
            return backupPath != null && Files.exists(backupPath);
        }
    }

    private static final class ServerLockSnapshot {
        final String project;
        final String gameVersion;
        final String build;

        ServerLockSnapshot(String project, String gameVersion, String build) {
            this.project = firstNonBlank(project, "");
            this.gameVersion = firstNonBlank(gameVersion, "");
            this.build = firstNonBlank(build, "");
        }
    }

    private static final class CacheMaintenance {
        private CacheMaintenance() {
        }

        static void run(AppConfig config) {
            Path cache = config.resolve(config.cacheDir);
            if (!Files.exists(cache)) {
                return;
            }
            warnIfSyncedCache(cache);
            CacheStats stats = new CacheStats();
            Instant now = Instant.now();
            pruneOldFiles(cache.resolve("staging"), now.minus(CACHE_STAGING_MAX_AGE), stats);
            pruneOldFiles(cache.resolve("source-build-failures"), now.minus(CACHE_SOURCE_FAILURE_MAX_AGE), stats);
            pruneDiscoveryMetadata(cache.resolve("discovery"), now.minus(CACHE_DISCOVERY_METADATA_MAX_AGE), stats);
            pruneDiscoveryJars(cache.resolve("discovery"), now.minus(CACHE_DISCOVERY_JAR_MAX_AGE), stats);
            pruneEmptyDirectories(cache.resolve("staging"));
            pruneEmptyDirectories(cache.resolve("source-build-failures"));
            if (stats.deletedFiles > 0) {
                Log.info("Cache maintenance removed " + stats.deletedFiles + " stale file(s), freeing "
                    + formatBytes(stats.deletedBytes) + ".");
            }
        }

        private static void warnIfSyncedCache(Path cache) {
            if (isLikelySyncedPath(cache)) {
                Log.warn("Cache directory is inside OneDrive: " + cache
                    + ". Source-build caches can become large; moving cacheDir outside OneDrive will avoid sync overhead.");
            }
        }

        private static void pruneDiscoveryMetadata(Path discovery, Instant cutoff, CacheStats stats) {
            if (!Files.isDirectory(discovery)) {
                return;
            }
            try (var stream = Files.walk(discovery)) {
                stream
                    .filter(Files::isRegularFile)
                    .filter(path -> !isUnder(path, "jars"))
                    .filter(path -> olderThan(path, cutoff))
                    .forEach(path -> deleteFile(path, stats));
            } catch (IOException ignored) {
                // Cache maintenance is best-effort.
            }
        }

        private static void pruneDiscoveryJars(Path discovery, Instant cutoff, CacheStats stats) {
            if (!Files.isDirectory(discovery)) {
                return;
            }
            List<Path> jarDirs = new ArrayList<>();
            try (var stream = Files.walk(discovery)) {
                stream
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName() != null && path.getFileName().toString().equalsIgnoreCase("jars"))
                    .forEach(jarDirs::add);
            } catch (IOException ignored) {
                return;
            }
            for (Path jarDir : jarDirs) {
                pruneOldFiles(jarDir, cutoff, stats);
                capDirectorySize(jarDir, CACHE_DISCOVERY_JAR_MAX_BYTES, stats);
                pruneEmptyDirectories(jarDir);
            }
        }

        private static void pruneOldFiles(Path dir, Instant cutoff, CacheStats stats) {
            if (!Files.isDirectory(dir)) {
                return;
            }
            try (var stream = Files.walk(dir)) {
                stream
                    .filter(Files::isRegularFile)
                    .filter(path -> olderThan(path, cutoff))
                    .forEach(path -> deleteFile(path, stats));
            } catch (IOException ignored) {
                // Cache maintenance is best-effort.
            }
        }

        private static void capDirectorySize(Path dir, long maxBytes, CacheStats stats) {
            if (!Files.isDirectory(dir)) {
                return;
            }
            try (var stream = Files.walk(dir)) {
                List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(CacheMaintenance::lastModifiedInstant))
                    .toList();
                long total = 0L;
                for (Path file : files) {
                    total += fileSize(file);
                }
                for (Path file : files) {
                    if (total <= maxBytes) {
                        break;
                    }
                    long size = fileSize(file);
                    if (deleteFile(file, stats)) {
                        total -= size;
                    }
                }
            } catch (IOException ignored) {
                // Cache maintenance is best-effort.
            }
        }

        private static void pruneEmptyDirectories(Path dir) {
            if (!Files.isDirectory(dir)) {
                return;
            }
            try (var stream = Files.walk(dir)) {
                List<Path> dirs = stream
                    .filter(Files::isDirectory)
                    .sorted(Comparator.reverseOrder())
                    .toList();
                for (Path item : dirs) {
                    if (!item.equals(dir)) {
                        try {
                            Files.deleteIfExists(item);
                        } catch (IOException ignored) {
                            // Directory is not empty or is in use.
                        }
                    }
                }
            } catch (IOException ignored) {
                // Cache maintenance is best-effort.
            }
        }

        private static boolean isUnder(Path path, String segment) {
            for (Path part : path) {
                if (part.toString().equalsIgnoreCase(segment)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean olderThan(Path path, Instant cutoff) {
            return lastModifiedInstant(path).isBefore(cutoff);
        }

        private static Instant lastModifiedInstant(Path path) {
            try {
                return Files.getLastModifiedTime(path).toInstant();
            } catch (IOException ex) {
                return Instant.EPOCH;
            }
        }

        private static long fileSize(Path path) {
            try {
                return Files.size(path);
            } catch (IOException ex) {
                return 0L;
            }
        }

        private static boolean deleteFile(Path path, CacheStats stats) {
            long size = fileSize(path);
            try {
                if (Files.deleteIfExists(path)) {
                    stats.deletedFiles++;
                    stats.deletedBytes += size;
                    return true;
                }
                return false;
            } catch (IOException ex) {
                return false;
            }
        }

        private static String formatBytes(long bytes) {
            if (bytes >= 1024L * 1024L * 1024L) {
                return String.format(Locale.ROOT, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0);
            }
            if (bytes >= 1024L * 1024L) {
                return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
            }
            if (bytes >= 1024L) {
                return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
            }
            return bytes + " bytes";
        }

        private static final class CacheStats {
            int deletedFiles;
            long deletedBytes;
        }
    }

    private static final class Updater {
        private final AppConfig config;
        private final HttpClient client;
        private final LockState lockState;
        private final Set<String> writtenDiagnostics = new HashSet<>();
        private final Set<String> githubBudgetWarnings = new HashSet<>();
        private final Set<String> githubBudgetLimitedPlugins = new HashSet<>();
        private final Map<String, List<PluginJarInfo>> githubDescriptorCache = new HashMap<>();
        private final Set<String> discoveryFound = new HashSet<>();
        private final Set<String> discoveryDeferred = new HashSet<>();
        private final Set<String> discoveryUnresolved = new HashSet<>();
        private final Set<String> discoveryProviderFailures = new HashSet<>();
        private final List<PendingSourceProof> pendingSourceProofs = new ArrayList<>();
        private final List<PendingRejectedSourceProof> pendingRejectedSourceProofs = new ArrayList<>();
        private final Map<String, String> testGithubRawResponses = new HashMap<>();
        private final Map<String, Object> testJsonResponses = new HashMap<>();
        private final Map<String, Integer> testJsonFailureAfterHits = new HashMap<>();
        private final Map<String, Integer> testJsonHitCounts = new HashMap<>();
        private final List<String> testDiscoveryTrace = new ArrayList<>();
        private int sourceProofCaptureDepth = 0;
        private boolean githubRateLimited = false;
        private boolean githubAuthFailed = false;
        private boolean githubAuthFailureLogged = false;
        private boolean configPathTraceWritten = false;
        private final String pathTraceRunId;
        private boolean pathTraceStartWritten = false;
        private final Set<String> pathTraceTargetMarkers = new HashSet<>();
        private final Set<String> pathTracePhaseMarkers = new HashSet<>();

        Updater(AppConfig config) {
            this.config = config;
            this.lockState = LockState.read(config);
            this.lockState.applyGithubRetryPause(config);
            this.pathTraceRunId = buildPathTraceRunId(config);
            this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        }

        private String buildPathTraceRunId(AppConfig config) {
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            String target = firstNonBlank(config.discovery.pathfindingDebugPlugin, "all")
                .replaceAll("[^A-Za-z0-9_.-]+", "-");
            return stamp + "-" + firstNonBlank(target, "all");
        }

        private boolean pathfindingDebugEnabled(TargetConfig target) {
            if (!config.discovery.pathfindingDebug) {
                return false;
            }
            String filter = lower(firstNonBlank(config.discovery.pathfindingDebugPlugin, ""));
            if (filter.isBlank() || filter.equals("*") || target == null) {
                return true;
            }
            return lower(target.displayName()).contains(filter)
                || lower(firstNonBlank(target.name, "")).contains(filter)
                || lower(firstNonBlank(target.installAs, "")).contains(filter);
        }

        private synchronized void pathDebug(TargetConfig target, String stage, String message) {
            if (!pathfindingDebugEnabled(target)) {
                return;
            }
            String targetName = target == null ? "global" : target.displayName();
            String line = Instant.now() + " [" + targetName + "] " + stage + " - " + message + System.lineSeparator();
            StringBuilder output = new StringBuilder();
            if (!pathTraceStartWritten) {
                pathTraceStartWritten = true;
                output.append(System.lineSeparator())
                    .append("========== PATHFINDING TRACE START ==========").append(System.lineSeparator())
                    .append("runId=").append(pathTraceRunId).append(System.lineSeparator())
                    .append("run=").append(Instant.now()).append(System.lineSeparator())
                    .append("targetFilter=").append(firstNonBlank(config.discovery.pathfindingDebugPlugin, "*")).append(System.lineSeparator())
                    .append("traceFile=").append(config.resolve(Paths.get(firstNonBlank(
                        config.discovery.pathfindingDebugFile,
                        "architecture-pathfinding.debug"
                    )))).append(System.lineSeparator())
                    .append("sourcePriority=").append(String.join(" -> ", normalizedDiscoveryPriority())).append(System.lineSeparator())
                    .append("==============================================").append(System.lineSeparator());
            }
            if (target != null && pathTraceTargetMarkers.add(pathTraceTargetKey(target))) {
                output.append(System.lineSeparator())
                    .append("----- TARGET ").append(targetName).append(" -----").append(System.lineSeparator());
            }
            String phase = pathTracePhase(stage);
            String phaseKey = (target == null ? "global" : pathTraceTargetKey(target)) + "|" + phase;
            if (pathTracePhaseMarkers.add(phaseKey)) {
                output.append("[PHASE ").append(phase).append("]").append(System.lineSeparator());
            }
            output.append(line);
            try {
                writePathTrace(output.toString());
            } catch (IOException ex) {
                Log.warn("Could not write pathfinding debug trace: " + ex.getMessage());
                config.discovery.pathfindingDebug = false;
            }
        }

        private synchronized void pathTraceEnd(String entrypoint, String result) {
            if (!config.discovery.pathfindingDebug || !pathTraceStartWritten) {
                return;
            }
            try {
                writePathTrace("========== PATHFINDING TRACE END ==========" + System.lineSeparator()
                    + "runId=" + pathTraceRunId + System.lineSeparator()
                    + "entrypoint=" + entrypoint + System.lineSeparator()
                    + "finished=" + Instant.now() + System.lineSeparator()
                    + "result=" + result + System.lineSeparator()
                    + "============================================" + System.lineSeparator());
            } catch (IOException ex) {
                Log.warn("Could not write pathfinding debug trace end marker: " + ex.getMessage());
                config.discovery.pathfindingDebug = false;
            }
        }

        private void writePathTrace(String text) throws IOException {
            Path path = config.resolve(Paths.get(firstNonBlank(
                config.discovery.pathfindingDebugFile,
                "architecture-pathfinding.debug"
            )));
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, text, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        private String pathTraceTargetKey(TargetConfig target) {
            return lower(firstNonBlank(target.installAs, target.displayName(), target.name, "unknown"));
        }

        private String pathTracePhase(String stage) {
            String value = lower(firstNonBlank(stage, "general"));
            if (value.startsWith("config.")) {
                return "config";
            }
            if (value.startsWith("lock.") || value.startsWith("sourceproof.") || value.startsWith("proof-refresh")) {
                return "lock-memory";
            }
            if (value.startsWith("provider.github")) {
                return "provider:github";
            }
            if (value.startsWith("provider.modrinth")) {
                return "provider:modrinth";
            }
            if (value.startsWith("provider.hangar")) {
                return "provider:hangar";
            }
            if (value.startsWith("candidate.score")
                || value.startsWith("discovery.candidate")
                || value.startsWith("discovery.candidates")) {
                return "candidate-ranking";
            }
            if (value.startsWith("source.") || value.startsWith("manual-protection") || value.startsWith("fallback.")) {
                return "source-apply";
            }
            if (value.startsWith("validation.")) {
                return "validation";
            }
            if (value.startsWith("startup.") || value.startsWith("restart.")) {
                return "startup-rollback";
            }
            if (value.startsWith("update.") || value.startsWith("resolve.") || value.startsWith("install.") || value.startsWith("git-preference.")) {
                return "update";
            }
            if (value.startsWith("discover.") || value.startsWith("discovery.") || value.startsWith("autoswitch.") || value.equals("startup")) {
                return "discovery";
            }
            return "general";
        }

        private void traceConfigPathOnce(String entrypoint) {
            if (configPathTraceWritten || !config.discovery.pathfindingDebug) {
                return;
            }
            configPathTraceWritten = true;
            pathDebug(null, "config.entry", "entrypoint=" + entrypoint
                + "; mode=" + config.mode
                + "; onFailure=" + config.onFailure
                + "; discovery.enabled=" + config.discovery.enabled
                + "; discovery.mode=" + config.discovery.mode);
            pathDebug(null, "config.discovery.normalized", "sourcePriority="
                + String.join(" -> ", normalizedDiscoveryPriority())
                + "; preferredOwners=" + (config.discovery.preferredOwners.isEmpty()
                    ? "none"
                    : String.join(", ", config.discovery.preferredOwners))
                + "; autoSwitchSource=" + config.discovery.autoSwitchSource
                + "; saveDiscoveredSources=" + config.discovery.saveDiscoveredSources
                + "; scanInstalledPlugins=" + config.discovery.scanInstalledPlugins
                + "; pruneMissingInstalledPlugins=" + config.discovery.pruneMissingInstalledPlugins
                + "; retryDeferredAfterStartup=" + config.discovery.retryDeferredAfterStartup);
            long manual = config.plugins.stream().filter(this::isManualConfiguredSource).count();
            long discovered = config.plugins.stream()
                .filter(target -> lower(firstNonBlank(target.sourceOrigin, "")).equals(SOURCE_ORIGIN_DISCOVERED))
                .count();
            long unverified = config.plugins.stream()
                .filter(target -> lower(firstNonBlank(target.sourceOrigin, "")).equals(SOURCE_ORIGIN_DISCOVERED_UNVERIFIED))
                .count();
            long unresolved = config.plugins.stream()
                .filter(target -> lower(firstNonBlank(target.sourceOrigin, "")).equals(SOURCE_ORIGIN_UNRESOLVED)
                    || isMissingSourceValue(target.source))
                .count();
            pathDebug(null, "config.sources.grouping", "plugins=" + config.plugins.size()
                + "; manual=" + manual
                + "; discovered=" + discovered
                + "; discoveredUnverified=" + unverified
                + "; unresolvedOrMissing=" + unresolved);
            for (TargetConfig target : config.plugins) {
                pathDebug(target, "config.plugin", "source=" + firstNonBlank(target.source, "<blank>")
                    + "; sourceOrigin=" + firstNonBlank(target.sourceOrigin, "<blank>")
                    + "; type=" + firstNonBlank(target.type, "<blank>")
                    + "; autoUpdate=" + target.autoUpdate
                    + "; githubRepo=" + firstNonBlank(target.githubRepo, "<blank>")
                    + "; fallbackCount=" + target.fallbackSources.size()
                    + "; installAs=" + firstNonBlank(target.installAs, "<blank>"));
            }
        }

        void printPlan() {
            traceConfigPathOnce("plan");
            Log.info("Mode: " + config.mode);
            Log.info("Failure behavior: " + config.onFailure);
            Log.info("Discovery: " + (config.discovery.enabled ? "enabled (" + config.discovery.mode + ")" : "disabled"));
            Log.info("Installed plugin scan: " + (config.discovery.scanInstalledPlugins ? "enabled" : "disabled"));
            Log.info("Prune missing auto-managed plugins: " + (config.discovery.pruneMissingInstalledPlugins ? "enabled" : "disabled"));
            Log.info("Retry deferred discovery after startup: " + (config.discovery.retryDeferredAfterStartup ? "enabled" : "disabled"));
            Log.info("Build from source: " + config.buildFromSource.enabled
                + ", preferHostedIfSameVersion=" + config.buildFromSource.preferHostedIfSameVersion);
            Log.info("Jar validation: " + (config.validation.enabled ? "enabled" : "disabled")
                + ", autoScore>=" + config.validation.minAutoInstallScore
                + ", trustedScore>=" + config.validation.minTrustedSourceScore);
            Log.info("Duplicate plugin handling: " + (config.duplicates.enabled ? config.duplicates.action : "disabled"));
            Log.info("Failure memory: " + (config.failureMemory.enabled ? "enabled (retryBadAfter=" + config.failureMemory.retryBadAfter + ")" : "disabled"));
            Log.info("Self-update: " + (config.selfUpdate.enabled
                ? "enabled, source=" + firstNonBlank(config.selfUpdate.source, config.selfUpdate.githubRepo, "not configured")
                : "disabled"));
            Log.info("Diagnostics file: " + config.resolve(config.diagnosticsFile));
            reportGithubAccess();
            Log.info("Server install target: " + config.resolve(Paths.get(config.server.installAs)));
            for (TargetConfig target : allTargets()) {
                if (!target.enabled) {
                    Log.info("Skipping disabled target: " + target.displayName());
                    continue;
                }
                if (!target.server && !target.autoUpdate) {
                    Log.info("Skipping " + target.displayName() + " because autoUpdate is false.");
                    continue;
                }
                if (isMissingSourceValue(target.source)) {
                    Log.info(target.displayName() + ": no source configured"
                        + (isNotFoundSourceValue(target.source) ? " (discovery previously found no reliable source)" : "")
                        + ", installAs=" + target.installAs);
                    continue;
                }
                SourcePlan plan = resolveSource(target);
                String fallbacks = target.fallbackSources.isEmpty() ? "" : ", fallbacks=" + target.fallbackSources.size();
                Log.info(target.displayName() + ": type=" + plan.type + ", installAs=" + target.installAs + ", source=" + plan.description + fallbacks);
            }
            if (config.restart.enabled) {
                Log.info("Restart: every " + prettyDuration(config.restart.interval) + " using command '" + config.restart.stopCommand + "'");
            } else {
                Log.info("Restart: disabled");
            }
        }

        void discover() {
            Log.info("Discovery mode: " + config.discovery.mode + " (" + (config.discovery.enabled ? "enabled" : "disabled in normal run") + ")");
            Log.info("Source priority: " + String.join(" -> ", normalizedDiscoveryPriority())
                + " (Spigot/Spiget/Jenkins discovery disabled; manual sources only)");
            Log.info("Check alternate sources when outdated: " + config.discovery.checkAlternateSourcesWhenOutdated
                + " after " + config.discovery.outdatedThresholdDays + " days");
            Log.info("Auto-switch source: " + config.discovery.autoSwitchSource);
            Log.info("Save discovered sources: " + config.discovery.saveDiscoveredSources);
            Log.info("Scan installed plugins: " + config.discovery.scanInstalledPlugins);
            Log.info("Prune missing auto-managed plugins: " + config.discovery.pruneMissingInstalledPlugins);
            Log.info("Retry deferred discovery after startup: " + config.discovery.retryDeferredAfterStartup);
            Log.info("Preferred fork owners: " + (config.discovery.preferredOwners.isEmpty()
                ? "none"
                : String.join(", ", config.discovery.preferredOwners)));
            if (config.discovery.pathfindingDebug) {
                Log.info("Pathfinding debug: enabled -> "
                    + config.resolve(Paths.get(firstNonBlank(config.discovery.pathfindingDebugFile, "architecture-pathfinding.debug")))
                    + (firstNonBlank(config.discovery.pathfindingDebugPlugin, "").isBlank()
                        ? ""
                        : " (plugin filter: " + config.discovery.pathfindingDebugPlugin + ")"));
                pathDebug(null, "startup", "discover command; sourcePriority=" + String.join(" -> ", normalizedDiscoveryPriority())
                    + "; autoSwitchSource=" + config.discovery.autoSwitchSource
                    + "; saveDiscoveredSources=" + config.discovery.saveDiscoveredSources
                    + "; pruneMissingInstalledPlugins=" + config.discovery.pruneMissingInstalledPlugins
                + "; retryDeferredAfterStartup=" + config.discovery.retryDeferredAfterStartup);
            }
            Log.info("Spigot/Spiget/Jenkins sources: manual download only; never discovered automatically");
            Log.info("Build from source: " + config.buildFromSource.enabled
                + ", onlyTrusted=" + config.buildFromSource.onlyTrusted
                + ", preferHostedIfSameVersion=" + config.buildFromSource.preferHostedIfSameVersion);
            Log.info("Jar validation: " + (config.validation.enabled ? "enabled" : "disabled")
                + ", autoScore>=" + config.validation.minAutoInstallScore
                + ", trustedScore>=" + config.validation.minTrustedSourceScore);
            Log.info("Duplicate plugin handling: " + (config.duplicates.enabled ? config.duplicates.action : "disabled"));
            Log.info("Failure memory: " + (config.failureMemory.enabled ? "enabled (retryBadAfter=" + config.failureMemory.retryBadAfter + ")" : "disabled"));
            reportGithubAccess();
            if (!config.buildFromSource.trustedGithubOrgs.isEmpty()) {
                Log.info("Trusted GitHub orgs: " + String.join(", ", config.buildFromSource.trustedGithubOrgs));
            }
            if (!config.buildFromSource.trustedGithubRepos.isEmpty()) {
                Log.info("Trusted GitHub repos: " + String.join(", ", config.buildFromSource.trustedGithubRepos));
            }
            markManualSourceOrigins();
            migrateKnownStaleDiscoveredSourcesIfAutoSwitchEnabled();

            for (TargetConfig target : allTargets()) {
                if (!target.enabled || target.server) {
                    continue;
                }
                config.githubBudget.beginPlugin(target);
                if (!target.autoUpdate) {
                    Log.info("");
                    Log.info("Discovery target: " + target.displayName());
                    Log.info("autoUpdate is false; skipping source discovery and updates for this plugin.");
                    continue;
                }
                Log.info("");
                Log.info("Discovery target: " + target.displayName());
                pathDebug(target, "discover.target", "source=" + firstNonBlank(target.source, "<blank>")
                    + "; origin=" + firstNonBlank(target.sourceOrigin, "<blank>")
                    + "; type=" + firstNonBlank(target.type, "<blank>")
                    + "; githubRepo=" + firstNonBlank(target.githubRepo, "<blank>")
                    + "; fallbacks=" + target.fallbackSources.size());
                if (!isMissingSourceValue(target.source)) {
                    try {
                        SourcePlan plan = resolveSource(target);
                        Log.info("Current source: " + plan.type + " -> " + plan.description);
                        pathDebug(target, "discover.current-source", "resolved type=" + plan.type + "; description=" + plan.description);
                        if (isManualConfiguredSource(target)) {
                            Log.info("Current source is marked manual; skipping replacement discovery for " + target.displayName() + ".");
                            pathDebug(target, "discover.skip", "manual configured source protected from replacement discovery");
                            continue;
                        }
                        Optional<SourceProof> proof = lockState.activeSourceProof(target);
                        if (proof.isPresent() && sourcesMatchLoosely(proof.get().source, target.source)) {
                            if (shouldRefreshRememberedSourceProof(target, proof.get())) {
                                Log.info("Current source proof for " + target.displayName()
                                    + " predates Folia support proof; refreshing discovery before trusting it.");
                            } else {
                                Log.info("Current source is already descriptor-proven for " + target.displayName()
                                    + "; skipping network discovery.");
                                pathDebug(target, "discover.skip", "active sourceProof matches current source; proof=" + proof.get().proof);
                                continue;
                            }
                        }
                    } catch (IllegalArgumentException ex) {
                        Log.warn("Current source is unsupported for " + target.displayName() + ": " + ex.getMessage());
                        pathDebug(target, "discover.current-source", "unsupported source: " + ex.getMessage());
                    }
                } else if (isNotFoundSourceValue(target.source)) {
                    Log.info("Current source: " + SOURCE_NOT_FOUND + " (will retry discovery)");
                    pathDebug(target, "discover.current-source", "source is Not Found; retry discovery path");
                } else {
                    Log.info("Current source: none");
                    pathDebug(target, "discover.current-source", "source is blank; discovery required");
                }
                boolean restoredFromProof = applyRememberedSourceProof(target);
                boolean refreshingStaleProof = false;
                if (restoredFromProof) {
                    Log.info("Using remembered source proof for " + target.displayName() + ": " + target.source);
                    pathDebug(target, "discover.sourceProof", "restored remembered proof source=" + target.source);
                    if (!needsDiscoveredSource(target)) {
                        Log.info("Skipping network discovery for " + target.displayName()
                            + " because the remembered source proof is still usable.");
                        pathDebug(target, "discover.skip", "restored source proof satisfied source");
                        continue;
                    }
                }
                Optional<SourceProof> activeProofForRefresh = lockState.activeSourceProof(target);
                if (activeProofForRefresh.isPresent()) {
                    refreshingStaleProof = shouldRefreshRememberedSourceProof(target, activeProofForRefresh.get());
                }
                if (shouldDeferMissingSourceDiscovery(target)) {
                    continue;
                }
                List<DiscoveryCandidate> discovered = discoverSourceCandidates(target);
                DiscoveryCandidate best = discovered.isEmpty() ? null : discovered.get(0);
                if (best != null) {
                    clearDeferredSummary(target);
                    clearDiscoveryState(target);
                    Log.info("Best discovered source: " + best.type + " -> " + best.source
                        + " (score " + best.score + ", latest=" + firstNonBlank(best.latestVersion, "unknown") + ")");
                    Log.info("Why: " + best.reason);
                    pathDebug(target, "discover.best", best.type + " score=" + best.score
                        + "; source=" + best.source + "; latest=" + firstNonBlank(best.latestVersion, "unknown")
                        + "; reason=" + best.reason);
                } else if (needsDiscoveredSource(target) && githubRateLimited) {
                    Log.warn("Discovery for " + target.displayName()
                        + " was incomplete because " + githubUnavailableReason() + "; leaving source unchanged for now.");
                    pathDebug(target, "discover.deferred", "no best candidate because " + githubUnavailableReason());
                    rememberDiscoveryDeferred(target, githubDeferredReason(), discoveryRetryAfter());
                } else if (needsDiscoveredSource(target)) {
                    Log.warn("No reliable hosted source found. Marking source as " + SOURCE_NOT_FOUND + ".");
                    pathDebug(target, "discover.unresolved", "no candidates met reliability threshold");
                    markSourceNotFound(target);
                }
                if (discovered.size() > 1) {
                    int shown = Math.min(3, discovered.size());
                    for (int i = 1; i < shown; i++) {
                        DiscoveryCandidate candidate = discovered.get(i);
                        Log.info("Alternate source: " + candidate.type + " -> " + candidate.source
                            + " (score " + candidate.score + ", latest=" + firstNonBlank(candidate.latestVersion, "unknown") + ")");
                        Log.info("Alternate why: " + candidate.reason);
                    }
                }
                boolean autoSwitched = false;
                if (config.discovery.autoSwitchSource) {
                    if (best != null && needsDiscoveredSource(target)) {
                        Log.info("autoSwitchSource will use " + best.source + " for " + target.displayName() + ".");
                        applyDiscoveredSource(target, discovered);
                        autoSwitched = true;
                    } else if (best != null && refreshingStaleProof && !sameDiscoverySource(target.source, best.source)) {
                        migratePrimarySource(target, best, discovered,
                            "stale Folia source proof", "refreshed source proof with Folia support evidence", true);
                        autoSwitched = true;
                    } else if (best != null && maybeMigrateConfiguredSource(target, best, discovered)) {
                        autoSwitched = true;
                    } else {
                        Log.info("autoSwitchSource is enabled.");
                    }
                }
                if (target.autoDiscovered || needsDiscoveredSource(target)) {
                    Log.info("Suggested config entry:");
                    Log.info("  - name: " + target.displayName());
                    String suggestedSource = best == null && githubRateLimited
                        ? firstNonBlank(target.source, "")
                        : (best == null ? SOURCE_NOT_FOUND : best.source);
                    Log.info("    source: " + quoteYaml(suggestedSource));
                    Log.info("    type: auto");
                    if (best != null && best.type.equals("github-release") && !best.projectHint.isBlank()) {
                        Log.info("    githubRepo: " + best.projectHint);
                    }
                    if (target.platform != null && !target.platform.isBlank()) {
                        Log.info("    platform: " + target.platform);
                    }
                    Log.info("    installAs: " + target.installAs);
                    Log.info("    required: false");
                }
                if (target.githubRepo != null && !target.githubRepo.isBlank()) {
                    boolean trusted = isTrustedGithubRepo(target.githubRepo);
                    boolean descriptorVerified = githubSourceDescriptorEvidence(
                        target,
                        "github-source",
                        "https://github.com/" + target.githubRepo,
                        target.githubRepo
                    ) == SourceDescriptorEvidence.MATCH;
                    String trustLabel = trusted
                        ? " (trusted)"
                        : descriptorVerified
                            ? " (descriptor-verified)"
                            : " (not trusted for source builds)";
                    Log.info("GitHub repo hint: " + target.githubRepo + trustLabel);
                    if (config.buildFromSource.preferHostedIfSameVersion) {
                        Log.info("Hosted jar preference: if a GitHub release/Hangar/Modrinth jar matches the build version, download it and skip compiling.");
                    }
                }
                if (target.fallbackSources.isEmpty()) {
                    Log.info("Fallback sources: none configured");
                } else {
                    for (String fallback : target.fallbackSources) {
                        try {
                            TargetConfig candidate = target.copyWithSource(fallback);
                            SourcePlan plan = resolveSource(candidate);
                            Log.info("Fallback source: " + plan.type + " -> " + plan.description);
                        } catch (IllegalArgumentException ex) {
                            Log.warn("Ignoring fallback source for " + target.displayName() + ": " + ex.getMessage());
                        }
                    }
                }
                if (autoSwitched && config.discovery.saveDiscoveredSources) {
                    Log.info("saveDiscoveredSources will write this source back to the config.");
                }
            }
            saveDiscoveredSourcesIfRequested();
            printDiscoverySummary("Discovery");
        }

        private void reportGithubAccess() {
            GithubTokenStatus token = githubTokenStatus(config);
            Log.info("GitHub API token: " + token.display());
            if (!token.warning.isBlank()) {
                Log.warn(token.warning);
            }
            Log.info("GitHub API budget: " + config.githubBudget.limits() + ".");
            reportGithubRateLimits(token);
        }

        private void reportGithubRateLimits(GithubTokenStatus token) {
            try {
                URI uri = URI.create("https://api.github.com/rate_limit");
                HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", config.userAgent)
                    .header("Accept", "application/json");
                applyGithubAuth(builder, config, uri);
                HttpResponse<String> response = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    if (response.statusCode() == 401) {
                        Optional<HttpResponse<String>> unauthenticated = retryGithubUnauthenticated(
                            config, client, uri, Duration.ofSeconds(20), "application/json", "GitHub rate limit check");
                        if (unauthenticated.isPresent()) {
                            response = unauthenticated.get();
                        } else {
                        handleGithubAuthFailure("GitHub rate limit check", uri, response);
                        return;
                        }
                    }
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    if (response.statusCode() == 401) {
                        handleGithubAuthFailure("GitHub rate limit check", uri, response);
                        return;
                    }
                    Log.warn("GitHub rate limit check failed with HTTP " + response.statusCode() + ".");
                    return;
                }
                Map<String, Object> root = asMap(new JsonParser(response.body()).parse());
                Map<String, Object> resources = asMap(root.get("resources"));
                Map<String, Object> core = asMap(resources.get("core"));
                Map<String, Object> search = asMap(resources.get("search"));
                int coreRemaining = intValue(core.get("remaining"), -1);
                int coreLimit = intValue(core.get("limit"), -1);
                int searchRemaining = intValue(search.get("remaining"), -1);
                int searchLimit = intValue(search.get("limit"), -1);
                Log.info("GitHub rate limit: core "
                    + rateCount(coreRemaining, core.get("remaining")) + "/" + rateCount(coreLimit, core.get("limit"))
                    + " remaining, search "
                    + rateCount(searchRemaining, search.get("remaining")) + "/" + rateCount(searchLimit, search.get("limit"))
                    + " remaining.");
                if (token.hasToken() && coreLimit > 0 && coreLimit <= 60) {
                    Log.warn("GitHub token appears not to be applied by GitHub; authenticated core limits are normally much higher than "
                        + coreLimit + "/hour. Check the token value and how the updater process receives it.");
                }
                if (coreRemaining >= 0 && coreRemaining <= GITHUB_CORE_RESERVE_REMAINING) {
                    pauseGithubFromRateMap(core);
                    githubRateLimited = true;
                    Log.warn("GitHub core API is near empty (" + coreRemaining + "/" + coreLimit
                        + "); pausing GitHub API calls until " + config.githubRateLimit.resetText() + ".");
                } else if (searchRemaining >= 0 && searchRemaining <= GITHUB_SEARCH_RESERVE_REMAINING) {
                    pauseGithubFromRateMap(search);
                    githubRateLimited = true;
                    Log.warn("GitHub search API is near empty (" + searchRemaining + "/" + searchLimit
                        + "); pausing GitHub search until " + config.githubRateLimit.resetText() + ".");
                }
            } catch (Exception ex) {
                Log.warn("Could not check GitHub rate limit: " + ex.getMessage());
            }
        }

        private void pauseGithubFromRateMap(Map<String, Object> rate) {
            String reset = stringValue(rate.get("reset"));
            if (!reset.isBlank()) {
                config.githubRateLimit.pauseUntil(githubResetInstant(reset));
            }
        }

        private String rateCount(int parsed, Object raw) {
            return parsed >= 0 ? Integer.toString(parsed) : stringValue(raw);
        }

        private void autoSwitchMissingPluginSources() {
            if (!config.discovery.enabled || !config.discovery.autoSwitchSource) {
                pathDebug(null, "autoswitch.skip", "discovery.enabled=" + config.discovery.enabled
                    + "; autoSwitchSource=" + config.discovery.autoSwitchSource);
                return;
            }
            pathDebug(null, "autoswitch.start", "pre-update source cleanup and missing-source discovery");
            markManualSourceOrigins();
            migrateUnsupportedSpigotPrimarySources();
            migrateMismatchedPrimarySources();
            migrateKnownStaleDiscoveredSources();
            for (TargetConfig target : allTargets()) {
                if (!target.enabled || target.server || !target.autoUpdate || !needsDiscoveredSource(target)) {
                    continue;
                }
                config.githubBudget.beginPlugin(target);
                if (applyRememberedSourceProof(target)) {
                    Log.info("Filled " + target.displayName() + " source from remembered proof -> " + target.source + ".");
                    pathDebug(target, "autoswitch.sourceProof", "filled missing source from remembered proof=" + target.source);
                    continue;
                }
                if (shouldDeferMissingSourceDiscovery(target)) {
                    pathDebug(target, "autoswitch.deferred", "active discovery deferral is still in force");
                    continue;
                }
                Log.info("Auto-switch discovery for " + target.displayName() + ".");
                pathDebug(target, "autoswitch.discover", "source missing; running discovery candidates");
                List<DiscoveryCandidate> discovered = discoverSourceCandidates(target);
                if (discovered.isEmpty()) {
                    if (githubRateLimited) {
                        Log.warn("Discovery deferred for " + target.displayName()
                            + " because " + githubUnavailableReason() + " prevented a complete search; leaving the current source unchanged.");
                        pathDebug(target, "autoswitch.deferred", "empty candidates because " + githubUnavailableReason());
                        rememberDiscoveryDeferred(target, githubDeferredReason(), discoveryRetryAfter());
                        continue;
                    }
                    Log.warn("No reliable hosted source found for " + target.displayName()
                        + "; marking source as " + SOURCE_NOT_FOUND + " and keeping existing jar.");
                    pathDebug(target, "autoswitch.unresolved", "empty candidates; mark Not Found");
                    markSourceNotFound(target);
                    continue;
                }
                applyDiscoveredSource(target, discovered);
            }
            refreshStaleRememberedSourceProofs();
            saveDiscoveredSourcesIfRequested();
            printDiscoverySummary("Auto-switch discovery");
        }

        synchronized Optional<Instant> nextDeferredDiscoveryRetryAfter() {
            if (!config.discovery.enabled || !config.discovery.autoSwitchSource || !config.discovery.retryDeferredAfterStartup) {
                return Optional.empty();
            }
            Instant now = Instant.now();
            return allTargets().stream()
                .filter(target -> target.enabled && !target.server && target.autoUpdate && needsDiscoveredSource(target))
                .map(this::deferredDiscoveryRetryInstant)
                .flatMap(Optional::stream)
                .map(retry -> retry.isBefore(now) ? now : retry)
                .min(Comparator.naturalOrder());
        }

        synchronized void retryDeferredDiscoverySourcesAfterStartup() {
            if (!config.discovery.enabled || !config.discovery.autoSwitchSource || !config.discovery.retryDeferredAfterStartup) {
                pathDebug(null, "startup.deferred-discovery.skip", "retryDeferredAfterStartup disabled or discovery unavailable");
                return;
            }
            config.githubRateLimit.isPaused();
            Instant now = Instant.now();
            List<TargetConfig> dueTargets = allTargets().stream()
                .filter(target -> target.enabled && !target.server && target.autoUpdate && needsDiscoveredSource(target))
                .filter(target -> deferredDiscoveryRetryInstant(target)
                    .map(retry -> !retry.isAfter(now))
                    .orElse(false))
                .toList();
            if (dueTargets.isEmpty()) {
                pathDebug(null, "startup.deferred-discovery.skip", "no deferred source discovery entries are due");
                return;
            }
            clearDiscoverySummary();
            if (!githubAuthFailed) {
                githubRateLimited = false;
                githubBudgetLimitedPlugins.clear();
                githubBudgetWarnings.clear();
                config.githubBudget.reset();
            }
            Log.info("Retrying deferred source discovery after startup for " + dueTargets.size()
                + " plugin(s). Found sources will be saved for a future update; no jars will be installed now.");
            pathDebug(null, "startup.deferred-discovery.retry", "dueTargets=" + dueTargets.stream()
                .map(TargetConfig::displayName)
                .collect(Collectors.joining(", ")));
            for (TargetConfig target : dueTargets) {
                config.githubBudget.beginPlugin(target);
                if (applyRememberedSourceProof(target)) {
                    Log.info("Filled " + target.displayName() + " source from remembered proof -> " + target.source + ".");
                    pathDebug(target, "startup.deferred-discovery.sourceProof", "filled source from remembered proof=" + target.source);
                    continue;
                }
                if (shouldDeferMissingSourceDiscovery(target)) {
                    pathDebug(target, "startup.deferred-discovery.deferred", "deferral is still active after retry check");
                    continue;
                }
                Log.info("Post-startup discovery retry for " + target.displayName() + ".");
                pathDebug(target, "startup.deferred-discovery.discover", "retrying due missing source after startup");
                List<DiscoveryCandidate> discovered = discoverSourceCandidates(target);
                if (discovered.isEmpty()) {
                    if (githubRateLimited) {
                        Log.warn("Discovery deferred for " + target.displayName()
                            + " because " + githubUnavailableReason() + " prevented a complete search; leaving the current source unchanged.");
                        pathDebug(target, "startup.deferred-discovery.deferred", "empty candidates because " + githubUnavailableReason());
                        rememberDiscoveryDeferred(target, githubDeferredReason(), discoveryRetryAfter());
                        continue;
                    }
                    Log.warn("No reliable hosted source found for " + target.displayName()
                        + "; keeping it unresolved for a later retry.");
                    pathDebug(target, "startup.deferred-discovery.unresolved", "empty candidates; keep unresolved with backoff");
                    markSourceNotFound(target);
                    continue;
                }
                applyDiscoveredSource(target, discovered);
            }
            saveDiscoveredSourcesIfRequested();
            printDiscoverySummary("Post-startup deferred discovery");
        }

        private Optional<Instant> deferredDiscoveryRetryInstant(TargetConfig target) {
            if (target == null) {
                return Optional.empty();
            }
            DiscoveryState state = lockState.discoveryStates.get(LockState.lockKey(target.installAs));
            if (state == null || state.nextRetryAfter.isBlank()) {
                return Optional.empty();
            }
            return parseInstantOptional(state.nextRetryAfter);
        }

        private void clearDiscoverySummary() {
            discoveryFound.clear();
            discoveryDeferred.clear();
            discoveryUnresolved.clear();
            discoveryProviderFailures.clear();
        }

        private boolean shouldDeferMissingSourceDiscovery(TargetConfig target) {
            if (!needsDiscoveredSource(target)) {
                return false;
            }
            Optional<DiscoveryState> deferred = lockState.activeDiscoveryDeferral(target);
            if (deferred.isEmpty()) {
                return false;
            }
            DiscoveryState state = deferred.get();
            if (lower(firstNonBlank(state.reason, "")).contains("github")) {
                Log.info("Retrying non-GitHub discovery for " + target.displayName()
                    + " even though GitHub discovery is deferred until " + state.nextRetryAfter + ".");
                pathDebug(target, "discovery.deferral", "GitHub-related deferral exists until "
                    + state.nextRetryAfter + ", but non-GitHub retry is allowed");
                return false;
            }
            Log.info("Discovery for " + target.displayName() + " is deferred until "
                + state.nextRetryAfter + " (" + firstNonBlank(state.reason, "waiting before retry") + ").");
            pathDebug(target, "discovery.deferral", "deferred until " + state.nextRetryAfter
                + "; reason=" + firstNonBlank(state.reason, "waiting before retry"));
            return true;
        }

        private Instant discoveryRetryAfter() {
            Instant reset = config.githubRateLimit.resetAt;
            if (reset != null && reset.isAfter(Instant.now())) {
                return reset;
            }
            return Instant.now().plus(DISCOVERY_NOT_FOUND_BACKOFF);
        }

        private String githubUnavailableReason() {
            return githubAuthFailed ? "GitHub authentication failed" : "GitHub rate limiting is active";
        }

        private String githubDeferredReason() {
            return githubAuthFailed ? "GitHub auth failed" : "GitHub rate limited";
        }

        private void rememberDiscoveryDeferred(TargetConfig target, String reason, Instant nextRetryAfter) {
            discoveryDeferred.add(target.displayName() + " (" + firstNonBlank(reason, "deferred") + ")");
            pathDebug(target, "lock.discovery.defer", "reason=" + firstNonBlank(reason, "deferred")
                + "; retryAfter=" + nextRetryAfter);
            lockState.rememberDiscoveryDeferred(target, reason, nextRetryAfter);
            writeLockQuietly();
        }

        private void clearDeferredSummary(TargetConfig target) {
            if (target == null) {
                return;
            }
            String prefix = target.displayName() + " (";
            discoveryDeferred.removeIf(item -> item.equals(target.displayName()) || item.startsWith(prefix));
        }

        private void rememberDiscoveryNotFound(TargetConfig target, String reason) {
            pathDebug(target, "lock.discovery.not-found", "reason=" + firstNonBlank(reason, "no reliable source found"));
            lockState.rememberDiscoveryNotFound(target, reason);
            writeLockQuietly();
        }

        private void markManualSourceOrigins() {
            for (TargetConfig target : config.plugins) {
                if (target.server || isMissingSourceValue(target.source)) {
                    continue;
                }
                String origin = lower(firstNonBlank(target.sourceOrigin, ""));
                if (origin.equals(SOURCE_ORIGIN_UNRESOLVED)) {
                    // A real configured source is no longer unresolved; promote it so sorting moves it out of that group.
                    target.sourceOrigin = SOURCE_ORIGIN_MANUAL;
                    target.sourceOriginUpdatedThisRun = true;
                    Log.info("Marked configured source for " + target.displayName()
                        + " as manual because it already has a source URL.");
                    pathDebug(target, "manual-protection", "origin unresolved but source is real; promote to manual");
                    continue;
                }
                if (origin.equals(SOURCE_ORIGIN_DISCOVERED)) {
                    Optional<SourceProof> proof = lockState.activeSourceProof(target);
                    if (proof.isEmpty()) {
                        target.sourceOrigin = SOURCE_ORIGIN_MANUAL;
                        target.sourceOriginUpdatedThisRun = true;
                        Log.info("Marked configured source for " + target.displayName()
                            + " as manual because it is marked discovered but no matching source proof exists.");
                        pathDebug(target, "manual-protection", "origin discovered but no active sourceProof; promote to manual");
                    } else if (!sourcesMatchLoosely(proof.get().source, target.source)) {
                        // The lock remembers what discovery wrote last time; a different configured URL is a user edit.
                        target.sourceOrigin = SOURCE_ORIGIN_MANUAL;
                        target.sourceOriginUpdatedThisRun = true;
                        Log.info("Marked configured source for " + target.displayName()
                            + " as manual because it differs from the remembered discovered source.");
                        pathDebug(target, "manual-protection", "current source differs from remembered sourceProof="
                            + proof.get().source + "; promote to manual");
                    }
                    continue;
                }
                if (origin.equals(SOURCE_ORIGIN_DISCOVERED_UNVERIFIED)) {
                    continue;
                }
                if (!origin.isBlank()) {
                    continue;
                }
                target.sourceOrigin = SOURCE_ORIGIN_MANUAL;
                target.sourceOriginUpdatedThisRun = true;
                Log.info("Marked configured source for " + target.displayName() + " as manual.");
                pathDebug(target, "manual-protection", "blank sourceOrigin with real source; promote to manual");
            }
        }

        private void clearDiscoveryState(TargetConfig target) {
            pathDebug(target, "lock.discovery.clear", "clearing discovery retry/not-found state");
            lockState.clearDiscoveryState(target);
            writeLockQuietly();
        }

        private void rememberSourceProof(TargetConfig target, String source, String type, GithubRepo repo,
                                         PluginJarInfo descriptor, String proofKind) {
            if (capturingSourceProofs()) {
                pathDebug(target, "lock.sourceProof.capture", "pending proof source=" + source
                    + "; type=" + type + "; kind=" + proofKind);
                captureSourceProof(target, source, type, repo == null ? "" : repo.owner + "/" + repo.name, descriptor, proofKind);
                return;
            }
            pathDebug(target, "lock.sourceProof.remember", "source=" + source + "; type=" + type
                + "; repo=" + (repo == null ? "" : repo.owner + "/" + repo.name) + "; kind=" + proofKind);
            lockState.rememberSourceProof(target, source, type, repo, descriptor, proofKind);
            writeLockQuietly();
        }

        private void rememberSourceProof(TargetConfig target, String source, String type, String project,
                                         PluginJarInfo descriptor, String proofKind) {
            if (capturingSourceProofs()) {
                pathDebug(target, "lock.sourceProof.capture", "pending proof source=" + source
                    + "; type=" + type + "; project=" + project + "; kind=" + proofKind);
                captureSourceProof(target, source, type, project, descriptor, proofKind);
                return;
            }
            pathDebug(target, "lock.sourceProof.remember", "source=" + source + "; type=" + type
                + "; project=" + project + "; kind=" + proofKind);
            lockState.rememberSourceProof(target, source, type, project, descriptor, proofKind);
            writeLockQuietly();
        }

        private void rememberRejectedSourceProof(TargetConfig target, String source, String type, String project,
                                                 PluginJarInfo descriptor, String reason) {
            if (capturingSourceProofs()) {
                pathDebug(target, "lock.sourceProof.reject.capture", "pending rejected proof source=" + source
                    + "; reason=" + reason);
                pendingRejectedSourceProofs.add(new PendingRejectedSourceProof(target, source, type, project, descriptor, reason));
                return;
            }
            pathDebug(target, "lock.sourceProof.reject", "source=" + source + "; type=" + type
                + "; project=" + project + "; reason=" + reason);
            lockState.rememberRejectedSourceProof(target, source, type, project, descriptor, reason);
            writeLockQuietly();
        }

        private boolean capturingSourceProofs() {
            return sourceProofCaptureDepth > 0;
        }

        private void captureSourceProof(TargetConfig target, String source, String type, String project,
                                        PluginJarInfo descriptor, String proofKind) {
            if (target == null || target.server || source.isBlank() || descriptor == null || !descriptor.hasDescriptor) {
                return;
            }
            PendingSourceProof pending = new PendingSourceProof(target, source, type, project, descriptor, proofKind);
            pendingSourceProofs.removeIf(existing -> existing.matches(target, source));
            pendingSourceProofs.add(pending);
            pathDebug(target, "lock.sourceProof.pending", "stored pending proof source=" + source
                + "; descriptor=" + firstNonBlank(descriptor.name, descriptor.id, "unknown")
                + "; foliaSupported=" + descriptor.foliaSupported);
        }

        private void enrichSourceProof(TargetConfig target, String source, ForkLineage lineage, String reason) {
            if (target == null || lineage == null || !lineage.isFork()) {
                return;
            }
            if (capturingSourceProofs()) {
                pendingSourceProofFor(target, source).ifPresent(proof -> {
                    proof.lineage = lineage;
                    proof.lineageReason = firstNonBlank(reason, lineage.describe());
                });
                pathDebug(target, "lock.sourceProof.lineage.capture", "pending lineage=" + lineage.describe()
                    + "; reason=" + firstNonBlank(reason, lineage.describe()));
                return;
            }
            pathDebug(target, "lock.sourceProof.lineage", "lineage=" + lineage.describe()
                + "; reason=" + firstNonBlank(reason, lineage.describe()));
            lockState.enrichSourceProof(target, lineage, reason);
            writeLockQuietly();
        }

        private Optional<PendingSourceProof> pendingSourceProofFor(TargetConfig target, String source) {
            for (int i = pendingSourceProofs.size() - 1; i >= 0; i--) {
                PendingSourceProof proof = pendingSourceProofs.get(i);
                if (proof.matches(target, source)) {
                    return Optional.of(proof);
                }
            }
            return Optional.empty();
        }

        private void discardPendingSourceProofs(TargetConfig target) {
            if (target == null) {
                return;
            }
            pendingSourceProofs.removeIf(proof -> normalizedConfigPath(proof.installAs).equals(normalizedConfigPath(target.installAs)));
        }

        private void flushPendingRejectedSourceProofs() {
            if (pendingRejectedSourceProofs.isEmpty()) {
                return;
            }
            boolean wrote = false;
            for (PendingRejectedSourceProof proof : pendingRejectedSourceProofs) {
                pathDebug(proof.target, "lock.sourceProof.reject.flush", "source=" + proof.source
                    + "; reason=" + proof.reason);
                lockState.rememberRejectedSourceProof(proof.target, proof.source, proof.type, proof.project,
                    proof.descriptor, proof.reason);
                wrote = true;
            }
            pendingRejectedSourceProofs.clear();
            if (wrote) {
                writeLockQuietly();
            }
        }

        private boolean applyRememberedSourceProof(TargetConfig target) {
            if (!needsDiscoveredSource(target)) {
                return false;
            }
            Optional<SourceProof> proof = lockState.activeSourceProof(target);
            if (proof.isEmpty()) {
                return false;
            }
            SourceProof remembered = proof.get();
            if (shouldRefreshRememberedSourceProof(target, remembered)) {
                Log.info("Not restoring remembered source proof for " + target.displayName()
                    + " because the installed jar is Folia-compatible but the old proof did not record Folia support.");
                pathDebug(target, "sourceProof.restore.skip", "proof needs Folia support refresh before reuse");
                return false;
            }
            target.source = remembered.source;
            target.sourceOrigin = SOURCE_ORIGIN_DISCOVERED;
            target.type = remembered.type.equals("github-source") ? "github-source" : "auto";
            if ((remembered.type.equals("github-release") || remembered.type.equals("github-source"))
                && !remembered.repo.isBlank()) {
                target.githubRepo = remembered.repo;
            } else {
                target.githubRepo = "";
            }
            target.sourceDiscoveredThisRun = true;
            clearDiscoveryState(target);
            pathDebug(target, "sourceProof.restore", "source=" + target.source + "; type=" + target.type
                + "; repo=" + firstNonBlank(target.githubRepo, "<blank>"));
            return true;
        }

        private void refreshStaleRememberedSourceProofs() {
            if (!config.discovery.enabled || !config.discovery.autoSwitchSource) {
                return;
            }
            if (githubAuthFailed || githubRateLimited || config.githubRateLimit.isPaused()) {
                Log.info("Skipping stale Folia source proof refresh because GitHub discovery is currently limited; unresolved plugins keep priority.");
                pathDebug(null, "proof-refresh.skip", "GitHub unavailable; authFailed=" + githubAuthFailed
                    + "; rateLimited=" + githubRateLimited + "; paused=" + config.githubRateLimit.isPaused());
                return;
            }
            for (TargetConfig target : allTargets()) {
                if (!target.enabled || target.server || !target.autoUpdate || needsDiscoveredSource(target)) {
                    continue;
                }
                if (shouldProtectConfiguredSource(target, "Folia proof refresh")) {
                    continue;
                }
                Optional<SourceProof> proof = lockState.activeSourceProof(target);
                if (proof.isEmpty() || !sourcesMatchLoosely(proof.get().source, target.source)
                    || !shouldRefreshRememberedSourceProof(target, proof.get())) {
                    continue;
                }
                config.githubBudget.beginPlugin(target);
                Log.info("Refreshing discovered source proof for " + target.displayName()
                    + " because the installed jar is Folia-compatible and the remembered proof lacks Folia support evidence.");
                List<DiscoveryCandidate> discovered = discoverSourceCandidates(target);
                if (discovered.isEmpty()) {
                    if (githubRateLimited) {
                        Log.warn("Folia proof refresh deferred for " + target.displayName()
                            + " because " + githubUnavailableReason() + " prevented a complete source check.");
                        rememberDiscoveryDeferred(target, githubDeferredReason(), discoveryRetryAfter());
                    }
                    continue;
                }
                applyDiscoveredSource(target, discovered);
            }
        }

        private boolean shouldRefreshRememberedSourceProof(TargetConfig target, SourceProof proof) {
            if (target == null || proof == null) {
                return false;
            }
            PluginJarInfo installed = installedPluginInfo(target);
            if (installed == null || !Boolean.TRUE.equals(installed.foliaSupported)) {
                return false;
            }
            if (Boolean.TRUE.equals(proof.foliaSupported)) {
                return false;
            }
            if (!proof.forkRepo.isBlank() || !proof.upstreamRepo.isBlank()) {
                return false;
            }
            String proofText = lower(String.join(" ",
                firstNonBlank(proof.proof, ""),
                firstNonBlank(proof.source, ""),
                firstNonBlank(proof.type, ""),
                firstNonBlank(proof.repo, ""),
                firstNonBlank(proof.proofReason, "")
            ));
            return !proofText.contains("folia");
        }

        private void writeLockQuietly() {
            try {
                lockState.write(config);
            } catch (IOException ex) {
                Log.warn("Could not update updater.lock.yml discovery state: " + ex.getMessage());
            }
        }

        private void migrateUnsupportedSpigotPrimarySources() {
            for (TargetConfig target : allTargets()) {
                if (!target.enabled || target.server || !target.autoUpdate || needsDiscoveredSource(target)) {
                    continue;
                }
                if (!isSpigotConfiguredSource(target)) {
                    continue;
                }
                if (shouldProtectConfiguredSource(target, "unsupported Spigot source migration")) {
                    continue;
                }
                Log.info("Checking for non-Spigot replacement source for unsupported Spigot source on " + target.displayName() + ".");
                List<DiscoveryCandidate> discovered = discoverSourceCandidates(target);
                Optional<DiscoveryCandidate> better = discovered.stream()
                    .filter(candidate -> !isSpigotType(candidate.type))
                    .findFirst();
                if (better.isEmpty()) {
                    if (githubRateLimited) {
                        Log.warn("Spigot migration deferred for " + target.displayName()
                            + " because " + githubUnavailableReason() + " prevented a complete replacement search; leaving the current source unchanged.");
                    } else {
                        Log.warn("No better non-Spigot source found for " + target.displayName()
                            + "; marking source as " + SOURCE_NOT_FOUND + " because Spigot sources are unsupported.");
                        markSourceNotFound(target);
                    }
                    continue;
                }
                migratePrimarySource(target, better.get(), discovered, "Spigot", "removed unsupported Spigot primary", false);
            }
        }

        private void migrateMismatchedPrimarySources() {
            for (TargetConfig target : allTargets()) {
                if (!target.enabled || target.server || !target.autoUpdate || needsDiscoveredSource(target)) {
                    continue;
                }
                SourceOwnerSignal currentOwner = configuredSourceOwnerSignal(target);
                if (!currentOwner.conflict) {
                    continue;
                }
                if (shouldProtectConfiguredSource(target, "author-mismatch migration")) {
                    continue;
                }
                Log.warn("Configured primary source for " + target.displayName()
                    + " appears to belong to a different author (" + currentOwner.reason + ").");
                List<DiscoveryCandidate> discovered = discoverSourceCandidates(target);
                Optional<DiscoveryCandidate> better = discovered.stream()
                    .filter(candidate -> !sourceOwnerSignal(
                        target.detectedAuthors,
                        candidate.type,
                        candidate.source,
                        candidate.projectHint,
                        candidate.label
                    ).conflict)
                    .findFirst();
                if (better.isPresent()) {
                    migratePrimarySource(target, better.get(), discovered,
                        "identity-mismatched source", "migrated away from author-mismatched primary", false);
                } else if (githubRateLimited) {
                    Log.warn("Author-mismatch migration deferred for " + target.displayName()
                        + " because " + githubUnavailableReason() + " prevented a complete source check; leaving the current source unchanged.");
                } else {
                    Log.warn("No author-compatible source found for " + target.displayName()
                        + "; marking source as " + SOURCE_NOT_FOUND + " instead of using the mismatched primary.");
                    markSourceNotFound(target);
                }
            }
        }

        private void migrateKnownStaleDiscoveredSources() {
            for (TargetConfig target : allTargets()) {
                if (!target.enabled || target.server || !target.autoUpdate || shouldProtectConfiguredSource(target, "known source correction")) {
                    continue;
                }
                String plugin = lower(target.displayName());
                String source = lower(canonicalDiscoverySource(target.source));
                if (plugin.equals("votifier") && source.contains("github.com/ichbinjoe/votifier")) {
                    forceDiscoveredSource(
                        target,
                        "https://github.com/NuVotifier/NuVotifier",
                        "github-source",
                        "NuVotifier/NuVotifier",
                        "migrated Votifier to the NuVotifier source repo"
                    );
                }
            }
        }

        private void migrateKnownStaleDiscoveredSourcesIfAutoSwitchEnabled() {
            if (config.discovery.autoSwitchSource) {
                migrateKnownStaleDiscoveredSources();
            }
        }

        private void forceDiscoveredSource(TargetConfig target, String source, String type, String repo, String reason) {
            Log.info("Correcting " + target.displayName() + " source -> " + source + " (" + reason + ").");
            target.source = source;
            target.sourceOrigin = SOURCE_ORIGIN_DISCOVERED_UNVERIFIED;
            target.type = type.equals("github-source") ? "github-source" : "auto";
            target.githubRepo = repo;
            target.fallbackSources.removeIf(existing -> sameDiscoverySource(existing, source)
                || isManualOnlySourceType(detectType(existing, target)));
            target.sourceDiscoveredThisRun = true;
            enforceSourceProofInvariant(target);
            clearDiscoveryState(target);
            Log.info("Marked " + target.displayName()
                + " source as discovered-unverified because this correction is machine-written but not descriptor-proven.");
        }

        private SourceOwnerSignal configuredSourceOwnerSignal(TargetConfig target) {
            SourceDescriptorEvidence descriptor = githubSourceDescriptorEvidence(
                target,
                detectType(target.source, target),
                target.source,
                firstNonBlank(target.githubRepo, target.project)
            );
            if (descriptor == SourceDescriptorEvidence.MATCH) {
                return new SourceOwnerSignal(0, false, "source descriptor matches installed plugin");
            }
            if (descriptor == SourceDescriptorEvidence.UNKNOWN && isGithubLikeSource(detectType(target.source, target), target.source, firstNonBlank(target.githubRepo, target.project))) {
                return new SourceOwnerSignal(0, false, "source descriptor could not be checked; keeping configured GitHub source until jar validation");
            }
            return sourceOwnerSignal(
                target.detectedAuthors,
                detectType(target.source, target),
                target.source,
                firstNonBlank(target.githubRepo, target.project),
                target.source
            );
        }

        private boolean maybeMigrateConfiguredSource(TargetConfig target, DiscoveryCandidate best, List<DiscoveryCandidate> discovered) {
            if (needsDiscoveredSource(target)) {
                pathDebug(target, "source.migration.skip", "current source is missing, so discovery will fill it instead of migrating it");
                return false;
            }
            if (shouldProtectConfiguredSource(target, "alternate source migration")) {
                pathDebug(target, "source.migration.skip", "configured source is protected by manual/sourceProof rules");
                return false;
            }
            SourceOwnerSignal currentOwner = configuredSourceOwnerSignal(target);
            if (currentOwner.conflict) {
                if (sameDiscoverySource(target.source, best.source)
                    && best.reason.contains("source descriptor matches installed plugin")) {
                    pathDebug(target, "source.migration.skip", "configured source has an owner warning, but the best candidate is the same descriptor-proven source");
                    return false;
                }
                pathDebug(target, "source.migration", "configured source owner conflict: " + currentOwner.reason
                    + "; selected " + best.type + " -> " + best.source + " because " + best.reason);
                Log.warn("Configured primary source for " + target.displayName()
                    + " appears to belong to a different author (" + currentOwner.reason + ").");
                migratePrimarySource(target, best, discovered,
                    "identity-mismatched source", "migrated away from author-mismatched primary", false);
                return true;
            }
            if (isSpigotConfiguredSource(target) && !isSpigotType(best.type)) {
                pathDebug(target, "source.migration", "configured source is Spigot/manual-only; selected " + best.type
                    + " -> " + best.source + " because " + best.reason);
                migratePrimarySource(target, best, discovered, "Spigot", "removed unsupported Spigot primary", false);
                return true;
            }
            pathDebug(target, "source.migration.skip", "configured source stays primary; no owner conflict or manual-only migration reason");
            return false;
        }

        private boolean sameDiscoverySource(String left, String right) {
            return canonicalDiscoverySource(left).equals(canonicalDiscoverySource(right));
        }

        private boolean isSpigotConfiguredSource(TargetConfig target) {
            String source = target.source == null ? "" : target.source.trim();
            return !source.isBlank() && !isNotFoundSourceValue(source) && isSpigotType(detectType(source, target));
        }

        private void migratePrimarySource(TargetConfig target, DiscoveryCandidate best, List<DiscoveryCandidate> discovered,
                                          String oldLabel, String action, boolean keepOldAsFallback) {
            String oldSource = target.source;
            pathDebug(target, "source.migrate.apply", action + "; old=" + firstNonBlank(oldSource, "blank")
                + "; new=" + best.source + "; candidate=" + best.type + "; reason=" + best.reason);
            target.source = best.source;
            target.sourceOrigin = SOURCE_ORIGIN_DISCOVERED_UNVERIFIED;
            target.type = best.type.equals("github-source") ? "github-source" : "auto";
            if ((best.type.equals("github-release") || best.type.equals("github-source")) && !best.projectHint.isBlank()) {
                target.githubRepo = best.projectHint;
            } else if (target.githubRepo != null && !target.githubRepo.isBlank()
                && sourceOwnerSignal(target.detectedAuthors, "github-release", target.githubRepo, target.githubRepo, target.githubRepo).conflict) {
                target.githubRepo = "";
            }

            LinkedHashMap<String, String> fallbackMap = new LinkedHashMap<>();
            if (keepOldAsFallback) {
                fallbackMap.put(oldSource, oldSource);
            }
            for (String fallback : target.fallbackSources) {
                if (!fallback.equals(best.source)) {
                    DiscoveryCandidate existingFallback = new DiscoveryCandidate(
                        detectType(fallback, target),
                        fallback,
                        "",
                        "",
                        fallback,
                        "existing fallback",
                        0,
                        0
                    );
                    if (shouldPersistFallback(target, existingFallback)) {
                        fallbackMap.putIfAbsent(fallback, fallback);
                    }
                }
            }
            for (DiscoveryCandidate candidate : discovered) {
                if (fallbackMap.size() >= 3) {
                    break;
                }
                if (!candidate.source.equals(best.source) && shouldPersistFallback(target, candidate)) {
                    fallbackMap.putIfAbsent(candidate.source, candidate.source);
                }
            }
            target.fallbackSources = new ArrayList<>(fallbackMap.keySet());
            target.sourceDiscoveredThisRun = true;
            if (commitSelectedSourceProof(target, best)) {
                target.sourceOrigin = SOURCE_ORIGIN_DISCOVERED;
                pathDebug(target, "source.migrate.proof", "selected source matched remembered/captured proof; sourceOrigin=discovered");
            } else {
                pathDebug(target, "source.migrate.proof", "selected source has no descriptor proof yet; sourceOrigin=discovered-unverified");
                Log.info("Marked " + target.displayName()
                    + " source as discovered-unverified because the selected source is machine-written but not descriptor-proven.");
            }
            clearDiscoveryState(target);
            Log.info("Migrated " + target.displayName() + " primary source from " + oldLabel + " to "
                + best.type + " -> " + best.source + "; " + action + ".");
            pathDebug(target, "source.migrate.fallbacks", "persisted " + target.fallbackSources.size() + " fallback source(s)");
            pathDebug(target, "fallback.order", "primary=" + target.source
                + "; fallbacks=" + (target.fallbackSources.isEmpty() ? "none" : String.join(" -> ", target.fallbackSources)));
        }

        private boolean needsDiscoveredSource(TargetConfig target) {
            return isMissingSourceValue(target.source);
        }

        private boolean shouldProtectConfiguredSource(TargetConfig target, String action) {
            if (!isManualConfiguredSource(target)) {
                return false;
            }
            pathDebug(target, "manual-protection.keep", "keeping configured source during " + action);
            Log.warn("Keeping configured source for " + target.displayName() + " during " + action
                + " because it appears to be manually supplied. The updater will validate it before installing jars.");
            return true;
        }

        private boolean isManualConfiguredSource(TargetConfig target) {
            if (target == null || needsDiscoveredSource(target)) {
                return false;
            }
            String origin = lower(firstNonBlank(target.sourceOrigin, ""));
            if (origin.isBlank() || origin.equals(SOURCE_ORIGIN_MANUAL) || origin.equals(SOURCE_ORIGIN_UNRESOLVED)) {
                return true;
            }
            if (origin.equals(SOURCE_ORIGIN_DISCOVERED)) {
                Optional<SourceProof> proof = lockState.activeSourceProof(target);
                return proof.isEmpty() || !sourcesMatchLoosely(proof.get().source, target.source);
            }
            if (origin.equals(SOURCE_ORIGIN_DISCOVERED_UNVERIFIED)) {
                return false;
            }
            return true;
        }

        private void markSourceNotFound(TargetConfig target) {
            target.source = SOURCE_NOT_FOUND;
            target.sourceOrigin = SOURCE_ORIGIN_UNRESOLVED;
            target.type = "auto";
            target.sourceDiscoveredThisRun = true;
            discoveryUnresolved.add(target.displayName());
            rememberDiscoveryNotFound(target, "no reliable source found");
        }

        private void applyDiscoveredSource(TargetConfig target, List<DiscoveryCandidate> discovered) {
            DiscoveryCandidate best = discovered.get(0);
            pathDebug(target, "source.apply", "selected " + best.type + " -> " + best.source
                + "; score=" + best.score + "; reason=" + best.reason);
            target.source = best.source;
            target.sourceOrigin = SOURCE_ORIGIN_DISCOVERED_UNVERIFIED;
            target.type = best.type.equals("github-source") ? "github-source" : "auto";
            if (best.type.equals("github-release") && !best.projectHint.isBlank()) {
                target.githubRepo = best.projectHint;
            }
            if (best.type.equals("github-source") && !best.projectHint.isBlank()) {
                target.githubRepo = best.projectHint;
            }
            if (!best.type.equals("github-release") && !best.type.equals("github-source")) {
                target.githubRepo = "";
            }
            target.sourceDiscoveredThisRun = true;
            discoveryFound.add(target.displayName() + " -> " + best.type);
            if (commitSelectedSourceProof(target, best)) {
                target.sourceOrigin = SOURCE_ORIGIN_DISCOVERED;
                pathDebug(target, "source.apply.proof", "selected source matched remembered/captured proof; sourceOrigin=discovered");
            } else {
                pathDebug(target, "source.apply.proof", "selected source has no descriptor proof yet; sourceOrigin=discovered-unverified");
                Log.info("Marked " + target.displayName()
                    + " source as discovered-unverified because the selected source is machine-written but not descriptor-proven.");
            }
            clearDiscoveryState(target);

            Set<String> fallbackSet = new HashSet<>(target.fallbackSources);
            for (DiscoveryCandidate candidate : discovered) {
                if (candidate.source.equals(best.source)) {
                    continue;
                }
                if (!shouldPersistFallback(target, candidate)) {
                    continue;
                }
                if (fallbackSet.add(candidate.source)) {
                    target.fallbackSources.add(candidate.source);
                }
                if (target.fallbackSources.size() >= 3) {
                    break;
                }
            }
            Log.info("Auto-switched " + target.displayName() + " source -> " + best.source
                + (target.fallbackSources.isEmpty() ? "" : " with " + target.fallbackSources.size() + " fallback(s)"));
            pathDebug(target, "source.apply.fallbacks", "persisted " + target.fallbackSources.size() + " fallback source(s)");
            pathDebug(target, "fallback.order", "primary=" + target.source
                + "; fallbacks=" + (target.fallbackSources.isEmpty() ? "none" : String.join(" -> ", target.fallbackSources)));
        }

        private boolean commitSelectedSourceProof(TargetConfig target, DiscoveryCandidate selected) {
            if (target == null || selected == null) {
                enforceSourceProofInvariant(target);
                return false;
            }
            Optional<PendingSourceProof> proof = pendingSourceProofFor(target, selected.source);
            if (proof.isPresent()) {
                PendingSourceProof pending = proof.get();
                lockState.rememberSourceProof(target, pending.source, pending.type, pending.project,
                    pending.descriptor, pending.proofKind);
                if (pending.lineage != null) {
                    lockState.enrichSourceProof(target, pending.lineage, pending.lineageReason);
                }
                pendingSourceProofs.remove(pending);
                writeLockQuietly();
            }
            enforceSourceProofInvariant(target);
            return lockState.activeSourceProof(target)
                .map(active -> sourcesMatchLoosely(active.source, target.source))
                .orElse(false);
        }

        private void enforceSourceProofInvariant(TargetConfig target) {
            if (target == null || target.server) {
                return;
            }
            Optional<SourceProof> proof = lockState.activeSourceProof(target);
            if (proof.isPresent() && !sourcesMatchLoosely(proof.get().source, target.source)) {
                lockState.forgetSourceProof(target);
                writeLockQuietly();
            }
        }

        private boolean shouldPersistFallback(TargetConfig target, DiscoveryCandidate candidate) {
            if (candidate == null || candidate.source == null || candidate.source.isBlank()) {
                pathDebug(target, "fallback.reject", "candidate is blank");
                return false;
            }
            if (isManualOnlySourceType(candidate.type)) {
                pathDebug(target, "fallback.reject", candidate.source + " is " + candidate.type + ", and that source type is manual-only");
                Log.info("Not persisting fallback for " + target.displayName() + " from " + candidate.source
                    + " because " + candidate.type + " sources are manual-only.");
                return false;
            }
            if (candidate.reason.contains("source descriptor matches installed plugin")) {
                pathDebug(target, "fallback.keep", candidate.source + " has descriptor proof from candidate inspection");
                return true;
            }
            Optional<SourceProof> proof = lockState.activeSourceProof(target);
            if (proof.isPresent() && sourcesMatchLoosely(proof.get().source, candidate.source)) {
                pathDebug(target, "fallback.keep", candidate.source + " matches the active remembered sourceProof");
                return true;
            }
            SourceOwnerSignal owner = sourceOwnerSignal(
                target.detectedAuthors,
                candidate.type,
                candidate.source,
                candidate.projectHint,
                candidate.label
            );
            if (owner.conflict) {
                pathDebug(target, "fallback.reject", candidate.source + " owner conflict: " + owner.reason);
                return false;
            }
            pathDebug(target, "fallback.reject", candidate.source + " was not descriptor-proven");
            Log.info("Not persisting fallback for " + target.displayName() + " from " + candidate.source
                + " because it has not been descriptor-proven.");
            return false;
        }

        private void saveDiscoveredSourcesIfRequested() {
            List<TargetConfig> changed = new ArrayList<>();
            for (TargetConfig target : config.plugins) {
                boolean persistDiscoveredSource = config.discovery.saveDiscoveredSources && target.sourceDiscoveredThisRun;
                boolean persistManualProtection = target.sourceOriginUpdatedThisRun && !target.sourceDiscoveredThisRun;
                boolean persistManualCatalog = target.manualCatalogUpdatedThisRun;
                if ((persistDiscoveredSource || persistManualProtection || persistManualCatalog)
                    && target.source != null && !target.source.isBlank()) {
                    changed.add(target);
                }
            }
            boolean persistPrunedMissing = config.discovery.saveDiscoveredSources && !config.prunedMissingPlugins.isEmpty();
            boolean persistNewPluginLinks = !config.consumedNewPluginLinks.isEmpty();
            if (changed.isEmpty() && !persistPrunedMissing && !persistNewPluginLinks) {
                pathDebug(null, "config.rewrite.skip", "no discovered/manual-catalog/manual-protection/pruned source changes need saving");
                return;
            }
            try {
                pathDebug(null, "config.rewrite", "saving " + changed.size()
                    + " source change(s), pruning " + config.prunedMissingPlugins.size()
                    + " missing plugin entr" + (config.prunedMissingPlugins.size() == 1 ? "y" : "ies")
                    + ", consuming " + config.consumedNewPluginLinks.size()
                    + " newPluginLinks item" + (config.consumedNewPluginLinks.size() == 1 ? "" : "s")
                    + " to updater.yml and normalizing editable settings");
                for (TargetConfig target : config.prunedMissingPlugins) {
                    pathDebug(target, "config.rewrite.prune", "missing installAs=" + firstNonBlank(target.installAs, "<blank>")
                        + "; origin=" + firstNonBlank(target.sourceOrigin, "<blank>"));
                }
                for (TargetConfig target : changed) {
                    pathDebug(target, "config.rewrite.target", "source=" + firstNonBlank(target.source, "<blank>")
                        + "; origin=" + firstNonBlank(target.sourceOrigin, "<blank>")
                        + "; type=" + firstNonBlank(target.type, "<blank>")
                        + "; manualCatalog=" + target.manualCatalogUpdatedThisRun
                        + "; fallbacks=" + target.fallbackSources.size());
                }
                ConfigRewriter.saveDiscoveredPluginSources(config, changed);
                sortPluginTargetsInMemory();
                pathDebug(null, "config.rewrite.sorted", "plugin targets sorted into manual/discovered/unresolved groups");
            } catch (IOException ex) {
                pathDebug(null, "config.rewrite.failed", ex.getMessage());
                Log.warn("Could not save discovered sources to config: " + ex.getMessage());
            }
        }

        private void sortPluginTargetsInMemory() {
            config.plugins.sort(Comparator
                .comparingInt((TargetConfig target) -> targetSourceGroup(target))
                .thenComparing(this::targetSortName, String.CASE_INSENSITIVE_ORDER));
        }

        private int targetSourceGroup(TargetConfig target) {
            if (target == null || isMissingSourceValue(target.source)) {
                return 2;
            }
            String origin = lower(firstNonBlank(target.sourceOrigin, ""));
            if (origin.equals(SOURCE_ORIGIN_UNRESOLVED)) {
                return 2;
            }
            if (origin.equals(SOURCE_ORIGIN_DISCOVERED)) {
                return 1;
            }
            if (origin.equals(SOURCE_ORIGIN_DISCOVERED_UNVERIFIED)) {
                return 1;
            }
            return 0;
        }

        private String targetSortName(TargetConfig target) {
            return target == null ? "zzzz" : firstNonBlank(target.name, target.installAs, target.displayName(), "zzzz");
        }

        private void printDiscoverySummary(String label) {
            if (discoveryFound.isEmpty()
                && discoveryDeferred.isEmpty()
                && discoveryUnresolved.isEmpty()
                && discoveryProviderFailures.isEmpty()
                && !githubAuthFailed
                && !githubRateLimited) {
                return;
            }
            Log.info("");
            Log.info(label + " summary: found " + discoveryFound.size()
                + ", deferred " + discoveryDeferred.size()
                + ", unresolved " + discoveryUnresolved.size()
                + ", provider issues " + discoveryProviderFailures.size() + ".");
            if (githubAuthFailed) {
                Log.warn("GitHub API disabled for this run because authentication failed with HTTP 401. Check githubToken/env and avoid using an expired or revoked token.");
            } else if (githubRateLimited) {
                Log.warn("GitHub API discovery was limited this run; non-GitHub discovery continued and unresolved GitHub work will retry later.");
            }
            printSummaryItems("Found", discoveryFound, LogLevel.INFO);
            printSummaryItems("Deferred", discoveryDeferred, LogLevel.WARN);
            printSummaryItems("Unresolved", discoveryUnresolved, LogLevel.WARN);
            printSummaryItems("Provider issues", discoveryProviderFailures, LogLevel.WARN);
        }

        private void printSummaryItems(String label, Set<String> items, LogLevel level) {
            if (items.isEmpty()) {
                return;
            }
            List<String> sorted = new ArrayList<>(items);
            sorted.sort(String.CASE_INSENSITIVE_ORDER);
            String text = label + ": " + String.join(", ", sorted.stream().limit(18).toList())
                + (sorted.size() > 18 ? " +" + (sorted.size() - 18) + " more" : "");
            if (level == LogLevel.WARN) {
                Log.warn(text);
            } else {
                Log.info(text);
            }
        }

        private enum LogLevel {
            INFO,
            WARN
        }

        private List<DiscoveryCandidate> discoverSourceCandidates(TargetConfig target) {
            discardPendingSourceProofs(target);
            sourceProofCaptureDepth++;
            try {
                List<DiscoveryCandidate> candidates = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                addKnownOfficialSourceCandidates(target, candidates, seen);
                addConfiguredFallbackCandidates(target, candidates, seen);
                if (target.detectedWebsite != null && !target.detectedWebsite.isBlank()) {
                    addWebsiteCandidate(target, target.detectedWebsite, candidates, seen, 0);
                }
                List<String> priority = normalizedDiscoveryPriority();
                for (int i = 0; i < priority.size(); i++) {
                    String type = lower(priority.get(i));
                    if ((type.equals("github") || type.equals("github-release")) && githubAuthFailed) {
                        githubRateLimited = true;
                        addCandidates(discoverTargetedGithubSources(target, i, false), candidates, seen);
                        if (!config.githubRateLimit.skipLogged) {
                            Log.warn("GitHub API discovery is disabled for this run because authentication failed; continuing with raw descriptor probes and non-GitHub sources.");
                            config.githubRateLimit.skipLogged = true;
                        }
                        continue;
                    }
                    if ((type.equals("github") || type.equals("github-release")) && config.githubRateLimit.isPaused()) {
                        githubRateLimited = true;
                        addCandidates(discoverTargetedGithubSources(target, i, false), candidates, seen);
                        if (!config.githubRateLimit.skipLogged) {
                            Log.warn("GitHub discovery is paused until " + config.githubRateLimit.resetText()
                                + " because the API rate limit was exhausted; continuing with raw descriptor probes and non-GitHub sources.");
                            config.githubRateLimit.skipLogged = true;
                        }
                        continue;
                    }
                    try {
                        switch (type) {
                            case "github":
                            case "github-release":
                                addCandidates(discoverGithubSources(target, i), candidates, seen);
                                break;
                            case "hangar":
                                addCandidates(discoverHangarSources(target, i), candidates, seen);
                                break;
                            case "modrinth":
                                addCandidates(discoverModrinthSources(target, i), candidates, seen);
                                break;
                            case "spigot":
                            case "spiget":
                            case "jenkins":
                                Log.warn("Ignoring manual-only discovery source priority entry: " + type);
                                break;
                            default:
                                Log.warn("Unknown discovery source priority entry: " + type);
                                break;
                        }
                    } catch (Exception ex) {
                        String message = "Discovery provider " + type + " failed for " + target.displayName() + ": " + ex.getMessage();
                        discoveryProviderFailures.add(target.displayName() + " (" + type + ")");
                        Log.warn(message);
                    }
                }
                candidates.sort(Comparator
                    .comparingInt((DiscoveryCandidate c) -> c.score).reversed()
                    .thenComparingInt((DiscoveryCandidate c) -> discoverySortPriority(c))
                    .thenComparing(c -> c.type));
                pathDebug(target, "discovery.candidates", "ranked " + candidates.size() + " candidate(s)");
                for (int i = 0; i < Math.min(5, candidates.size()); i++) {
                    DiscoveryCandidate candidate = candidates.get(i);
                    pathDebug(target, "discovery.candidate#" + (i + 1),
                        candidate.type + " score=" + candidate.score
                            + "; priority=" + candidate.priority
                            + "; source=" + candidate.source
                            + "; latest=" + firstNonBlank(candidate.latestVersion, "unknown")
                            + "; reason=" + candidate.reason);
                }
                return candidates;
            } finally {
                sourceProofCaptureDepth--;
                if (!capturingSourceProofs()) {
                    flushPendingRejectedSourceProofs();
                }
            }
        }

        private void addConfiguredFallbackCandidates(TargetConfig target, List<DiscoveryCandidate> candidates, Set<String> seen) {
            int priority = -6;
            for (String fallback : target.fallbackSources) {
                if (fallback == null || fallback.isBlank()) {
                    continue;
                }
                String type = lower(firstNonBlank(detectType(fallback, target), "auto"));
                if (isManualOnlySourceType(type)) {
                    continue;
                }
                String projectHint = "";
                GithubRepo repo = repoFromGithubText(fallback);
                if (repo != null) {
                    projectHint = repo.owner + "/" + repo.name;
                }
                try {
                    if (type.equals("modrinth") || type.equals("hangar")) {
                        TargetConfig candidateTarget = target.copyWithSource(fallback);
                        ResolvedDownload download = type.equals("modrinth")
                            ? new ModrinthResolver(config, client).resolve(candidateTarget)
                            : new HangarResolver(config, client).resolve(candidateTarget);
                        addCandidates(List.of(candidateFromResolved(
                            target,
                            type,
                            fallback,
                            firstNonBlank(candidateTarget.project, projectHint),
                            latestFromDownload(download),
                            download.label,
                            priority,
                            "configured fallback source",
                            download
                        )), candidates, seen);
                    } else {
                        addCandidates(List.of(candidateFromResolved(
                            target,
                            type,
                            fallback,
                            projectHint,
                            "",
                            firstNonBlank(projectHint, fallback),
                            priority,
                            "configured fallback source"
                        )), candidates, seen);
                    }
                } catch (Exception ex) {
                    Log.info("Could not verify fallback source for " + target.displayName()
                        + " from " + fallback + ": " + ex.getMessage());
                }
                priority++;
            }
        }

        private List<String> normalizedDiscoveryPriority() {
            List<String> raw = config.discovery.sourcePriority.isEmpty()
                ? List.of("github-release", "hangar", "modrinth")
                : config.discovery.sourcePriority;
            List<String> normalized = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String item : raw) {
                String type = lower(item);
                if (type.equals("spiget")) {
                    type = "spigot";
                }
                if (type.equals("spigot") || type.equals("jenkins")) {
                    continue;
                }
                if (seen.add(type)) {
                    normalized.add(type);
                }
            }
            return normalized;
        }

        private int discoverySortPriority(DiscoveryCandidate candidate) {
            return candidate.priority;
        }

        private boolean isSpigotType(String type) {
            String value = lower(type);
            return value.equals("spigot") || value.equals("spiget");
        }

        private boolean isManualOnlySourceType(String type) {
            String value = lower(type);
            return value.equals("jenkins") || isSpigotType(value);
        }

        private void addKnownOfficialSourceCandidates(TargetConfig target, List<DiscoveryCandidate> candidates, Set<String> seen) {
            for (SourceHint hint : config.sourceHints) {
                if (!hint.matches(target)) {
                    continue;
                }
                String hintType = lower(firstNonBlank(hint.type, detectType(hint.source, target)));
                if (isManualOnlySourceType(hintType)) {
                    Log.info("Ignoring source hint for " + target.displayName() + " from " + hint.source
                        + " because " + hintType + " sources are manual-only.");
                    continue;
                }
                DiscoveryCandidate candidate = candidateFromSourceHint(target, hint, hintType);
                addCandidates(List.of(candidate), candidates, seen);
                if (candidate.reason.contains("source descriptor matches installed plugin")
                    && isGithubLikeSource(hintType, hint.source, hint.project)) {
                    GithubRepo repo = githubRepoFromSourceParts(hint.source, hint.project);
                    if (repo != null) {
                        addCandidates(discoverGithubForkNeighborhood(target, repo, hint.priority, true), candidates, seen);
                    }
                }
                if (!hint.githubRepo.isBlank() && (target.githubRepo == null || target.githubRepo.isBlank()
                    || sourceOwnerSignal(target.detectedAuthors, "github-release", target.githubRepo, target.githubRepo, target.githubRepo).conflict)) {
                    target.githubRepo = hint.githubRepo;
                }
            }
        }

        private DiscoveryCandidate candidateFromSourceHint(TargetConfig target, SourceHint hint, String hintType) {
            String label = firstNonBlank(hint.label, hint.source);
            String reason = firstNonBlank(hint.reason, "configured source hint");
            if (hintType.equals("modrinth") || hintType.equals("hangar")) {
                try {
                    TargetConfig candidateTarget = target.copyWithSource(hint.source);
                    candidateTarget.project = firstNonBlank(hint.project, candidateTarget.project);
                    if (candidateTarget.loader == null || candidateTarget.loader.isBlank()) {
                        candidateTarget.loader = target.platform;
                    }
                    ResolvedDownload download = hintType.equals("modrinth")
                        ? new ModrinthResolver(config, client).resolve(candidateTarget)
                        : new HangarResolver(config, client).resolve(candidateTarget);
                    DiscoveryCandidate candidate = candidateFromResolved(target, hintType, hint.source, hint.project,
                        latestFromDownload(download), label, hint.priority, reason, download);
                    if (candidate.reason.contains("source descriptor matches installed plugin")) {
                        return withScore(candidate, Math.max(candidate.score, hint.score));
                    }
                    return candidate;
                } catch (Exception ex) {
                    Log.info("Could not verify source hint for " + target.displayName()
                        + " from " + hint.source + ": " + ex.getMessage());
                }
            }
            DiscoveryCandidate candidate = candidateFromResolved(
                target,
                firstNonBlank(hint.type, "auto"),
                hint.source,
                hint.project,
                "",
                label,
                hint.priority,
                reason
            );
            if (candidate.reason.contains("source descriptor matches installed plugin")) {
                return withScore(candidate, Math.max(candidate.score, hint.score));
            }
            return candidate;
        }

        private DiscoveryCandidate withScore(DiscoveryCandidate candidate, int score) {
            return new DiscoveryCandidate(candidate.type, candidate.source, candidate.projectHint, candidate.latestVersion,
                candidate.label, candidate.reason, score, candidate.priority);
        }

        private void addCandidates(List<DiscoveryCandidate> source, List<DiscoveryCandidate> candidates, Set<String> seen) {
            for (DiscoveryCandidate candidate : source) {
                String key = discoveryCandidateKey(candidate);
                if (seen.contains(key)) {
                    continue;
                }
                if (candidate.score < 50) {
                    Log.info("Ignoring weak/stale " + candidate.type + " candidate for " + candidate.label + " (" + candidate.reason + ")");
                    continue;
                }
                if (seen.add(key)) {
                    candidates.add(candidate);
                }
            }
        }

        private String discoveryCandidateKey(DiscoveryCandidate candidate) {
            return lower(candidate.type + "|" + canonicalDiscoverySource(candidate.source));
        }

        private String canonicalDiscoverySource(String source) {
            if (source == null || source.isBlank()) {
                return "";
            }
            try {
                URI uri = URI.create(source);
                if (lower(uri.getHost()).equals("github.com")) {
                    List<String> parts = pathParts(uri);
                    if (parts.size() >= 2) {
                        return "https://github.com/" + parts.get(0) + "/" + parts.get(1).replace(".git", "");
                    }
                }
            } catch (RuntimeException ignored) {
                // Keep the raw source below.
            }
            return source;
        }

        private void addWebsiteCandidate(TargetConfig target, String website, List<DiscoveryCandidate> candidates, Set<String> seen, int priority) {
            String lowerWebsite = lower(website);
            try {
                if (lowerWebsite.contains("github.com/")) {
                    GithubRepo repo = repoFromGithubUrl(website);
                    latestGithubCandidate(target, repo, priority, "plugin metadata website").ifPresent(candidate -> addCandidates(List.of(candidate), candidates, seen));
                } else if (lowerWebsite.contains("hangar.papermc.io/")) {
                    TargetConfig candidateTarget = target.copyWithSource(website);
                    ResolvedDownload download = new HangarResolver(config, client).resolve(candidateTarget);
                    addCandidates(List.of(candidateFromResolved(target, "hangar", website, "", latestFromDownload(download), download.label, priority, "plugin metadata website", download)), candidates, seen);
                } else if (lowerWebsite.contains("modrinth.com/")) {
                    TargetConfig candidateTarget = target.copyWithSource(website);
                    ResolvedDownload download = new ModrinthResolver(config, client).resolve(candidateTarget);
                    addCandidates(List.of(candidateFromResolved(target, "modrinth", website, "", latestFromDownload(download), download.label, priority, "plugin metadata website", download)), candidates, seen);
                } else if (lowerWebsite.contains("geysermc.org")) {
                    String project = lower(String.join(" ", firstNonBlank(target.name, ""), firstNonBlank(target.installAs, ""))).contains("floodgate")
                        ? "floodgate"
                        : "geyser";
                    String source = "https://geysermc.org/download/?project=" + project;
                    addCandidates(List.of(new DiscoveryCandidate(
                        "geysermc",
                        source,
                        project,
                        "",
                        "GeyserMC " + project + " official download",
                        "plugin metadata website",
                        110,
                        priority
                    )), candidates, seen);
                } else if (lowerWebsite.contains("spigotmc.org/resources") || lowerWebsite.contains("api.spiget.org/")) {
                    Log.info("Ignoring Spigot metadata website for " + target.displayName()
                        + " because Spigot/Spiget sources are manual-only and never discovered: " + website);
                } else if (lowerWebsite.contains("/job/")) {
                    Log.info("Ignoring Jenkins metadata website for " + target.displayName()
                        + " because Jenkins sources are manual-only and never discovered: " + website);
                }
            } catch (Exception ex) {
                Log.warn("Metadata website did not resolve for " + target.displayName() + ": " + website + " (" + ex.getMessage() + ")");
            }
        }

        private List<DiscoveryCandidate> discoverGithubSources(TargetConfig target, int priority) throws Exception {
            List<DiscoveryCandidate> candidates = new ArrayList<>();
            pathDebug(target, "provider.github.start", "priority=" + priority
                + "; githubRepo=" + firstNonBlank(target.githubRepo, "<blank>")
                + "; searchTerms=" + String.join(", ", discoverySearchTerms(target)));
            if (target.githubRepo != null && !target.githubRepo.isBlank()) {
                pathDebug(target, "provider.github.configuredRepo", "probing configured githubRepo=" + target.githubRepo);
                latestGithubCandidate(target, repoFromGithubValue(target.githubRepo), priority, "configured githubRepo").ifPresent(candidates::add);
                boolean refreshNeedsForkSearch = lockState.activeSourceProof(target)
                    .map(proof -> shouldRefreshRememberedSourceProof(target, proof))
                    .orElse(false);
                if (!candidates.isEmpty() && !refreshNeedsForkSearch) {
                    pathDebug(target, "provider.github.done", "configured githubRepo produced " + candidates.size()
                        + " candidate(s); skipping targeted/broad search");
                    return candidates;
                }
                if (refreshNeedsForkSearch) {
                    pathDebug(target, "provider.github.continue", "configured repo found candidates, but stale Folia proof requires fork/source search");
                }
            }
            candidates.addAll(discoverTargetedGithubSources(target, priority, true));
            if (!candidates.isEmpty()) {
                pathDebug(target, "provider.github.done", "targeted GitHub discovery produced " + candidates.size()
                    + " candidate(s); skipping broad search");
                return candidates;
            }
            if (githubRateLimited || config.githubRateLimit.isPaused()) {
                pathDebug(target, "provider.github.defer", "GitHub broad search skipped because rate limit/auth pause is active");
                return candidates;
            }
            for (String term : discoverySearchTerms(target)) {
                if (githubRateLimited || config.githubRateLimit.isPaused()) {
                    pathDebug(target, "provider.github.defer", "GitHub broad search stopped during term=" + term
                        + " because rate limit/auth pause became active");
                    return candidates;
                }
                pathDebug(target, "provider.github.search", "broad repository search term=" + term);
                URI uri = URI.create("https://api.github.com/search/repositories?q="
                    + urlEncode(term + " minecraft plugin")
                    + "&per_page=8");
                Object json = getJson(uri, "GitHub repository search", target);
                Object itemsObj = asMap(json).get("items");
                if (!(itemsObj instanceof List<?> items)) {
                    continue;
                }
                for (Object item : items) {
                    Map<String, Object> repoMap = asMap(item);
                    if (Boolean.TRUE.equals(repoMap.get("archived")) || Boolean.TRUE.equals(repoMap.get("disabled"))) {
                        pathDebug(target, "provider.github.archived.skip", "broad search skipped archived/disabled repo "
                            + firstNonBlank(stringValue(repoMap.get("full_name")), stringValue(repoMap.get("name")), "unknown"));
                        continue;
                    }
                    String fullName = stringValue(repoMap.get("full_name"));
                    if (!fullName.contains("/")) {
                        continue;
                    }
                    String name = stringValue(repoMap.get("name"));
                    int match = nameMatchScore(target, name, fullName);
                    GithubRepo repo = repoFromGithubValue(fullName);
                    SourceDescriptorEvidence descriptorEvidence = SourceDescriptorEvidence.UNKNOWN;
                    if (match < 35) {
                        descriptorEvidence = githubSourceDescriptorEvidence(
                            target,
                            "github-source",
                            "https://github.com/" + repo.owner + "/" + repo.name,
                            repo.owner + "/" + repo.name
                        );
                    }
                    if (match < 35 && descriptorEvidence != SourceDescriptorEvidence.MATCH) {
                        continue;
                    }
                    String reason = match < 35
                        ? "GitHub search descriptor match: " + name
                        : "GitHub search match: " + name;
                    latestGithubCandidate(target, repo, priority, reason).ifPresent(candidates::add);
                }
            }
            pathDebug(target, "provider.github.done", "broad GitHub search produced " + candidates.size() + " candidate(s)");
            return candidates;
        }

        private List<DiscoveryCandidate> discoverTargetedGithubSources(TargetConfig target, int priority, boolean allowReleaseLookup) {
            List<DiscoveryCandidate> candidates = new ArrayList<>();
            pathDebug(target, "provider.github.targeted.start", "allowReleaseLookup=" + allowReleaseLookup
                + "; likelyRepos=" + likelyGithubRepos(target).stream()
                    .map(repo -> repo.owner + "/" + repo.name)
                    .collect(Collectors.joining(", ")));
            if (shouldProbePreferredGithubForkSources(target)) {
                pathDebug(target, "provider.github.preferredOwners", "probing preferred owners="
                    + String.join(", ", config.discovery.preferredOwners));
                candidates.addAll(discoverPreferredGithubForkSources(target, priority, allowReleaseLookup));
                if (!candidates.isEmpty() || isGithubPluginBudgetLimited(target)) {
                    pathDebug(target, "provider.github.targeted.done", "preferred-owner probes produced "
                        + candidates.size() + " candidate(s); budgetLimited=" + isGithubPluginBudgetLimited(target));
                    return candidates;
                }
            }
            Set<String> forkNeighborhoods = new HashSet<>();
            for (GithubRepo repo : likelyGithubRepos(target)) {
                if (isGithubPluginBudgetLimited(target)) {
                    break;
                }
                String repoKey = lower(repo.owner + "/" + repo.name);
                if (forkNeighborhoods.add(repoKey)) {
                    pathDebug(target, "provider.github.forks", "probing fork neighborhood for " + repo.owner + "/" + repo.name);
                    candidates.addAll(discoverGithubForkNeighborhood(target, repo, priority, allowReleaseLookup));
                }
                List<GithubRepo> matchedRepos = matchingGithubRepoVariants(target, repo, allowReleaseLookup);
                pathDebug(target, "provider.github.variants", repo.owner + "/" + repo.name + " produced "
                    + matchedRepos.size() + " descriptor-matched variant(s)");
                for (GithubRepo matchedRepo : matchedRepos) {
                    if (isGithubPluginBudgetLimited(target)) {
                        break;
                    }
                    String source = githubSourceUrl(matchedRepo);
                    String repoName = matchedRepo.owner + "/" + matchedRepo.name;
                    String matchedKey = lower(matchedRepo.owner + "/" + matchedRepo.name);
                    if (forkNeighborhoods.add(matchedKey)) {
                        pathDebug(target, "provider.github.forks", "probing fork neighborhood for matched repo "
                            + matchedRepo.owner + "/" + matchedRepo.name);
                        candidates.addAll(discoverGithubForkNeighborhood(target, matchedRepo, priority, allowReleaseLookup));
                    }
                    Optional<DiscoveryCandidate> latest = allowReleaseLookup
                        ? latestGithubCandidate(target, matchedRepo, priority, "targeted GitHub repo descriptor match")
                        : Optional.empty();
                    if (latest.isPresent()) {
                        candidates.add(latest.get());
                    } else if (sourceBuildAllowedForRepo(repoName, SourceDescriptorEvidence.MATCH)) {
                        candidates.add(candidateFromResolved(
                            target,
                            "github-source",
                            source,
                            repoName,
                            "",
                            repoName,
                            sourceBuildFallbackPriority(priority),
                            allowReleaseLookup
                                ? "targeted GitHub repo descriptor match; no hosted release jar resolved"
                                : "targeted GitHub repo descriptor match; GitHub API paused so using source build fallback"
                        ));
                    }
                }
            }
            pathDebug(target, "provider.github.targeted.done", "targeted probes produced " + candidates.size() + " candidate(s)");
            return candidates;
        }

        private boolean shouldProbePreferredGithubForkSources(TargetConfig target) {
            if (target == null || target.server || config.discovery.preferredOwners.isEmpty()) {
                return false;
            }
            if (isManualConfiguredSource(target)) {
                return false;
            }
            String origin = lower(firstNonBlank(target.sourceOrigin, ""));
            if (needsDiscoveredSource(target) || origin.equals(SOURCE_ORIGIN_UNRESOLVED)) {
                return true;
            }
            PluginJarInfo installed = installedPluginInfo(target);
            if (installed != null && Boolean.TRUE.equals(installed.foliaSupported)) {
                return true;
            }
            return installedPluginLooksFoliaCustomOrSnapshot(target, installed);
        }

        private boolean installedPluginLooksFoliaCustomOrSnapshot(TargetConfig target, PluginJarInfo installed) {
            String text = lower(String.join(" ",
                installed == null ? "" : firstNonBlank(installed.version, ""),
                installed == null ? "" : firstNonBlank(installed.name, installed.id, ""),
                firstNonBlank(target.detectedVersion, ""),
                firstNonBlank(target.name, ""),
                firstNonBlank(target.detectedPluginId, ""),
                firstNonBlank(target.installAs, "")
            ));
            return text.contains("folia")
                || text.contains("snapshot")
                || text.matches(".*\\d+-[0-9a-f]{6,}.*")
                || text.matches(".*\\+[0-9a-f]{6,}.*");
        }

        private List<DiscoveryCandidate> discoverPreferredGithubForkSources(TargetConfig target, int priority, boolean allowReleaseLookup) {
            List<DiscoveryCandidate> candidates = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (GithubRepo repo : preferredOwnerRepoCandidates(target)) {
                if (isGithubPluginBudgetLimited(target)) {
                    Log.warn("Preferred fork discovery deferred for " + target.displayName()
                        + " because GitHub budget was limited before preferred-owner probes completed.");
                    break;
                }
                String repoName = repo.owner + "/" + repo.name;
                testDiscoveryTrace.add("preferred:" + repoName);
                Optional<PluginJarInfo> descriptor = preferredGithubRawDescriptorMatch(target, repo);
                if (descriptor.isEmpty()) {
                    continue;
                }
                if (!preferredGithubForkDescriptorAcceptable(target, descriptor.get())) {
                    continue;
                }
                if (!githubRepoSelectableForPreferredFork(target, repo, allowReleaseLookup)) {
                    continue;
                }
                String source = githubSourceUrl(repo);
                if (!seen.add(lower(source))) {
                    continue;
                }
                rememberSourceProof(target, source, "github-source", repo, descriptor.get(), "preferred-owner-raw-descriptor-match");
                candidates.add(candidateFromResolved(
                    target,
                    "github-source",
                    source,
                    repoName,
                    firstNonBlank(descriptor.get().version, ""),
                    repoName,
                    Math.max(0, priority - 1),
                    "preferred-owner GitHub repo descriptor match"
                ));
            }
            return candidates;
        }

        private List<GithubRepo> preferredOwnerRepoCandidates(TargetConfig target) {
            Map<String, GithubRepo> repos = new LinkedHashMap<>();
            for (String owner : config.discovery.preferredOwners) {
                String cleanOwner = cleanGithubPathPart(owner);
                if (cleanOwner.isBlank()) {
                    continue;
                }
                for (String repoName : preferredGithubRepoNames(target)) {
                    addLikelyGithubRepo(repos, new GithubRepo(cleanOwner, repoName));
                }
            }
            return new ArrayList<>(repos.values());
        }

        private List<String> preferredGithubRepoNames(TargetConfig target) {
            List<String> bases = new ArrayList<>();
            for (String value : Arrays.asList(
                target.detectedPluginId,
                target.name,
                stripJarName(target.installAs),
                cleanSearchTerm(target.detectedPluginId),
                cleanSearchTerm(target.name),
                cleanSearchTerm(stripJarName(target.installAs))
            )) {
                addPreferredGithubRepoRawBase(bases, value);
                addPreferredGithubRepoBase(bases, value);
                addAddonBaseRepoName(bases, value);
            }
            List<String> names = new ArrayList<>();
            for (String base : bases) {
                addPreferredGithubRepoName(names, base);
                addPreferredGithubRepoName(names, base + "-Folia");
                addPreferredGithubRepoName(names, base + "Folia");
                String noSeparators = base.replaceAll("[_\\-\\s]+", "");
                if (!noSeparators.equals(base)) {
                    addPreferredGithubRepoName(names, noSeparators);
                    addPreferredGithubRepoName(names, noSeparators + "-Folia");
                    addPreferredGithubRepoName(names, noSeparators + "Folia");
                }
            }
            return names.stream().limit(36).toList();
        }

        private void addPreferredGithubRepoBase(List<String> names, String value) {
            String cleaned = cleanGithubPathPart(cleanSearchTerm(value));
            if (cleaned.isBlank() || normalizeIdentity(cleaned).length() < 3) {
                return;
            }
            addPreferredGithubRepoName(names, cleaned);
        }

        private void addPreferredGithubRepoRawBase(List<String> names, String value) {
            String cleaned = cleanGithubPathPart(value);
            if (cleaned.isBlank() || normalizeIdentity(cleaned).length() < 3) {
                return;
            }
            addPreferredGithubRepoName(names, cleaned);
        }

        private void addPreferredGithubRepoName(List<String> names, String value) {
            String cleaned = cleanGithubPathPart(value);
            if (cleaned.isBlank()) {
                return;
            }
            String normalized = normalizeIdentity(cleaned);
            if (normalized.length() < 3
                || normalized.equals("plugin")
                || normalized.equals("minecraft")
                || normalized.equals("bukkit")
                || normalized.equals("paper")
                || normalized.equals("spigot")
                || normalized.equals("folia")) {
                return;
            }
            if (names.stream().noneMatch(existing -> existing.equalsIgnoreCase(cleaned))) {
                names.add(cleaned);
            }
        }

        private Optional<PluginJarInfo> preferredGithubRawDescriptorMatch(TargetConfig target, GithubRepo repo) {
            PluginJarInfo installed = installedPluginInfo(target);
            for (String branch : likelyGithubBranches(target, repo)) {
                GithubRepo branchRepo = branch.isBlank() ? new GithubRepo(repo.owner, repo.name) : new GithubRepo(repo.owner, repo.name, branch);
                for (String path : likelyGithubDescriptorPaths(target, repo)) {
                    try {
                        Optional<String> text = fetchGithubRawOptional(branchRepo, path);
                        if (text.isEmpty()) {
                            continue;
                        }
                        PluginJarInfo info = parsePluginDescriptor(path, text.get());
                        if (info.hasDescriptor && preferredGithubForkDescriptorMatches(installed, target, info)) {
                            return Optional.of(info);
                        }
                    } catch (Exception ignored) {
                        // Preferred-owner probes are cheap hints; keep trying bounded raw descriptor paths.
                    }
                }
            }
            return Optional.empty();
        }

        private boolean preferredGithubForkDescriptorMatches(PluginJarInfo installed, TargetConfig target, PluginJarInfo candidate) {
            if (candidate == null || !candidate.hasDescriptor) {
                return false;
            }
            Set<String> installedNames = new HashSet<>();
            if (installed != null) {
                installedNames.addAll(pluginIdentityValues(installed).stream().map(AutoUpdater::normalizeIdentity).toList());
            }
            installedNames.add(normalizeIdentity(firstNonBlank(target.detectedPluginId, "")));
            installedNames.add(normalizeIdentity(firstNonBlank(target.name, "")));
            installedNames.add(normalizeIdentity(jarIdentityHint(target.installAs)));
            installedNames.remove("");

            Set<String> candidateNames = pluginIdentityValues(candidate).stream()
                .map(AutoUpdater::normalizeIdentity)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toSet());
            if (Collections.disjoint(installedNames, candidateNames)) {
                return false;
            }

            String installedMain = installed == null ? "" : firstNonBlank(installed.mainClass, target.detectedMainClass);
            return installedMain.isBlank()
                || candidate.mainClass.isBlank()
                || installedMain.equalsIgnoreCase(candidate.mainClass)
                || packageSimilarityScore(installedMain, candidate.mainClass) > 0;
        }

        private boolean preferredGithubForkDescriptorAcceptable(TargetConfig target, PluginJarInfo candidate) {
            if (!preferredGithubForkDescriptorMatches(installedPluginInfo(target), target, candidate)) {
                return false;
            }
            if (inferPaperMcProject(config.server).equals("folia") && !Boolean.TRUE.equals(candidate.foliaSupported)) {
                return false;
            }
            String localVersion = firstNonBlank(target.detectedVersion, installedPluginInfo(target) == null ? "" : installedPluginInfo(target).version);
            return localVersion.isBlank()
                || candidate.version.isBlank()
                || comparePluginVersions(candidate.version, localVersion) != VersionOrder.OLDER;
        }

        private boolean githubRepoSelectableForPreferredFork(TargetConfig target, GithubRepo repo, boolean allowApiInspection) {
            if (!allowApiInspection) {
                Log.warn("Preferred fork discovery deferred for " + target.displayName()
                    + " because GitHub API inspection is unavailable before checking " + repo.owner + "/" + repo.name + ".");
                rememberDiscoveryDeferred(target, "preferred fork discovery deferred by GitHub budget", discoveryRetryAfter());
                return false;
            }
            try {
                URI uri = URI.create("https://api.github.com/repos/" + urlEncode(repo.owner) + "/" + urlEncode(repo.name));
                Object json = getJson(uri, "GitHub preferred fork repo metadata", target);
                Map<String, Object> root = asMap(json);
                boolean archived = Boolean.TRUE.equals(root.get("archived"));
                boolean disabled = Boolean.TRUE.equals(root.get("disabled"));
                if (archived || disabled) {
                    pathDebug(target, "provider.github.archived.skip", "preferred-owner repo skipped "
                        + repo.owner + "/" + repo.name + "; archived=" + archived + "; disabled=" + disabled);
                    return false;
                }
                pathDebug(target, "provider.github.preferred.metadata", repo.owner + "/" + repo.name + " is selectable");
                return true;
            } catch (Exception ex) {
                if (isGithubPluginBudgetLimited(target) || githubRateLimited || config.githubRateLimit.isPaused() || githubAuthFailed) {
                    Log.warn("Preferred fork discovery deferred for " + target.displayName()
                        + " because GitHub budget was limited before checking " + repo.owner + "/" + repo.name + ".");
                    rememberDiscoveryDeferred(target, "preferred fork discovery deferred by GitHub budget", discoveryRetryAfter());
                } else if (!isExpectedMissingGithubProbe(ex)) {
                    Log.info("Preferred fork metadata check failed for " + repo.owner + "/" + repo.name
                        + ": " + ex.getMessage());
                }
                pathDebug(target, "provider.github.preferred.metadata.failed", repo.owner + "/" + repo.name
                    + " -> " + ex.getMessage());
                return false;
            }
        }

        private List<DiscoveryCandidate> discoverGithubForkNeighborhood(TargetConfig target, GithubRepo upstreamRepo,
                                                                        int priority, boolean allowApiInspection) {
            testDiscoveryTrace.add("forks:" + upstreamRepo.owner + "/" + upstreamRepo.name);
            if (!allowApiInspection || githubAuthFailed || githubRateLimited || config.githubRateLimit.isPaused()
                || isGithubPluginBudgetLimited(target)) {
                pathDebug(target, "provider.github.forks.skip", upstreamRepo.owner + "/" + upstreamRepo.name
                    + "; allowApiInspection=" + allowApiInspection
                    + "; authFailed=" + githubAuthFailed
                    + "; rateLimited=" + githubRateLimited
                    + "; paused=" + config.githubRateLimit.isPaused()
                    + "; budgetLimited=" + isGithubPluginBudgetLimited(target));
                return Collections.emptyList();
            }
            if (!shouldSearchGithubForkNeighborhood(target)) {
                pathDebug(target, "provider.github.forks.skip", upstreamRepo.owner + "/" + upstreamRepo.name
                    + "; installed/plugin identity does not suggest Folia fork neighborhood search");
                return Collections.emptyList();
            }
            List<DiscoveryCandidate> candidates = new ArrayList<>();
            try {
                URI uri = URI.create("https://api.github.com/repos/" + urlEncode(upstreamRepo.owner) + "/" + urlEncode(upstreamRepo.name)
                    + "/forks?sort=newest&per_page=100");
                Object json = getJson(uri, "GitHub fork neighborhood", target);
                if (!(json instanceof List<?> forks)) {
                    return candidates;
                }
                for (Object item : forks) {
                    Map<String, Object> fork = asMap(item);
                    if (Boolean.TRUE.equals(fork.get("archived")) || Boolean.TRUE.equals(fork.get("disabled"))) {
                        pathDebug(target, "provider.github.archived.skip", "fork search skipped archived/disabled repo "
                            + firstNonBlank(stringValue(fork.get("full_name")), stringValue(fork.get("name")), "unknown"));
                        continue;
                    }
                    String fullName = stringValue(fork.get("full_name"));
                    GithubRepo forkRepo = repoFromGithubValue(fullName);
                    if (forkRepo == null || !looksLikeUsefulForkCandidate(target, forkRepo, fork)) {
                        pathDebug(target, "provider.github.fork.reject", firstNonBlank(fullName, "unknown")
                            + " did not pass useful-fork heuristic");
                        continue;
                    }
                    Optional<GithubRepo> matchedFork = githubCommonRawDescriptorMatch(target, forkRepo);
                    if (matchedFork.isEmpty()) {
                        matchedFork = githubArchiveDescriptorMatch(target, forkRepo);
                    }
                    if (matchedFork.isEmpty() && githubSourceDescriptorEvidence(
                        target,
                        "github-source",
                        githubSourceUrl(forkRepo),
                        forkRepo.owner + "/" + forkRepo.name
                    ) == SourceDescriptorEvidence.MATCH) {
                        matchedFork = Optional.of(forkRepo);
                    }
                    if (matchedFork.isEmpty()) {
                        pathDebug(target, "provider.github.fork.reject", fullName
                            + " had no matching raw/archive/source descriptor");
                        continue;
                    }
                    GithubRepo repo = matchedFork.get();
                    String repoName = repo.owner + "/" + repo.name;
                    candidates.add(candidateFromResolved(
                        target,
                        "github-source",
                        githubSourceUrl(repo),
                        repoName,
                        "",
                        repoName,
                        priority + 1,
                        "GitHub fork neighborhood descriptor match from " + upstreamRepo.owner + "/" + upstreamRepo.name
                    ));
                }
            } catch (Exception ex) {
                if (!githubRateLimited && !config.githubRateLimit.isPaused() && !isGithubPluginBudgetLimited(target)
                    && !isExpectedMissingGithubProbe(ex)) {
                    Log.info("GitHub fork neighborhood lookup failed for " + upstreamRepo.owner + "/" + upstreamRepo.name
                        + ": " + ex.getMessage());
                }
                pathDebug(target, "provider.github.forks.failed", upstreamRepo.owner + "/" + upstreamRepo.name
                    + " -> " + ex.getMessage());
            }
            pathDebug(target, "provider.github.forks.done", upstreamRepo.owner + "/" + upstreamRepo.name
                + " produced " + candidates.size() + " candidate(s)");
            return candidates;
        }

        private Optional<GithubRepo> githubArchiveDescriptorMatch(TargetConfig target, GithubRepo repo) {
            try {
                pathDebug(target, "provider.github.archive.inspect", "checking source archive descriptors for "
                    + repo.owner + "/" + repo.name);
                List<PluginJarInfo> descriptors = githubRepoArchiveDescriptors(repo);
                if (descriptors.isEmpty()) {
                    pathDebug(target, "provider.github.archive.reject", repo.owner + "/" + repo.name
                        + " archive had no plugin descriptors");
                    return Optional.empty();
                }
                PluginJarInfo installed = installedPluginInfo(target);
                for (PluginJarInfo descriptor : descriptors) {
                    if (pluginDescriptorMatchesTarget(installed, target, descriptor)) {
                        pathDebug(target, "provider.github.archive.match", repo.owner + "/" + repo.name
                            + " descriptor=" + firstNonBlank(descriptor.name, descriptor.id, "unknown")
                            + "; foliaSupported=" + descriptor.foliaSupported);
                        rememberSourceProof(target, githubSourceUrl(repo), "github-source", repo, descriptor, "archive-descriptor-match");
                        return Optional.of(repo);
                    }
                }
                pathDebug(target, "provider.github.archive.reject", repo.owner + "/" + repo.name
                    + " descriptors did not match installed plugin");
            } catch (Exception ex) {
                if (!isGithubPluginBudgetLimited(target) && !isExpectedMissingGithubProbe(ex)) {
                    Log.info("Could not inspect GitHub fork source archive for " + repo.owner + "/" + repo.name
                        + ": " + ex.getMessage());
                }
                pathDebug(target, "provider.github.archive.failed", repo.owner + "/" + repo.name
                    + " -> " + ex.getMessage());
            }
            return Optional.empty();
        }

        private boolean shouldSearchGithubForkNeighborhood(TargetConfig target) {
            PluginJarInfo installed = installedPluginInfo(target);
            if (installed != null && Boolean.TRUE.equals(installed.foliaSupported)) {
                return true;
            }
            String text = lower(String.join(" ",
                firstNonBlank(target.name, ""),
                firstNonBlank(target.detectedPluginId, ""),
                firstNonBlank(target.installAs, ""),
                firstNonBlank(target.detectedMainClass, "")
            ));
            return text.contains("folia");
        }

        private boolean looksLikeUsefulForkCandidate(TargetConfig target, GithubRepo repo, Map<String, Object> fork) {
            String text = lower(String.join(" ",
                repo.owner,
                repo.name,
                stringValue(fork.get("description")),
                stringValue(fork.get("full_name")),
                firstNonBlank(target.name, ""),
                firstNonBlank(target.detectedPluginId, "")
            ));
            if (text.contains("folia") || text.contains("inquisitor")) {
                return true;
            }
            PluginJarInfo installed = installedPluginInfo(target);
            if (installed != null && Boolean.TRUE.equals(installed.foliaSupported)) {
                return false;
            }
            String targetName = normalizeIdentity(firstNonBlank(target.detectedPluginId, target.name, stripJarName(target.installAs)));
            String repoName = normalizeIdentity(repo.name);
            return !targetName.isBlank() && (repoName.equals(targetName) || targetName.contains(repoName) || repoName.contains(targetName));
        }

        private List<GithubRepo> matchingGithubRepoVariants(TargetConfig target, GithubRepo repo, boolean allowApiInspection) {
            Optional<GithubRepo> rawMatch = githubCommonRawDescriptorMatch(target, repo);
            if (rawMatch.isPresent()) {
                return List.of(rawMatch.get());
            }
            if (allowApiInspection && githubSourceDescriptorEvidence(
                target,
                "github-source",
                githubSourceUrl(repo),
                repo.owner + "/" + repo.name
            ) == SourceDescriptorEvidence.MATCH) {
                return List.of(repo);
            }
            return Collections.emptyList();
        }

        private List<GithubRepo> likelyGithubRepos(TargetConfig target) {
            Map<String, GithubRepo> repos = new LinkedHashMap<>();
            addLikelyGithubRepo(repos, repoFromGithubText(firstNonBlank(target.detectedWebsite, "")));
            addLikelyGithubRepo(repos, repoFromGithubText(firstNonBlank(target.source, "")));
            addLikelyGithubRepo(repos, repoFromGithubText(firstNonBlank(target.githubRepo, "")));
            addLikelyProjectRepoAliases(target, repos);

            List<String> owners = likelyGithubOwners(target);
            List<String> repoNames = likelyGithubRepoNames(target);
            int budget = 12;
            for (String owner : owners) {
                for (String repoName : repoNames) {
                    if (repos.size() >= budget) {
                        return new ArrayList<>(repos.values());
                    }
                    addLikelyGithubRepo(repos, new GithubRepo(owner, repoName));
                }
            }
            return new ArrayList<>(repos.values());
        }

        private void addLikelyProjectRepoAliases(TargetConfig target, Map<String, GithubRepo> repos) {
            for (String value : Arrays.asList(target.detectedPluginId, target.name, stripJarName(target.installAs))) {
                String cleaned = cleanGithubPathPart(cleanSearchTerm(value));
                String normalized = normalizeIdentity(cleaned);
                if (normalized.equals("viaversion") || normalized.equals("viabackwards") || normalized.equals("viarewind")) {
                    addLikelyGithubRepo(repos, new GithubRepo("ViaVersion", cleaned));
                }
                if (normalized.equals("betterrtp") || normalized.equals("betterrtpaddons")) {
                    addLikelyGithubRepo(repos, new GithubRepo("RonanPlugins", "BetterRTP"));
                }
                if (normalized.equals("grimac")) {
                    addLikelyGithubRepo(repos, new GithubRepo("GrimAnticheat", "Grim"));
                }
            }
        }

        private void addLikelyGithubRepo(Map<String, GithubRepo> repos, GithubRepo repo) {
            if (repo == null || repo.owner.isBlank() || repo.name.isBlank()) {
                return;
            }
            String owner = cleanGithubPathPart(repo.owner);
            String name = cleanGithubPathPart(repo.name);
            String ref = cleanGithubRef(repo.ref);
            if (owner.isBlank() || name.isBlank()) {
                return;
            }
            String key = lower(owner + "/" + name + (ref.isBlank() ? "" : "@" + ref));
            repos.putIfAbsent(key, new GithubRepo(owner, name, ref));
        }

        private GithubRepo repoFromGithubText(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                if (value.contains("github.com/")) {
                    return repoFromGithubUrl(value);
                }
                if (value.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
                    return repoFromGithubValue(value);
                }
            } catch (RuntimeException ignored) {
                // Not a repo-like value.
            }
            return null;
        }

        private List<String> likelyGithubOwners(TargetConfig target) {
            List<String> owners = new ArrayList<>();
            addLikelyGithubOwner(owners, repoOwnerFromGithubText(firstNonBlank(target.detectedWebsite, "")));
            addLikelyGithubOwner(owners, repoOwnerFromGithubText(firstNonBlank(target.source, "")));
            addLikelyGithubOwner(owners, repoOwnerFromGithubText(firstNonBlank(target.githubRepo, "")));
            for (String token : Arrays.asList(target.detectedPluginId, target.name, stripJarName(target.installAs))) {
                for (String mapped : ownerAliasesForToken(token)) {
                    addLikelyGithubOwner(owners, mapped);
                }
            }
            for (String token : mainClassSearchTerms(target.detectedMainClass)) {
                for (String mapped : ownerAliasesForToken(token)) {
                    addLikelyGithubOwner(owners, mapped);
                }
            }
            for (String author : authorSearchTerms(target.detectedAuthors)) {
                for (String mapped : ownerAliasesForToken(author)) {
                    addLikelyGithubOwner(owners, mapped);
                }
                addLikelyGithubOwner(owners, author);
            }
            for (String token : authorOwnerTokens(target.detectedAuthors)) {
                addLikelyGithubOwner(owners, token);
            }
            for (String token : mainClassSearchTerms(target.detectedMainClass)) {
                addLikelyGithubOwner(owners, token);
            }
            for (String owner : provenGithubOwners()) {
                addLikelyGithubOwner(owners, owner);
            }
            return owners;
        }

        private String repoOwnerFromGithubText(String value) {
            GithubRepo repo = repoFromGithubText(value);
            return repo == null ? "" : repo.owner;
        }

        private void addLikelyGithubOwner(List<String> owners, String value) {
            String cleaned = cleanGithubPathPart(value);
            if (cleaned.isBlank()) {
                return;
            }
            String normalized = normalizeIdentity(cleaned);
            if (normalized.length() < 3
                || normalized.equals("plugin")
                || normalized.equals("plugins")
                || normalized.equals("minecraft")
                || normalized.equals("bukkit")
                || normalized.equals("paper")
                || normalized.equals("spigot")
                || normalized.equals("folia")) {
                return;
            }
            if (owners.stream().noneMatch(existing -> existing.equalsIgnoreCase(cleaned))) {
                owners.add(cleaned);
            }
        }

        private List<String> provenGithubOwners() {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (SourceProof proof : lockState.sourceProofs.values()) {
                String type = lower(proof.type);
                if (!type.equals("github-release") && !type.equals("github-source")) {
                    continue;
                }
                GithubRepo repo = repoFromGithubText(firstNonBlank(proof.repo, proof.source));
                if (repo == null || repo.owner.isBlank()) {
                    continue;
                }
                String owner = cleanGithubPathPart(repo.owner);
                if (owner.isBlank()) {
                    continue;
                }
                counts.put(owner, counts.getOrDefault(owner, 0) + 1);
            }
            return counts.entrySet().stream()
                .filter(entry -> entry.getValue() >= 2)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .limit(8)
                .toList();
        }

        private List<String> ownerAliasesForToken(String token) {
            String normalized = normalizeIdentity(token);
            List<String> aliases = new ArrayList<>();
            if (normalized.equals("superronancraft") || normalized.equals("betterrtp") || normalized.equals("betterrtpaddons")) {
                aliases.add("RonanPlugins");
            }
            if (normalized.equals("tcoded")) {
                aliases.add("TechnicallyCoded");
            }
            if (normalized.equals("grim") || normalized.equals("grimac")) {
                aliases.add("GrimAnticheat");
            }
            if (normalized.equals("viaversion")) {
                aliases.add("ViaVersion");
            }
            if (normalized.equals("viabackwards") || normalized.equals("viarewind")) {
                aliases.add("ViaVersion");
            }
            if (normalized.equals("hsgamer")) {
                aliases.add("HSGamer");
            }
            return aliases;
        }

        private List<String> likelyGithubRepoNames(TargetConfig target) {
            List<String> names = new ArrayList<>();
            for (String value : Arrays.asList(
                target.detectedPluginId,
                target.name,
                stripJarName(target.installAs)
            )) {
                addLikelyGithubRepoName(names, value);
                addLikelyGithubRepoName(names, cleanSearchTerm(value));
                addAddonBaseRepoName(names, value);
                addAddonBaseRepoName(names, cleanSearchTerm(value));
            }
            return names;
        }

        private void addAddonBaseRepoName(List<String> names, String value) {
            String cleaned = cleanGithubPathPart(cleanSearchTerm(value));
            String normalized = normalizeIdentity(cleaned);
            if (normalized.endsWith("addons") && cleaned.length() > "Addons".length()) {
                addLikelyGithubRepoName(names, cleaned.substring(0, cleaned.length() - "Addons".length()));
            } else if (normalized.endsWith("addon") && cleaned.length() > "Addon".length()) {
                addLikelyGithubRepoName(names, cleaned.substring(0, cleaned.length() - "Addon".length()));
            }
        }

        private void addLikelyGithubRepoName(List<String> names, String value) {
            String cleaned = cleanGithubPathPart(cleanSearchTerm(value));
            if (cleaned.isBlank()) {
                return;
            }
            String normalized = normalizeIdentity(cleaned);
            if (normalized.length() < 3
                || normalized.equals("plugin")
                || normalized.equals("minecraft")
                || normalized.equals("bukkit")
                || normalized.equals("paper")
                || normalized.equals("spigot")
                || normalized.equals("folia")) {
                return;
            }
            if (names.stream().noneMatch(existing -> existing.equalsIgnoreCase(cleaned))) {
                names.add(cleaned);
            }
            String noSeparators = cleaned.replaceAll("[_\\-\\s]+", "");
            if (!noSeparators.equals(cleaned)
                && names.stream().noneMatch(existing -> existing.equalsIgnoreCase(noSeparators))) {
                names.add(noSeparators);
            }
        }

        private String cleanGithubPathPart(String value) {
            String cleaned = firstNonBlank(value, "").trim();
            if (cleaned.isBlank()) {
                return "";
            }
            cleaned = cleaned.replace('\\', '/');
            if (cleaned.contains("github.com/")) {
                try {
                    List<String> parts = pathParts(URI.create(cleaned));
                    if (!parts.isEmpty()) {
                        cleaned = parts.get(parts.size() - 1);
                    }
                } catch (RuntimeException ignored) {
                    // Fall back to generic cleanup below.
                }
            }
            int slash = cleaned.lastIndexOf('/');
            if (slash >= 0) {
                cleaned = cleaned.substring(slash + 1);
            }
            cleaned = cleaned.replace(".git", "");
            cleaned = cleaned.replaceAll("[^A-Za-z0-9_.-]+", "");
            return cleaned;
        }

        private Optional<DiscoveryCandidate> latestGithubCandidate(TargetConfig target, GithubRepo repo, int priority, String reason) {
            try {
                pathDebug(target, "provider.github.release.lookup", repo.owner + "/" + repo.name
                    + "; reason=" + reason);
                URI uri = URI.create("https://api.github.com/repos/" + urlEncode(repo.owner) + "/" + urlEncode(repo.name) + "/releases?per_page=10");
                Object json = getJson(uri, "GitHub releases", target);
                if (!(json instanceof List<?> releases)) {
                    pathDebug(target, "provider.github.release.reject", repo.owner + "/" + repo.name
                        + " response was not a release list");
                    return Optional.empty();
                }
                for (Object item : releases) {
                    Map<String, Object> release = asMap(item);
                    if (Boolean.TRUE.equals(release.get("draft"))) {
                        pathDebug(target, "provider.github.release.skip", repo.owner + "/" + repo.name
                            + " skipped draft release " + firstNonBlank(stringValue(release.get("tag_name")), stringValue(release.get("name")), "unknown"));
                        continue;
                    }
                    if (target.versionType == null || target.versionType.isBlank()) {
                        if (Boolean.TRUE.equals(release.get("prerelease"))) {
                            pathDebug(target, "provider.github.release.skip", repo.owner + "/" + repo.name
                                + " skipped prerelease " + firstNonBlank(stringValue(release.get("tag_name")), stringValue(release.get("name")), "unknown"));
                            continue;
                        }
                    }
                    Optional<Map<String, Object>> asset = githubJarAsset(release);
                    if (asset.isEmpty()) {
                        pathDebug(target, "provider.github.release.skip", repo.owner + "/" + repo.name
                            + " release had no usable jar asset "
                            + firstNonBlank(stringValue(release.get("tag_name")), stringValue(release.get("name")), "unknown"));
                        continue;
                    }
                    String version = firstNonBlank(stringValue(release.get("tag_name")), stringValue(release.get("name")));
                    String source = githubSourceUrl(repo);
                    String label = repo.owner + "/" + repo.name + " " + version + " " + stringValue(asset.get().get("name"));
                    String repoName = repo.owner + "/" + repo.name;
                    String assetName = stringValue(asset.get().get("name"));
                    SourceDescriptorEvidence descriptorEvidence = githubSourceDescriptorEvidence(target, "github-source", source, repoName);
                    boolean sourceBuildAllowed = sourceBuildAllowedForRepo(repoName, descriptorEvidence);
                    if (descriptorEvidence == SourceDescriptorEvidence.MATCH
                        && sourceBuildAllowed
                        && !releaseAssetNameMatchesTarget(target, assetName)
                        && !repoNameMatchesTarget(target, repo)) {
                        pathDebug(target, "provider.github.release.source-build", repoName
                            + " descriptor matches source tree, but release asset " + assetName + " looks like another module");
                        return Optional.of(candidateFromResolved(target, "github-source", source, repoName, "", repoName,
                            sourceBuildFallbackPriority(priority), reason + "; source tree contains this plugin but release jar appears to be another module"));
                    }
                    Instant publishedAt = parseInstantOrNull(firstNonBlank(
                        stringValue(release.get("published_at")),
                        stringValue(release.get("created_at"))
                    ));
                    if (config.buildFromSource.autoFallback()
                        && config.buildFromSource.preferHostedIfSameVersion
                        && sourceBuildAllowed
                        && publishedAt != null) {
                        Optional<Instant> latestCommit = latestGitHubCommitTime(repo, target, repoName);
                        if (latestCommit.isPresent() && latestCommit.get().isAfter(publishedAt.plus(Duration.ofMinutes(5)))) {
                            pathDebug(target, "provider.github.release.source-build", repoName
                                + " latest commit " + latestCommit.get() + " is newer than release " + publishedAt);
                            return Optional.of(candidateFromResolved(target, "github-source", source, repoName, "", repoName,
                                sourceBuildFallbackPriority(priority), reason + "; latest commit is newer than release jar"));
                        }
                    }
                    pathDebug(target, "provider.github.release.match", repoName + " release="
                        + firstNonBlank(version, "unknown") + "; asset=" + assetName);
                    return Optional.of(candidateFromResolved(target, "github-release", source, repo.owner + "/" + repo.name, version, label, priority, reason));
                }
            } catch (Exception ex) {
                pathDebug(target, "provider.github.release.failed", repo.owner + "/" + repo.name + " -> " + ex.getMessage());
                Log.warn("GitHub releases lookup failed for " + repo.owner + "/" + repo.name + ": " + ex.getMessage());
            }
            if (config.buildFromSource.allowsBuild()) {
                String source = githubSourceUrl(repo);
                String repoName = repo.owner + "/" + repo.name;
                if (sourceBuildAllowedForRepo(repoName, githubSourceDescriptorEvidence(target, "github-source", source, repoName))) {
                    pathDebug(target, "provider.github.source-build.fallback", repoName
                        + " has no release jar candidate, but trusted/descriptor source build is allowed");
                    return Optional.of(candidateFromResolved(target, "github-source", source, repoName, "", repoName,
                        sourceBuildFallbackPriority(priority), reason + "; no release jar found, trusted source build fallback"));
                }
            }
            pathDebug(target, "provider.github.release.reject", repo.owner + "/" + repo.name
                + " produced no release/source-build candidate");
            return Optional.empty();
        }

        private boolean sourceBuildAllowedForRepo(String repoName, SourceDescriptorEvidence descriptorEvidence) {
            return config.buildFromSource.allowsBuild()
                && (!config.buildFromSource.onlyTrusted
                    || isTrustedGithubRepo(repoName)
                    || descriptorEvidence == SourceDescriptorEvidence.MATCH);
        }

        private boolean releaseAssetNameMatchesTarget(TargetConfig target, String assetName) {
            String normalizedAsset = normalizeName(cleanSearchTerm(assetName));
            if (normalizedAsset.isBlank()) {
                return false;
            }
            for (String value : Arrays.asList(target.detectedPluginId, target.name, stripJarName(target.installAs))) {
                String normalized = normalizeName(cleanSearchTerm(value));
                if (!normalized.isBlank() && normalizedAsset.contains(normalized)) {
                    return true;
                }
            }
            return false;
        }

        private boolean repoNameMatchesTarget(TargetConfig target, GithubRepo repo) {
            String normalizedRepo = normalizeName(repo.name);
            if (normalizedRepo.isBlank()) {
                return false;
            }
            for (String value : Arrays.asList(target.detectedPluginId, target.name, stripJarName(target.installAs))) {
                String normalized = normalizeName(cleanSearchTerm(value));
                if (!normalized.isBlank() && normalizedRepo.equals(normalized)) {
                    return true;
                }
            }
            return false;
        }

        private int sourceBuildFallbackPriority(int priority) {
            return Math.max(priority + 6, 6);
        }

        private Optional<Map<String, Object>> githubJarAsset(Map<String, Object> release) {
            Object assetsObj = release.get("assets");
            if (!(assetsObj instanceof List<?> assets)) {
                return Optional.empty();
            }
            List<Map<String, Object>> jars = new ArrayList<>();
            for (Object item : assets) {
                Map<String, Object> asset = asMap(item);
                String name = lower(stringValue(asset.get("name")));
                if (!name.endsWith(".jar")) {
                    continue;
                }
                if (name.contains("sources") || name.contains("javadoc") || name.contains("-dev") || name.contains("-plain")) {
                    continue;
                }
                jars.add(asset);
            }
            return jars.isEmpty() ? Optional.empty() : Optional.of(jars.get(0));
        }

        private List<DiscoveryCandidate> discoverModrinthSources(TargetConfig target, int priority) throws Exception {
            List<DiscoveryCandidate> candidates = new ArrayList<>();
            Set<String> triedSlugs = new HashSet<>();
            pathDebug(target, "provider.modrinth.start", "priority=" + priority
                + "; exactSlugs=" + String.join(", ", exactModrinthSlugs(target))
                + "; searchTerms=" + String.join(", ", discoverySearchTerms(target)));
            for (String slug : exactModrinthSlugs(target)) {
                if (!triedSlugs.add(lower(slug))) {
                    continue;
                }
                TargetConfig candidateTarget = target.copyWithSource("https://modrinth.com/plugin/" + slug + "/versions");
                candidateTarget.project = slug;
                if (candidateTarget.loader == null || candidateTarget.loader.isBlank()) {
                    candidateTarget.loader = target.platform;
                }
                try {
                    ResolvedDownload download = new ModrinthResolver(config, client).resolve(candidateTarget);
                    String latest = latestFromDownload(download);
                    candidates.add(candidateFromResolved(target, "modrinth", candidateTarget.source, slug, latest, slug, priority,
                        "Modrinth exact slug probe: " + slug, download));
                } catch (Exception ignored) {
                    pathDebug(target, "provider.modrinth.exact.failed", "slug=" + slug);
                    // Exact slug probes are intentionally quiet; broad search below will report useful failures.
                }
            }
            for (String term : discoverySearchTerms(target)) {
                pathDebug(target, "provider.modrinth.search", "term=" + term);
                URI uri = URI.create("https://api.modrinth.com/v2/search?query="
                    + urlEncode(term)
                    + "&facets=" + urlEncode("[[\"project_type:plugin\",\"project_type:mod\"]]")
                    + "&index=relevance&limit=8");
                Object json = getJson(uri, "Modrinth search");
                Object hitsObj = asMap(json).get("hits");
                if (!(hitsObj instanceof List<?> hits)) {
                    continue;
                }
                for (Object item : hits) {
                    Map<String, Object> hit = asMap(item);
                    String slug = stringValue(hit.get("slug"));
                    String title = stringValue(hit.get("title"));
                    String projectId = stringValue(hit.get("project_id"));
                    if (slug.isBlank()) {
                        continue;
                    }
                    if (!triedSlugs.add(lower(slug))) {
                        continue;
                    }
                    int match = nameMatchScore(target, title, slug, projectId);
                    if (match < 35) {
                        pathDebug(target, "provider.modrinth.reject", slug + " name match " + match + " < 35");
                        continue;
                    }
                    TargetConfig candidateTarget = target.copyWithSource("https://modrinth.com/plugin/" + slug + "/versions");
                    candidateTarget.project = slug;
                    if (candidateTarget.loader == null || candidateTarget.loader.isBlank()) {
                        candidateTarget.loader = target.platform;
                    }
                    try {
                        ResolvedDownload download = new ModrinthResolver(config, client).resolve(candidateTarget);
                        String latest = latestFromDownload(download);
                        candidates.add(candidateFromResolved(target, "modrinth", candidateTarget.source, slug, latest, title, priority,
                            "Modrinth search match: " + title, download));
                    } catch (Exception ex) {
                        pathDebug(target, "provider.modrinth.resolve.failed", slug + " -> " + ex.getMessage());
                        Log.warn("Modrinth candidate did not resolve for " + slug + ": " + ex.getMessage());
                    }
                }
            }
            pathDebug(target, "provider.modrinth.done", "tried " + triedSlugs.size()
                + " slug(s); produced " + candidates.size() + " candidate(s)");
            return candidates;
        }

        private List<DiscoveryCandidate> discoverHangarSources(TargetConfig target, int priority) throws Exception {
            List<DiscoveryCandidate> candidates = new ArrayList<>();
            Set<String> triedProjects = new HashSet<>();
            pathDebug(target, "provider.hangar.start", "priority=" + priority
                + "; exactProjects=" + exactHangarProjects(target).stream()
                    .map(project -> project.owner + "/" + project.slug)
                    .collect(Collectors.joining(", "))
                + "; searchTerms=" + String.join(", ", discoverySearchTerms(target)));
            for (HangarProject project : exactHangarProjects(target)) {
                String key = lower(project.owner + "/" + project.slug);
                if (!triedProjects.add(key)) {
                    continue;
                }
                String source = "https://hangar.papermc.io/" + project.owner + "/" + project.slug + "/versions";
                TargetConfig candidateTarget = target.copyWithSource(source);
                candidateTarget.project = project.owner + "/" + project.slug;
                try {
                    ResolvedDownload download = new HangarResolver(config, client).resolve(candidateTarget);
                    String latest = latestFromDownload(download);
                    candidates.add(candidateFromResolved(target, "hangar", source, candidateTarget.project, latest, project.slug, priority,
                        "Hangar exact project probe: " + project.owner + "/" + project.slug, download));
                } catch (Exception ignored) {
                    pathDebug(target, "provider.hangar.exact.failed", "project=" + project.owner + "/" + project.slug);
                    // Exact project probes are intentionally quiet; broad search below will report useful failures.
                }
            }
            for (String term : discoverySearchTerms(target)) {
                pathDebug(target, "provider.hangar.search", "term=" + term);
                URI uri = URI.create("https://hangar.papermc.io/api/v1/projects?limit=8&offset=0&q=" + urlEncode(term));
                Object json = getJson(uri, "Hangar search");
                Object resultObj = asMap(json).get("result");
                if (!(resultObj instanceof List<?> results)) {
                    continue;
                }
                for (Object item : results) {
                    Map<String, Object> project = asMap(item);
                    HangarProject hangarProject = hangarProjectFromSearch(project);
                    if (hangarProject == null) {
                        continue;
                    }
                    triedProjects.add(lower(hangarProject.owner + "/" + hangarProject.slug));
                    String name = firstNonBlank(stringValue(project.get("name")), hangarProject.slug);
                    int match = nameMatchScore(target, name, hangarProject.slug, hangarProject.owner + "/" + hangarProject.slug);
                    if (match < 35) {
                        pathDebug(target, "provider.hangar.reject", hangarProject.owner + "/" + hangarProject.slug
                            + " name match " + match + " < 35");
                        continue;
                    }
                    String source = "https://hangar.papermc.io/" + hangarProject.owner + "/" + hangarProject.slug + "/versions";
                    TargetConfig candidateTarget = target.copyWithSource(source);
                    candidateTarget.project = hangarProject.owner + "/" + hangarProject.slug;
                    try {
                        ResolvedDownload download = new HangarResolver(config, client).resolve(candidateTarget);
                        String latest = latestFromDownload(download);
                        candidates.add(candidateFromResolved(target, "hangar", source, candidateTarget.project, latest, name, priority,
                            "Hangar search match: " + name, download));
                    } catch (Exception ex) {
                        pathDebug(target, "provider.hangar.resolve.failed", hangarProject.owner + "/" + hangarProject.slug
                            + " -> " + ex.getMessage());
                        Log.warn("Hangar candidate did not resolve for " + hangarProject.owner + "/" + hangarProject.slug + ": " + ex.getMessage());
                    }
                }
            }
            pathDebug(target, "provider.hangar.done", "tried " + triedProjects.size()
                + " project(s); produced " + candidates.size() + " candidate(s)");
            return candidates;
        }

        private List<String> exactModrinthSlugs(TargetConfig target) {
            List<String> slugs = new ArrayList<>();
            addModrinthSlugFromUrl(slugs, target.detectedWebsite);
            for (String seed : sourceSlugSeeds(target)) {
                addSlugVariants(slugs, seed);
            }
            return slugs.stream().limit(8).toList();
        }

        private List<HangarProject> exactHangarProjects(TargetConfig target) {
            List<HangarProject> projects = new ArrayList<>();
            addHangarProjectFromUrl(projects, target.detectedWebsite);
            List<String> owners = new ArrayList<>();
            for (String author : authorOwnerTerms(target.detectedAuthors)) {
                String owner = cleanProjectSegment(author);
                if (!owner.isBlank() && owners.stream().noneMatch(existing -> existing.equalsIgnoreCase(owner))) {
                    owners.add(owner);
                }
            }
            if (owners.isEmpty()) {
                for (String owner : mainClassSearchTerms(target.detectedMainClass)) {
                    String cleaned = cleanProjectSegment(owner);
                    if (!cleaned.isBlank() && owners.stream().noneMatch(existing -> existing.equalsIgnoreCase(cleaned))) {
                        owners.add(cleaned);
                    }
                }
            }
            List<String> slugs = new ArrayList<>();
            for (String seed : sourceSlugSeeds(target)) {
                addSlugVariants(slugs, seed);
            }
            Set<String> seen = new HashSet<>();
            for (String owner : owners.stream().limit(1).toList()) {
                String cleanOwner = cleanProjectSegment(owner);
                if (cleanOwner.isBlank()) {
                    continue;
                }
                for (String slug : slugs.stream().limit(3).toList()) {
                    String key = lower(cleanOwner + "/" + slug);
                    if (seen.add(key)) {
                        projects.add(new HangarProject(cleanOwner, slug));
                    }
                }
            }
            return projects;
        }

        private List<String> authorOwnerTerms(String authors) {
            if (authors == null || authors.isBlank()) {
                return Collections.emptyList();
            }
            String cleaned = authors
                .replace("[", " ")
                .replace("]", " ")
                .replace("\"", " ")
                .replace("'", " ");
            List<String> result = new ArrayList<>();
            for (String part : cleaned.split("[,;/|]+")) {
                String author = part.trim();
                if (author.isBlank()) {
                    continue;
                }
                if (result.stream().noneMatch(existing -> existing.equalsIgnoreCase(author))) {
                    result.add(author);
                }
                String noSeparators = author.replaceAll("[_\\-\\s]+", "");
                if (!noSeparators.equals(author)
                    && result.stream().noneMatch(existing -> existing.equalsIgnoreCase(noSeparators))) {
                    result.add(noSeparators);
                }
            }
            return result;
        }

        private List<String> sourceSlugSeeds(TargetConfig target) {
            List<String> seeds = new ArrayList<>();
            addSearchTerm(seeds, target.detectedPluginId);
            addSearchTerm(seeds, target.name);
            addSearchTerm(seeds, stripJarName(target.installAs));
            return seeds;
        }

        private void addSlugVariants(List<String> slugs, String value) {
            String cleaned = cleanSearchTerm(value);
            if (cleaned.isBlank()) {
                return;
            }
            String kebab = slugify(cleaned);
            String compact = normalizeName(cleaned);
            if (!kebab.isBlank() && slugs.stream().noneMatch(existing -> existing.equalsIgnoreCase(kebab))) {
                slugs.add(kebab);
            }
            if (!compact.isBlank() && slugs.stream().noneMatch(existing -> existing.equalsIgnoreCase(compact))) {
                slugs.add(compact);
            }
        }

        private String slugify(String value) {
            String expanded = firstNonBlank(value, "")
                .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1-$2");
            return expanded.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        }

        private String cleanProjectSegment(String value) {
            return firstNonBlank(value, "")
                .trim()
                .replaceAll("[^A-Za-z0-9_.-]+", "");
        }

        private void addModrinthSlugFromUrl(List<String> slugs, String url) {
            if (url == null || url.isBlank()) {
                return;
            }
            try {
                URI uri = URI.create(url);
                if (!lower(uri.getHost()).equals("modrinth.com")) {
                    return;
                }
                List<String> parts = pathParts(uri);
                if (parts.size() >= 2 && parts.get(0).equalsIgnoreCase("plugin")) {
                    addSlugVariants(slugs, parts.get(1));
                }
            } catch (RuntimeException ignored) {
                // Ignore malformed metadata websites.
            }
        }

        private void addHangarProjectFromUrl(List<HangarProject> projects, String url) {
            if (url == null || url.isBlank()) {
                return;
            }
            try {
                URI uri = URI.create(url);
                if (!lower(uri.getHost()).equals("hangar.papermc.io")) {
                    return;
                }
                List<String> parts = pathParts(uri);
                if (parts.size() >= 2) {
                    projects.add(new HangarProject(parts.get(0), parts.get(1)));
                }
            } catch (RuntimeException ignored) {
                // Ignore malformed metadata websites.
            }
        }

        private DiscoveryCandidate candidateFromResolved(TargetConfig target, String type, String source, String projectHint, String latestVersion, String label, int priority, String reason) {
            return candidateFromResolved(target, type, source, projectHint, latestVersion, label, priority, reason, null);
        }

        private DiscoveryCandidate candidateFromResolved(TargetConfig target, String type, String source, String projectHint, String latestVersion,
                                                         String label, int priority, String reason, ResolvedDownload download) {
            int match = nameMatchScore(target, label, source, projectHint);
            int score = 35 + match - (priority * 3);
            List<String> scoreParts = new ArrayList<>();
            scoreParts.add("base=35");
            scoreParts.add("nameMatch=+" + match);
            scoreParts.add("priorityPenalty=-" + (priority * 3));
            SourceOwnerSignal ownerSignal = sourceOwnerSignal(target.detectedAuthors, type, source, projectHint, label);
            SourceDescriptorEvidence descriptorEvidence = reason.contains("targeted GitHub repo descriptor match")
                || reason.contains("preferred-owner GitHub repo descriptor match")
                ? SourceDescriptorEvidence.MATCH
                : (type.equals("modrinth") || type.equals("hangar"))
                    ? hostedJarDescriptorEvidence(target, type, source, projectHint, download)
                    : githubSourceDescriptorEvidence(target, type, source, projectHint);
            String descriptorReason = "";
            if (descriptorEvidence == SourceDescriptorEvidence.MATCH) {
                score += 45;
                scoreParts.add("descriptorMatch=+45");
                ownerSignal = new SourceOwnerSignal(0, false, "");
                descriptorReason = "; source descriptor matches installed plugin";
                Optional<PluginJarInfo> matchingDescriptor = matchingCandidateDescriptor(target, type, source, projectHint, download);
                DescriptorRankingSignal foliaSignal = foliaDescriptorRankingSignal(target, matchingDescriptor);
                score += foliaSignal.scoreDelta;
                if (foliaSignal.scoreDelta != 0) {
                    scoreParts.add("foliaSignal=" + signedScore(foliaSignal.scoreDelta));
                }
                descriptorReason += foliaSignal.reason;
                Optional<ForkLineage> forkLineage = forkLineageForCandidate(target, type, source, projectHint, download);
                if (forkLineage.isPresent()) {
                    score += 10;
                    scoreParts.add("forkLineage=+10");
                    String lineage = forkLineage.get().describe();
                    descriptorReason += "; accepted as descriptor-proven fork"
                        + (lineage.isBlank() ? "" : " (" + lineage + ")");
                    enrichSourceProof(target, source, forkLineage.get(),
                        "descriptor/package match; " + firstNonBlank(lineage, "fork lineage detected"));
                }
            } else if (descriptorEvidence == SourceDescriptorEvidence.MISMATCH) {
                score -= 100;
                scoreParts.add("descriptorMismatch=-100");
                descriptorReason = "; source descriptors did not match installed plugin";
            } else if ((type.equals("modrinth") || type.equals("hangar")) && download != null) {
                score -= 55;
                scoreParts.add("hostedDescriptorUnverified=-55");
                descriptorReason = "; source descriptor could not be verified from candidate jar";
            }
            score += ownerSignal.scoreDelta;
            if (ownerSignal.scoreDelta != 0) {
                scoreParts.add("ownerSignal=" + signedScore(ownerSignal.scoreDelta));
            }
            String localVersion = target.detectedVersion == null ? "" : target.detectedVersion;
            String versionReason = "";
            if (!localVersion.isBlank() && !latestVersion.isBlank()) {
                if (isClearlyOlderVersion(latestVersion, localVersion)) {
                    score -= 100;
                    scoreParts.add("olderThanLocal=-100");
                    versionReason = "; rejected-looking version: latest " + latestVersion + " appears older than local " + localVersion;
                } else {
                    int cmp = compareVersionValues(cleanVersion(latestVersion), cleanVersion(localVersion));
                    if (cmp == 0) {
                        score += 25;
                        scoreParts.add("versionMatch=+25");
                        versionReason = "; version matches local " + localVersion;
                    } else if (cmp > 0) {
                        score += 15;
                        scoreParts.add("newerThanLocal=+15");
                        versionReason = "; latest " + latestVersion + " appears newer than local " + localVersion;
                    } else {
                        versionReason = "; latest " + latestVersion + " is not clearly newer than local " + localVersion;
                    }
                }
            } else if (!localVersion.isBlank() && type.equals("github-source")) {
                versionReason = "; source build candidate has no release version to compare with local " + localVersion;
            } else if (!localVersion.isBlank()) {
                score -= 25;
                scoreParts.add("noComparableVersion=-25");
                versionReason = "; no comparable latest version found while local version is " + localVersion;
            }
            String ownerReason = ownerSignal.reason.isBlank() ? "" : "; " + ownerSignal.reason;
            String fullReason = reason + "; name match score " + match + ownerReason + descriptorReason + versionReason;
            pathDebug(target, "candidate.score", type + " -> " + source
                + "; total=" + score
                + "; parts=" + String.join(", ", scoreParts)
                + "; descriptorEvidence=" + descriptorEvidence
                + "; latest=" + firstNonBlank(latestVersion, "unknown")
                + "; local=" + firstNonBlank(localVersion, "unknown"));
            return new DiscoveryCandidate(type, source, projectHint, latestVersion, label, fullReason, score, priority);
        }

        private String signedScore(int value) {
            return value >= 0 ? "+" + value : Integer.toString(value);
        }

        private Optional<PluginJarInfo> matchingCandidateDescriptor(TargetConfig target, String type, String source,
                                                                    String projectHint, ResolvedDownload download) {
            try {
                if (type.equals("modrinth") || type.equals("hangar")) {
                    if (download == null || download.uri == null) {
                        return Optional.empty();
                    }
                    Path jar = cachedHostedCandidateJar(type, projectHint, download);
                    PluginJarInfo candidate = readPluginJarInfo(jar);
                    PluginJarInfo installed = installedPluginInfo(target);
                    return candidate.hasDescriptor && pluginDescriptorMatchesTarget(installed, target, candidate)
                        ? Optional.of(candidate)
                        : Optional.empty();
                }
                if (isGithubLikeSource(type, source, projectHint)) {
                    GithubRepo repo = githubRepoFromSourceParts(source, projectHint);
                    if (repo == null) {
                        return Optional.empty();
                    }
                    Optional<PluginJarInfo> rawDescriptor = matchingGithubRawDescriptor(target, repo);
                    if (rawDescriptor.isPresent()) {
                        return rawDescriptor;
                    }
                    PluginJarInfo installed = installedPluginInfo(target);
                    for (PluginJarInfo descriptor : githubRepoDescriptors(target, repo)) {
                        if (pluginDescriptorMatchesTarget(installed, target, descriptor)) {
                            return Optional.of(descriptor);
                        }
                    }
                }
            } catch (Exception ignored) {
                // Descriptor evidence already decided whether this candidate is usable.
            }
            return Optional.empty();
        }

        private Optional<PluginJarInfo> matchingGithubRawDescriptor(TargetConfig target, GithubRepo repo) {
            List<String> paths = likelyGithubDescriptorPaths(target, repo);
            List<String> branches = likelyGithubBranches(target, repo);
            PluginJarInfo installed = installedPluginInfo(target);
            for (String branch : branches) {
                GithubRepo branchRepo = branch.isBlank() ? new GithubRepo(repo.owner, repo.name) : new GithubRepo(repo.owner, repo.name, branch);
                for (String path : paths) {
                    try {
                        Optional<String> text = fetchGithubRawOptional(branchRepo, path);
                        if (text.isEmpty()) {
                            continue;
                        }
                        PluginJarInfo info = parsePluginDescriptor(path, text.get());
                        if (info.hasDescriptor && pluginDescriptorMatchesTarget(installed, target, info)) {
                            return Optional.of(info);
                        }
                    } catch (Exception ignored) {
                        // Keep probing likely descriptor paths.
                    }
                }
            }
            return Optional.empty();
        }

        private DescriptorRankingSignal foliaDescriptorRankingSignal(TargetConfig target, Optional<PluginJarInfo> candidateDescriptor) {
            PluginJarInfo installed = installedPluginInfo(target);
            if (installed == null || !Boolean.TRUE.equals(installed.foliaSupported)) {
                return new DescriptorRankingSignal(0, "");
            }
            if (candidateDescriptor.isEmpty()) {
                return new DescriptorRankingSignal(-10, "; installed jar is Folia-compatible but candidate descriptor could not be re-read for Folia proof");
            }
            PluginJarInfo candidate = candidateDescriptor.get();
            if (Boolean.TRUE.equals(candidate.foliaSupported)) {
                return new DescriptorRankingSignal(30, "; preserves installed Folia support");
            }
            return new DescriptorRankingSignal(-25, "; source descriptor matches identity but does not prove Folia support");
        }

        private Optional<ForkLineage> forkLineageForCandidate(TargetConfig target, String type, String source,
                                                              String projectHint, ResolvedDownload download) {
            try {
                GithubRepo repo = null;
                if (isGithubLikeSource(type, source, projectHint)) {
                    repo = githubRepoFromSourceParts(source, projectHint);
                }
                if (repo == null && lower(type).equals("modrinth")) {
                    String modrinthSource = modrinthProjectSourceUrl(projectHint);
                    if (modrinthSource.contains("github.com/")) {
                        repo = repoFromGithubUrl(modrinthSource);
                    }
                }
                if (repo == null && download != null && download.label.contains("github.com/")) {
                    repo = repoFromGithubUrl(download.label);
                }
                if (repo == null) {
                    return Optional.empty();
                }
                ForkLineage lineage = githubForkLineage(repo, target);
                return lineage.isFork() ? Optional.of(lineage) : Optional.empty();
            } catch (Exception ex) {
                return Optional.empty();
            }
        }

        private String modrinthProjectSourceUrl(String projectHint) {
            String project = firstNonBlank(projectHint, "");
            if (project.startsWith("modrinth/")) {
                project = project.substring("modrinth/".length());
            }
            if (project.isBlank() || project.contains("/")) {
                return "";
            }
            try {
                URI uri = URI.create("https://api.modrinth.com/v2/project/" + urlEncode(project));
                Object json = getJson(uri, "Modrinth project metadata");
                Map<String, Object> root = asMap(json);
                return firstNonBlank(
                    stringValue(root.get("source_url")),
                    stringValue(root.get("issues_url")),
                    stringValue(root.get("wiki_url"))
                );
            } catch (Exception ex) {
                return "";
            }
        }

        private ForkLineage githubForkLineage(GithubRepo repo, TargetConfig target) throws Exception {
            URI uri = URI.create("https://api.github.com/repos/" + urlEncode(repo.owner) + "/" + urlEncode(repo.name));
            Object json = getJson(uri, "GitHub repo metadata", target);
            Map<String, Object> root = asMap(json);
            boolean fork = Boolean.TRUE.equals(root.get("fork"));
            String fullName = firstNonBlank(stringValue(root.get("full_name")), repo.owner + "/" + repo.name);
            Map<String, Object> parent = root.get("parent") instanceof Map<?, ?> parentMap
                ? asMap(parentMap)
                : Collections.emptyMap();
            Map<String, Object> source = root.get("source") instanceof Map<?, ?> sourceMap
                ? asMap(sourceMap)
                : Collections.emptyMap();
            return new ForkLineage(
                fullName,
                stringValue(parent.get("full_name")),
                stringValue(source.get("full_name")),
                fork
            );
        }

        private SourceDescriptorEvidence githubSourceDescriptorEvidence(TargetConfig target, String type, String source, String projectHint) {
            if (!isGithubLikeSource(type, source, projectHint)) {
                return SourceDescriptorEvidence.UNKNOWN;
            }
            try {
                GithubRepo repo = githubRepoFromSourceParts(source, projectHint);
                if (repo == null) {
                    return SourceDescriptorEvidence.UNKNOWN;
                }
                Optional<SourceProof> proof = lockState.activeSourceProof(target, repo);
                if (proof.isPresent()) {
                    return SourceDescriptorEvidence.MATCH;
                }
                Optional<RejectedSourceProof> rejected = lockState.activeRejectedSourceProof(
                    target,
                    source,
                    type,
                    repo.owner + "/" + repo.name
                );
                if (rejected.isPresent()) {
                    return SourceDescriptorEvidence.MISMATCH;
                }
                List<PluginJarInfo> descriptors = githubRepoDescriptors(target, repo);
                if (descriptors.isEmpty()) {
                    return SourceDescriptorEvidence.UNKNOWN;
                }
                PluginJarInfo installed = installedPluginInfo(target);
                for (PluginJarInfo descriptor : descriptors) {
                    if (pluginDescriptorMatchesTarget(installed, target, descriptor)) {
                        rememberSourceProof(target, source, type, repo, descriptor, "descriptor-match");
                        return SourceDescriptorEvidence.MATCH;
                    }
                }
                PluginJarInfo firstDescriptor = descriptors.get(0);
                rememberRejectedSourceProof(target, source, type, repo.owner + "/" + repo.name, firstDescriptor,
                    "GitHub source descriptors did not match installed plugin");
                return SourceDescriptorEvidence.MISMATCH;
            } catch (Exception ex) {
                if (!githubRateLimited && !isExpectedMissingGithubProbe(ex)) {
                    Log.info("Could not inspect GitHub source descriptors for " + target.displayName()
                        + " from " + firstNonBlank(projectHint, source) + ": " + ex.getMessage());
                }
                return SourceDescriptorEvidence.UNKNOWN;
            }
        }

        private SourceDescriptorEvidence hostedJarDescriptorEvidence(TargetConfig target, String type, String source,
                                                                     String projectHint, ResolvedDownload download) {
            Optional<SourceProof> proof = lockState.activeSourceProof(target);
            if (proof.isPresent()
                && lower(proof.get().type).equals(lower(type))
                && sourcesMatchLoosely(proof.get().source, source)) {
                return SourceDescriptorEvidence.MATCH;
            }
            Optional<RejectedSourceProof> rejected = lockState.activeRejectedSourceProof(target, source, type, projectHint);
            if (rejected.isPresent()) {
                return SourceDescriptorEvidence.MISMATCH;
            }
            if (download == null || download.uri == null) {
                return SourceDescriptorEvidence.UNKNOWN;
            }
            try {
                Path jar = cachedHostedCandidateJar(type, projectHint, download);
                PluginJarInfo candidate = readPluginJarInfo(jar);
                if (!candidate.hasDescriptor) {
                    return SourceDescriptorEvidence.UNKNOWN;
                }
                PluginJarInfo installed = installedPluginInfo(target);
                if (pluginDescriptorMatchesTarget(installed, target, candidate)) {
                    rememberSourceProof(target, source, type, sourceProofProject(type, projectHint), candidate,
                        type + "-jar-descriptor-match");
                    return SourceDescriptorEvidence.MATCH;
                }
                rememberRejectedSourceProof(target, source, type, sourceProofProject(type, projectHint), candidate,
                    type + " candidate jar descriptor did not match installed plugin");
                return SourceDescriptorEvidence.MISMATCH;
            } catch (Exception ex) {
                Log.info("Could not inspect " + type + " candidate jar for " + target.displayName()
                    + " from " + source + ": " + ex.getMessage());
                return SourceDescriptorEvidence.UNKNOWN;
            }
        }

        private Path cachedHostedCandidateJar(String type, String projectHint, ResolvedDownload download) throws Exception {
            String filename = safeName(firstNonBlank(projectHint, type, "candidate") + "-" + sha256Text(download.uri.toString())) + ".jar";
            Path dir = config.resolve(config.cacheDir).resolve("discovery").resolve(type).resolve("jars");
            Files.createDirectories(dir);
            Path jar = dir.resolve(filename);
            if (Files.isRegularFile(jar) && isFreshFile(jar, GITHUB_CACHE_STALE)) {
                Files.setLastModifiedTime(jar, FileTime.from(Instant.now()));
                return jar;
            }
            Path tmp = jar.resolveSibling(jar.getFileName() + ".tmp");
            HttpRequest request = HttpRequest.newBuilder(download.uri)
                .timeout(DISCOVERY_JAR_TIMEOUT)
                .header("User-Agent", config.userAgent)
                .GET()
                .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new IOException("candidate jar download failed with HTTP " + status + " from " + download.uri);
            }
            try (InputStream in = response.body()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
            return jar;
        }

        private boolean isFreshFile(Path path, Duration maxAge) {
            try {
                return Files.isRegularFile(path)
                    && Files.getLastModifiedTime(path).toInstant().plus(maxAge).isAfter(Instant.now());
            } catch (IOException ex) {
                return false;
            }
        }

        private boolean isExpectedMissingGithubProbe(Exception ex) {
            String message = lower(ex == null ? "" : firstNonBlank(ex.getMessage(), ""));
            return message.contains("http 404")
                || message.contains("not found");
        }

        private SourceDescriptorEvidence githubTargetedDescriptorEvidence(TargetConfig target, GithubRepo repo) {
            SourceDescriptorEvidence rawEvidence = githubCommonRawDescriptorEvidence(target, repo);
            if (rawEvidence == SourceDescriptorEvidence.MATCH) {
                return rawEvidence;
            }
            return githubSourceDescriptorEvidence(
                target,
                "github-source",
                "https://github.com/" + repo.owner + "/" + repo.name,
                repo.owner + "/" + repo.name
            );
        }

        private SourceDescriptorEvidence githubCommonRawDescriptorEvidence(TargetConfig target, GithubRepo repo) {
            return githubCommonRawDescriptorMatch(target, repo).isPresent()
                ? SourceDescriptorEvidence.MATCH
                : SourceDescriptorEvidence.UNKNOWN;
        }

        private Optional<GithubRepo> githubCommonRawDescriptorMatch(TargetConfig target, GithubRepo repo) {
            Optional<SourceProof> proof = lockState.activeSourceProof(target, repo);
            if (proof.isPresent()) {
                GithubRepo proofRepo = repoFromGithubText(firstNonBlank(proof.get().source, proof.get().repo));
                return Optional.of(proofRepo == null ? repo : proofRepo);
            }
            List<String> paths = likelyGithubDescriptorPaths(target, repo);
            List<String> branches = likelyGithubBranches(target, repo);
            PluginJarInfo installed = installedPluginInfo(target);
            for (String branch : branches) {
                GithubRepo branchRepo = branch.isBlank() ? new GithubRepo(repo.owner, repo.name) : new GithubRepo(repo.owner, repo.name, branch);
                for (String path : paths) {
                    try {
                        Optional<String> text = fetchGithubRawOptional(branchRepo, path);
                        if (text.isEmpty()) {
                            continue;
                        }
                        PluginJarInfo info = parsePluginDescriptor(path, text.get());
                        if (info.hasDescriptor && pluginDescriptorMatchesTarget(installed, target, info)) {
                            rememberSourceProof(target, githubSourceUrl(branchRepo),
                                "github-source", branchRepo, info, "raw-descriptor-match");
                            return Optional.of(branchRepo);
                        }
                    } catch (Exception ignored) {
                        // Raw descriptor probing is an optimization; fall back to API tree inspection.
                    }
                }
            }
            return Optional.empty();
        }

        private List<String> likelyGithubDescriptorPaths(TargetConfig target, GithubRepo repo) {
            List<String> paths = new ArrayList<>();
            List<String> descriptorNames = List.of(
                "plugin.yml",
                "paper-plugin.yml",
                "bungee.yml",
                "velocity-plugin.json"
            );
            for (String descriptor : descriptorNames) {
                paths.add("src/main/resources/" + descriptor);
                paths.add(descriptor);
            }
            for (String module : likelyGithubModuleNames(target, repo)) {
                for (String descriptor : descriptorNames) {
                    paths.add(module + "/src/main/resources/" + descriptor);
                    paths.add(module + "/" + descriptor);
                }
            }
            return paths.stream().distinct().limit(40).toList();
        }

        private List<String> likelyGithubBranches(TargetConfig target, GithubRepo repo) {
            List<String> branches = new ArrayList<>();
            addLikelyGithubBranch(branches, repo.ref);
            addLikelyGithubBranch(branches, "HEAD");
            addLikelyGithubBranch(branches, "master");
            addLikelyGithubBranch(branches, "main");
            if (repoNameMatchesTarget(target, repo) || !cleanGithubRef(repo.ref).isBlank()) {
                addLikelyGithubBranch(branches, "Master-Lite-Version");
                addLikelyGithubBranch(branches, "folia");
                addLikelyGithubBranch(branches, "paper");
            }
            return branches.stream().limit(6).toList();
        }

        private void addLikelyGithubBranch(List<String> branches, String value) {
            String cleaned = cleanGithubRef(value);
            if (cleaned.isBlank() || cleaned.equalsIgnoreCase("HEAD")) {
                cleaned = "";
            }
            String branch = cleaned;
            if (branches.stream().noneMatch(existing -> existing.equalsIgnoreCase(branch))) {
                branches.add(branch);
            }
        }

        private List<String> likelyGithubModuleNames(TargetConfig target, GithubRepo repo) {
            List<String> modules = new ArrayList<>();
            for (String value : Arrays.asList(
                target.detectedPluginId,
                target.name,
                stripJarName(target.installAs),
                repo.name
            )) {
                addLikelyGithubModuleName(modules, value);
                addLikelyGithubModuleName(modules, cleanSearchTerm(value));
            }
            for (String commonModule : List.of("bukkit", "paper", "spigot", "plugin", "velocity", "bungee")) {
                addLikelyGithubModuleName(modules, commonModule);
            }
            List<String> snapshot = new ArrayList<>(modules);
            for (String module : snapshot) {
                String normalized = normalizeIdentity(module);
                if (normalized.endsWith("addons") && module.length() > "Addons".length()) {
                    addLikelyGithubModuleName(modules, module.substring(0, module.length() - "Addons".length()));
                } else if (normalized.endsWith("addon") && module.length() > "Addon".length()) {
                    addLikelyGithubModuleName(modules, module.substring(0, module.length() - "Addon".length()));
                }
            }
            return modules;
        }

        private void addLikelyGithubModuleName(List<String> modules, String value) {
            String cleaned = cleanGithubPathPart(cleanSearchTerm(value));
            if (cleaned.isBlank() || normalizeIdentity(cleaned).length() < 3) {
                return;
            }
            if (modules.stream().noneMatch(existing -> existing.equalsIgnoreCase(cleaned))) {
                modules.add(cleaned);
            }
        }

        private GithubRepo githubRepoFromSourceParts(String source, String projectHint) {
            String value = source != null && source.contains("github.com/")
                ? source
                : firstNonBlank(projectHint, source, "");
            if (value.isBlank()) {
                return null;
            }
            if (value.contains("github.com/")) {
                return repoFromGithubUrl(value);
            }
            if (value.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
                return repoFromGithubValue(value);
            }
            if (source != null && source.contains("github.com/")) {
                return repoFromGithubUrl(source);
            }
            return null;
        }

        private List<PluginJarInfo> githubRepoDescriptors(TargetConfig target, GithubRepo repo) throws Exception {
            String key = lower(repo.owner + "/" + repo.name);
            if (githubDescriptorCache.containsKey(key)) {
                return githubDescriptorCache.get(key);
            }

            List<PluginJarInfo> descriptors = Collections.emptyList();
            try {
                URI uri = URI.create("https://api.github.com/repos/" + urlEncode(repo.owner) + "/" + urlEncode(repo.name)
                    + "/git/trees/HEAD?recursive=1");
                Object json = getJson(uri, "GitHub source tree", target);
                Object treeObj = asMap(json).get("tree");
                if (treeObj instanceof List<?> tree) {
                    List<String> descriptorPaths = new ArrayList<>();
                    for (Object item : tree) {
                        Map<String, Object> node = asMap(item);
                        if (!"blob".equals(stringValue(node.get("type")))) {
                            continue;
                        }
                        String path = stringValue(node.get("path"));
                        if (isPluginDescriptorPath(path)) {
                            descriptorPaths.add(path);
                        }
                    }
                    descriptorPaths.sort(Comparator
                        .comparingInt((String path) -> descriptorPathPriority(path))
                        .thenComparing(String::length));
                    List<PluginJarInfo> apiDescriptors = new ArrayList<>();
                    for (String path : descriptorPaths.stream().limit(16).toList()) {
                        String text = fetchGithubRaw(repo, path);
                        PluginJarInfo info = parsePluginDescriptor(path, text);
                        if (info.hasDescriptor) {
                            apiDescriptors.add(info);
                        }
                    }
                    descriptors = apiDescriptors;
                }
            } catch (Exception ex) {
                if (!githubRateLimited && !isExpectedMissingGithubProbe(ex)) {
                    Log.info("GitHub API descriptor scan failed for " + repo.owner + "/" + repo.name
                        + "; trying cached source archive scan: " + ex.getMessage());
                }
            }

            if (descriptors.isEmpty()) {
                descriptors = githubRepoArchiveDescriptors(repo);
            }
            githubDescriptorCache.put(key, descriptors);
            return descriptors;
        }

        private List<PluginJarInfo> githubRepoArchiveDescriptors(GithubRepo repo) throws Exception {
            Path sourceDir = githubRepoArchiveSourceDir(repo);
            if (!isFreshDirectory(sourceDir, GITHUB_CACHE_STALE)) {
                downloadGithubRepoArchive(repo, sourceDir);
            }
            return pluginDescriptorsInSourceTree(sourceDir);
        }

        private Path githubRepoArchiveSourceDir(GithubRepo repo) {
            String name = repo.owner + "-" + repo.name + (cleanGithubRef(repo.ref).isBlank() ? "" : "-" + cleanGithubRef(repo.ref).replace("/", "-"));
            return config.resolve(config.cacheDir)
                .resolve("discovery")
                .resolve("github-source")
                .resolve(safeName(name));
        }

        private boolean isFreshDirectory(Path path, Duration maxAge) {
            try {
                return Files.isDirectory(path)
                    && Files.getLastModifiedTime(path).toInstant().plus(maxAge).isAfter(Instant.now());
            } catch (IOException ex) {
                return false;
            }
        }

        private void downloadGithubRepoArchive(GithubRepo repo, Path sourceDir) throws Exception {
            Files.createDirectories(sourceDir.getParent());
            Path zip = sourceDir.resolveSibling(sourceDir.getFileName() + ".zip");
            Path tmpZip = sourceDir.resolveSibling(sourceDir.getFileName() + ".zip.tmp");
            String ref = cleanGithubRef(repo.ref);
            String archiveRef = ref.isBlank() ? "HEAD" : "refs/heads/" + ref;
            URI uri = URI.create("https://github.com/" + urlEncode(repo.owner) + "/" + urlEncode(repo.name) + "/archive/" + encodePath(archiveRef) + ".zip");
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(DISCOVERY_ARCHIVE_TIMEOUT)
                .header("User-Agent", config.userAgent)
                .GET()
                .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new IOException("GitHub source archive fetch failed with HTTP " + status + " for " + repo.owner + "/" + repo.name);
            }
            try (InputStream in = response.body()) {
                Files.copy(in, tmpZip, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmpZip, zip, StandardCopyOption.REPLACE_EXISTING);

            Path tmpDir = sourceDir.resolveSibling(sourceDir.getFileName() + ".tmp");
            deleteRecursively(tmpDir);
            Files.createDirectories(tmpDir);
            unzipSafely(zip, tmpDir);
            deleteRecursively(sourceDir);
            Files.move(tmpDir, sourceDir, StandardCopyOption.REPLACE_EXISTING);
            Log.info("Cached GitHub source archive for " + repo.owner + "/" + repo.name + " to " + sourceDir + ".");
        }

        private String fetchGithubRaw(GithubRepo repo, String path) throws Exception {
            String ref = cleanGithubRef(repo.ref);
            String branch = ref.isBlank() ? "HEAD" : ref;
            URI uri = URI.create("https://raw.githubusercontent.com/" + urlEncode(repo.owner) + "/" + urlEncode(repo.name)
                + "/" + encodePath(branch) + "/" + encodePath(path));
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(DISCOVERY_HTTP_TIMEOUT)
                .header("User-Agent", config.userAgent);
            HttpRequest request = builder.GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("GitHub raw descriptor fetch failed with HTTP " + response.statusCode() + " for " + path);
            }
            return response.body();
        }

        private Optional<String> fetchGithubRawOptional(GithubRepo repo, String path) throws Exception {
            String ref = cleanGithubRef(repo.ref);
            String branch = ref.isBlank() ? "HEAD" : ref;
            URI uri = URI.create("https://raw.githubusercontent.com/" + urlEncode(repo.owner) + "/" + urlEncode(repo.name)
                + "/" + encodePath(branch) + "/" + encodePath(path));
            if (testGithubRawResponses.containsKey(uri.toString())) {
                return Optional.ofNullable(testGithubRawResponses.get(uri.toString()));
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(DISCOVERY_RAW_TIMEOUT)
                .header("User-Agent", config.userAgent);
            HttpRequest request = builder.GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Optional.empty();
            }
            return Optional.of(response.body());
        }

        private PluginJarInfo installedPluginInfo(TargetConfig target) {
            if (target.installAs != null && !target.installAs.isBlank()) {
                Path path = config.resolve(Paths.get(target.installAs));
                if (Files.isRegularFile(path)) {
                    return readPluginJarInfo(path);
                }
            }
            return new PluginJarInfo(
                firstNonBlank(target.detectedPluginId, target.name, ""),
                firstNonBlank(target.name, target.detectedPluginId, ""),
                firstNonBlank(target.detectedVersion, ""),
                firstNonBlank(target.detectedWebsite, ""),
                firstNonBlank(target.detectedMainClass, ""),
                firstNonBlank(target.detectedAuthors, ""),
                "",
                Set.of(),
                null,
                !firstNonBlank(target.detectedPluginId, target.name, "").isBlank()
            );
        }

        private Object getJson(URI uri, String apiName) throws Exception {
            return getJson(uri, apiName, null);
        }

        private Object getJson(URI uri, String apiName, TargetConfig budgetTarget) throws Exception {
            if (testJsonResponses.containsKey(uri.toString())) {
                int hits = testJsonHitCounts.merge(uri.toString(), 1, Integer::sum);
                Integer failAfter = testJsonFailureAfterHits.get(uri.toString());
                if (failAfter != null && hits > failAfter) {
                    throw new IOException("test GitHub budget/rate limit after cached metadata");
                }
                return testJsonResponses.get(uri.toString());
            }
            boolean githubApi = isGithubApiUri(uri);
            if (githubApi) {
                Optional<String> fresh = readGithubApiCache(config, uri, GITHUB_CACHE_FRESH);
                if (fresh.isPresent()) {
                    return new JsonParser(fresh.get()).parse();
                }
                if (githubAuthFailed) {
                    Optional<String> stale = readGithubApiCache(config, uri, GITHUB_CACHE_STALE);
                    if (stale.isPresent()) {
                        Log.info("Using cached GitHub API response for " + apiName + " while GitHub auth is unavailable: " + uri);
                        return new JsonParser(stale.get()).parse();
                    }
                    throw new IOException("GitHub API auth failed earlier this run; skipping API call for " + apiName);
                }
                if (config.githubRateLimit.isPaused()) {
                    Optional<String> stale = readGithubApiCache(config, uri, GITHUB_CACHE_STALE);
                    if (stale.isPresent()) {
                        Log.info("Using cached GitHub API response for " + apiName + " while rate limited: " + uri);
                        return new JsonParser(stale.get()).parse();
                    }
                    throw new IOException("GitHub API calls are paused until " + config.githubRateLimit.resetText());
                }
                enforceGithubBudget(budgetTarget, apiName, uri);
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(DISCOVERY_HTTP_TIMEOUT)
                .header("User-Agent", config.userAgent)
                .header("Accept", "application/json");
            applyGithubAuth(builder, config, uri);
            HttpRequest request = builder.GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                if (status == 401 && githubApi) {
                    Optional<HttpResponse<String>> unauthenticated = retryGithubUnauthenticated(
                        config, client, uri, DISCOVERY_HTTP_TIMEOUT, "application/json", apiName);
                    if (unauthenticated.isPresent()) {
                        HttpResponse<String> retry = unauthenticated.get();
                        observeGithubRateHeaders(apiName, retry);
                        writeGithubApiCache(config, uri, retry.body());
                        return new JsonParser(retry.body()).parse();
                    }
                    String diagnosis = handleGithubAuthFailure(apiName, uri, response);
                    Optional<String> stale = readGithubApiCache(config, uri, GITHUB_CACHE_STALE);
                    if (stale.isPresent()) {
                        Log.info("Using cached GitHub API response for " + apiName + " after 401: " + uri);
                        return new JsonParser(stale.get()).parse();
                    }
                    throw new IOException(apiName + " failed with HTTP 401 for " + uri + " (" + diagnosis + ")");
                }
                if ((status == 403 || status == 429) && githubApi) {
                    String diagnosis = github403Diagnosis(apiName, uri, response);
                    Optional<String> stale = readGithubApiCache(config, uri, GITHUB_CACHE_STALE);
                    if (stale.isPresent()) {
                        Log.info("Using cached GitHub API response for " + apiName + " after " + status + ": " + uri);
                        return new JsonParser(stale.get()).parse();
                    }
                    throw new IOException(apiName + " failed with HTTP " + status + " for " + uri + " (" + diagnosis + ")");
                }
                throw new IOException(apiName + " failed with HTTP " + status + " for " + uri);
            }
            if (githubApi) {
                observeGithubRateHeaders(apiName, response);
                writeGithubApiCache(config, uri, response.body());
            }
            return new JsonParser(response.body()).parse();
        }

        private String handleGithubAuthFailure(String apiName, URI uri, HttpResponse<String> response) {
            githubAuthFailed = true;
            githubRateLimited = true;
            String message = jsonMessage(response.body());
            String diagnosis = "GitHub authentication failed with HTTP 401"
                + (message.isBlank() ? "" : " (" + message + ")");
            writeDiagnosticOnce(
                "github-401",
                "GitHub API 401",
                "Context: " + apiName + System.lineSeparator()
                    + "URL: " + uri + System.lineSeparator()
                    + "Diagnosis: " + diagnosis + System.lineSeparator()
                    + "Token setting: " + githubTokenStatus(config).display() + System.lineSeparator()
                    + "Body: " + abbreviate(response.body(), 700)
            );
            if (!githubAuthFailureLogged) {
                Log.warn(diagnosis + ". Disabling GitHub API discovery for this run; non-GitHub discovery will continue. Details written to "
                    + config.diagnosticsFile + ".");
                githubAuthFailureLogged = true;
            }
            return diagnosis;
        }

        private void observeGithubRateHeaders(String apiName, HttpResponse<?> response) {
            String remainingText = header(response, "x-ratelimit-remaining");
            String limitText = header(response, "x-ratelimit-limit");
            String resetText = header(response, "x-ratelimit-reset");
            String resource = firstNonBlank(header(response, "x-ratelimit-resource"), githubBudgetResource(apiName, null));
            int remaining = intValue(remainingText, -1);
            int limit = intValue(limitText, -1);
            int reserve = lower(resource).equals("search") ? GITHUB_SEARCH_RESERVE_REMAINING : GITHUB_CORE_RESERVE_REMAINING;
            if (remaining >= 0 && remaining <= reserve) {
                githubRateLimited = true;
                config.githubRateLimit.pauseUntil(githubResetInstant(resetText));
                if (!config.githubRateLimit.pauseLogged) {
                    Log.warn("GitHub " + resource + " API reserve reached (" + remaining + "/" + limit
                        + " remaining) after " + apiName + "; pausing GitHub API calls until "
                        + config.githubRateLimit.resetText() + ".");
                    config.githubRateLimit.pauseLogged = true;
                }
            }
        }

        private void enforceGithubBudget(TargetConfig target, String apiName, URI uri) throws IOException {
            String resource = githubBudgetResource(apiName, uri);
            if (config.githubBudget.tryUse(target, resource)) {
                return;
            }
            if (config.githubBudget.runBudgetReached(resource) || target == null) {
                githubRateLimited = true;
            }
            Instant retry = Instant.now().plus(DISCOVERY_NOT_FOUND_BACKOFF);
            boolean alreadyResolved = target != null && target.sourceDiscoveredThisRun;
            if (target != null && !target.server) {
                githubBudgetLimitedPlugins.add(pluginBudgetKey(target));
                if (!alreadyResolved) {
                    rememberDiscoveryDeferred(target, "GitHub API budget reached", retry);
                }
            }
            String message = "GitHub " + resource + " API budget reached"
                + (target == null ? "" : " for " + target.displayName())
                + " (" + config.githubBudget.status(target) + "); "
                + (alreadyResolved ? "skipping extra GitHub inspection after a source was already selected." : "deferring further GitHub discovery.");
            String warningKey = normalizedConfigPath((target == null ? "" : firstNonBlank(target.installAs, target.displayName()))
                + "|" + resource);
            if (githubBudgetWarnings.add(warningKey)) {
                Log.warn(message);
            }
            throw new IOException(message);
        }

        private boolean isGithubPluginBudgetLimited(TargetConfig target) {
            return target != null && githubBudgetLimitedPlugins.contains(pluginBudgetKey(target));
        }

        private String pluginBudgetKey(TargetConfig target) {
            return normalizedConfigPath(firstNonBlank(target.installAs, target.displayName()));
        }

        private String githubBudgetResource(String apiName, URI uri) {
            String text = lower(firstNonBlank(apiName, "") + " " + (uri == null ? "" : uri.toString()));
            return text.contains("/search/") || text.contains("repository search") ? "search" : "core";
        }

        private boolean isGithubApiUri(URI uri) {
            return lower(uri.getHost()).equals("api.github.com");
        }

        private String github403Diagnosis(String apiName, URI uri, HttpResponse<String> response) {
            String remaining = header(response, "x-ratelimit-remaining");
            String limit = header(response, "x-ratelimit-limit");
            String reset = header(response, "x-ratelimit-reset");
            String resource = header(response, "x-ratelimit-resource");
            String message = jsonMessage(response.body());
            String lowerBody = lower(response.body());
            String resetText = reset.isBlank() ? "" : " reset=" + githubResetText(reset);
            String diagnosis;
            if ("0".equals(remaining)) {
                diagnosis = "GitHub API rate limit appears exhausted"
                    + (resource.isBlank() ? "" : " for " + resource)
                    + (limit.isBlank() ? "" : " (" + remaining + "/" + limit + " remaining)")
                    + resetText;
                githubRateLimited = true;
                config.githubRateLimit.pauseUntil(githubResetInstant(reset));
            } else if (lowerBody.contains("secondary rate limit") || lowerBody.contains("abuse detection")) {
                diagnosis = "GitHub secondary rate limit/abuse protection appears active";
                githubRateLimited = true;
                config.githubRateLimit.pauseUntil(githubResetInstant(reset));
            } else if (lowerBody.contains("rate limit")) {
                diagnosis = "GitHub rate limiting appears active";
                githubRateLimited = true;
                config.githubRateLimit.pauseUntil(githubResetInstant(reset));
            } else {
                diagnosis = "GitHub returned 403 but did not clearly identify rate limiting";
            }
            writeDiagnosticOnce(
                "github-403|" + apiName + "|" + uri,
                "GitHub API 403",
                "Context: " + apiName + System.lineSeparator()
                    + "URL: " + uri + System.lineSeparator()
                    + "Diagnosis: " + diagnosis + System.lineSeparator()
                    + "Message: " + firstNonBlank(message, "(none)") + System.lineSeparator()
                    + "Headers: x-ratelimit-limit=" + firstNonBlank(limit, "?")
                    + ", x-ratelimit-remaining=" + firstNonBlank(remaining, "?")
                    + ", x-ratelimit-reset=" + firstNonBlank(reset, "?")
                    + ", x-ratelimit-resource=" + firstNonBlank(resource, "?") + System.lineSeparator()
                    + "Body: " + abbreviate(response.body(), 700)
            );
            if (!config.githubRateLimit.pauseLogged) {
                Log.warn(diagnosis + ". Details written to " + config.diagnosticsFile + ".");
                config.githubRateLimit.pauseLogged = true;
            }
            return diagnosis;
        }

        private String header(HttpResponse<?> response, String name) {
            return response.headers().firstValue(name).orElse("");
        }

        private String githubResetText(String reset) {
            try {
                return Instant.ofEpochSecond(Long.parseLong(reset)).toString();
            } catch (NumberFormatException ex) {
                return reset;
            }
        }

        private Instant githubResetInstant(String reset) {
            try {
                return Instant.ofEpochSecond(Long.parseLong(firstNonBlank(reset, "0")));
            } catch (NumberFormatException ex) {
                return Instant.now().plus(Duration.ofMinutes(15));
            }
        }

        private String jsonMessage(String body) {
            try {
                Object json = new JsonParser(body).parse();
                if (json instanceof Map<?, ?> map) {
                    return stringValue(castStringMap(map).get("message"));
                }
            } catch (Exception ignored) {
                // Body may not be JSON.
            }
            return "";
        }

        private void writeDiagnosticOnce(String key, String title, String detail) {
            if (!writtenDiagnostics.add(key)) {
                return;
            }
            try {
                Path path = config.resolve(config.diagnosticsFile);
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                String text = "[" + Instant.now() + "] " + title + System.lineSeparator()
                    + detail + System.lineSeparator() + System.lineSeparator();
                Files.writeString(path, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ex) {
                Log.warn("Could not write diagnostics file: " + ex.getMessage());
            }
        }

        private GithubRepo repoFromGithubUrl(String value) {
            List<String> parts = pathParts(URI.create(value));
            if (parts.size() < 2) {
                throw new IllegalArgumentException("GitHub URL needs owner/repo: " + value);
            }
            String ref = "";
            if (parts.size() >= 4 && parts.get(2).equalsIgnoreCase("tree")) {
                ref = String.join("/", parts.subList(3, parts.size()));
            }
            return new GithubRepo(parts.get(0), parts.get(1).replace(".git", ""), ref);
        }

        private GithubRepo repoFromGithubValue(String value) {
            if (value.contains("github.com/")) {
                return repoFromGithubUrl(value);
            }
            String[] parts = value.split("/", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("GitHub repo needs Owner/Repo: " + value);
            }
            return new GithubRepo(parts[0], parts[1].replace(".git", ""));
        }

        private String githubSourceUrl(GithubRepo repo) {
            String base = "https://github.com/" + repo.owner + "/" + repo.name;
            String ref = cleanGithubRef(repo.ref);
            return ref.isBlank() ? base : base + "/tree/" + encodePath(ref);
        }

        private HangarProject hangarProjectFromSearch(Map<String, Object> project) {
            Object namespaceObj = project.get("namespace");
            if (namespaceObj instanceof Map<?, ?> rawNamespace) {
                Map<String, Object> namespace = castStringMap(rawNamespace);
                String owner = firstNonBlank(stringValue(namespace.get("owner")), stringValue(namespace.get("ownerName")), stringValue(namespace.get("author")));
                String slug = firstNonBlank(stringValue(namespace.get("slug")), stringValue(namespace.get("project")), stringValue(project.get("slug")), stringValue(project.get("name")));
                if (!owner.isBlank() && !slug.isBlank()) {
                    return new HangarProject(owner, slug);
                }
            } else if (namespaceObj != null) {
                String namespace = stringValue(namespaceObj);
                if (namespace.contains("/")) {
                    String[] parts = namespace.split("/", 2);
                    return new HangarProject(parts[0], parts[1]);
                }
            }
            String owner = firstNonBlank(stringValue(project.get("owner")), stringValue(project.get("ownerName")), stringValue(project.get("author")));
            String slug = firstNonBlank(stringValue(project.get("slug")), stringValue(project.get("name")));
            if (!owner.isBlank() && !slug.isBlank()) {
                return new HangarProject(owner, slug);
            }
            return null;
        }

        private List<String> discoverySearchTerms(TargetConfig target) {
            List<String> terms = new ArrayList<>();
            addSearchTerm(terms, target.detectedPluginId);
            addSearchTerm(terms, target.name);
            addSearchTerm(terms, target.installAs == null ? "" : stripJarName(target.installAs));
            String pluginName = firstNonBlank(target.detectedPluginId, target.name, stripJarName(target.installAs));
            for (String author : authorSearchTerms(target.detectedAuthors)) {
                addSearchTerm(terms, author + " " + pluginName);
                addSearchTerm(terms, author);
            }
            for (String owner : mainClassSearchTerms(target.detectedMainClass)) {
                addSearchTerm(terms, owner + " " + pluginName);
                addSearchTerm(terms, owner);
            }
            return terms;
        }

        private List<String> mainClassSearchTerms(String mainClass) {
            if (mainClass == null || mainClass.isBlank()) {
                return Collections.emptyList();
            }
            List<String> result = new ArrayList<>();
            String[] parts = mainClass.split("\\.");
            for (int i = 0; i < Math.min(parts.length, 4); i++) {
                String part = parts[i].trim();
                String normalized = normalizeIdentity(part);
                if (normalized.isBlank()
                    || normalized.equals("com")
                    || normalized.equals("org")
                    || normalized.equals("net")
                    || normalized.equals("me")
                    || normalized.equals("io")
                    || normalized.equals("java")
                    || normalized.equals("main")) {
                    continue;
                }
                addSearchTerm(result, part);
                if (normalized.equals("superronancraft")) {
                    addSearchTerm(result, "RonanPlugins");
                }
            }
            return result;
        }

        private List<String> authorSearchTerms(String authors) {
            if (authors == null || authors.isBlank()) {
                return Collections.emptyList();
            }
            String cleaned = authors
                .replace("[", " ")
                .replace("]", " ")
                .replace("\"", " ")
                .replace("'", " ");
            List<String> result = new ArrayList<>();
            for (String part : cleaned.split("[,;/|]+")) {
                String author = part.trim();
                if (author.isBlank()) {
                    continue;
                }
                addSearchTerm(result, author);
                String noSeparators = author.replaceAll("[_\\-\\s]+", "");
                if (!noSeparators.equals(author)) {
                    addSearchTerm(result, noSeparators);
                }
            }
            return result;
        }

        private void addSearchTerm(List<String> terms, String value) {
            String cleaned = cleanSearchTerm(value);
            if (cleaned.isBlank()) {
                return;
            }
            if (terms.stream().noneMatch(existing -> existing.equalsIgnoreCase(cleaned))) {
                terms.add(cleaned);
            }
        }

        private String cleanSearchTerm(String value) {
            String cleaned = value == null ? "" : value;
            cleaned = cleaned.replace('\\', '/');
            int slash = cleaned.lastIndexOf('/');
            if (slash >= 0) {
                cleaned = cleaned.substring(slash + 1);
            }
            if (cleaned.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                cleaned = cleaned.substring(0, cleaned.length() - 4);
            }
            cleaned = cleaned.replaceAll("(?i)[-_ ]?(bukkit|paper|spigot|folia|velocity|plugin)$", "");
            cleaned = cleaned.replaceAll("(?i)[-_ ]?v?\\d+(\\.\\d+){0,4}.*$", "");
            return cleaned.trim();
        }

        private String stripJarName(String value) {
            return cleanSearchTerm(value);
        }

        private int nameMatchScore(TargetConfig target, String... candidateValues) {
            List<String> needles = new ArrayList<>();
            addNormalizedName(needles, target.detectedPluginId);
            addNormalizedName(needles, target.name);
            addNormalizedName(needles, stripJarName(target.installAs));
            int best = 0;
            for (String needle : needles) {
                if (needle.length() < 3) {
                    continue;
                }
                for (String value : candidateValues) {
                    String haystack = normalizeName(value);
                    if (haystack.isBlank()) {
                        continue;
                    }
                    if (haystack.equals(needle)) {
                        best = Math.max(best, 60);
                    } else if (haystack.contains(needle) || needle.contains(haystack)) {
                        int containsScore = 20 + Math.min(needle.length(), haystack.length());
                        if (needle.length() >= 6 && (haystack.endsWith(needle) || needle.endsWith(haystack))) {
                            containsScore += 10;
                        }
                        best = Math.max(best, Math.min(45, containsScore));
                    }
                }
            }
            return best;
        }

        private void addNormalizedName(List<String> names, String value) {
            String normalized = normalizeName(cleanSearchTerm(value));
            if (!normalized.isBlank() && names.stream().noneMatch(existing -> existing.equals(normalized))) {
                names.add(normalized);
            }
        }

        private String normalizeName(String value) {
            return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
        }

        private String normalizeNumericId(String value) {
            if (value == null) {
                return "";
            }
            String trimmed = value.trim();
            if (trimmed.matches("\\d+\\.0+")) {
                return trimmed.substring(0, trimmed.indexOf('.'));
            }
            return trimmed;
        }

        private String latestFromLabel(String label) {
            if (label == null || label.isBlank()) {
                return "";
            }
            List<String> tokens = List.of(label.split("\\s+"));
            for (String token : tokens) {
                String cleaned = cleanVersion(token);
                if (!cleaned.isBlank() && cleaned.matches(".*\\d.*")) {
                    return cleaned;
                }
            }
            return "";
        }

        private String latestFromDownload(ResolvedDownload download) {
            if (download == null) {
                return "";
            }
            return firstNonBlank(download.version, latestFromLabel(download.label));
        }

        private boolean isClearlyOlderVersion(String candidate, String local) {
            return comparePluginVersions(candidate, local) == VersionOrder.OLDER;
        }

        boolean relaunchForSelfUpdateIfNeeded(String[] originalArgs, boolean relaunchAfterApply) throws Exception {
            if (!config.selfUpdate.enabled) {
                return false;
            }
            if (firstNonBlank(config.selfUpdate.source, config.selfUpdate.githubRepo).isBlank()) {
                Log.warn("Self-update is enabled but no selfUpdate.source or selfUpdate.githubRepo is configured.");
                return false;
            }
            Path currentJar = currentLauncherJar();
            if (currentJar == null) {
                Log.warn("Self-update skipped because Auto-Updater is not running from a jar file.");
                return false;
            }

            TargetConfig target = new TargetConfig(APP_NAME, false);
            target.source = config.selfUpdate.source;
            target.type = firstNonBlank(config.selfUpdate.type, "auto");
            target.githubRepo = config.selfUpdate.githubRepo;
            target.installAs = isAutoValue(config.selfUpdate.installAs) || config.selfUpdate.installAs.isBlank()
                ? currentJar.toString()
                : config.selfUpdate.installAs;
            if (target.source.isBlank() && !target.githubRepo.isBlank()) {
                target.source = target.githubRepo;
            }

            SourcePlan plan = resolveSource(target);
            if (plan.type.equals("git") || plan.type.equals("github-source")) {
                Log.warn("Self-update source builds are intentionally unsupported; use a release jar or direct jar URL for Auto-Updater itself.");
                return false;
            }

            Log.info("Checking Auto-Updater self-update (" + plan.type + ").");
            ResolvedDownload download = plan.resolver.resolve(target);
            Path selfDir = config.resolve(config.cacheDir).resolve("self-update");
            Files.createDirectories(selfDir);
            Path candidate = selfDir.resolve("auto-updater-candidate-" + System.currentTimeMillis() + ".jar");
            download(download.uri, candidate);
            try {
                validateSelfUpdateJar(candidate);
                if (Files.exists(currentJar) && sha256(currentJar).equalsIgnoreCase(sha256(candidate))) {
                    Files.deleteIfExists(candidate);
                    Log.info("Auto-Updater is already current (" + shortHash(sha256(currentJar)) + ").");
                    return false;
                }
                String currentVersion = selfUpdateJarVersion(currentJar);
                String candidateVersion = selfUpdateJarVersion(candidate);
                if (comparePluginVersions(candidateVersion, currentVersion) == VersionOrder.OLDER) {
                    Files.deleteIfExists(candidate);
                    Log.warn("Self-update candidate is older than the running Auto-Updater (candidate "
                        + candidateVersion + ", current " + currentVersion + "); keeping current jar.");
                    return false;
                }
            } catch (Exception ex) {
                Files.deleteIfExists(candidate);
                throw ex;
            }

            Path helper = selfDir.resolve("auto-updater-self-update-helper-" + System.currentTimeMillis() + ".jar");
            Files.copy(currentJar, helper, StandardCopyOption.REPLACE_EXISTING);
            Log.warn("Auto-Updater update found. Relaunching through helper so " + currentJar.getFileName()
                + " can be replaced safely.");
            SelfUpdater.launchHelper(config, helper, candidate, currentJar, relaunchAfterApply, originalArgs);
            return true;
        }

        private Path currentLauncherJar() {
            try {
                URI uri = AutoUpdater.class.getProtectionDomain().getCodeSource().getLocation().toURI();
                Path path = Paths.get(uri).toAbsolutePath().normalize();
                return Files.isRegularFile(path) && lower(path.getFileName().toString()).endsWith(".jar") ? path : null;
            } catch (Exception ex) {
                return null;
            }
        }

        private void validateSelfUpdateJar(Path jar) throws IOException {
            validateJar(jar);
            try (JarFile file = new JarFile(jar.toFile(), false)) {
                Manifest manifest = file.getManifest();
                String main = manifest == null ? "" : firstNonBlank(
                    manifest.getMainAttributes().getValue("Main-Class"),
                    manifest.getMainAttributes().getValue("Launcher-Agent-Class")
                );
                if (!main.equals("dev.autoupdater.AutoUpdater")
                    && !main.equals("dev.velocityupdater.VelocityAutoUpdater")) {
                    throw new IOException("Self-update jar does not look like Auto-Updater (Main-Class=" + firstNonBlank(main, "missing") + ")");
                }
            }
        }

        private String selfUpdateJarVersion(Path jar) {
            try (JarFile file = new JarFile(jar.toFile(), false)) {
                Manifest manifest = file.getManifest();
                if (manifest == null) {
                    return "";
                }
                return firstNonBlank(
                    manifest.getMainAttributes().getValue("Implementation-Version"),
                    manifest.getMainAttributes().getValue("Specification-Version")
                );
            } catch (IOException ex) {
                return "";
            }
        }

        List<InstalledUpdate> updateAll() throws Exception {
            traceConfigPathOnce("updateAll");
            List<InstalledUpdate> installed = new ArrayList<>();
            Files.createDirectories(config.resolve(config.cacheDir));
            Files.createDirectories(config.resolve(config.backupDir));
            autoSwitchMissingPluginSources();
            for (TargetConfig target : allTargets()) {
                pathDebug(target, "update.target", "begin update pass; enabled=" + target.enabled
                    + "; autoUpdate=" + target.autoUpdate + "; source=" + firstNonBlank(target.source, "blank")
                    + "; origin=" + firstNonBlank(target.sourceOrigin, "blank")
                    + "; type=" + firstNonBlank(target.type, "blank"));
                if (!target.enabled) {
                    pathDebug(target, "update.skip", "target disabled");
                    Log.info("Skipping disabled target: " + target.displayName());
                    continue;
                }
                if (!target.server && !target.autoUpdate) {
                    pathDebug(target, "update.skip", "plugin autoUpdate is false");
                    Log.info("Skipping " + target.displayName() + " because autoUpdate is false.");
                    continue;
                }
                if (isMissingSourceValue(target.source)
                    && !canBuildFromSource(target)) {
                    pathDebug(target, "update.skip", "no source configured and no trusted source build target is available");
                    Log.info("No source configured for " + target.displayName()
                        + (isNotFoundSourceValue(target.source) ? " (discovery marked Not Found)" : "")
                        + "; keeping existing jar.");
                    continue;
                }
                try {
                    Optional<InstalledUpdate> update = updateOne(target);
                    update.ifPresent(installed::add);
                } catch (Exception ex) {
                    Path targetPath = config.resolve(Paths.get(target.installAs));
                    boolean mayContinue = config.onFailure.equals("keep-current") && Files.exists(targetPath);
                    if (!target.required && config.onFailure.equals("keep-current")) {
                        mayContinue = true;
                    }
                    if (mayContinue) {
                        pathDebug(target, "update.failure.keep-current", safeExceptionMessage(ex));
                        Log.warn("Update failed for " + target.displayName() + ": " + safeExceptionMessage(ex));
                        Log.warn("Keeping current jar for " + target.displayName() + ".");
                    } else {
                        pathDebug(target, "update.failure.throw", safeExceptionMessage(ex));
                        throw ex;
                    }
                }
            }
            return installed;
        }

        private List<TargetConfig> allTargets() {
            List<TargetConfig> targets = new ArrayList<>();
            targets.add(config.server);
            targets.addAll(config.plugins);
            return targets;
        }

        private Optional<InstalledUpdate> updateOne(TargetConfig target) throws Exception {
            if (isMissingSourceValue(target.source)) {
                if (canBuildFromSource(target)) {
                    pathDebug(target, "update.source", "no hosted source configured; using trusted Git source build");
                    Log.info("Decision for " + target.displayName()
                        + ": no hosted source is configured, so building from trusted Git source.");
                    return updateOneFromSource(sourceBuildTarget(target));
                }
                pathDebug(target, "update.skip", "no source configured");
                Log.info("No source configured for " + target.displayName()
                    + (isNotFoundSourceValue(target.source) ? " (discovery marked Not Found)" : "")
                    + "; keeping existing jar.");
                return Optional.empty();
            }
            List<String> sources = new ArrayList<>();
            sources.add(target.source);
            sources.addAll(target.fallbackSources);
            pathDebug(target, "update.sources", "source order=" + String.join(" -> ", sources));
            Exception last = null;
            Set<String> attemptedSources = new HashSet<>();
            boolean gitBuildAttempted = false;
            if (shouldBuildExplicitPrimarySourceFirst(target)) {
                attemptedSources.add(canonicalSourceMatchKey(target.source));
                gitBuildAttempted = true;
                try {
                    pathDebug(target, "update.source-build.primary", "explicit primary source type " + lower(target.type));
                    Log.info("Decision for " + target.displayName()
                        + ": primary source is explicitly configured as " + lower(target.type) + ", so building it before hosted fallbacks.");
                    return updateOneFromSource(target);
                } catch (Exception ex) {
                    last = ex;
                    pathDebug(target, "update.source-build.primary.failed", safeExceptionMessage(ex));
                    Log.warn("Primary Git source failed for " + target.displayName()
                        + "; trying configured fallback source(s): " + safeExceptionMessage(ex));
                }
            }
            List<HostedCandidate> hostedCandidates = resolveFreshestHostedCandidates(target, sources);
            pathDebug(target, "update.hosted-candidates", "resolved " + hostedCandidates.size() + " hosted candidate(s)"
                + (hostedCandidates.isEmpty() ? "" : ": " + hostedCandidates.stream()
                    .map(this::sourceLabel)
                    .collect(Collectors.joining(", "))));
            if (!hostedCandidates.isEmpty()) {
                Optional<Instant> latestCommit = latestGitHubCommitTime(target);
                for (int i = 0; i < hostedCandidates.size(); i++) {
                    HostedCandidate candidate = hostedCandidates.get(i);
                    attemptedSources.add(canonicalSourceMatchKey(candidate.target.source));
                    if (!gitBuildAttempted && shouldBuildFromNewerGitSource(target, candidate, latestCommit)) {
                        gitBuildAttempted = true;
                        try {
                            pathDebug(target, "update.source-build.newer-git", "latest Git commit is preferred over " + sourceLabel(candidate));
                            return updateOneFromSource(sourceBuildTarget(target));
                        } catch (Exception ex) {
                            last = ex;
                            pathDebug(target, "update.source-build.newer-git.failed", safeExceptionMessage(ex));
                            Log.warn("Git source build failed for " + target.displayName()
                                + "; falling back to hosted jar " + sourceLabel(candidate) + ": " + ex.getMessage());
                        }
                    }
                    try {
                        if (i == 0) {
                            logHostedDecision(target, candidate, latestCommit);
                        } else {
                            pathDebug(target, "update.hosted.alternate", "trying alternate hosted source " + (i + 1)
                                + " of " + hostedCandidates.size() + ": " + sourceLabel(candidate));
                            Log.warn("Trying alternate hosted source " + (i + 1) + " of "
                                + hostedCandidates.size() + " for " + target.displayName()
                                + ": " + sourceLabel(candidate) + ".");
                        }
                        return installResolvedDownload(candidate.target, candidate.plan, candidate.download);
                    } catch (Exception ex) {
                        last = ex;
                        pathDebug(target, "update.hosted.failed", sourceLabel(candidate) + " -> " + safeExceptionMessage(ex));
                        if (i + 1 < hostedCandidates.size()) {
                            Log.warn("Hosted source failed for " + target.displayName() + ": " + safeExceptionMessage(ex));
                        }
                    }
                }
            }
            for (int i = 0; i < sources.size(); i++) {
                TargetConfig candidate = i == 0 ? target : target.copyWithSource(sources.get(i));
                if (attemptedSources.contains(canonicalSourceMatchKey(candidate.source))) {
                    pathDebug(target, "update.source.skip", "already attempted " + candidate.source);
                    continue;
                }
                try {
                    pathDebug(target, "update.source.try", "trying source " + (i + 1) + " of " + sources.size() + ": " + candidate.source);
                    return updateOneFromSource(candidate);
                } catch (Exception ex) {
                    last = ex;
                    pathDebug(target, "update.source.failed", candidate.source + " -> " + safeExceptionMessage(ex));
                    if (i + 1 < sources.size()) {
                        Log.warn("Source failed for " + target.displayName() + ": " + safeExceptionMessage(ex));
                        Log.warn("Trying fallback source " + (i + 2) + " of " + sources.size() + ".");
                    }
                }
            }
            if (!gitBuildAttempted && shouldTrySourceBuildFallback(target, sources)) {
                try {
                    pathDebug(target, "update.source-build.fallback", "hosted sources failed; trying trusted Git source build");
                    Log.warn("Decision for " + target.displayName()
                        + ": hosted sources failed to resolve, so trying trusted Git source build.");
                    return updateOneFromSource(sourceBuildTarget(target));
                } catch (Exception ex) {
                    last = ex;
                    pathDebug(target, "update.source-build.fallback.failed", safeExceptionMessage(ex));
                }
            }
            if (last instanceof KnownBadPluginUpdateException) {
                pathDebug(target, "update.known-bad", last.getMessage());
                Log.warn(last.getMessage() + "; keeping current jar.");
                return Optional.empty();
            }
            throw last == null ? new IOException("No source configured for " + target.displayName()) : last;
        }

        private boolean shouldBuildExplicitPrimarySourceFirst(TargetConfig target) {
            if (target == null || target.server || isMissingSourceValue(target.source)) {
                return false;
            }
            String type = lower(firstNonBlank(target.type, ""));
            return (type.equals("github-source") || type.equals("git")) && canBuildFromSource(target);
        }

        private boolean shouldTrySourceBuildFallback(TargetConfig target, List<String> triedSources) {
            if (!config.buildFromSource.autoFallback() || !canBuildFromSource(target)) {
                return false;
            }
            for (String source : triedSources) {
                String type = detectType(source, target);
                if (type.equals("git") || type.equals("github-source")) {
                    return false;
                }
            }
            return true;
        }

        private List<HostedCandidate> resolveFreshestHostedCandidates(TargetConfig target, List<String> sources) {
            if (!config.buildFromSource.autoFallback()
                || !config.buildFromSource.preferHostedIfSameVersion
                || !canBuildFromSource(target)) {
                return Collections.emptyList();
            }
            HostedCandidate freshest = null;
            int resolvedCount = 0;
            List<HostedCandidate> datedCandidates = new ArrayList<>();
            for (int i = 0; i < sources.size(); i++) {
                TargetConfig candidate = i == 0 ? target : target.copyWithSource(sources.get(i));
                SourcePlan plan;
                try {
                    plan = resolveSource(candidate);
                } catch (Exception ex) {
                    Log.warn("Hosted metadata lookup skipped for " + target.displayName()
                        + " source " + (i + 1) + ": " + ex.getMessage());
                    continue;
                }
                if (plan.type.equals("git") || plan.type.equals("github-source")) {
                    continue;
                }
                try {
                    ResolvedDownload download = plan.resolver.resolve(candidate);
                    resolvedCount++;
                    if (download.publishedAt == null) {
                        continue;
                    }
                    HostedCandidate hosted = new HostedCandidate(candidate, plan, download);
                    datedCandidates.add(hosted);
                    if (freshest == null || download.publishedAt.isAfter(freshest.download.publishedAt)) {
                        freshest = hosted;
                    }
                } catch (Exception ex) {
                    Log.warn("Hosted metadata lookup failed for " + target.displayName()
                        + " source " + (i + 1) + ": " + ex.getMessage());
                }
            }
            if (freshest == null) {
                return Collections.emptyList();
            }
            datedCandidates.sort((a, b) -> b.download.publishedAt.compareTo(a.download.publishedAt));
            if (datedCandidates.size() > 1) {
                Log.info("Hosted freshness for " + target.displayName() + ": "
                    + describeHostedCandidates(datedCandidates) + "; freshest is "
                    + sourceLabel(freshest) + ".");
            }
            return datedCandidates;
        }

        private boolean canBuildFromSource(TargetConfig target) {
            if (!config.buildFromSource.allowsBuild() || target.server) {
                return false;
            }
            String repo = gitRepoHint(target);
            return !repo.isBlank() && (!config.buildFromSource.onlyTrusted || isTrustedGithubRepo(repo)
                || githubSourceDescriptorEvidence(target, "github-source", firstNonBlank(target.source, repo), repo) == SourceDescriptorEvidence.MATCH);
        }

        private TargetConfig sourceBuildTarget(TargetConfig target) {
            TargetConfig copy = target.copyWithSource(sourceBuildSource(target));
            copy.type = "github-source";
            return copy;
        }

        private String sourceBuildSource(TargetConfig target) {
            String source = firstNonBlank(target.source, "");
            if (!githubRepoHintFromText(source).isBlank()) {
                return source;
            }
            return firstNonBlank(target.githubRepo, target.project, source, "");
        }

        private boolean shouldBuildFromNewerGitSource(TargetConfig target, HostedCandidate hosted, Optional<Instant> latestCommit) {
            if (!config.buildFromSource.autoFallback()
                || !config.buildFromSource.preferHostedIfSameVersion
                || !canBuildFromSource(target)) {
                pathDebug(target, "git-preference.skip", "source build fallback disabled, preferHostedIfSameVersion disabled, or no trusted Git source available");
                return false;
            }
            if (hosted.plan.type.equals("git") || hosted.plan.type.equals("github-source")) {
                pathDebug(target, "git-preference.skip", "candidate is already a Git/source build plan");
                return false;
            }
            if (isManualConfiguredSource(target) && !lower(firstNonBlank(target.type, "")).equals("github-source")) {
                pathDebug(target, "git-preference.skip", "configured source is manual; Git commits are not preferred unless type=github-source");
                Log.info("Decision for " + target.displayName() + ": using hosted jar because the configured source is manual; "
                    + "newer Git commits are not built unless the entry explicitly uses type=github-source.");
                return false;
            }
            if (hosted.download.publishedAt == null || latestCommit.isEmpty()) {
                pathDebug(target, "git-preference.skip", "missing hosted publish time or latest Git commit time");
                return false;
            }
            Instant hostedTime = hosted.download.publishedAt;
            Instant commitTime = latestCommit.get();
            if (commitTime.isAfter(hostedTime.plus(Duration.ofMinutes(5)))) {
                pathDebug(target, "git-preference.use-git", "latest commit " + commitTime
                    + " is newer than hosted " + sourceLabel(hosted));
                Log.warn("Decision for " + target.displayName() + ": building from Git because latest commit "
                    + commitTime + " is newer than freshest hosted source " + sourceLabel(hosted) + ".");
                return true;
            }
            pathDebug(target, "git-preference.use-hosted", "hosted source is fresh enough compared with latest commit " + commitTime);
            return false;
        }

        private void logHostedDecision(TargetConfig target, HostedCandidate hosted, Optional<Instant> latestCommit) {
            if (latestCommit.isPresent() && hosted.download.publishedAt != null) {
                Log.info("Decision for " + target.displayName() + ": using " + sourceLabel(hosted)
                    + " because it is at least as fresh as latest Git commit " + latestCommit.get() + ".");
                return;
            }
            if (hosted.download.publishedAt != null) {
                Log.info("Decision for " + target.displayName() + ": using freshest hosted source "
                    + sourceLabel(hosted) + "; Git freshness could not be confirmed.");
                return;
            }
            Log.info("Decision for " + target.displayName() + ": using hosted source "
                + hosted.plan.type + "; no comparable publish time was available.");
        }

        private String describeHostedCandidates(List<HostedCandidate> candidates) {
            return candidates.stream()
                .sorted((a, b) -> b.download.publishedAt.compareTo(a.download.publishedAt))
                .map(this::sourceLabel)
                .collect(Collectors.joining(", "));
        }

        private String sourceLabel(HostedCandidate hosted) {
            String version = firstNonBlank(hosted.download.version, latestFromLabel(hosted.download.label));
            String date = hosted.download.publishedAt == null ? "unknown date" : hosted.download.publishedAt.toString();
            if (version.isBlank()) {
                return hosted.plan.type + "@" + date;
            }
            return hosted.plan.type + " " + version + "@" + date;
        }

        private Optional<Instant> latestGitHubCommitTime(TargetConfig target) {
            String value = gitRepoHint(target);
            if (value.isBlank()) {
                return Optional.empty();
            }
            try {
                GithubRepo repo = repoFromGithubValue(value);
                return latestGitHubCommitTime(repo, target, target.displayName());
            } catch (Exception ex) {
                Log.warn("GitHub commit lookup failed for " + target.displayName() + ": " + ex.getMessage());
                return Optional.empty();
            }
        }

        private Optional<Instant> latestGitHubCommitTime(GithubRepo repo, TargetConfig target, String context) {
            try {
                URI uri = URI.create("https://api.github.com/repos/"
                    + urlEncode(repo.owner) + "/" + urlEncode(repo.name) + "/commits?per_page=1");
                Optional<String> fresh = readGithubApiCache(config, uri, GITHUB_CACHE_FRESH);
                if (fresh.isPresent()) {
                    return commitTimeFromGitHubResponse(fresh.get());
                }
                if (githubAuthFailed) {
                    Optional<String> stale = readGithubApiCache(config, uri, GITHUB_CACHE_STALE);
                    if (stale.isPresent()) {
                        return commitTimeFromGitHubResponse(stale.get());
                    }
                    return Optional.empty();
                }
                if (config.githubRateLimit.isPaused()) {
                    Optional<String> stale = readGithubApiCache(config, uri, GITHUB_CACHE_STALE);
                    if (stale.isPresent()) {
                        return commitTimeFromGitHubResponse(stale.get());
                    }
                    return Optional.empty();
                }
                enforceGithubBudget(target, "GitHub commit lookup", uri);
                HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(DISCOVERY_HTTP_TIMEOUT)
                    .header("User-Agent", config.userAgent)
                    .header("Accept", "application/vnd.github+json");
                applyGithubAuth(builder, config, uri);
                HttpRequest request = builder.GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if (status < 200 || status >= 300) {
                    if (status == 401) {
                        Optional<HttpResponse<String>> unauthenticated = retryGithubUnauthenticated(
                            config, client, uri, DISCOVERY_HTTP_TIMEOUT, "application/vnd.github+json", "GitHub commit lookup");
                        if (unauthenticated.isPresent()) {
                            HttpResponse<String> retry = unauthenticated.get();
                            observeGithubRateHeaders("GitHub commit lookup", retry);
                            writeGithubApiCache(config, uri, retry.body());
                            return commitTimeFromGitHubResponse(retry.body());
                        }
                        handleGithubAuthFailure("GitHub commit lookup", uri, response);
                        Optional<String> stale = readGithubApiCache(config, uri, GITHUB_CACHE_STALE);
                        if (stale.isPresent()) {
                            return commitTimeFromGitHubResponse(stale.get());
                        }
                        return Optional.empty();
                    }
                    if (status == 403 || status == 429) {
                        String diagnosis = github403Diagnosis("GitHub commit lookup", uri, response);
                        Log.warn("GitHub commit lookup failed with HTTP " + status + " for " + repo.owner + "/" + repo.name + ": " + diagnosis);
                        Optional<String> stale = readGithubApiCache(config, uri, GITHUB_CACHE_STALE);
                        if (stale.isPresent()) {
                            return commitTimeFromGitHubResponse(stale.get());
                        }
                        return Optional.empty();
                    }
                    Log.warn("GitHub commit lookup failed with HTTP " + status + " for " + repo.owner + "/" + repo.name);
                    return Optional.empty();
                }
                observeGithubRateHeaders("GitHub commit lookup", response);
                writeGithubApiCache(config, uri, response.body());
                return commitTimeFromGitHubResponse(response.body());
            } catch (Exception ex) {
                Log.warn("GitHub commit lookup failed for " + context + ": " + ex.getMessage());
                return Optional.empty();
            }
        }

        private Optional<Instant> commitTimeFromGitHubResponse(String body) {
            Object json = new JsonParser(body).parse();
            if (!(json instanceof List<?> commits) || commits.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Object> first = asMap(commits.get(0));
            Map<String, Object> commit = asMap(first.get("commit"));
            Map<String, Object> committer = asMap(commit.get("committer"));
            Map<String, Object> author = asMap(commit.get("author"));
            Instant time = parseInstantOrNull(firstNonBlank(
                stringValue(committer.get("date")),
                stringValue(author.get("date"))
            ));
            return Optional.ofNullable(time);
        }

        private String gitRepoHint(TargetConfig target) {
            String source = firstNonBlank(target.source, "");
            String sourceRepo = githubRepoHintFromText(source);
            if (!sourceRepo.isBlank()) {
                return sourceRepo;
            }
            String configuredType = lower(firstNonBlank(target.type, ""));
            if (isExplicitNonGithubUrl(source) && !configuredType.equals("github-source") && !configuredType.equals("git")) {
                return "";
            }
            return githubRepoHintFromText(firstNonBlank(target.githubRepo, target.project, ""));
        }

        private String githubRepoHintFromText(String value) {
            value = firstNonBlank(value, "");
            if (value.contains("github.com/")) {
                try {
                    GithubRepo repo = repoFromGithubUrl(value);
                    return repo.owner + "/" + repo.name;
                } catch (Exception ignored) {
                    return "";
                }
            }
            if (value.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
                return value.replace(".git", "");
            }
            return "";
        }

        private boolean isExplicitNonGithubUrl(String source) {
            String value = lower(firstNonBlank(source, ""));
            return (value.startsWith("http://") || value.startsWith("https://")) && !value.contains("github.com/");
        }

        private Optional<InstalledUpdate> updateOneFromSource(TargetConfig target) throws Exception {
            SourcePlan plan = resolveSource(target);
            pathDebug(target, "resolve.source", "resolved " + firstNonBlank(target.source, "blank") + " as " + plan.type);
            ResolvedDownload download = plan.resolver.resolve(target);
            pathDebug(target, "resolve.download", "resolved download uri=" + download.uri
                + "; version=" + firstNonBlank(download.version, "unknown")
                + "; publishedAt=" + (download.publishedAt == null ? "unknown" : download.publishedAt.toString()));
            if (!plan.type.equals("git") && !plan.type.equals("github-source")
                && shouldBuildFromNewerGitSource(target, new HostedCandidate(target, plan, download), latestGitHubCommitTime(target))) {
                try {
                    pathDebug(target, "update.source-build.newer-git", "hosted plan resolved, but newer Git source is preferred");
                    return updateOneFromSource(sourceBuildTarget(target));
                } catch (Exception ex) {
                    pathDebug(target, "update.source-build.newer-git.failed", safeExceptionMessage(ex));
                    Log.warn("Git source build failed for " + target.displayName()
                        + "; falling back to hosted jar: " + ex.getMessage());
                }
            }
            return installResolvedDownload(target, plan, download);
        }

        private Optional<InstalledUpdate> installResolvedDownload(TargetConfig target, SourcePlan plan, ResolvedDownload download) throws Exception {
            Log.info("Checking " + target.displayName() + " (" + plan.type + ")");
            pathDebug(target, "install.check", "plan=" + plan.type + "; uri=" + download.uri
                + "; version=" + firstNonBlank(download.version, "unknown")
                + "; project=" + firstNonBlank(download.project, "unknown"));
            Path targetPath = config.resolve(Paths.get(target.installAs));
            Path stagingDir = config.resolve(config.cacheDir).resolve("staging");
            Files.createDirectories(stagingDir);
            Path staging = stagingDir.resolve(safeName(target.displayName()) + "-" + System.currentTimeMillis() + ".jar");

            download(download.uri, staging);
            pathDebug(target, "install.downloaded", "staged at " + staging);
            try {
                validateJar(staging);
                validateDownloadedJar(target, plan, download, staging, targetPath);
            } catch (Exception ex) {
                Files.deleteIfExists(staging);
                pathDebug(target, "install.validation.failed", safeExceptionMessage(ex));
                throw ex;
            }

            String newHash = sha256(staging);
            Optional<BadPluginVersion> knownBad = knownBadPlugin(target, download, newHash);
            if (knownBad.isPresent()) {
                Files.deleteIfExists(staging);
                BadPluginVersion bad = knownBad.get();
                pathDebug(target, "install.known-bad", "version=" + firstNonBlank(bad.version, "unknown")
                    + "; sha256=" + firstNonBlank(bad.sha256, newHash));
                throw new KnownBadPluginUpdateException("Skipping known-bad update for " + target.displayName()
                    + " (" + firstNonBlank(bad.version, shortHash(bad.sha256)) + ")");
            }
            if (Files.exists(targetPath)) {
                String oldHash = sha256(targetPath);
                if (oldHash.equalsIgnoreCase(newHash)) {
                    Files.deleteIfExists(staging);
                    Log.info(target.displayName() + " is already current (" + shortHash(newHash) + ").");
                    pathDebug(target, "install.current", "candidate hash matches installed jar " + shortHash(newHash));
                    quarantineDuplicatePluginJars(target, targetPath);
                    updateLockIfNeeded(target, download);
                    return Optional.empty();
                }
            }

            ServerLockSnapshot previousServerLock = target.server ? currentServerLockSnapshot() : null;
            Path backupPath = Files.exists(targetPath) ? backup(targetPath) : null;
            Path parent = targetPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            moveReplace(staging, targetPath);
            Log.info("Installed " + target.displayName() + " -> " + targetPath + " (" + shortHash(newHash) + ")");
            pathDebug(target, "install.installed", "installed " + targetPath + "; backup="
                + (backupPath == null ? "none" : backupPath.toString()) + "; hash=" + shortHash(newHash));
            quarantineDuplicatePluginJars(target, targetPath);
            updateLockIfNeeded(target, download);
            String installedVersion = downloadVersion(download);
            try {
                PluginJarInfo installedInfo = readPluginJarInfo(targetPath);
                installedVersion = firstNonBlank(installedInfo.version, installedVersion);
            } catch (Exception ignored) {
                // The jar already passed validation; keep the resolver version if rereading metadata fails.
            }
            return Optional.of(new InstalledUpdate(target, targetPath, backupPath, target.source, installedVersion, newHash,
                download.project, download.gameVersion, download.build, previousServerLock));
        }

        private ServerLockSnapshot currentServerLockSnapshot() {
            LockState state = LockState.read(config);
            return new ServerLockSnapshot(state.serverProject, state.serverGameVersion, state.serverBuild);
        }

        private Optional<BadPluginVersion> knownBadPlugin(TargetConfig target, ResolvedDownload download, String sha256) {
            LockState lock = LockState.read(config);
            return lock.activeBadPlugin(config, target, download, sha256);
        }

        private void validateDownloadedJar(TargetConfig target, SourcePlan plan, ResolvedDownload download, Path staging, Path targetPath) throws IOException {
            if (!config.validation.enabled || target.server) {
                pathDebug(target, "validation.skip", target.server ? "server target" : "validation disabled");
                return;
            }
            PluginJarInfo incoming = readPluginJarInfo(staging);
            pathDebug(target, "validation.incoming", "descriptor=" + incoming.descriptorSummary()
                + "; name=" + firstNonBlank(incoming.name, incoming.id, "unknown")
                + "; version=" + firstNonBlank(incoming.version, "unknown")
                + "; foliaSupported=" + incoming.foliaSupported);
            if (!incoming.hasDescriptor) {
                throw new IOException("Downloaded jar for " + target.displayName()
                    + " has no plugin descriptor (plugin.yml, paper-plugin.yml, velocity-plugin.json, or bungee.yml)");
            }

            PluginJarInfo current = Files.exists(targetPath) ? readPluginJarInfo(targetPath) : null;
            if (current != null && current.hasDescriptor) {
                pathDebug(target, "validation.current", "descriptor=" + current.descriptorSummary()
                    + "; name=" + firstNonBlank(current.name, current.id, "unknown")
                    + "; version=" + firstNonBlank(current.version, "unknown")
                    + "; foliaSupported=" + current.foliaSupported);
            }
            String actualServerProject = inferPaperMcProject(config.server);
            String expectedPlatform = firstNonBlank(inferredPluginPlatform(config.server), lower(target.platform), lower(target.loader));
            if (current != null && current.hasDescriptor) {
                VersionOrder versionOrder = comparePluginVersions(incoming.version, current.version);
                if (versionOrder == VersionOrder.OLDER) {
                    throw new IOException("Downloaded jar for " + target.displayName()
                        + " would downgrade plugin version from " + current.version + " to " + incoming.version);
                }
                if (versionOrder == VersionOrder.UNKNOWN && hostedJarLooksOlderThanInstalled(download, targetPath)) {
                    throw new IOException("Downloaded jar for " + target.displayName()
                        + " is from " + download.publishedAt
                        + " but installed jar was modified at " + installedJarTime(targetPath)
                        + "; keeping the newer-looking installed jar because versions are not comparable");
                }
            }
            if (config.validation.rejectWrongPlatform && !incoming.supportsPlatform(expectedPlatform)) {
                throw new IOException("Downloaded jar for " + target.displayName()
                    + " looks like " + incoming.descriptorSummary() + " but this server expects " + firstNonBlank(expectedPlatform, "a compatible") + " plugins");
            }
            if (config.validation.rejectWrongPlatform
                && actualServerProject.equals("folia")
                && (incoming.descriptorTypes.contains("paper") || incoming.descriptorTypes.contains("bukkit"))
                && !Boolean.TRUE.equals(incoming.foliaSupported)) {
                if (current != null && Boolean.TRUE.equals(current.foliaSupported)) {
                    throw new IOException("Downloaded jar for " + target.displayName()
                        + " would downgrade Folia support; installed jar is marked Folia-compatible but the candidate is not");
                }
                if (!isManualConfiguredSource(target)) {
                    throw new IOException("Downloaded jar for " + target.displayName()
                        + " does not prove Folia support; auto-discovered Folia updates require folia-supported: true");
                }
                Log.warn("Downloaded jar for " + target.displayName()
                    + " does not declare Folia support; allowing it because the source is manually configured.");
            }

            if (config.validation.rejectOnPluginNameMismatch && current != null && current.hasDescriptor) {
                int identity = maxIdentitySimilarity(current, incoming);
                if (identity < 70) {
                    throw new IOException("Downloaded jar identity does not match installed plugin for "
                        + target.displayName() + " (installed=" + current.name + ", downloaded="
                        + incoming.name + ", match=" + identity + "%)");
                }
            }
            if (config.validation.rejectOnPluginFingerprintMismatch && current != null && current.hasDescriptor) {
                String mismatch = pluginFingerprintMismatchReason(target, current, incoming, download);
                if (!mismatch.isBlank()) {
                    throw new IOException("Downloaded jar fingerprint does not match installed plugin for "
                        + target.displayName() + ": " + mismatch);
                }
            }

            int score = validationScore(target, current, incoming, download);
            int minimum = validationMinimum(target, plan);
            pathDebug(target, "validation.score", "score=" + score + "; minimum=" + minimum
                + "; parts=" + validationScoreBreakdown(target, current, incoming, download)
                + "; expectedPlatform=" + firstNonBlank(expectedPlatform, "unknown")
                + "; actualServerProject=" + firstNonBlank(actualServerProject, "unknown"));
            if (score < minimum) {
                throw new IOException("Downloaded jar for " + target.displayName()
                    + " failed validation score " + score + "/" + minimum
                    + " (downloaded plugin=" + firstNonBlank(incoming.name, incoming.id, "unknown") + ")");
            }
            Log.info("Validated " + target.displayName() + " candidate as "
                + firstNonBlank(incoming.name, incoming.id) + " (" + incoming.descriptorSummary()
                + ", score " + score + ")");
        }

        private boolean hostedJarLooksOlderThanInstalled(ResolvedDownload download, Path targetPath) {
            if (download == null || download.publishedAt == null || !Files.isRegularFile(targetPath)) {
                return false;
            }
            Instant installed = installedJarTime(targetPath);
            return installed != null && installed.isAfter(download.publishedAt.plus(Duration.ofMinutes(5)));
        }

        private Instant installedJarTime(Path targetPath) {
            try {
                return Files.getLastModifiedTime(targetPath).toInstant();
            } catch (IOException ex) {
                return null;
            }
        }

        private int validationMinimum(TargetConfig target, SourcePlan plan) {
            boolean trusted = sourceOriginAllowsTrustedValidation(target)
                && (isTrustedHostedSource(target, plan) || !target.fallbackSources.contains(target.source));
            return trusted ? config.validation.minTrustedSourceScore : config.validation.minAutoInstallScore;
        }

        private boolean sourceOriginAllowsTrustedValidation(TargetConfig target) {
            if (target == null || target.autoDiscovered || target.sourceDiscoveredThisRun) {
                return false;
            }
            String origin = lower(firstNonBlank(target.sourceOrigin, ""));
            if (origin.isBlank() || origin.equals(SOURCE_ORIGIN_MANUAL)) {
                return true;
            }
            if (origin.equals(SOURCE_ORIGIN_DISCOVERED)) {
                Optional<SourceProof> proof = lockState.activeSourceProof(target);
                return proof.isPresent() && sourcesMatchLoosely(proof.get().source, target.source);
            }
            return false;
        }

        private String pluginFingerprintMismatchReason(TargetConfig target, PluginJarInfo current,
                                                       PluginJarInfo incoming, ResolvedDownload download) {
            boolean authorKnown = !current.authors.isBlank() && !incoming.authors.isBlank();
            boolean authorConflict = authorKnown && !normalizedAuthorTokensOverlap(current.authors, incoming.authors);
            boolean packageKnown = !current.mainClass.isBlank() && !incoming.mainClass.isBlank();
            boolean packageConflict = packageKnown && packageSimilarityScore(current.mainClass, incoming.mainClass) == 0;
            boolean websiteKnown = !current.website.isBlank() && !incoming.website.isBlank();
            boolean websiteConflict = websiteKnown && !sameHost(current.website, incoming.website);
            boolean sourceOwnerConflict = sourceOwnerConflictsWithInstalledAuthor(target, download, current);

            if (pluginNamesConflict(current, incoming)
                && !current.mainClass.equalsIgnoreCase(incoming.mainClass)) {
                return "installed plugin name " + firstNonBlank(current.name, current.id)
                    + " does not match downloaded plugin name " + firstNonBlank(incoming.name, incoming.id);
            }
            if (authorConflict) {
                return "installed authors " + current.authors
                    + " do not match downloaded authors " + incoming.authors;
            }
            if (packageConflict && sourceOwnerConflict) {
                return "downloaded main class " + incoming.mainClass
                    + " is unrelated to installed main class " + current.mainClass
                    + " and source owner does not match installed author " + current.authors;
            }
            if (packageConflict && !authorKnown && !websiteKnown) {
                return "installed jar has no author/website metadata, and downloaded main class "
                    + incoming.mainClass + " is unrelated to installed main class " + current.mainClass;
            }
            return "";
        }

        private boolean isTrustedHostedSource(TargetConfig target, SourcePlan plan) {
            String type = lower(plan.type);
            if (type.equals("github-release") || type.equals("github-source")) {
                String repo = gitRepoHint(target);
                return !repo.isBlank() && isTrustedGithubRepo(repo);
            }
            return sourceOriginAllowsTrustedValidation(target) && target.source != null && !target.source.isBlank();
        }

        private int validationScore(TargetConfig target, PluginJarInfo current, PluginJarInfo incoming, ResolvedDownload download) {
            int score = 0;
            if (current != null && current.hasDescriptor) {
                int identity = maxIdentitySimilarity(current, incoming);
                score += Math.round(identity * 0.60f);
                if (!current.mainClass.isBlank() && !incoming.mainClass.isBlank()) {
                    score += current.mainClass.equalsIgnoreCase(incoming.mainClass) ? 15 : packageSimilarityScore(current.mainClass, incoming.mainClass);
                }
                if (incoming.supportsPlatform(inferredPluginPlatform(config.server))) {
                    score += 10;
                }
                if (!current.authors.isBlank() && !incoming.authors.isBlank() && normalizedAuthorTokensOverlap(current.authors, incoming.authors)) {
                    score += 5;
                }
                if (!current.website.isBlank() && !incoming.website.isBlank() && sameHost(current.website, incoming.website)) {
                    score += 5;
                }
                if (!current.dependencies.isBlank() && !incoming.dependencies.isBlank() && normalizedTokensOverlap(current.dependencies, incoming.dependencies)) {
                    score += 3;
                }
            } else {
                int identity = maxIdentitySimilarity(target, incoming);
                score += Math.round(identity * 0.65f);
                if (incoming.supportsPlatform(inferredPluginPlatform(config.server))) {
                    score += 20;
                }
                if (incoming.hasDescriptor) {
                    score += 10;
                }
            }
            if (download != null && !download.version.isBlank() && !incoming.version.isBlank()
                && cleanVersion(download.version).equalsIgnoreCase(cleanVersion(incoming.version))) {
                score += 3;
            }
            if (sourceTextMatchesPlugin(target, incoming)) {
                score += 7;
            }
            return Math.min(100, score);
        }

        private String validationScoreBreakdown(TargetConfig target, PluginJarInfo current, PluginJarInfo incoming, ResolvedDownload download) {
            List<String> parts = new ArrayList<>();
            if (current != null && current.hasDescriptor) {
                int identity = maxIdentitySimilarity(current, incoming);
                parts.add("installedIdentity=" + identity + "%=>" + signedScore(Math.round(identity * 0.60f)));
                if (!current.mainClass.isBlank() && !incoming.mainClass.isBlank()) {
                    int packageScore = current.mainClass.equalsIgnoreCase(incoming.mainClass) ? 15 : packageSimilarityScore(current.mainClass, incoming.mainClass);
                    parts.add("mainClass=" + signedScore(packageScore));
                }
                if (incoming.supportsPlatform(inferredPluginPlatform(config.server))) {
                    parts.add("platform=+10");
                }
                if (!current.authors.isBlank() && !incoming.authors.isBlank() && normalizedAuthorTokensOverlap(current.authors, incoming.authors)) {
                    parts.add("authors=+5");
                }
                if (!current.website.isBlank() && !incoming.website.isBlank() && sameHost(current.website, incoming.website)) {
                    parts.add("website=+5");
                }
                if (!current.dependencies.isBlank() && !incoming.dependencies.isBlank() && normalizedTokensOverlap(current.dependencies, incoming.dependencies)) {
                    parts.add("dependencies=+3");
                }
            } else {
                int identity = maxIdentitySimilarity(target, incoming);
                parts.add("targetIdentity=" + identity + "%=>" + signedScore(Math.round(identity * 0.65f)));
                if (incoming.supportsPlatform(inferredPluginPlatform(config.server))) {
                    parts.add("platform=+20");
                }
                if (incoming.hasDescriptor) {
                    parts.add("descriptor=+10");
                }
            }
            if (download != null && !download.version.isBlank() && !incoming.version.isBlank()
                && cleanVersion(download.version).equalsIgnoreCase(cleanVersion(incoming.version))) {
                parts.add("resolverVersionMatchesDescriptor=+3");
            }
            if (sourceTextMatchesPlugin(target, incoming)) {
                parts.add("sourceTextMatchesPlugin=+7");
            }
            return String.join(", ", parts);
        }

        private void quarantineDuplicatePluginJars(TargetConfig target, Path targetPath) throws IOException {
            if (!config.duplicates.enabled || target.server || !Files.isRegularFile(targetPath)) {
                return;
            }
            if (!lower(config.duplicates.action).equals("quarantine")) {
                return;
            }
            Path pluginDir = targetPath.getParent();
            if (pluginDir == null || !Files.isDirectory(pluginDir)) {
                return;
            }
            PluginJarInfo installed = readPluginJarInfo(targetPath);
            Set<String> installedKeys = pluginIdentityKeys(installed);
            if (installedKeys.isEmpty()) {
                return;
            }
            Path canonicalTarget = targetPath.toAbsolutePath().normalize();
            for (Path jar : listJarFiles(pluginDir)) {
                Path canonicalJar = jar.toAbsolutePath().normalize();
                if (canonicalJar.equals(canonicalTarget)) {
                    continue;
                }
                PluginJarInfo other = readPluginJarInfo(jar);
                if (Collections.disjoint(installedKeys, pluginIdentityKeys(other))) {
                    continue;
                }
                Path quarantined = quarantineDuplicate(jar);
                Log.warn("Quarantined duplicate plugin jar for " + installed.name + ": "
                    + jar.getFileName() + " -> " + quarantined);
            }
        }

        void quarantineAllDuplicatePluginJars() throws IOException {
            if (!config.duplicates.enabled || !lower(config.duplicates.action).equals("quarantine")) {
                return;
            }
            Path pluginDir = config.resolve(Paths.get("plugins"));
            if (!Files.isDirectory(pluginDir)) {
                return;
            }
            Map<String, List<PluginJarCandidate>> byIdentity = new LinkedHashMap<>();
            for (Path jar : listJarFiles(pluginDir)) {
                PluginJarInfo info = readPluginJarInfo(jar);
                for (String key : pluginIdentityKeys(info)) {
                    byIdentity.computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(new PluginJarCandidate(jar, info));
                }
            }

            Set<Path> quarantined = new HashSet<>();
            Set<Path> configuredPaths = configuredPluginPaths();
            for (List<PluginJarCandidate> matches : byIdentity.values()) {
                List<PluginJarCandidate> active = matches.stream()
                    .filter(candidate -> !quarantined.contains(candidate.path.toAbsolutePath().normalize()))
                    .toList();
                if (active.size() < 2) {
                    continue;
                }
                PluginJarCandidate keeper = chooseDuplicateKeeper(active, configuredPaths);
                for (PluginJarCandidate candidate : active) {
                    Path canonical = candidate.path.toAbsolutePath().normalize();
                    if (canonical.equals(keeper.path.toAbsolutePath().normalize())) {
                        continue;
                    }
                    Path moved = quarantineDuplicate(candidate.path);
                    quarantined.add(canonical);
                    Log.warn("Quarantined duplicate plugin jar before startup for " + keeper.info.name + ": "
                        + candidate.path.getFileName() + " -> " + moved);
                }
            }
        }

        private Set<Path> configuredPluginPaths() {
            Set<Path> paths = new HashSet<>();
            for (TargetConfig plugin : config.plugins) {
                if (plugin.installAs != null && !plugin.installAs.isBlank()) {
                    paths.add(config.resolve(Paths.get(plugin.installAs)).toAbsolutePath().normalize());
                }
            }
            return paths;
        }

        private PluginJarCandidate chooseDuplicateKeeper(List<PluginJarCandidate> candidates, Set<Path> configuredPaths) {
            for (PluginJarCandidate candidate : candidates) {
                if (configuredPaths.contains(candidate.path.toAbsolutePath().normalize())) {
                    return candidate;
                }
            }
            return candidates.stream()
                .max(Comparator.comparing(candidate -> {
                    try {
                        return Files.getLastModifiedTime(candidate.path);
                    } catch (IOException ex) {
                        return java.nio.file.attribute.FileTime.fromMillis(0);
                    }
                }))
                .orElse(candidates.get(0));
        }

        private Path quarantineDuplicate(Path duplicate) throws IOException {
            Path duplicatesDir = config.resolve(config.duplicates.directory);
            Files.createDirectories(duplicatesDir);
            String filename = duplicate.getFileName().toString();
            String stamp = LocalDateTime.now().format(BACKUP_TIME);
            Path destination = duplicatesDir.resolve(filename + "." + stamp + ".duplicate");
            int attempt = 1;
            while (Files.exists(destination)) {
                destination = duplicatesDir.resolve(filename + "." + stamp + "." + attempt + ".duplicate");
                attempt++;
            }
            moveReplace(duplicate, destination);
            return destination;
        }

        void rememberStartupFailures(List<InstalledUpdate> updates, String reason) {
            if (!config.failureMemory.enabled || updates.isEmpty()) {
                pathDebug(null, "lock.failureMemory.skip", "enabled=" + config.failureMemory.enabled
                    + "; updates=" + updates.size() + "; reason=" + reason);
                return;
            }
            try {
                LockState lock = LockState.read(config);
                int remembered = 0;
                int rememberedServers = 0;
                for (InstalledUpdate update : updates) {
                    if (update != null && update.target.server) {
                        pathDebug(update.target, "lock.failureMemory.server", "remember known-bad server build; reason=" + reason
                            + "; version=" + firstNonBlank(update.serverGameVersion, update.version, "unknown")
                            + "; build=" + firstNonBlank(update.serverBuild, "unknown"));
                        lock.rememberBadServerBuild(update, reason);
                        rememberedServers++;
                    } else if (update != null) {
                        pathDebug(update.target, "lock.failureMemory.plugin", "remember known-bad plugin update; reason=" + reason
                            + "; version=" + firstNonBlank(update.version, "unknown")
                            + "; hash=" + shortHash(update.sha256));
                        lock.rememberBadPlugin(update, reason);
                        remembered++;
                    }
                }
                if (remembered > 0 || rememberedServers > 0) {
                    lock.write(config);
                    if (remembered > 0) {
                        Log.warn("Remembered " + remembered + " known-bad plugin update" + (remembered == 1 ? "" : "s") + " in updater.lock.yml.");
                    }
                    if (rememberedServers > 0) {
                        Log.warn("Remembered " + rememberedServers + " known-bad server build" + (rememberedServers == 1 ? "" : "s") + " in updater.lock.yml.");
                    }
                }
            } catch (IOException ex) {
                pathDebug(null, "lock.failureMemory.write.failed", ex.getMessage());
                Log.warn("Could not write failure memory to updater.lock.yml: " + ex.getMessage());
            }
        }

        void restoreServerLockAfterRollback(InstalledUpdate update) {
            if (update == null || !update.target.server || update.previousServerLock == null) {
                pathDebug(update == null ? null : update.target, "lock.server.restore.skip", "not a server rollback or no previous lock snapshot");
                return;
            }
            try {
                LockState state = LockState.read(config);
                state.serverProject = update.previousServerLock.project;
                state.serverGameVersion = update.previousServerLock.gameVersion;
                state.serverBuild = update.previousServerLock.build;
                state.write(config);
                Log.warn("Restored server version lock after rollback -> "
                    + firstNonBlank(state.serverProject, "server") + " "
                    + firstNonBlank(state.serverGameVersion, "unlocked")
                    + (state.serverBuild.isBlank() ? "" : " build " + state.serverBuild) + ".");
                pathDebug(update.target, "lock.server.restore", "project=" + firstNonBlank(state.serverProject, "server")
                    + "; gameVersion=" + firstNonBlank(state.serverGameVersion, "unlocked")
                    + "; build=" + firstNonBlank(state.serverBuild, "unlocked"));
            } catch (IOException ex) {
                pathDebug(update.target, "lock.server.restore.failed", ex.getMessage());
                Log.warn("Could not restore updater.lock.yml server version after rollback: " + ex.getMessage());
            }
        }

        private String downloadVersion(ResolvedDownload download) {
            return firstNonBlank(download.version, latestFromLabel(download.label));
        }

        private boolean isTrustedGithubRepo(String repo) {
            String normalized = repo.trim();
            if (config.buildFromSource.trustedGithubRepos.stream().anyMatch(r -> r.equalsIgnoreCase(normalized))) {
                return true;
            }
            int slash = normalized.indexOf('/');
            if (slash <= 0) {
                return false;
            }
            String org = normalized.substring(0, slash);
            return config.buildFromSource.trustedGithubOrgs.stream().anyMatch(o -> o.equalsIgnoreCase(org));
        }

        private void updateLockIfNeeded(TargetConfig target, ResolvedDownload download) {
            if (!target.server || !"papermc".equals(download.sourceType) || download.gameVersion == null || download.gameVersion.isBlank()) {
                pathDebug(target, "lock.server.update.skip", "target.server=" + target.server
                    + "; sourceType=" + firstNonBlank(download.sourceType, "unknown")
                    + "; gameVersion=" + firstNonBlank(download.gameVersion, "blank"));
                return;
            }
            try {
                Path lock = config.resolve(Paths.get("updater.lock.yml"));
                LockState state = LockState.read(config);
                state.serverProject = download.project;
                state.serverGameVersion = download.gameVersion;
                state.serverBuild = download.build;
                state.write(config);
                Log.info("Updated version lock -> " + lock.getFileName() + " (" + download.project + " " + download.gameVersion + ")");
                pathDebug(target, "lock.server.update", "project=" + download.project
                    + "; gameVersion=" + download.gameVersion
                    + "; build=" + firstNonBlank(download.build, "unknown"));
            } catch (IOException ex) {
                pathDebug(target, "lock.server.update.failed", ex.getMessage());
                Log.warn("Could not write updater.lock.yml: " + ex.getMessage());
            }
        }

        private SourcePlan resolveSource(TargetConfig target) {
            String type = target.type == null || target.type.isBlank() ? "auto" : lower(target.type);
            String source = target.source == null ? "" : target.source.trim();
            if (isNotFoundSourceValue(source)) {
                throw new IllegalArgumentException("No source has been found yet for " + target.displayName());
            }
            if (type.equals("auto")) {
                type = detectType(source, target);
            }
            if (config.mode.equals("hosted-safe") && (type.equals("git") || type.equals("github-source"))) {
                if (!config.buildFromSource.allowsBuild()) {
                    throw new IllegalArgumentException("Source builds are disabled for " + target.displayName());
                }
            }
            switch (type) {
                case "papermc":
                    return new SourcePlan(type, source.isBlank() ? "PaperMC downloads API" : source, new PaperMcResolver(config, client));
                case "geysermc":
                    return new SourcePlan(type, source.isBlank() ? "GeyserMC downloads API" : source, new GeyserMcResolver(config, client));
                case "hangar":
                    return new SourcePlan(type, source.isBlank() ? "Hangar API" : source, new HangarResolver(config, client));
                case "github-release":
                case "github":
                    return new SourcePlan("github-release", source.isBlank() ? target.githubRepo : source, new GithubReleaseResolver(config, client));
                case "modrinth":
                    return new SourcePlan(type, source.isBlank() ? "Modrinth API" : source, new ModrinthResolver(config, client));
                case "spigot":
                case "spiget":
                    return new SourcePlan("spigot", source, new SpigotResolver(config));
                case "jenkins":
                    return new SourcePlan(type, source, new JenkinsResolver(config, client));
                case "direct":
                    return new SourcePlan(type, source, new DirectResolver(config));
                case "git":
                case "github-source":
                    return new SourcePlan("github-source", source.isBlank() ? target.githubRepo : source, new GitSourceResolver(config));
                default:
                    throw new IllegalArgumentException("Unsupported source type for " + target.displayName() + ": " + type);
            }
        }

        private String detectType(String source, TargetConfig target) {
            String lowerSource = lower(source);
            if (lowerSource.contains("papermc.io/downloads") || lowerSource.contains("papermc.io/software")
                || lowerSource.contains("fill.papermc.io") || lowerSource.contains("api.papermc.io")) {
                return "papermc";
            }
            if (lowerSource.contains("geysermc.org/download") && !lowerSource.contains("download.geysermc.org")) {
                return "geysermc";
            }
            if (lowerSource.contains("modrinth.com/") || lowerSource.contains("api.modrinth.com/")) {
                return "modrinth";
            }
            if (lowerSource.contains("hangar.papermc.io/")) {
                return "hangar";
            }
            if (lowerSource.contains("/job/") && !lowerSource.endsWith(".jar")) {
                return "jenkins";
            }
            if (lowerSource.contains("spigotmc.org/resources") || lowerSource.contains("api.spiget.org/")) {
                return "spigot";
            }
            if (lowerSource.contains("github.com/")
                && (lowerSource.contains("/releases/download/") || lowerSource.endsWith(".jar"))) {
                return "direct";
            }
            if (lowerSource.endsWith(".git") || lowerSource.startsWith("git@")) {
                return "git";
            }
            if (target.type != null && lower(target.type).equals("github-source")) {
                return "github-source";
            }
            if (lowerSource.contains("github.com/")) {
                return "github-release";
            }
            if (lowerSource.contains("download.geysermc.org/v2/")) {
                return "direct";
            }
            if (lowerSource.startsWith("http://") || lowerSource.startsWith("https://")) {
                return "direct";
            }
            if (target.githubRepo != null && !target.githubRepo.isBlank()) {
                return "github-release";
            }
            if (target.server && (target.project != null || lower(target.displayName()).contains("velocity"))) {
                return "papermc";
            }
            return "direct";
        }

        private void download(URI uri, Path destination) throws Exception {
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                Path source = Paths.get(uri);
                Log.info("Copying " + source);
                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                return;
            }
            Log.info("Downloading " + uri);
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", config.userAgent)
                .GET()
                .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Download failed with HTTP " + status + " from " + uri);
            }
            try (InputStream in = response.body()) {
                Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private Path backup(Path targetPath) throws IOException {
            Path backups = config.resolve(config.backupDir);
            Files.createDirectories(backups);
            String filename = targetPath.getFileName().toString();
            String stamp = LocalDateTime.now().format(BACKUP_TIME);
            Path backup = backups.resolve(filename + "." + stamp + ".bak");
            Files.copy(targetPath, backup, StandardCopyOption.REPLACE_EXISTING);
            Log.info("Backed up " + targetPath.getFileName() + " -> " + backup);
            return backup;
        }

        private static void validateJar(Path path) throws IOException {
            try (JarFile ignored = new JarFile(path.toFile(), false)) {
                // Opening the JarFile is enough to verify that this is a readable jar/zip.
            }
        }

        private static void moveReplace(Path from, Path to) throws IOException {
            try {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static final class DirectResolver implements DownloadResolver {
        private final AppConfig config;

        DirectResolver(AppConfig config) {
            this.config = config;
        }

        @Override
        public ResolvedDownload resolve(TargetConfig target) {
            if (target.source == null || target.source.isBlank()) {
                throw new IllegalArgumentException("Direct source needs a URL for " + target.displayName());
            }
            return new ResolvedDownload(sourceUri(target.source, config), target.source);
        }
    }

    private static final class SelfUpdater {
        private SelfUpdater() {
        }

        static void launchHelper(AppConfig config, Path helperJar, Path pendingJar, Path targetJar,
                                 boolean relaunchAfterApply, String[] originalArgs) throws IOException {
            List<String> command = new ArrayList<>();
            command.add(javaExecutable());
            command.add("-jar");
            command.add(helperJar.toString());
            command.add("apply-self-update");
            command.add("--parent-pid");
            command.add(Long.toString(ProcessHandle.current().pid()));
            command.add("--pending");
            command.add(pendingJar.toString());
            command.add("--target");
            command.add(targetJar.toString());
            command.add("--cwd");
            command.add(config.baseDir.toString());
            command.add("--relaunch");
            command.add(Boolean.toString(relaunchAfterApply));
            command.add("--");
            command.addAll(Arrays.asList(originalArgs));
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(config.baseDir.toFile());
            builder.inheritIO();
            builder.start();
        }

        static int applyReplacement(String[] args) throws Exception {
            SelfUpdateApplyCommand command = SelfUpdateApplyCommand.parse(args);
            waitForParent(command.parentPid);
            validateReplacementInputs(command.pendingJar, command.targetJar);

            Path parent = command.targetJar.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path backup = command.targetJar.resolveSibling(command.targetJar.getFileName()
                + "." + LocalDateTime.now().format(BACKUP_TIME) + ".self-update.bak");
            if (Files.exists(command.targetJar)) {
                Files.copy(command.targetJar, backup, StandardCopyOption.REPLACE_EXISTING);
                Log.info("Self-update backed up " + command.targetJar.getFileName() + " -> " + backup);
            }
            moveReplace(command.pendingJar, command.targetJar);
            Log.info("Self-update installed " + command.targetJar + ".");

            if (command.relaunch) {
                List<String> relaunch = new ArrayList<>();
                relaunch.add(javaExecutable());
                relaunch.add("-jar");
                relaunch.add(command.targetJar.toString());
                relaunch.addAll(command.relaunchArgs);
                ProcessBuilder builder = new ProcessBuilder(relaunch);
                builder.directory(command.cwd.toFile());
                builder.inheritIO();
                Log.info("Self-update relaunching: " + String.join(" ", relaunch));
                builder.start();
            }
            return 0;
        }

        private static void waitForParent(long parentPid) {
            if (parentPid <= 0) {
                return;
            }
            try {
                Optional<ProcessHandle> parent = ProcessHandle.of(parentPid);
                if (parent.isPresent() && parent.get().isAlive()) {
                    parent.get().onExit().get(120, TimeUnit.SECONDS);
                }
            } catch (Exception ignored) {
                // If the parent cannot be observed, continue; the replacement will still fail safely if locked.
            }
        }

        private static void validateReplacementInputs(Path pendingJar, Path targetJar) throws IOException {
            if (!Files.isRegularFile(pendingJar)) {
                throw new IOException("Pending self-update jar does not exist: " + pendingJar);
            }
            if (Files.exists(targetJar) && !Files.isRegularFile(targetJar)) {
                throw new IOException("Self-update target is not a file: " + targetJar);
            }
            try (JarFile file = new JarFile(pendingJar.toFile(), false)) {
                Manifest manifest = file.getManifest();
                String main = manifest == null ? "" : firstNonBlank(
                    manifest.getMainAttributes().getValue("Main-Class"),
                    manifest.getMainAttributes().getValue("Launcher-Agent-Class")
                );
                if (!main.equals("dev.autoupdater.AutoUpdater")
                    && !main.equals("dev.velocityupdater.VelocityAutoUpdater")) {
                    throw new IOException("Pending self-update jar is not Auto-Updater (Main-Class="
                        + firstNonBlank(main, "missing") + ")");
                }
            }
        }

        private static String javaExecutable() {
            String home = System.getProperty("java.home", "");
            String name = isWindows() ? "java.exe" : "java";
            if (!home.isBlank()) {
                Path java = Paths.get(home).resolve("bin").resolve(name);
                if (Files.isRegularFile(java)) {
                    return java.toString();
                }
            }
            return "java";
        }

        private static boolean isWindows() {
            return lower(System.getProperty("os.name", "")).contains("win");
        }

        private static void moveReplace(Path from, Path to) throws IOException {
            try {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static final class SelfUpdateApplyCommand {
        long parentPid;
        Path pendingJar;
        Path targetJar;
        Path cwd;
        boolean relaunch;
        List<String> relaunchArgs = new ArrayList<>();

        static SelfUpdateApplyCommand parse(String[] args) {
            SelfUpdateApplyCommand command = new SelfUpdateApplyCommand();
            command.cwd = Paths.get(".").toAbsolutePath().normalize();
            for (int i = 1; i < args.length; i++) {
                String arg = args[i];
                if (arg.equals("--")) {
                    command.relaunchArgs.addAll(Arrays.asList(args).subList(i + 1, args.length));
                    break;
                }
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException(arg + " needs a value");
                }
                String value = args[++i];
                switch (arg) {
                    case "--parent-pid":
                        command.parentPid = Long.parseLong(value);
                        break;
                    case "--pending":
                        command.pendingJar = Paths.get(value).toAbsolutePath().normalize();
                        break;
                    case "--target":
                        command.targetJar = Paths.get(value).toAbsolutePath().normalize();
                        break;
                    case "--cwd":
                        command.cwd = Paths.get(value).toAbsolutePath().normalize();
                        break;
                    case "--relaunch":
                        command.relaunch = parseBoolean(value);
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown self-update argument: " + arg);
                }
            }
            if (command.pendingJar == null || command.targetJar == null) {
                throw new IllegalArgumentException("apply-self-update needs --pending and --target");
            }
            return command;
        }
    }

    private static final class SpigotResolver implements DownloadResolver {
        private final AppConfig config;

        SpigotResolver(AppConfig config) {
            this.config = config;
        }

        @Override
        public ResolvedDownload resolve(TargetConfig target) {
            if (target.source == null || target.source.isBlank()) {
                throw new IllegalArgumentException("Spigot source needs a URL for " + target.displayName());
            }
            String source = target.source.trim();
            String lower = lower(source);
            if (lower.contains("api.spiget.org/") && lower.contains("/download")) {
                return new ResolvedDownload(sourceUri(source, config), "Manual Spiget " + source, "spigot", "", "", "", "");
            }
            String id = spigotResourceId(source);
            if (id.isBlank()) {
                throw new IllegalArgumentException("Could not find Spigot resource id in source for " + target.displayName() + ": " + source);
            }
            String url = "https://api.spiget.org/v2/resources/" + id + "/download";
            return new ResolvedDownload(URI.create(url), "Manual Spigot resource " + id, "spigot", id, "", "", "");
        }

        private static String spigotResourceId(String source) {
            Matcher matcher = Pattern.compile("(?i)(?:spigotmc\\.org/resources/)(?:[^/?#]*\\.)?(\\d+)(?:[/?#].*)?$").matcher(source);
            if (matcher.find()) {
                return matcher.group(1);
            }
            matcher = Pattern.compile("(?i)api\\.spiget\\.org/v2/resources/(\\d+)").matcher(source);
            return matcher.find() ? matcher.group(1) : "";
        }
    }

    private static final class JenkinsResolver implements DownloadResolver {
        private final AppConfig config;
        private final HttpClient client;

        JenkinsResolver(AppConfig config, HttpClient client) {
            this.config = config;
            this.client = client;
        }

        @Override
        public ResolvedDownload resolve(TargetConfig target) throws Exception {
            if (target.source == null || target.source.isBlank()) {
                throw new IllegalArgumentException("Jenkins source needs a URL for " + target.displayName());
            }
            String source = target.source.trim();
            String lower = lower(source);
            if (lower.endsWith(".jar") || lower.contains("/artifact/")) {
                return new ResolvedDownload(sourceUri(source, config), "Manual Jenkins artifact " + source, "jenkins", "", "", "", "");
            }
            String base = jenkinsBuildBase(source);
            URI api = URI.create(base + "/api/json?tree=artifacts[fileName,relativePath],timestamp,id,number,url");
            HttpRequest request = HttpRequest.newBuilder(api)
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", config.userAgent)
                .header("Accept", "application/json")
                .GET()
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Jenkins build metadata failed with HTTP " + status + " for " + api);
            }
            Map<String, Object> root = asMap(new JsonParser(response.body()).parse());
            Object artifactsObject = root.get("artifacts");
            if (!(artifactsObject instanceof List<?> artifacts) || artifacts.isEmpty()) {
                throw new IOException("Jenkins build has no artifacts for " + target.displayName() + ": " + base);
            }
            Map<String, Object> artifact = chooseJenkinsArtifact(target, artifacts)
                .orElseThrow(() -> new IOException("Jenkins build has no jar artifact for " + target.displayName() + ": " + base));
            String relativePath = stringValue(artifact.get("relativePath"));
            if (relativePath.isBlank()) {
                relativePath = stringValue(artifact.get("fileName"));
            }
            if (relativePath.isBlank()) {
                throw new IOException("Jenkins jar artifact has no path for " + target.displayName() + ": " + base);
            }
            String url = base + "/artifact/" + encodePathSegments(relativePath);
            String version = firstNonBlank(stringValue(root.get("id")), stringValue(root.get("number")));
            Instant publishedAt = instantFromJson(root.get("timestamp"));
            return new ResolvedDownload(URI.create(url), "Manual Jenkins " + relativePath,
                "jenkins", base, "", stringValue(root.get("number")), version, publishedAt);
        }

        private static String jenkinsBuildBase(String source) {
            URI uri = URI.create(source);
            String value = uri.getScheme() + "://" + uri.getRawAuthority() + firstNonBlank(uri.getRawPath(), "");
            value = value.replaceAll("/+$", "");
            value = value.replaceAll("(?i)/api/json$", "");
            if (!value.matches("(?i).*/(?:lastSuccessfulBuild|lastStableBuild|lastCompletedBuild|\\d+)$")) {
                value = value + "/lastSuccessfulBuild";
            }
            return value;
        }

        private static Optional<Map<String, Object>> chooseJenkinsArtifact(TargetConfig target, List<?> artifacts) {
            List<Map<String, Object>> jars = new ArrayList<>();
            for (Object item : artifacts) {
                Map<String, Object> artifact = asMap(item);
                String path = firstNonBlank(stringValue(artifact.get("relativePath")), stringValue(artifact.get("fileName")));
                if (lower(path).endsWith(".jar") && !lower(path).contains("-sources") && !lower(path).contains("-javadoc")) {
                    jars.add(artifact);
                }
            }
            if (jars.isEmpty()) {
                return Optional.empty();
            }
            String wanted = simpleArtifactName(firstNonBlank(target.detectedPluginId, target.name, stripJarExtension(target.installAs)));
            return jars.stream()
                .max(Comparator.comparingInt(artifact -> artifactScore(wanted,
                    firstNonBlank(stringValue(artifact.get("fileName")), stringValue(artifact.get("relativePath"))))));
        }

        private static int artifactScore(String wanted, String artifactName) {
            String candidate = simpleArtifactName(artifactName);
            if (wanted.isBlank() || candidate.isBlank()) {
                return 0;
            }
            if (candidate.equals(wanted)) {
                return 100;
            }
            if (candidate.contains(wanted) || wanted.contains(candidate)) {
                return 60;
            }
            return 0;
        }

        private static String simpleArtifactName(String value) {
            String name = value == null ? "" : value;
            int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
            if (slash >= 0) {
                name = name.substring(slash + 1);
            }
            name = name.replaceAll("(?i)\\.jar$", "");
            name = name.replaceAll("(?i)-\\d.*$", "");
            return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
        }

        private static String stripJarExtension(String value) {
            String name = value == null ? "" : value;
            int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
            if (slash >= 0) {
                name = name.substring(slash + 1);
            }
            return name.replaceAll("(?i)\\.jar$", "");
        }

        private static String encodePathSegments(String path) {
            return Arrays.stream(path.split("/"))
                .map(AutoUpdater::urlEncode)
                .collect(Collectors.joining("/"));
        }
    }

    private static final class GitSourceResolver implements DownloadResolver {
        private final AppConfig config;

        GitSourceResolver(AppConfig config) {
            this.config = config;
        }

        @Override
        public ResolvedDownload resolve(TargetConfig target) throws Exception {
            if (!config.buildFromSource.allowsBuild()) {
                throw new IllegalArgumentException("buildFromSource.enabled is not true/auto for " + target.displayName());
            }
            GithubRepo repo = inferRepo(target);
            String repoName = repo.owner + "/" + repo.name;

            String sourceCacheName = repo.owner + "-" + repo.name
                + (cleanGithubRef(repo.ref).isBlank() ? "" : "-" + cleanGithubRef(repo.ref).replace("/", "-"));
            Path sourceDir = config.resolve(config.cacheDir)
                .resolve("source")
                .resolve(safeName(sourceCacheName));
            syncRepo(repo, sourceDir);
            if (config.buildFromSource.onlyTrusted && !isTrusted(repoName)) {
                PluginJarInfo installed = installedPluginInfo(target);
                if (sourceTreeDescriptorEvidence(sourceDir, installed, target) != SourceDescriptorEvidence.MATCH) {
                    throw new IllegalArgumentException("Git source repo is not trusted and its plugin descriptor did not match "
                        + target.displayName() + ": " + repoName);
                }
                Log.info("Git source " + repoName + " is trusted for " + target.displayName()
                    + " because a source descriptor matches the installed plugin.");
            }
            String commit = currentCommit(sourceDir);
            LockState lock = LockState.read(config);
            Optional<BadSourceBuild> badBuild = lock.activeBadSourceBuild(config, repoName, commit);
            if (badBuild.isPresent()) {
                throw new IOException("Skipping known-bad source build for " + repoName
                    + " at " + shortHash(commit) + " (" + firstNonBlank(badBuild.get().summary, badBuild.get().reason) + ")");
            }
            try {
                Path buildDir = buildDirectoryForTarget(sourceDir, target);
                if (!buildDir.equals(sourceDir)) {
                    Log.info("Using source module for " + target.displayName() + ": "
                        + normalizeSlashes(sourceDir.relativize(buildDir).toString()));
                }
                repairSourceBuildFiles(buildDir, target);
                List<BuildCommand> commands = detectBuildCommands(buildDir);
                BuildJavaChoice javaChoice = chooseInitialBuildJava(buildDir, commands, target);
                Path builtJar = runBuildCommands(repoName, commit, buildDir, target, commands, javaChoice);
                validateBuiltJar(builtJar);
                return new ResolvedDownload(builtJar.toUri(), "Git source " + repoName + " " + shortHash(commit) + " " + builtJar.getFileName(),
                    "github-source", repoName, "", "", commit);
            } catch (Exception ex) {
                if (!(ex instanceof MissingBuildToolException)) {
                    String details = ex instanceof SourceBuildException sourceEx ? sourceEx.details : safeExceptionMessage(ex);
                    String logFile = writeSourceBuildFailureLog(repoName, commit, details);
                    lock.rememberBadSourceBuild(repoName, commit, summarizeFailure(safeExceptionMessage(ex)), details, logFile);
                    lock.write(config);
                }
                throw ex;
            }
        }

        private Path runBuildCommands(String repoName, String commit, Path buildDir, TargetConfig target,
                                      List<BuildCommand> commands, BuildJavaChoice javaChoice) throws Exception {
            List<String> failures = new ArrayList<>();
            List<String> details = new ArrayList<>();
            Instant deadline = Instant.now().plus(Duration.ofMinutes(12));
            for (int i = 0; i < commands.size(); i++) {
                BuildCommand command = commands.get(i);
                String label = command.reason.isBlank() ? "" : " (" + command.reason + ")";
                Log.info("Building " + repoName + "@" + shortHash(commit)
                    + " with " + String.join(" ", command.command)
                    + buildJavaLabel(javaChoice) + label);
                try {
                    runBuildProcess(command, buildDir, remainingBuildTimeout(deadline), false, javaChoice.javaHome);
                    return findBuiltJar(buildDir, target);
                } catch (BuildProcessException ex) {
                    if (isJavaBuildToolCompatibilityFailure(ex.output)) {
                        JavaRetryResult retry = retryWithManagedJava(repoName, commit, buildDir, target, command, deadline, ex.output, javaChoice.major);
                        if (retry.jar != null) {
                            return retry.jar;
                        }
                        String failure = "Build command " + (i + 1) + "/" + commands.size()
                            + " failed because the build tool is incompatible with the current Java runtime: "
                            + String.join(" ", command.command) + " -> "
                            + firstNonBlank(retry.failureSummary, shortBuildFailureSummary(ex.output));
                        failures.add(failure);
                        details.add(failure + System.lineSeparator() + firstNonBlank(retry.details, ex.output));
                        Log.warn(failure);
                        Log.warn("Stopping source build fallbacks for " + target.displayName()
                            + " because this is a Java/build-tool compatibility failure, not a command-choice failure.");
                        break;
                    }
                    boolean dependencyFailure = isDependencyResolutionFailure(ex.output);
                    boolean corruptCache = isCorruptDependencyCacheFailure(ex.output);
                    if (dependencyFailure && attemptDependencyRescue(repoName, buildDir, target, ex.output)) {
                        Log.warn("Retrying source build for " + target.displayName()
                            + " after installing rescued dependencies into the temporary Maven repo.");
                        try {
                            runBuildProcess(command, buildDir, remainingBuildTimeout(deadline), true, javaChoice.javaHome);
                            return findBuiltJar(buildDir, target);
                        } catch (BuildProcessException retryEx) {
                            String summary = shortBuildFailureSummary(retryEx.output);
                            if (isCompilationFailure(retryEx.output)) {
                                summary = "dependency rescue reached compilation, but source/API errors remain: " + summary;
                            }
                            String failure = "Build command " + (i + 1) + "/" + commands.size()
                                + " failed after dependency rescue: " + String.join(" ", command.command)
                                + " -> " + summary;
                            failures.add(failure);
                            details.add(failure + System.lineSeparator() + retryEx.output);
                            Log.warn(failure);
                            if (isCompilationFailure(retryEx.output)) {
                                Log.warn("Stopping source build fallbacks for " + target.displayName()
                                    + " because dependencies resolved and the remaining failure is source/API compilation drift.");
                            } else {
                                Log.warn("Stopping source build fallbacks for " + target.displayName()
                                    + " because dependency rescue did not make this command succeed.");
                            }
                            break;
                        }
                    }
                    if ((dependencyFailure || corruptCache) && !ex.refreshed) {
                        String retryReason = corruptCache ? "corrupt dependency cache" : "dependency resolution";
                        Log.warn("Build command " + (i + 1) + "/" + commands.size() + " hit " + retryReason
                            + " for " + target.displayName() + "; retrying once with refreshed dependencies.");
                        try {
                            runBuildProcess(command, buildDir, remainingBuildTimeout(deadline), true, javaChoice.javaHome);
                            return findBuiltJar(buildDir, target);
                        } catch (BuildProcessException retryEx) {
                            if (dependencyFailure && attemptDependencyRescue(repoName, buildDir, target, retryEx.output)) {
                                Log.warn("Retrying source build for " + target.displayName()
                                    + " after refreshed dependency resolution exposed a rescueable dependency.");
                                try {
                                    runBuildProcess(command, buildDir, remainingBuildTimeout(deadline), true, javaChoice.javaHome);
                                    return findBuiltJar(buildDir, target);
                                } catch (BuildProcessException rescuedRetryEx) {
                                    retryEx = rescuedRetryEx;
                                }
                            }
                            String summary = shortBuildFailureSummary(retryEx.output);
                            String failure = "Build command " + (i + 1) + "/" + commands.size()
                                + " failed after dependency refresh: " + String.join(" ", command.command)
                                + " -> " + summary;
                            failures.add(failure);
                            details.add(failure + System.lineSeparator() + retryEx.output);
                            Log.warn(failure);
                            Log.warn("Stopping source build fallbacks for " + target.displayName()
                                + " because this is still a dependency/cache failure, not a command-choice failure.");
                            break;
                        }
                    }
                    String summary = shortBuildFailureSummary(ex.output);
                    String failure = "Build command " + (i + 1) + "/" + commands.size()
                        + " failed: " + String.join(" ", command.command) + " -> " + summary;
                    failures.add(failure);
                    details.add(failure + System.lineSeparator() + ex.output);
                    if (dependencyFailure || corruptCache) {
                        Log.warn(failure);
                        Log.warn("Stopping source build fallbacks for " + target.displayName()
                            + " because dependency resolution failed; another command is unlikely to fix it.");
                        break;
                    }
                    if (i + 1 < commands.size()) {
                        Log.warn(failure);
                        Log.warn("Trying next source build command for " + target.displayName() + ".");
                    }
                } catch (Exception ex) {
                    String failure = "Build command " + (i + 1) + "/" + commands.size()
                        + " failed: " + String.join(" ", command.command) + " -> " + safeExceptionMessage(ex);
                    failures.add(failure);
                    details.add(failure);
                    if (i + 1 < commands.size()) {
                        Log.warn(failure);
                        Log.warn("Trying next source build command for " + target.displayName() + ".");
                    }
                }
            }
            String summary = "Source build failed for " + target.displayName() + ": "
                + (failures.isEmpty() ? "no build command succeeded" : failures.get(failures.size() - 1));
            throw new SourceBuildException(summary, "All source build commands failed for " + target.displayName()
                + System.lineSeparator() + String.join(System.lineSeparator() + System.lineSeparator(), details));
        }

        private Duration remainingBuildTimeout(Instant deadline) throws IOException {
            Duration remaining = Duration.between(Instant.now(), deadline);
            if (remaining.isNegative() || remaining.isZero()) {
                throw new IOException("Source build time budget exceeded");
            }
            return remaining.compareTo(Duration.ofMinutes(20)) > 0 ? Duration.ofMinutes(20) : remaining;
        }

        private String buildJavaLabel(BuildJavaChoice javaChoice) {
            if (javaChoice == null || javaChoice.javaHome == null || javaChoice.major <= 0) {
                return "";
            }
            return " (Java " + javaChoice.major + " preflight)";
        }

        private BuildJavaChoice chooseInitialBuildJava(Path buildDir, List<BuildCommand> commands, TargetConfig target) {
            int current = Runtime.version().feature();
            int major = preferredBuildJavaMajor(buildDir, commands, current);
            if (major <= 0 || major == current) {
                return BuildJavaChoice.current();
            }
            try {
                Optional<Path> home = ManagedJava.home(config, major);
                if (home.isPresent()) {
                    Log.info("Build preflight for " + target.displayName() + ": using Java " + major
                        + " before the first build command because " + buildJavaReason(buildDir, commands, current, major) + ".");
                    return new BuildJavaChoice(major, home.get());
                }
            } catch (Exception ex) {
                Log.warn("Build preflight for " + target.displayName() + " wanted Java " + major
                    + " but it is not available yet: " + safeExceptionMessage(ex));
            }
            return BuildJavaChoice.current();
        }

        private int preferredBuildJavaMajor(Path buildDir, List<BuildCommand> commands, int current) {
            int gradleVersionChoice = javaForGradleWrapper(buildDir, current);
            if (gradleVersionChoice > 0) {
                return gradleVersionChoice;
            }
            int ciChoice = javaFromCiHints(buildDir);
            if (ciChoice > 0 && ciChoice != current) {
                return closestManagedJava(ciChoice, current);
            }
            int buildFileChoice = javaFromBuildFiles(buildDir);
            if (buildFileChoice > 0 && buildFileChoice != current && isMavenBuild(commands)) {
                return closestManagedJava(buildFileChoice, current);
            }
            return current;
        }

        private String buildJavaReason(Path buildDir, List<BuildCommand> commands, int current, int chosen) {
            String gradleVersion = gradleWrapperVersion(buildDir);
            if (!gradleVersion.isBlank()) {
                return "Gradle wrapper " + gradleVersion + " should not run on Java " + current;
            }
            int ci = javaFromCiHints(buildDir);
            if (ci > 0) {
                return "CI requests Java " + ci;
            }
            int buildFile = javaFromBuildFiles(buildDir);
            if (buildFile > 0 && isMavenBuild(commands)) {
                return "build files request Java " + buildFile;
            }
            return "Java " + chosen + " is a safer build runtime than Java " + current;
        }

        private boolean isMavenBuild(List<BuildCommand> commands) {
            for (BuildCommand command : commands) {
                if (isMavenCommand(command.command)) {
                    return true;
                }
            }
            return false;
        }

        private int javaForGradleWrapper(Path buildDir, int current) {
            String version = gradleWrapperVersion(buildDir);
            if (version.isBlank()) {
                return 0;
            }
            int[] parts = versionParts(version);
            int major = parts[0];
            int minor = parts[1];
            if (major <= 0) {
                return 0;
            }
            int max = maxJavaForGradle(major, minor);
            if (current <= max) {
                return 0;
            }
            return closestManagedJava(Math.min(max, current - 1), current);
        }

        private String gradleWrapperVersion(Path buildDir) {
            Path properties = buildDir.resolve(Paths.get("gradle", "wrapper", "gradle-wrapper.properties"));
            if (!Files.isRegularFile(properties)) {
                return "";
            }
            try {
                String text = Files.readString(properties, StandardCharsets.UTF_8);
                Matcher matcher = Pattern.compile("gradle-([0-9]+(?:\\.[0-9]+){0,2})-(?:bin|all)\\.zip").matcher(text);
                return matcher.find() ? matcher.group(1) : "";
            } catch (IOException ex) {
                return "";
            }
        }

        private int maxJavaForGradle(int major, int minor) {
            if (major >= 9) {
                return 25;
            }
            if (major == 8) {
                if (minor >= 14) {
                    return 24;
                }
                if (minor >= 10) {
                    return 23;
                }
                if (minor >= 7) {
                    return 22;
                }
                if (minor >= 5) {
                    return 21;
                }
                return 20;
            }
            if (major == 7) {
                return minor >= 3 ? 17 : 16;
            }
            return 11;
        }

        private int javaFromCiHints(Path buildDir) {
            Path workflows = buildDir.resolve(".github").resolve("workflows");
            if (!Files.isDirectory(workflows)) {
                return 0;
            }
            try (var stream = Files.walk(workflows, 2)) {
                List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = lower(path.getFileName().toString());
                        return name.endsWith(".yml") || name.endsWith(".yaml");
                    })
                    .sorted()
                    .limit(12)
                    .toList();
                for (Path file : files) {
                    int version = firstJavaVersionInText(Files.readString(file, StandardCharsets.UTF_8));
                    if (version > 0) {
                        return version;
                    }
                }
            } catch (IOException ignored) {
                return 0;
            }
            return 0;
        }

        private int javaFromBuildFiles(Path buildDir) {
            for (String name : List.of("build.gradle", "build.gradle.kts", "pom.xml")) {
                Path file = buildDir.resolve(name);
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                try {
                    int version = firstJavaVersionInText(Files.readString(file, StandardCharsets.UTF_8));
                    if (version > 0) {
                        return version;
                    }
                } catch (IOException ignored) {
                    return 0;
                }
            }
            return 0;
        }

        private int firstJavaVersionInText(String text) {
            String value = firstNonBlank(text, "");
            List<Pattern> patterns = List.of(
                Pattern.compile("(?i)java-version\\s*[:=]\\s*['\"]?([0-9]{2})"),
                Pattern.compile("(?i)JavaLanguageVersion\\.of\\s*\\(\\s*([0-9]{2})\\s*\\)"),
                Pattern.compile("(?i)targetJavaVersion\\s*=\\s*([0-9]{2})"),
                Pattern.compile("(?i)(?:sourceCompatibility|targetCompatibility)\\s*=\\s*(?:JavaVersion\\.VERSION_)?([0-9]{2})"),
                Pattern.compile("(?i)options\\.release\\.set\\s*\\(?\\s*([0-9]{2})"),
                Pattern.compile("(?i)<maven\\.compiler\\.(?:release|source|target)>\\s*([0-9]{2})\\s*</maven\\.compiler\\.(?:release|source|target)>")
            );
            for (Pattern pattern : patterns) {
                Matcher matcher = pattern.matcher(value);
                if (matcher.find()) {
                    int version = intValue(matcher.group(1), 0);
                    if (version >= 8 && version <= 30) {
                        return version;
                    }
                }
            }
            return 0;
        }

        private int closestManagedJava(int requested, int current) {
            if (requested <= 0 || requested == current) {
                return current;
            }
            if (MANAGED_BUILD_JAVA_FALLBACKS.contains(requested)) {
                return requested;
            }
            List<Integer> sorted = new ArrayList<>(MANAGED_BUILD_JAVA_FALLBACKS);
            sorted.sort(Comparator.reverseOrder());
            for (int candidate : sorted) {
                if (candidate <= requested) {
                    return candidate;
                }
            }
            return sorted.isEmpty() ? current : sorted.get(sorted.size() - 1);
        }

        private int[] versionParts(String version) {
            String[] raw = firstNonBlank(version, "").split("\\.");
            int major = raw.length > 0 ? intValue(raw[0], 0) : 0;
            int minor = raw.length > 1 ? intValue(raw[1], 0) : 0;
            return new int[] { major, minor };
        }

        private JavaRetryResult retryWithManagedJava(String repoName, String commit, Path buildDir, TargetConfig target,
                                                     BuildCommand command, Instant deadline, String originalOutput, int alreadyTriedMajor) {
            JavaRetryResult result = new JavaRetryResult();
            for (int major : preferredManagedJavaFallbacks(originalOutput)) {
                if (major == alreadyTriedMajor) {
                    continue;
                }
                Path selectedJavaHome = null;
                try {
                    Optional<Path> javaHome = ManagedJava.home(config, major);
                    if (javaHome.isEmpty()) {
                        result.failureSummary = "managed/local Java " + major + " is not available";
                        continue;
                    }
                    selectedJavaHome = javaHome.get();
                    Log.warn("Build command for " + target.displayName()
                        + " appears incompatible with Java " + Runtime.version().feature()
                        + "; retrying once with Java " + major + " from " + selectedJavaHome + ".");
                    Log.info("Building " + repoName + "@" + shortHash(commit)
                        + " with " + String.join(" ", command.command)
                        + " (Java " + major + " compatibility retry)");
                    runBuildProcess(command, buildDir, remainingBuildTimeout(deadline), false, selectedJavaHome);
                    result.jar = findBuiltJar(buildDir, target);
                    return result;
                } catch (BuildProcessException ex) {
                    if (isDependencyResolutionFailure(ex.output)
                        && attemptDependencyRescue(repoName, buildDir, target, ex.output)) {
                        Log.warn("Retrying Java " + major + " source build for " + target.displayName()
                            + " after installing rescued dependencies into the temporary Maven repo.");
                        try {
                            runBuildProcess(command, buildDir, remainingBuildTimeout(deadline), true, selectedJavaHome);
                            result.jar = findBuiltJar(buildDir, target);
                            return result;
                        } catch (BuildProcessException retryEx) {
                            ex = retryEx;
                        } catch (Exception retryEx) {
                            result.failureSummary = "Java " + major + " retry after dependency rescue could not run: "
                                + safeExceptionMessage(retryEx);
                            result.details = result.failureSummary;
                            return result;
                        }
                    }
                    result.failureSummary = "Java " + major + " retry failed: " + shortBuildFailureSummary(ex.output);
                    result.details = result.failureSummary + System.lineSeparator() + ex.output;
                    if (!isJavaBuildToolCompatibilityFailure(ex.output)) {
                        return result;
                    }
                } catch (Exception ex) {
                    result.failureSummary = "Java " + major + " retry could not run: " + safeExceptionMessage(ex);
                    result.details = result.failureSummary;
                }
            }
            return result;
        }

        private List<Integer> preferredManagedJavaFallbacks(String output) {
            String text = lower(output);
            if (text.contains("java 25") || text.contains("25.0.") || text.contains("major version 69")) {
                return MANAGED_BUILD_JAVA_FALLBACKS;
            }
            return MANAGED_BUILD_JAVA_FALLBACKS;
        }

        private boolean isJavaBuildToolCompatibilityFailure(String output) {
            String text = lower(output);
            return text.contains("unsupported class file major version")
                || text.contains("unsupported major.minor version")
                || text.contains("invalid source release")
                || text.contains("invalid target release")
                || text.contains("could not target platform")
                || text.contains("* what went wrong: 25.")
                || text.contains("java 25") && text.contains("gradle")
                || text.contains("this version of gradle") && text.contains("java");
        }

        private boolean attemptDependencyRescue(String repoName, Path buildDir, TargetConfig target, String output) {
            String text = firstNonBlank(output, "");
            if (!looksLikeMavenDependencyFailure(text) && !looksLikeGradleDependencyFailure(text)) {
                return false;
            }
            boolean rescued = false;
            try {
                if (text.contains("com.github.GrimAnticheat:GrimAPI")) {
                    String version = missingArtifactVersion(text, "com.github.GrimAnticheat", "GrimAPI");
                    if (!version.isBlank()) {
                        rescued |= rescueGrimApi(version);
                    }
                }
                for (String version : missingFoliaLibVersions(text)) {
                    rescued |= rescueFoliaLib(version);
                }
            } catch (Exception ex) {
                Log.warn("Dependency rescue failed for " + target.displayName() + " from " + repoName
                    + ": " + ex.getMessage());
                return false;
            }
            return rescued;
        }

        private boolean looksLikeMavenDependencyFailure(String output) {
            String text = lower(output);
            return text.contains("failed to execute goal")
                && (text.contains("could not resolve dependencies")
                || text.contains("the following artifacts could not be resolved")
                || text.contains("couldn't download artifact")
                || text.contains("failed to read artifact descriptor"));
        }

        private boolean looksLikeGradleDependencyFailure(String output) {
            String text = lower(output);
            return text.contains("could not resolve all dependencies")
                || text.contains("could not determine the dependencies")
                || text.contains("could not resolve ")
                || text.contains("could not get resource")
                || text.contains("received status code");
        }

        private String missingArtifactVersion(String output, String groupId, String artifactId) {
            String marker = groupId + ":" + artifactId + ":";
            int index = output.indexOf(marker);
            if (index < 0) {
                return "";
            }
            String rest = output.substring(index + marker.length());
            List<String> tokens = new ArrayList<>();
            for (String token : rest.split("[\\s,;():]+")) {
                if (!token.isBlank()) {
                    tokens.add(token.trim());
                }
                if (tokens.size() >= 5) {
                    break;
                }
            }
            for (String token : tokens) {
                String cleaned = token.replace(":", "").replace("[", "").replace("]", "");
                if (!cleaned.equalsIgnoreCase("jar")
                    && !cleaned.equalsIgnoreCase("pom")
                    && !cleaned.equalsIgnoreCase("provided")
                    && cleaned.matches("[A-Za-z0-9_.-]{6,}")) {
                    return cleaned;
                }
            }
            return "";
        }

        private List<String> missingFoliaLibVersions(String output) {
            String lowerOutput = lower(output);
            if (!lowerOutput.contains("com.tcoded:folialib")) {
                return Collections.emptyList();
            }
            List<String> versions = new ArrayList<>();
            for (String version : List.of("0.2.4", "0.3.1")) {
                if (lowerOutput.contains("folialib:" + version)
                    || lowerOutput.contains("folialib:pom:" + version)
                    || lowerOutput.contains("folialib:jar:" + version)
                    || lowerOutput.contains("/folialib/" + version + "/")) {
                    versions.add(version);
                }
            }
            return versions;
        }

        private boolean rescueGrimApi(String version) throws Exception {
            Path repo = buildMavenRepo();
            Path lowercaseJar = repo.resolve(Paths.get("com", "github", "grimanticheat", "grimapi", version, "grimapi-" + version + ".jar"));
            if (!Files.isRegularFile(lowercaseJar)) {
                String mvn = ManagedMaven.executable(config);
                Log.info("Trying dependency rescue for GrimAPI " + version + " using lowercase JitPack coordinates.");
                runProcessForOutput(List.of(
                    mvn,
                    "-B",
                    "-Dmaven.repo.local=" + repo,
                    "-U",
                    "org.apache.maven.plugins:maven-dependency-plugin:3.7.0:get",
                    "-Dartifact=com.github.grimanticheat:grimapi:" + version,
                    "-DremoteRepositories=jitpack::default::https://jitpack.io"
                ), config.baseDir, Duration.ofMinutes(5));
            }
            if (!Files.isRegularFile(lowercaseJar)) {
                return false;
            }
            Path requestedJar = repo.resolve(Paths.get("com", "github", "GrimAnticheat", "GrimAPI", version, "GrimAPI-" + version + ".jar"));
            if (!Files.isRegularFile(requestedJar)) {
                installMavenJar(lowercaseJar, "com.github.GrimAnticheat", "GrimAPI", version);
            }
            Log.info("Dependency rescue installed GrimAPI " + version
                + " under the coordinate requested by the source build.");
            return true;
        }

        private boolean rescueFoliaLib(String version) throws Exception {
            Path repo = buildMavenRepo();
            Path installed = repo.resolve(Paths.get("com", "tcoded", "FoliaLib", version, "FoliaLib-" + version + ".jar"));
            if (Files.isRegularFile(installed)) {
                return true;
            }

            Path sourceDir = config.resolve(config.cacheDir)
                .resolve("source-deps")
                .resolve("TechnicallyCoded-FoliaLib");
            syncFullRepo(new GithubRepo("TechnicallyCoded", "FoliaLib"), sourceDir);
            String ref = foliaLibRef(sourceDir, version);
            Log.info("Trying dependency rescue for FoliaLib " + version
                + " by building TechnicallyCoded/FoliaLib@" + ref + ".");
            runProcess(List.of("git", "checkout", "--force", ref), sourceDir, Duration.ofMinutes(2));
            ensureUsableGradleWrapper(sourceDir);
            BuildCommand command = new BuildCommand(List.of(gradleWrapperPath(sourceDir), "clean", "build", "-x", "test"),
                "dependency rescue Gradle build");
            runBuildProcess(command, sourceDir, Duration.ofMinutes(10), true);
            Path jar = sourceDir.resolve(Paths.get("build", "libs", "FoliaLib-" + version + ".jar"));
            if (!Files.isRegularFile(jar)) {
                jar = findLargestJar(sourceDir.resolve(Paths.get("build", "libs")));
            }
            if (!Files.isRegularFile(jar)) {
                throw new IOException("FoliaLib dependency rescue build did not produce a jar for " + version);
            }
            installMavenJar(jar, "com.tcoded", "FoliaLib", version);
            Log.info("Dependency rescue installed FoliaLib " + version
                + " from TechnicallyCoded/FoliaLib into the temporary Maven repo.");
            return true;
        }

        private void syncFullRepo(GithubRepo repo, Path sourceDir) throws Exception {
            Files.createDirectories(sourceDir.getParent());
            String url = "https://github.com/" + repo.owner + "/" + repo.name + ".git";
            if (!Files.isDirectory(sourceDir.resolve(".git"))) {
                runProcess(List.of("git", "-c", "core.longpaths=true", "clone", url, sourceDir.toString()),
                    config.baseDir, Duration.ofMinutes(10));
                configureGitLongPaths(sourceDir);
                return;
            }
            configureGitLongPaths(sourceDir);
            runProcess(List.of("git", "fetch", "--tags", "origin"), sourceDir, Duration.ofMinutes(5));
            runProcess(List.of("git", "reset", "--hard"), sourceDir, Duration.ofMinutes(2));
        }

        private String foliaLibRef(Path sourceDir, String version) throws Exception {
            String tag = "v" + version;
            if (gitRefExists(sourceDir, tag)) {
                return tag;
            }
            String needle = "version = '" + version + "'";
            String output = runProcessForOutput(List.of("git", "log", "--all", "-S" + needle,
                "--format=%H", "--", "build.gradle"), sourceDir, Duration.ofMinutes(2));
            for (String line : output.split("\\R")) {
                String commit = line.trim();
                if (commit.isBlank()) {
                    continue;
                }
                if (gitFileContains(sourceDir, commit, "build.gradle", needle)) {
                    return commit;
                }
                String parent = commit + "^";
                if (gitFileContains(sourceDir, parent, "build.gradle", needle)) {
                    return parent;
                }
            }
            throw new IOException("Could not find a FoliaLib source commit for version " + version);
        }

        private boolean gitRefExists(Path sourceDir, String ref) {
            try {
                runProcessForOutput(List.of("git", "rev-parse", "--verify", "--quiet", ref), sourceDir, Duration.ofSeconds(30));
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }

        private boolean gitFileContains(Path sourceDir, String ref, String path, String needle) {
            try {
                return runProcessForOutput(List.of("git", "show", ref + ":" + path), sourceDir, Duration.ofSeconds(30))
                    .contains(needle);
            } catch (Exception ignored) {
                return false;
            }
        }

        private void ensureUsableGradleWrapper(Path sourceDir) throws Exception {
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            Path gradlew = sourceDir.resolve(windows ? "gradlew.bat" : "gradlew");
            if (!Files.isRegularFile(gradlew)) {
                runProcess(List.of("git", "checkout", "origin/HEAD", "--", "gradlew", "gradlew.bat", "gradle"),
                    sourceDir, Duration.ofMinutes(2));
            }
            Path properties = sourceDir.resolve(Paths.get("gradle", "wrapper", "gradle-wrapper.properties"));
            if (Files.isRegularFile(properties)) {
                String text = Files.readString(properties, StandardCharsets.UTF_8);
                String updated = text.replaceAll("gradle-[0-9.]+-bin\\.zip", "gradle-" + MANAGED_GRADLE_VERSION + "-bin.zip");
                if (!updated.equals(text)) {
                    Files.writeString(properties, updated, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
                }
            }
            if (!Files.isRegularFile(gradlew)) {
                throw new MissingBuildToolException("Gradle wrapper could not be prepared for dependency rescue in " + sourceDir);
            }
        }

        private String gradleWrapperPath(Path sourceDir) {
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            return sourceDir.resolve(windows ? "gradlew.bat" : "gradlew").toString();
        }

        private Path findLargestJar(Path dir) throws IOException {
            if (!Files.isDirectory(dir)) {
                return dir.resolve("missing.jar");
            }
            try (var stream = Files.list(dir)) {
                return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> lower(path.getFileName().toString()).endsWith(".jar"))
                    .filter(path -> !isRejectedBuildJar(path))
                    .max((a, b) -> {
                        try {
                            return Long.compare(Files.size(a), Files.size(b));
                        } catch (IOException ex) {
                            return 0;
                        }
                    })
                    .orElse(dir.resolve("missing.jar"));
            }
        }

        private void installMavenJar(Path jar, String groupId, String artifactId, String version) throws Exception {
            String mvn = ManagedMaven.executable(config);
            runProcessForOutput(List.of(
                mvn,
                "-B",
                "-Dmaven.repo.local=" + buildMavenRepo(),
                "org.apache.maven.plugins:maven-install-plugin:3.1.2:install-file",
                "-Dfile=" + jar,
                "-DgroupId=" + groupId,
                "-DartifactId=" + artifactId,
                "-Dversion=" + version,
                "-Dpackaging=jar"
            ), config.baseDir, Duration.ofMinutes(5));
        }

        private Path buildMavenRepo() throws IOException {
            Path repo = config.resolve(config.cacheDir).resolve("build-home").resolve("maven").resolve("repository");
            Files.createDirectories(repo);
            return repo;
        }

        private void repairSourceBuildFiles(Path buildDir, TargetConfig target) {
            repairHardcodedBuildOutputPaths(buildDir, target);
            prepareMavenBuildRescue(buildDir, target);
            prepareGradleBuildRescue(buildDir, target);
        }

        private void prepareGradleBuildRescue(Path buildDir, TargetConfig target) {
            for (String filename : List.of("build.gradle", "build.gradle.kts")) {
                Path gradle = buildDir.resolve(filename);
                if (!Files.isRegularFile(gradle)) {
                    continue;
                }
                try {
                    String original = Files.readString(gradle, StandardCharsets.UTF_8);
                    String text = original;
                    List<String> changes = new ArrayList<>();
                    if (text.contains("repositories {") && !text.contains("mavenLocal()")) {
                        text = text.replaceFirst("repositories\\s*\\{", "repositories {\n    mavenLocal()");
                        changes.add("added mavenLocal for updater-rescued dependencies");
                    }
                    if (!text.equals(original)) {
                        Files.writeString(gradle, text, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
                        Log.info("Prepared Gradle source build for " + target.displayName() + ": " + String.join("; ", changes) + ".");
                    }
                } catch (IOException ex) {
                    Log.warn("Could not prepare Gradle build rescue for " + target.displayName()
                        + ": " + safeExceptionMessage(ex));
                }
            }
        }

        private void prepareMavenBuildRescue(Path buildDir, TargetConfig target) {
            Path pom = buildDir.resolve("pom.xml");
            if (!Files.isRegularFile(pom)) {
                return;
            }
            try {
                String original = Files.readString(pom, StandardCharsets.UTF_8);
                String text = original;
                List<String> changes = new ArrayList<>();

                if (text.contains("https://papermc.io/repo/repository/maven-public/")) {
                    text = text.replace("https://papermc.io/repo/repository/maven-public/",
                        "https://repo.papermc.io/repository/maven-public/");
                    changes.add("updated stale PaperMC Maven repository URL");
                }

                MavenLocalDependencyRescue localRescue = rescueLocalPluginDependencies(text, target);
                text = localRescue.pomText;
                changes.addAll(localRescue.changes);

                if (sourceTreeContains(buildDir, "import lombok.", "lombok.")) {
                    String next = bumpMavenDependencyVersion(text, "org.projectlombok", "lombok", "1.18.42");
                    if (!next.equals(text)) {
                        text = next;
                        changes.add("updated Lombok to 1.18.42 for modern Java compatibility");
                    }
                    next = bumpMavenPluginVersion(text, "org.apache.maven.plugins", "maven-compiler-plugin", "3.13.0");
                    if (!next.equals(text)) {
                        text = next;
                        changes.add("updated maven-compiler-plugin to 3.13.0 for annotation processor support");
                    }
                    next = addLombokAnnotationProcessorPath(text);
                    if (!next.equals(text)) {
                        text = next;
                        changes.add("enabled explicit Lombok annotation processing");
                    }
                }

                if (sourceTreeContains(buildDir, "import javax.annotation.", "@Nullable")
                    && !mavenPomHasDependency(text, "com.google.code.findbugs", "jsr305")) {
                    String next = addMavenDependency(text,
                        "com.google.code.findbugs", "jsr305", "3.0.2", "compile");
                    if (!next.equals(text)) {
                        text = next;
                        changes.add("added jsr305 for javax.annotation compatibility");
                    }
                }

                if (!sourceTreeContains(buildDir, "org.bukkit.craftbukkit", "net.minecraft.server", "net.minecraft.")) {
                    String next = removeMavenDependency(text, "org.spigotmc", "spigot");
                    if (!next.equals(text)) {
                        text = next;
                        changes.add("removed full Spigot server dependency because source uses only API imports");
                    }
                }

                if (!text.equals(original)) {
                    Files.writeString(pom, text, StandardCharsets.UTF_8,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
                    Log.info("Prepared Maven source build for " + target.displayName() + ": "
                        + String.join("; ", changes) + ".");
                }
            } catch (Exception ex) {
                Log.warn("Could not prepare Maven source build rescue for " + target.displayName()
                    + ": " + ex.getMessage());
            }
        }

        private MavenLocalDependencyRescue rescueLocalPluginDependencies(String pomText, TargetConfig target) throws Exception {
            Matcher matcher = Pattern.compile("(?s)<dependency>.*?</dependency>").matcher(pomText);
            StringBuffer result = new StringBuffer();
            List<String> changes = new ArrayList<>();
            while (matcher.find()) {
                String block = matcher.group();
                String groupId = xmlTagValue(block, "groupId");
                String artifactId = xmlTagValue(block, "artifactId");
                String version = xmlTagValue(block, "version");
                if (!groupId.isBlank() && !artifactId.isBlank()) {
                    LocalPluginArtifact local = findLocalPluginArtifactForDependency(artifactId, target);
                    if (local != null) {
                        String requestedVersion = firstNonBlank(version, "");
                        boolean floating = requestedVersion.isBlank()
                            || requestedVersion.equalsIgnoreCase("LATEST")
                            || requestedVersion.equalsIgnoreCase("RELEASE");
                        boolean sameVersion = !requestedVersion.isBlank()
                            && !local.info.version.isBlank()
                            && requestedVersion.equalsIgnoreCase(local.info.version);
                        if (floating || sameVersion) {
                            String installVersion = firstNonBlank(local.info.version, requestedVersion, "local");
                            installMavenJar(local.path, groupId, artifactId, installVersion);
                            String nextBlock = block;
                            if (floating) {
                                nextBlock = replaceOrAddXmlTag(nextBlock, "version", installVersion, "artifactId");
                            }
                            matcher.appendReplacement(result, Matcher.quoteReplacement(nextBlock));
                            changes.add("installed local provided dependency " + groupId + ":" + artifactId + ":" + installVersion);
                            continue;
                        }
                        if (!requestedVersion.isBlank() && !local.info.version.isBlank()) {
                            changes.add("did not spoof local " + artifactId + " " + local.info.version
                                + " as requested dependency version " + requestedVersion);
                        }
                    }
                }
                matcher.appendReplacement(result, Matcher.quoteReplacement(block));
            }
            matcher.appendTail(result);
            return new MavenLocalDependencyRescue(result.toString(), changes);
        }

        private LocalPluginArtifact findLocalPluginArtifactForDependency(String artifactId, TargetConfig target) throws IOException {
            Set<String> wanted = new HashSet<>();
            wanted.add(normalizeIdentity(artifactId));
            wanted.add(normalizeIdentity(jarIdentityHint(artifactId)));
            Set<Path> candidates = new HashSet<>();
            Path defaultPluginDir = config.resolve(Paths.get("plugins"));
            if (Files.isDirectory(defaultPluginDir)) {
                candidates.addAll(listJarFiles(defaultPluginDir));
            }
            if (target.installAs != null && !target.installAs.isBlank()) {
                Path targetPath = config.resolve(Paths.get(target.installAs));
                Path parent = targetPath.getParent();
                if (parent != null && Files.isDirectory(parent)) {
                    candidates.addAll(listJarFiles(parent));
                }
            }
            Path targetPath = target.installAs == null || target.installAs.isBlank()
                ? null
                : config.resolve(Paths.get(target.installAs)).toAbsolutePath().normalize();
            for (Path jar : candidates) {
                Path normalized = jar.toAbsolutePath().normalize();
                if (targetPath != null && normalized.equals(targetPath)) {
                    continue;
                }
                PluginJarInfo info = readPluginJarInfo(jar);
                Set<String> names = new HashSet<>();
                names.add(normalizeIdentity(info.id));
                names.add(normalizeIdentity(info.name));
                names.add(normalizeIdentity(jarIdentityHint(jar.getFileName().toString())));
                names.retainAll(wanted);
                if (!names.isEmpty() && !info.version.isBlank()) {
                    return new LocalPluginArtifact(jar, info);
                }
            }
            return null;
        }

        private boolean sourceTreeContains(Path buildDir, String... needles) throws IOException {
            try (var stream = Files.walk(buildDir, 10)) {
                List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = lower(path.getFileName().toString());
                        return name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".gradle")
                            || name.endsWith(".kts") || name.equals("pom.xml");
                    })
                    .limit(800)
                    .toList();
                for (Path file : files) {
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    for (String needle : needles) {
                        if (text.contains(needle)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private String bumpMavenDependencyVersion(String pomText, String groupId, String artifactId, String version) {
            return updateMavenBlocks(pomText, "dependency", groupId, artifactId,
                block -> replaceOrAddXmlTag(block, "version", version, "artifactId"));
        }

        private String bumpMavenPluginVersion(String pomText, String groupId, String artifactId, String version) {
            return updateMavenBlocks(pomText, "plugin", groupId, artifactId,
                block -> replaceOrAddXmlTag(block, "version", version, "artifactId"));
        }

        private String addLombokAnnotationProcessorPath(String pomText) {
            return updateMavenBlocks(pomText, "plugin", "org.apache.maven.plugins", "maven-compiler-plugin", block -> {
                if (block.contains("<annotationProcessorPaths>")) {
                    return block;
                }
                String processor = System.lineSeparator()
                    + "                    <annotationProcessorPaths>" + System.lineSeparator()
                    + "                        <path>" + System.lineSeparator()
                    + "                            <groupId>org.projectlombok</groupId>" + System.lineSeparator()
                    + "                            <artifactId>lombok</artifactId>" + System.lineSeparator()
                    + "                            <version>1.18.42</version>" + System.lineSeparator()
                    + "                        </path>" + System.lineSeparator()
                    + "                    </annotationProcessorPaths>";
                if (block.contains("</configuration>")) {
                    return block.replace("</configuration>", processor + System.lineSeparator() + "                </configuration>");
                }
                return block.replace("</plugin>", "                <configuration>" + processor
                    + System.lineSeparator() + "                </configuration>" + System.lineSeparator() + "            </plugin>");
            });
        }

        private String updateMavenBlocks(String pomText, String blockName, String groupId, String artifactId,
                                         java.util.function.Function<String, String> updater) {
            Matcher matcher = Pattern.compile("(?s)<" + blockName + ">.*?</" + blockName + ">").matcher(pomText);
            StringBuffer result = new StringBuffer();
            while (matcher.find()) {
                String block = matcher.group();
                String blockGroup = xmlTagValue(block, "groupId");
                String blockArtifact = xmlTagValue(block, "artifactId");
                boolean groupMatches = groupId.isBlank() || blockGroup.isBlank() || blockGroup.equals(groupId);
                if (groupMatches && blockArtifact.equals(artifactId)) {
                    block = updater.apply(block);
                }
                matcher.appendReplacement(result, Matcher.quoteReplacement(block));
            }
            matcher.appendTail(result);
            return result.toString();
        }

        private boolean mavenPomHasDependency(String pomText, String groupId, String artifactId) {
            Matcher matcher = Pattern.compile("(?s)<dependency>.*?</dependency>").matcher(pomText);
            while (matcher.find()) {
                String block = matcher.group();
                if (xmlTagValue(block, "groupId").equals(groupId)
                    && xmlTagValue(block, "artifactId").equals(artifactId)) {
                    return true;
                }
            }
            return false;
        }

        private String addMavenDependency(String pomText, String groupId, String artifactId, String version, String scope) {
            int end = pomText.lastIndexOf("</dependencies>");
            if (end < 0) {
                return pomText;
            }
            String dependency = System.lineSeparator()
                + "        <dependency>" + System.lineSeparator()
                + "            <groupId>" + groupId + "</groupId>" + System.lineSeparator()
                + "            <artifactId>" + artifactId + "</artifactId>" + System.lineSeparator()
                + "            <version>" + version + "</version>" + System.lineSeparator()
                + "            <scope>" + scope + "</scope>" + System.lineSeparator()
                + "        </dependency>";
            return pomText.substring(0, end) + dependency + System.lineSeparator() + pomText.substring(end);
        }

        private String removeMavenDependency(String pomText, String groupId, String artifactId) {
            Matcher matcher = Pattern.compile("(?s)\\s*<dependency>.*?</dependency>").matcher(pomText);
            StringBuffer result = new StringBuffer();
            while (matcher.find()) {
                String block = matcher.group();
                if (xmlTagValue(block, "groupId").equals(groupId)
                    && xmlTagValue(block, "artifactId").equals(artifactId)) {
                    matcher.appendReplacement(result, "");
                    continue;
                }
                matcher.appendReplacement(result, Matcher.quoteReplacement(block));
            }
            matcher.appendTail(result);
            return result.toString();
        }

        private String xmlTagValue(String text, String tag) {
            Matcher matcher = Pattern.compile("(?s)<" + Pattern.quote(tag) + ">\\s*(.*?)\\s*</" + Pattern.quote(tag) + ">").matcher(text);
            return matcher.find() ? matcher.group(1).trim() : "";
        }

        private String replaceOrAddXmlTag(String block, String tag, String value, String afterTag) {
            Pattern pattern = Pattern.compile("(?s)<" + Pattern.quote(tag) + ">.*?</" + Pattern.quote(tag) + ">");
            Matcher matcher = pattern.matcher(block);
            String replacement = "<" + tag + ">" + value + "</" + tag + ">";
            if (matcher.find()) {
                return matcher.replaceFirst(Matcher.quoteReplacement(replacement));
            }
            String afterClose = "</" + afterTag + ">";
            int index = block.indexOf(afterClose);
            if (index < 0) {
                return block;
            }
            int insert = index + afterClose.length();
            return block.substring(0, insert) + System.lineSeparator()
                + "            " + replacement + block.substring(insert);
        }

        private void repairHardcodedBuildOutputPaths(Path buildDir, TargetConfig target) {
            List<String> repaired = new ArrayList<>();
            try (var stream = Files.walk(buildDir, 5)) {
                List<Path> poms = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase("pom.xml"))
                    .toList();
                for (Path pom : poms) {
                    if (repairMavenOutputFilePath(buildDir, pom, target)) {
                        repaired.add(normalizeSlashes(buildDir.relativize(pom).toString()));
                    }
                }
            } catch (IOException ex) {
                Log.warn("Could not inspect source build output paths for " + target.displayName()
                    + ": " + ex.getMessage());
            }
            if (!repaired.isEmpty()) {
                Log.info("Repaired hardcoded build output path for " + target.displayName()
                    + " in " + String.join(", ", repaired) + ".");
            }
        }

        private boolean repairMavenOutputFilePath(Path buildDir, Path pom, TargetConfig target) throws IOException {
            String text = Files.readString(pom, StandardCharsets.UTF_8);
            StringBuilder result = new StringBuilder(text.length());
            int cursor = 0;
            boolean changed = false;
            String openTag = "<outputFile>";
            String closeTag = "</outputFile>";
            Path outputDir = buildDir.resolve("updater-build-output");
            String outputFile = outputDir.resolve(safeName(target.displayName()) + ".jar").toString();
            while (true) {
                int start = text.indexOf(openTag, cursor);
                if (start < 0) {
                    result.append(text.substring(cursor));
                    break;
                }
                int valueStart = start + openTag.length();
                int end = text.indexOf(closeTag, valueStart);
                if (end < 0) {
                    result.append(text.substring(cursor));
                    break;
                }
                String value = text.substring(valueStart, end).trim();
                result.append(text, cursor, valueStart);
                if (isHardcodedExternalOutputPath(buildDir, value)) {
                    result.append(outputFile);
                    changed = true;
                } else {
                    result.append(text, valueStart, end);
                }
                cursor = end;
            }
            if (changed) {
                Files.createDirectories(outputDir);
                Files.writeString(pom, result.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
            }
            return changed;
        }

        private boolean isHardcodedExternalOutputPath(Path buildDir, String value) {
            if (value.isBlank() || value.contains("${")) {
                return false;
            }
            if (!looksLikeAbsolutePath(value)) {
                return false;
            }
            try {
                Path output = Paths.get(value).toAbsolutePath().normalize();
                Path root = buildDir.toAbsolutePath().normalize();
                return !output.startsWith(root);
            } catch (Exception ex) {
                return true;
            }
        }

        private boolean looksLikeAbsolutePath(String value) {
            String normalized = value.replace('\\', '/');
            return normalized.startsWith("/")
                || normalized.startsWith("//")
                || (normalized.length() >= 3
                && Character.isLetter(normalized.charAt(0))
                && normalized.charAt(1) == ':'
                && normalized.charAt(2) == '/');
        }

        private String writeSourceBuildFailureLog(String repoName, String commit, String message) {
            try {
                Path dir = config.resolve(config.cacheDir).resolve("source-build-failures");
                Files.createDirectories(dir);
                String file = safeName(repoName.replace("/", "-") + "-" + shortHash(commit)) + ".log";
                Path log = dir.resolve(file);
                List<String> lines = new ArrayList<>();
                lines.add("repo: " + repoName);
                lines.add("commit: " + commit);
                lines.add("failedAt: " + Instant.now());
                lines.add("");
                lines.add(firstNonBlank(message, "source build failed"));
                Files.write(log, lines, StandardCharsets.UTF_8);
                return normalizeSlashes(config.baseDir.relativize(log).toString());
            } catch (Exception ignored) {
                return "";
            }
        }

        private void validateBuiltJar(Path path) throws IOException {
            try (JarFile ignored = new JarFile(path.toFile(), false)) {
                // Opening the JarFile verifies the built artifact is a readable jar.
            }
        }

        private GithubRepo inferRepo(TargetConfig target) {
            String value = firstNonBlank(target.githubRepo, target.project, target.source, "");
            if (value.contains("github.com/")) {
                List<String> parts = pathParts(URI.create(value));
                if (parts.size() >= 2) {
                    return new GithubRepo(parts.get(0), parts.get(1).replace(".git", ""));
                }
            }
            if (value.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
                String[] parts = value.split("/", 2);
                return new GithubRepo(parts[0], parts[1].replace(".git", ""));
            }
            throw new IllegalArgumentException("Git source needs githubRepo: Owner/Repo or a GitHub URL for " + target.displayName());
        }

        private boolean isTrusted(String repo) {
            String normalized = repo.trim();
            if (config.buildFromSource.trustedGithubRepos.stream().anyMatch(r -> r.equalsIgnoreCase(normalized))) {
                return true;
            }
            int slash = normalized.indexOf('/');
            if (slash <= 0) {
                return false;
            }
            String org = normalized.substring(0, slash);
            return config.buildFromSource.trustedGithubOrgs.stream().anyMatch(o -> o.equalsIgnoreCase(org));
        }

        private void syncRepo(GithubRepo repo, Path sourceDir) throws Exception {
            Files.createDirectories(sourceDir.getParent());
            String url = "https://github.com/" + repo.owner + "/" + repo.name + ".git";
            String ref = cleanGithubRef(repo.ref);
            if (!Files.isDirectory(sourceDir.resolve(".git"))) {
                List<String> command = new ArrayList<>(List.of("git", "-c", "core.longpaths=true", "clone", "--depth", "1"));
                if (!ref.isBlank()) {
                    command.addAll(List.of("--branch", ref, "--single-branch"));
                }
                command.addAll(List.of(url, sourceDir.toString()));
                runProcess(command, config.baseDir, Duration.ofMinutes(5));
                configureGitLongPaths(sourceDir);
                return;
            }
            configureGitLongPaths(sourceDir);
            if (ref.isBlank()) {
                runProcess(List.of("git", "fetch", "--depth", "1", "origin"), sourceDir, Duration.ofMinutes(5));
                runProcess(List.of("git", "reset", "--hard", "origin/HEAD"), sourceDir, Duration.ofMinutes(2));
            } else {
                runProcess(List.of("git", "fetch", "--depth", "1", "origin", ref), sourceDir, Duration.ofMinutes(5));
                runProcess(List.of("git", "checkout", "--force", "FETCH_HEAD"), sourceDir, Duration.ofMinutes(2));
            }
        }

        private void configureGitLongPaths(Path sourceDir) {
            try {
                runProcessForOutput(List.of("git", "config", "core.longpaths", "true"), sourceDir, Duration.ofSeconds(30));
            } catch (Exception ignored) {
                // Best effort for Windows source trees with long Java package paths.
            }
        }

        private String currentCommit(Path sourceDir) throws Exception {
            return runProcessForOutput(List.of("git", "rev-parse", "HEAD"), sourceDir, Duration.ofSeconds(30)).trim();
        }

        private PluginJarInfo installedPluginInfo(TargetConfig target) {
            if (target.installAs != null && !target.installAs.isBlank()) {
                Path path = config.resolve(Paths.get(target.installAs));
                if (Files.isRegularFile(path)) {
                    return readPluginJarInfo(path);
                }
            }
            return new PluginJarInfo(
                firstNonBlank(target.detectedPluginId, target.name, ""),
                firstNonBlank(target.name, target.detectedPluginId, ""),
                firstNonBlank(target.detectedVersion, ""),
                firstNonBlank(target.detectedWebsite, ""),
                firstNonBlank(target.detectedMainClass, ""),
                firstNonBlank(target.detectedAuthors, ""),
                "",
                Set.of(),
                null,
                !firstNonBlank(target.detectedPluginId, target.name, "").isBlank()
            );
        }

        private Path buildDirectoryForTarget(Path sourceDir, TargetConfig target) throws IOException {
            PluginJarInfo installed = installedPluginInfo(target);
            try (var stream = Files.walk(sourceDir, 8)) {
                List<Path> paths = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> isPluginDescriptorPath(normalizeSlashes(sourceDir.relativize(path).toString())))
                    .sorted(Comparator
                        .comparingInt((Path path) -> descriptorPathPriority(normalizeSlashes(sourceDir.relativize(path).toString())))
                        .thenComparing(path -> normalizeSlashes(sourceDir.relativize(path).toString()).length()))
                    .limit(40)
                    .toList();
                for (Path path : paths) {
                    String relative = normalizeSlashes(sourceDir.relativize(path).toString());
                    PluginJarInfo info = parsePluginDescriptor(relative, Files.readString(path, StandardCharsets.UTF_8));
                    if (info.hasDescriptor && pluginDescriptorMatchesTarget(installed, target, info)) {
                        Path buildRoot = nearestBuildRoot(sourceDir, path.getParent());
                        if (buildRoot != null) {
                            return buildRoot;
                        }
                    }
                }
            }
            return sourceDir;
        }

        private Path nearestBuildRoot(Path sourceDir, Path start) {
            Path root = sourceDir.toAbsolutePath().normalize();
            Path current = start.toAbsolutePath().normalize();
            while (current != null && current.startsWith(root)) {
                if (Files.isRegularFile(current.resolve("pom.xml"))
                    || Files.isRegularFile(current.resolve("mvnw"))
                    || Files.isRegularFile(current.resolve("mvnw.cmd"))
                    || Files.isRegularFile(current.resolve("build.gradle"))
                    || Files.isRegularFile(current.resolve("build.gradle.kts"))
                    || Files.isRegularFile(current.resolve("settings.gradle"))
                    || Files.isRegularFile(current.resolve("settings.gradle.kts"))
                    || Files.isRegularFile(current.resolve("gradlew"))
                    || Files.isRegularFile(current.resolve("gradlew.bat"))) {
                    return current;
                }
                current = current.getParent();
            }
            return null;
        }

        private List<BuildCommand> detectBuildCommands(Path sourceDir) throws IOException {
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            Path mvnw = sourceDir.resolve(windows ? "mvnw.cmd" : "mvnw");
            Path gradlew = sourceDir.resolve(windows ? "gradlew.bat" : "gradlew");
            boolean hasMavenWrapper = Files.isRegularFile(mvnw);
            boolean hasGradleWrapper = Files.isRegularFile(gradlew);
            boolean hasPom = Files.isRegularFile(sourceDir.resolve("pom.xml"));
            boolean hasGradleBuild = Files.isRegularFile(sourceDir.resolve("build.gradle"))
                || Files.isRegularFile(sourceDir.resolve("build.gradle.kts"))
                || Files.isRegularFile(sourceDir.resolve("settings.gradle"))
                || Files.isRegularFile(sourceDir.resolve("settings.gradle.kts"));
            if (!hasMavenWrapper && !hasGradleWrapper && !hasPom && !hasGradleBuild) {
                throw new IllegalArgumentException("Could not auto-detect Gradle or Maven build files in " + sourceDir);
            }
            String mavenExecutable = "";
            String gradleExecutable = "";
            if (hasMavenWrapper) {
                mavenExecutable = mvnw.toString();
            } else if (hasPom) {
                mavenExecutable = ManagedMaven.executable(config);
            }
            if (hasGradleWrapper) {
                gradleExecutable = gradlew.toString();
            } else if (hasGradleBuild) {
                gradleExecutable = ManagedGradle.executable(config);
            }
            Map<String, BuildCommand> commands = new LinkedHashMap<>();
            for (BuildCommand command : detectCiBuildCommands(sourceDir, mavenExecutable, gradleExecutable)) {
                addBuildCommand(commands, command);
            }
            if (!mavenExecutable.isBlank()) {
                addMavenBuildCommands(commands, mavenExecutable, hasMavenWrapper ? "Maven wrapper default" : "Managed/system Maven default");
            }
            if (!gradleExecutable.isBlank()) {
                addGradleBuildCommands(commands, gradleExecutable, sourceDir, hasGradleWrapper ? "Gradle wrapper default" : "Managed/system Gradle default");
            }
            return new ArrayList<>(commands.values());
        }

        private void addMavenBuildCommands(Map<String, BuildCommand> commands, String executable, String reason) {
            addBuildCommand(commands, new BuildCommand(List.of(executable, "-B", "package", "-DskipTests"), reason));
            addBuildCommand(commands, new BuildCommand(List.of(executable, "-B", "clean", "package", "-DskipTests"), "Maven clean package fallback"));
            addBuildCommand(commands, new BuildCommand(List.of(executable, "-B", "install", "-DskipTests"), "Maven install fallback"));
            addBuildCommand(commands, new BuildCommand(List.of(executable, "-B", "clean", "install", "-DskipTests"), "Maven clean install fallback"));
            addBuildCommand(commands, new BuildCommand(List.of(executable, "-B", "package", "-Dmaven.test.skip=true"), "Maven package fallback with test compilation skipped"));
        }

        private void addGradleBuildCommands(Map<String, BuildCommand> commands, String executable, Path sourceDir, String reason) {
            if (gradleBuildMentions(sourceDir, "shadowjar") || gradleBuildMentions(sourceDir, "com.github.johnrengelman.shadow")) {
                addBuildCommand(commands, new BuildCommand(List.of(executable, "shadowJar", "-x", "test"), "Gradle shadowJar detected in build files"));
            }
            addBuildCommand(commands, new BuildCommand(List.of(executable, "build", "-x", "test"), reason));
            addBuildCommand(commands, new BuildCommand(List.of(executable, "clean", "build", "-x", "test"), "Gradle clean build fallback"));
            addBuildCommand(commands, new BuildCommand(List.of(executable, "jar", "-x", "test"), "Gradle jar fallback"));
        }

        private void addBuildCommand(Map<String, BuildCommand> commands, BuildCommand command) {
            commands.putIfAbsent(String.join("\u0000", command.command), command);
        }

        private List<BuildCommand> detectCiBuildCommands(Path sourceDir, String mavenExecutable, String gradleExecutable) {
            Path workflows = sourceDir.resolve(".github").resolve("workflows");
            if (!Files.isDirectory(workflows)) {
                return Collections.emptyList();
            }
            List<BuildCommand> commands = new ArrayList<>();
            try (var stream = Files.walk(workflows, 2)) {
                List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = lower(path.getFileName().toString());
                        return name.endsWith(".yml") || name.endsWith(".yaml");
                    })
                    .sorted()
                    .limit(12)
                    .toList();
                for (Path file : files) {
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (String line : lines) {
                        String command = ciRunCommand(line);
                        if (command.isBlank()) {
                            continue;
                        }
                        BuildCommand buildCommand = ciBuildCommand(command, mavenExecutable, gradleExecutable);
                        if (buildCommand != null) {
                            commands.add(buildCommand);
                        }
                    }
                }
            } catch (IOException ex) {
                Log.info("Could not inspect GitHub Actions build commands in " + sourceDir + ": " + ex.getMessage());
            }
            return commands;
        }

        private String ciRunCommand(String line) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- ")) {
                trimmed = trimmed.substring(2).trim();
            }
            if (!lower(trimmed).startsWith("run:")) {
                return "";
            }
            String command = ConfigParser.unquote(trimmed.substring(4).trim());
            if (command.contains("&&") || command.contains(";") || command.contains("|") || command.contains(">") || command.contains("<")) {
                return "";
            }
            return command;
        }

        private BuildCommand ciBuildCommand(String command, String mavenExecutable, String gradleExecutable) {
            List<String> parts = splitCommand(command);
            if (parts.isEmpty()) {
                return null;
            }
            String executable = normalizeBuildExecutable(parts.get(0));
            String lowerCommand = lower(command);
            if (isMavenExecutableName(executable)) {
                if (mavenExecutable.isBlank() || !looksLikeBuildCommand(lowerCommand)) {
                    return null;
                }
                parts.set(0, mavenExecutable);
                return new BuildCommand(parts, "GitHub Actions Maven command");
            }
            if (isGradleExecutableName(executable)) {
                if (gradleExecutable.isBlank() || !looksLikeBuildCommand(lowerCommand)) {
                    return null;
                }
                parts.set(0, gradleExecutable);
                return new BuildCommand(parts, "GitHub Actions Gradle command");
            }
            return null;
        }

        private String normalizeBuildExecutable(String value) {
            String result = value.replace("\\", "/");
            while (result.startsWith("./")) {
                result = result.substring(2);
            }
            return lower(result);
        }

        private boolean isMavenExecutableName(String value) {
            return value.equals("mvn") || value.equals("mvn.cmd") || value.equals("mvnw") || value.equals("mvnw.cmd");
        }

        private boolean isGradleExecutableName(String value) {
            return value.equals("gradle") || value.equals("gradle.bat") || value.equals("gradle.cmd")
                || value.equals("gradlew") || value.equals("gradlew.bat");
        }

        private boolean looksLikeBuildCommand(String command) {
            return command.contains(" package")
                || command.contains(" install")
                || command.contains(" build")
                || command.contains(" shadowjar")
                || command.contains(" jar");
        }

        private boolean gradleBuildMentions(Path sourceDir, String needle) {
            for (String file : List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")) {
                Path path = sourceDir.resolve(file);
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                try {
                    if (lower(Files.readString(path, StandardCharsets.UTF_8)).contains(lower(needle))) {
                        return true;
                    }
                } catch (IOException ignored) {
                    // Fall back to the generic Gradle commands.
                }
            }
            return false;
        }

        private boolean commandExists(String command) {
            String path = firstNonBlank(System.getenv("PATH"), "");
            if (path.isBlank()) {
                return false;
            }
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            List<String> names = windows
                ? List.of(command + ".exe", command + ".cmd", command + ".bat", command)
                : List.of(command);
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (dir.isBlank()) {
                    continue;
                }
                for (String name : names) {
                    if (Files.isRegularFile(Paths.get(dir).resolve(name))) {
                        return true;
                    }
                }
            }
            return false;
        }

        private void runProcess(List<String> command, Path dir, Duration timeout) throws Exception {
            runProcessForOutput(command, dir, timeout);
        }

        private void runBuildProcess(BuildCommand command, Path dir, Duration timeout, boolean refreshDependencies) throws Exception {
            runBuildProcess(command, dir, timeout, refreshDependencies, null);
        }

        private void runBuildProcess(BuildCommand command, Path dir, Duration timeout, boolean refreshDependencies, Path javaHome) throws Exception {
            BuildInvocation invocation = buildInvocation(command, refreshDependencies, javaHome);
            runProcessForOutput(invocation.command, dir, timeout, invocation.environment, refreshDependencies);
        }

        private String runProcessForOutput(List<String> command, Path dir, Duration timeout) throws Exception {
            return runProcessForOutput(command, dir, timeout, Collections.emptyMap(), false);
        }

        private String runProcessForOutput(List<String> command, Path dir, Duration timeout,
                                           Map<String, String> environment, boolean refreshed) throws Exception {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(dir.toFile());
            builder.redirectErrorStream(true);
            if (!environment.isEmpty()) {
                builder.environment().putAll(environment);
            }
            Process process = builder.start();
            StringBuilder output = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        output.append(line).append(System.lineSeparator());
                    }
                } catch (IOException ignored) {
                    // Process ended.
                }
            }, "git-source-build-output");
            reader.setDaemon(true);
            reader.start();
            boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroy();
                process.waitFor(15, TimeUnit.SECONDS);
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                throw new IOException("Command timed out: " + String.join(" ", command));
            }
            reader.join(TimeUnit.SECONDS.toMillis(5));
            if (process.exitValue() != 0) {
                throw new BuildProcessException(
                    "Command failed (" + process.exitValue() + "): " + String.join(" ", command),
                    output.toString(),
                    refreshed
                );
            }
            return output.toString();
        }

        private BuildInvocation buildInvocation(BuildCommand command, boolean refreshDependencies, Path javaHome) throws IOException {
            List<String> result = new ArrayList<>(command.command);
            Map<String, String> environment = new HashMap<>();
            if (javaHome != null) {
                Path bin = javaHome.resolve("bin");
                environment.put("JAVA_HOME", javaHome.toString());
                environment.put("PATH", bin + java.io.File.pathSeparator + firstNonBlank(System.getenv("PATH"), ""));
            }
            if (isMavenCommand(result)) {
                Path repo = config.resolve(config.cacheDir).resolve("build-home").resolve("maven").resolve("repository");
                Files.createDirectories(repo);
                addMavenArgIfMissing(result, "-Dmaven.repo.local=" + repo);
                if (refreshDependencies) {
                    addMavenArgIfMissing(result, "-U");
                }
            } else if (isGradleCommand(result)) {
                Path gradleHome = config.resolve(config.cacheDir).resolve("build-home").resolve("gradle");
                Path mavenRepo = config.resolve(config.cacheDir).resolve("build-home").resolve("maven").resolve("repository");
                Files.createDirectories(gradleHome);
                Files.createDirectories(mavenRepo);
                environment.put("GRADLE_USER_HOME", gradleHome.toString());
                addGradleArgIfMissing(result, "-Dmaven.repo.local=" + mavenRepo);
                if (refreshDependencies && !result.contains("--refresh-dependencies")) {
                    result.add("--refresh-dependencies");
                }
            }
            return new BuildInvocation(result, environment);
        }

        private void addMavenArgIfMissing(List<String> command, String arg) {
            String key = arg.contains("=") ? arg.substring(0, arg.indexOf('=')) : arg;
            for (String existing : command) {
                if (existing.equals(arg) || existing.startsWith(key + "=")) {
                    return;
                }
            }
            command.add(1, arg);
        }

        private void addGradleArgIfMissing(List<String> command, String arg) {
            if (command.contains(arg)) {
                return;
            }
            command.add(1, arg);
        }

        private boolean isMavenCommand(List<String> command) {
            if (command.isEmpty()) {
                return false;
            }
            String executable = normalizeBuildExecutable(Path.of(command.get(0)).getFileName().toString());
            return isMavenExecutableName(executable);
        }

        private boolean isGradleCommand(List<String> command) {
            if (command.isEmpty()) {
                return false;
            }
            String executable = normalizeBuildExecutable(Path.of(command.get(0)).getFileName().toString());
            return isGradleExecutableName(executable);
        }

        private boolean isDependencyResolutionFailure(String output) {
            String text = lower(output);
            return text.contains("could not resolve")
                || text.contains("could not collect dependencies")
                || text.contains("failed to collect dependencies")
                || text.contains("could not transfer artifact")
                || text.contains("could not transfer metadata")
                || text.contains("could not get resource")
                || text.contains("the following artifacts could not be resolved")
                || text.contains("non-resolvable parent pom")
                || text.contains("status code: 403")
                || text.contains("status code: 521")
                || text.contains("received status code 521");
        }

        private boolean isCorruptDependencyCacheFailure(String output) {
            String text = lower(output);
            return text.contains("zip end header not found")
                || text.contains("non-parseable pom")
                || text.contains("invalid: expected = after attribute name")
                || text.contains("error in opening zip file");
        }

        private boolean isCompilationFailure(String output) {
            String text = lower(output);
            return text.contains("compilation error")
                || text.contains("compilation failure")
                || text.contains("cannot find symbol")
                || text.contains("package ") && text.contains(" does not exist")
                || text.contains("method ") && text.contains(" cannot be applied")
                || text.contains("is not abstract and does not override");
        }

        private String shortBuildFailureSummary(String output) {
            String[] lines = firstNonBlank(output, "").split("\\R");
            for (String line : lines) {
                String trimmed = line.trim();
                String lower = lower(trimmed);
                if (lower.startsWith("[error] dependency:")
                    || lower.contains("could not find artifact")
                    || lower.contains("the pom for ") && lower.contains(" is missing")) {
                    return trimmed;
                }
            }
            for (String line : lines) {
                String trimmed = line.trim();
                String lower = lower(trimmed);
                if (lower.contains("could not collect dependencies")
                    || lower.contains("the following artifacts could not be resolved")
                    || lower.contains("could not transfer artifact")
                    || lower.contains("could not find artifact")
                    || lower.contains("zip end header not found")
                    || lower.contains("non-parseable pom")
                    || lower.contains("failed to execute goal")
                    || lower.contains("cannot find symbol")
                    || lower.contains("package ") && lower.contains(" does not exist")) {
                    return trimmed;
                }
            }
            return abbreviate(tail(output, 700).replaceAll("\\R+", " ").trim(), 700);
        }

        private Path findBuiltJar(Path sourceDir, TargetConfig target) throws IOException {
            List<Path> jars = new ArrayList<>();
            try (var stream = Files.walk(sourceDir, 8)) {
                stream.filter(Files::isRegularFile)
                    .filter(path -> lower(path.getFileName().toString()).endsWith(".jar"))
                    .filter(path -> !isRejectedBuildJar(path))
                    .forEach(jars::add);
            }
            if (jars.isEmpty()) {
                throw new IOException("Build completed but no usable jar was found under " + sourceDir);
            }
            PluginJarInfo installed = installedPluginInfo(target);
            List<Path> matching = new ArrayList<>();
            for (Path jar : jars) {
                PluginJarInfo info = readPluginJarInfo(jar);
                if (pluginDescriptorMatchesTarget(installed, target, info)) {
                    matching.add(jar);
                }
            }
            if (!matching.isEmpty()) {
                jars = matching;
            }
            jars.sort((a, b) -> {
                try {
                    int modified = Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                    if (modified != 0) {
                        return modified;
                    }
                    return Long.compare(Files.size(b), Files.size(a));
                } catch (IOException ex) {
                    return 0;
                }
            });
            return jars.get(0);
        }

        private boolean isRejectedBuildJar(Path path) {
            String filename = lower(path.getFileName().toString());
            String normalized = normalizeSlashes(path.toString()).toLowerCase(Locale.ROOT);
            return filename.contains("sources")
                || filename.contains("javadoc")
                || filename.contains("-plain")
                || filename.contains("-dev")
                || filename.startsWith("original-")
                || normalized.contains("/build/tmp/")
                || normalized.contains("/.gradle/");
        }

        private String tail(String text, int maxChars) {
            if (text.length() <= maxChars) {
                return text;
            }
            return text.substring(text.length() - maxChars);
        }

        private static final class MavenLocalDependencyRescue {
            final String pomText;
            final List<String> changes;

            MavenLocalDependencyRescue(String pomText, List<String> changes) {
                this.pomText = pomText;
                this.changes = changes;
            }
        }

        private static final class LocalPluginArtifact {
            final Path path;
            final PluginJarInfo info;

            LocalPluginArtifact(Path path, PluginJarInfo info) {
                this.path = path;
                this.info = info;
            }
        }

        private static final class BuildCommand {
            final List<String> command;
            final String reason;

            BuildCommand(List<String> command) {
                this(command, "");
            }

            BuildCommand(List<String> command, String reason) {
                this.command = command;
                this.reason = firstNonBlank(reason, "");
            }
        }

        private static final class BuildInvocation {
            final List<String> command;
            final Map<String, String> environment;

            BuildInvocation(List<String> command, Map<String, String> environment) {
                this.command = command;
                this.environment = environment;
            }
        }

        private static final class BuildJavaChoice {
            final int major;
            final Path javaHome;

            BuildJavaChoice(int major, Path javaHome) {
                this.major = major;
                this.javaHome = javaHome;
            }

            static BuildJavaChoice current() {
                return new BuildJavaChoice(Runtime.version().feature(), null);
            }
        }

        private static final class JavaRetryResult {
            Path jar;
            String failureSummary = "";
            String details = "";
        }

        private static final class BuildProcessException extends IOException {
            final String output;
            final boolean refreshed;

            BuildProcessException(String message, String output, boolean refreshed) {
                super(message);
                this.output = firstNonBlank(output, "");
                this.refreshed = refreshed;
            }
        }

        private static final class SourceBuildException extends IOException {
            final String details;

            SourceBuildException(String message, String details) {
                super(message);
                this.details = firstNonBlank(details, message);
            }
        }
    }

    private static final class ManagedMaven {
        private ManagedMaven() {
        }

        static String executable(AppConfig config) throws IOException {
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            String envHome = firstNonBlank(System.getenv("MAVEN_HOME"), System.getenv("M2_HOME"));
            if (!envHome.isBlank()) {
                Path envExe = Paths.get(envHome).resolve("bin").resolve(windows ? "mvn.cmd" : "mvn");
                if (Files.isRegularFile(envExe)) {
                    return envExe.toString();
                }
            }
            String pathExe = commandOnPath("mvn", windows);
            if (!pathExe.isBlank()) {
                return pathExe;
            }
            return ensureManaged(config, windows).toString();
        }

        private static Path ensureManaged(AppConfig config, boolean windows) throws IOException {
            Path toolsDir = config.resolve(config.cacheDir).resolve("tools").resolve("maven");
            Path installDir = toolsDir.resolve("apache-maven-" + MANAGED_MAVEN_VERSION);
            Path executable = installDir.resolve("bin").resolve(windows ? "mvn.cmd" : "mvn");
            if (Files.isRegularFile(executable)) {
                return executable;
            }

            Files.createDirectories(toolsDir);
            String filename = "apache-maven-" + MANAGED_MAVEN_VERSION + "-bin.zip";
            URI zipUri = URI.create("https://archive.apache.org/dist/maven/maven-3/"
                + MANAGED_MAVEN_VERSION + "/binaries/" + filename);
            URI shaUri = URI.create(zipUri + ".sha512");
            Path zip = toolsDir.resolve(filename);
            Path sha = toolsDir.resolve(filename + ".sha512");

            Log.info("Managed Maven " + MANAGED_MAVEN_VERSION + " is not cached; downloading to " + toolsDir + ".");
            download(config, zipUri, zip);
            download(config, shaUri, sha);
            verifySha512(zip, sha);

            if (Files.exists(installDir)) {
                deleteRecursively(installDir);
            }
            unzip(zip, toolsDir);
            if (!Files.isRegularFile(executable)) {
                throw new IOException("Managed Maven download did not produce expected executable: " + executable);
            }
            deleteQuietly(zip);
            deleteQuietly(sha);
            if (!windows) {
                executable.toFile().setExecutable(true);
            }
            Log.info("Managed Maven ready: " + executable);
            return executable;
        }

        private static String commandOnPath(String command, boolean windows) {
            String path = firstNonBlank(System.getenv("PATH"), "");
            if (path.isBlank()) {
                return "";
            }
            List<String> names = windows
                ? List.of(command + ".cmd", command + ".bat", command + ".exe", command)
                : List.of(command);
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (dir.isBlank()) {
                    continue;
                }
                for (String name : names) {
                    Path candidate = Paths.get(dir).resolve(name);
                    if (Files.isRegularFile(candidate)) {
                        return candidate.toString();
                    }
                }
            }
            return "";
        }

        private static void download(AppConfig config, URI uri, Path destination) throws IOException {
            try {
                Path tmp = destination.resolveSibling(destination.getFileName() + ".tmp");
                HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMinutes(5))
                    .header("User-Agent", config.userAgent)
                    .GET()
                    .build();
                HttpResponse<InputStream> response = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status < 200 || status >= 300) {
                    throw new IOException("Managed Maven download failed with HTTP " + status + " from " + uri);
                }
                try (InputStream in = response.body()) {
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(tmp, destination, StandardCopyOption.REPLACE_EXISTING);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while downloading managed Maven from " + uri, ex);
            }
        }

        private static void verifySha512(Path zip, Path shaFile) throws IOException {
            String expected = expectedSha512(shaFile);
            String actual;
            try {
                actual = sha512(zip);
            } catch (Exception ex) {
                throw new IOException("Could not calculate SHA-512 for managed Maven download", ex);
            }
            if (!actual.equalsIgnoreCase(expected)) {
                Files.deleteIfExists(zip);
                throw new IOException("Managed Maven SHA-512 mismatch: expected " + expected + " but got " + actual);
            }
        }

        private static String expectedSha512(Path shaFile) throws IOException {
            String text = Files.readString(shaFile, StandardCharsets.UTF_8);
            for (String token : text.split("\\s+")) {
                if (token.matches("[A-Fa-f0-9]{128}")) {
                    return token;
                }
            }
            throw new IOException("Managed Maven SHA-512 file did not contain a valid hash: " + shaFile);
        }

        private static void unzip(Path zip, Path destination) throws IOException {
            Path normalizedDestination = destination.toAbsolutePath().normalize();
            try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
                ZipEntry entry;
                while ((entry = in.getNextEntry()) != null) {
                    Path output = normalizedDestination.resolve(entry.getName()).normalize();
                    if (!output.startsWith(normalizedDestination)) {
                        throw new IOException("Refusing to extract managed Maven entry outside cache: " + entry.getName());
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(output);
                    } else {
                        Files.createDirectories(output.getParent());
                        Files.copy(in, output, StandardCopyOption.REPLACE_EXISTING);
                    }
                    in.closeEntry();
                }
            }
        }

        private static void deleteRecursively(Path path) throws IOException {
            if (!Files.exists(path)) {
                return;
            }
            try (var stream = Files.walk(path)) {
                List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
                for (Path item : paths) {
                    Files.deleteIfExists(item);
                }
            }
        }

        private static void deleteQuietly(Path path) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // A cached archive is disposable; leave it alone if another process has it open.
            }
        }
    }

    private static final class ManagedGradle {
        private ManagedGradle() {
        }

        static String executable(AppConfig config) throws IOException {
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            String envHome = firstNonBlank(System.getenv("GRADLE_HOME"));
            if (!envHome.isBlank()) {
                Path envExe = Paths.get(envHome).resolve("bin").resolve(windows ? "gradle.bat" : "gradle");
                if (Files.isRegularFile(envExe)) {
                    return envExe.toString();
                }
            }
            String pathExe = ManagedMaven.commandOnPath("gradle", windows);
            if (!pathExe.isBlank()) {
                return pathExe;
            }
            return ensureManaged(config, windows).toString();
        }

        private static Path ensureManaged(AppConfig config, boolean windows) throws IOException {
            Path toolsDir = config.resolve(config.cacheDir).resolve("tools").resolve("gradle");
            Path installDir = toolsDir.resolve("gradle-" + MANAGED_GRADLE_VERSION);
            Path executable = installDir.resolve("bin").resolve(windows ? "gradle.bat" : "gradle");
            if (Files.isRegularFile(executable)) {
                return executable;
            }

            Files.createDirectories(toolsDir);
            String filename = "gradle-" + MANAGED_GRADLE_VERSION + "-bin.zip";
            URI zipUri = URI.create("https://services.gradle.org/distributions/" + filename);
            URI shaUri = URI.create(zipUri + ".sha256");
            Path zip = toolsDir.resolve(filename);
            Path sha = toolsDir.resolve(filename + ".sha256");

            Log.info("Managed Gradle " + MANAGED_GRADLE_VERSION + " is not cached; downloading to " + toolsDir + ".");
            ManagedMaven.download(config, zipUri, zip);
            ManagedMaven.download(config, shaUri, sha);
            verifySha256(zip, sha);

            if (Files.exists(installDir)) {
                ManagedMaven.deleteRecursively(installDir);
            }
            ManagedMaven.unzip(zip, toolsDir);
            if (!Files.isRegularFile(executable)) {
                throw new IOException("Managed Gradle download did not produce expected executable: " + executable);
            }
            ManagedMaven.deleteQuietly(zip);
            ManagedMaven.deleteQuietly(sha);
            if (!windows) {
                executable.toFile().setExecutable(true);
            }
            Log.info("Managed Gradle ready: " + executable);
            return executable;
        }

        private static void verifySha256(Path zip, Path shaFile) throws IOException {
            String expected = expectedSha256(shaFile);
            String actual;
            try {
                actual = sha256(zip);
            } catch (Exception ex) {
                throw new IOException("Could not calculate SHA-256 for managed Gradle download", ex);
            }
            if (!actual.equalsIgnoreCase(expected)) {
                Files.deleteIfExists(zip);
                throw new IOException("Managed Gradle SHA-256 mismatch: expected " + expected + " but got " + actual);
            }
        }

        private static String expectedSha256(Path shaFile) throws IOException {
            String text = Files.readString(shaFile, StandardCharsets.UTF_8);
            for (String token : text.split("\\s+")) {
                if (token.matches("[A-Fa-f0-9]{64}")) {
                    return token;
                }
            }
            throw new IOException("Managed Gradle SHA-256 file did not contain a valid hash: " + shaFile);
        }
    }

    private static final class ManagedJava {
        private ManagedJava() {
        }

        static Optional<Path> home(AppConfig config, int major) throws IOException {
            Optional<Path> local = localHome(major);
            if (local.isPresent()) {
                return local;
            }
            return Optional.of(ensureManaged(config, major));
        }

        private static Optional<Path> localHome(int major) {
            for (String name : List.of("JAVA" + major + "_HOME", "JDK" + major + "_HOME")) {
                Path home = envJavaHome(name);
                if (home != null) {
                    return Optional.of(home);
                }
            }
            return Optional.empty();
        }

        private static Path envJavaHome(String name) {
            String value = firstNonBlank(System.getenv(name), "");
            if (value.isBlank()) {
                return null;
            }
            Path home = Paths.get(value);
            Path java = home.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
            return Files.isRegularFile(java) ? home : null;
        }

        private static Path ensureManaged(AppConfig config, int major) throws IOException {
            Path toolsDir = config.resolve(config.cacheDir).resolve("tools").resolve("java");
            Path markerDir = toolsDir.resolve("temurin-" + major);
            Path existing = findJavaHome(markerDir);
            if (existing != null) {
                return existing;
            }
            Files.createDirectories(markerDir);
            JavaDownload download = resolveDownload(config, major);
            Path archive = markerDir.resolve("temurin-" + major + ".zip");
            Log.info("Managed Java " + major + " is not cached; downloading to " + markerDir + ".");
            ManagedMaven.download(config, download.link, archive);
            if (!download.checksum.isBlank()) {
                String actual;
                try {
                    actual = sha256(archive);
                } catch (Exception ex) {
                    throw new IOException("Could not calculate SHA-256 for managed Java download", ex);
                }
                if (!actual.equalsIgnoreCase(download.checksum)) {
                    Files.deleteIfExists(archive);
                    throw new IOException("Managed Java " + major + " SHA-256 mismatch: expected "
                        + download.checksum + " but got " + actual);
                }
            }
            ManagedMaven.unzip(archive, markerDir);
            Path home = findJavaHome(markerDir);
            if (home == null) {
                throw new IOException("Managed Java " + major + " download did not contain a usable java executable");
            }
            ManagedMaven.deleteQuietly(archive);
            if (!isWindows()) {
                home.resolve("bin").resolve("java").toFile().setExecutable(true);
            }
            Log.info("Managed Java " + major + " ready: " + home);
            return home;
        }

        private static JavaDownload resolveDownload(AppConfig config, int major) throws IOException {
            String os = isWindows() ? "windows" : lower(System.getProperty("os.name", "")).contains("mac") ? "mac" : "linux";
            String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT).contains("aarch64") ? "aarch64" : "x64";
            URI uri = URI.create("https://api.adoptium.net/v3/assets/latest/" + major
                + "/hotspot?architecture=" + arch
                + "&image_type=jdk&os=" + os
                + "&vendor=eclipse");
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(45))
                    .header("User-Agent", config.userAgent)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
                HttpResponse<String> response = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("Adoptium API failed with HTTP " + response.statusCode() + " for Java " + major);
                }
                Object json = new JsonParser(response.body()).parse();
                if (!(json instanceof List<?> list) || list.isEmpty()) {
                    throw new IOException("Adoptium API returned no Java " + major + " downloads");
                }
                Map<String, Object> root = asMap(list.get(0));
                Map<String, Object> binary = asMap(root.get("binary"));
                Map<String, Object> pkg = asMap(binary.get("package"));
                String link = stringValue(pkg.get("link"));
                if (link.isBlank()) {
                    throw new IOException("Adoptium API returned Java " + major + " without a download link");
                }
                return new JavaDownload(URI.create(link), stringValue(pkg.get("checksum")));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while resolving managed Java " + major, ex);
            }
        }

        private static Path findJavaHome(Path root) throws IOException {
            if (!Files.isDirectory(root)) {
                return null;
            }
            String javaName = isWindows() ? "java.exe" : "java";
            try (var stream = Files.walk(root, 4)) {
                return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase(javaName))
                    .filter(path -> path.getParent() != null && path.getParent().getFileName().toString().equalsIgnoreCase("bin"))
                    .map(path -> path.getParent().getParent())
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
            }
        }

        private static boolean isWindows() {
            return lower(System.getProperty("os.name", "")).contains("win");
        }

        private static final class JavaDownload {
            final URI link;
            final String checksum;

            JavaDownload(URI link, String checksum) {
                this.link = link;
                this.checksum = firstNonBlank(checksum, "");
            }
        }
    }

    private static final class GithubReleaseResolver implements DownloadResolver {
        private final AppConfig config;
        private final HttpClient client;

        GithubReleaseResolver(AppConfig config, HttpClient client) {
            this.config = config;
            this.client = client;
        }

        @Override
        public ResolvedDownload resolve(TargetConfig target) throws Exception {
            GithubRepo repo = inferRepo(target);
            List<Map<String, Object>> releases = loadReleases(repo);
            for (Map<String, Object> release : releases) {
                if (Boolean.TRUE.equals(release.get("draft"))) {
                    continue;
                }
                if (target.versionType == null || target.versionType.isBlank()) {
                    if (Boolean.TRUE.equals(release.get("prerelease"))) {
                        continue;
                    }
                }
                Optional<Map<String, Object>> asset = findJarAsset(release, target);
                if (asset.isEmpty()) {
                    continue;
                }
                String url = stringValue(asset.get().get("browser_download_url"));
                if (url.isBlank()) {
                    continue;
                }
                String tag = firstNonBlank(stringValue(release.get("tag_name")), stringValue(release.get("name")), "latest");
                String filename = stringValue(asset.get().get("name"));
                Instant publishedAt = parseInstantOrNull(firstNonBlank(
                    stringValue(release.get("published_at")),
                    stringValue(release.get("created_at"))
                ));
                return new ResolvedDownload(URI.create(url), "GitHub release " + repo.owner + "/" + repo.name + " " + tag + " " + filename,
                    "", "", "", "", tag, publishedAt);
            }
            throw new IOException("No GitHub release jar found for " + repo.owner + "/" + repo.name);
        }

        private List<Map<String, Object>> loadReleases(GithubRepo repo) throws Exception {
            URI uri = URI.create("https://api.github.com/repos/" + urlEncode(repo.owner) + "/" + urlEncode(repo.name) + "/releases?per_page=30");
            Optional<String> fresh = readGithubApiCache(config, uri, GITHUB_CACHE_FRESH);
            if (fresh.isPresent()) {
                return parseReleaseList(repo, fresh.get());
            }
            if (config.githubRateLimit.isPaused()) {
                Optional<String> stale = readGithubApiCache(config, uri, GITHUB_CACHE_STALE);
                if (stale.isPresent()) {
                    Log.info("Using cached GitHub releases for " + repo.owner + "/" + repo.name + " while rate limited.");
                    return parseReleaseList(repo, stale.get());
                }
                throw new IOException("GitHub releases API skipped for " + repo.owner + "/" + repo.name
                    + " because GitHub API calls are paused until " + config.githubRateLimit.resetText());
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", config.userAgent)
                .header("Accept", "application/vnd.github+json");
            applyGithubAuth(builder, config, uri);
            HttpRequest request = builder.GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                if (status == 401) {
                    Optional<HttpResponse<String>> unauthenticated = retryGithubUnauthenticated(
                        config, client, uri, Duration.ofSeconds(45), "application/vnd.github+json",
                        "GitHub releases " + repo.owner + "/" + repo.name);
                    if (unauthenticated.isPresent()) {
                        response = unauthenticated.get();
                        status = response.statusCode();
                    }
                }
            }
            if (status < 200 || status >= 300) {
                if (status == 403 || status == 429) {
                    pauseGithubFromResponse(config, response);
                    Optional<String> stale = readGithubApiCache(config, uri, GITHUB_CACHE_STALE);
                    if (stale.isPresent()) {
                        Log.info("Using cached GitHub releases for " + repo.owner + "/" + repo.name + " after HTTP " + status + ".");
                        return parseReleaseList(repo, stale.get());
                    }
                }
                throw new IOException("GitHub releases API failed with HTTP " + status + " for " + repo.owner + "/" + repo.name);
            }
            writeGithubApiCache(config, uri, response.body());
            return parseReleaseList(repo, response.body());
        }

        private List<Map<String, Object>> parseReleaseList(GithubRepo repo, String body) throws IOException {
            Object json = new JsonParser(body).parse();
            if (!(json instanceof List<?> list)) {
                throw new IOException("GitHub releases API returned an unexpected response for " + repo.owner + "/" + repo.name);
            }
            List<Map<String, Object>> releases = new ArrayList<>();
            for (Object item : list) {
                releases.add(asMap(item));
            }
            return releases;
        }

        private Optional<Map<String, Object>> findJarAsset(Map<String, Object> release, TargetConfig target) {
            Object assetsObj = release.get("assets");
            if (!(assetsObj instanceof List<?> assets)) {
                return Optional.empty();
            }
            List<Map<String, Object>> jars = new ArrayList<>();
            for (Object item : assets) {
                Map<String, Object> asset = asMap(item);
                String name = lower(stringValue(asset.get("name")));
                if (!name.endsWith(".jar")) {
                    continue;
                }
                if (name.contains("sources") || name.contains("javadoc") || name.contains("-dev") || name.contains("-plain")) {
                    continue;
                }
                if (assetPlatformScore(name, target) <= -100) {
                    continue;
                }
                jars.add(asset);
            }
            jars.sort(Comparator.comparingInt((Map<String, Object> asset) -> assetPlatformScore(lower(stringValue(asset.get("name"))), target)).reversed());
            return jars.isEmpty() ? Optional.empty() : Optional.of(jars.get(0));
        }

        private int assetPlatformScore(String filename, TargetConfig target) {
            String expected = firstNonBlank(lower(target.platform), lower(target.loader), inferredPluginPlatform(config.server));
            int score = 0;
            String targetName = normalizeIdentity(target.name);
            String targetProject = normalizeIdentity(target.project);
            if ((!targetName.isBlank() && filename.contains(targetName)) || (!targetProject.isBlank() && filename.contains(targetProject))) {
                score += 15;
            }
            if (expected.equals("paper") || expected.equals("folia")) {
                if (filename.contains("velocity") || filename.contains("fabric") || filename.contains("neoforge") || filename.contains("forge")) {
                    return -100;
                }
                if (filename.contains("paper") || filename.contains("bukkit") || filename.contains("spigot") || filename.contains("folia")) {
                    score += 25;
                }
            } else if (expected.equals("velocity")) {
                if (filename.contains("paper") || filename.contains("bukkit") || filename.contains("spigot") || filename.contains("folia")) {
                    return -100;
                }
                if (filename.contains("velocity")) {
                    score += 25;
                }
            } else if (expected.equals("waterfall") || expected.equals("bungee")) {
                if (filename.contains("paper") || filename.contains("bukkit") || filename.contains("spigot") || filename.contains("folia") || filename.contains("velocity")) {
                    return -100;
                }
                if (filename.contains("bungee") || filename.contains("waterfall")) {
                    score += 25;
                }
            }
            return score;
        }

        private GithubRepo inferRepo(TargetConfig target) {
            String value = firstNonBlank(target.githubRepo, target.project, target.source, "");
            if (value.contains("github.com/")) {
                List<String> parts = pathParts(URI.create(value));
                if (parts.size() >= 2) {
                    return new GithubRepo(parts.get(0), parts.get(1).replace(".git", ""));
                }
            }
            if (value.contains("/")) {
                String[] parts = value.split("/", 2);
                return new GithubRepo(parts[0], parts[1].replace(".git", ""));
            }
            throw new IllegalArgumentException("GitHub release source needs a repo like Owner/Repo or https://github.com/Owner/Repo");
        }
    }

    private static final class GeyserMcResolver implements DownloadResolver {
        private final AppConfig config;
        private final HttpClient client;

        GeyserMcResolver(AppConfig config, HttpClient client) {
            this.config = config;
            this.client = client;
        }

        @Override
        public ResolvedDownload resolve(TargetConfig target) throws Exception {
            if (target.source != null && target.source.contains("download.geysermc.org/v2/")) {
                return new ResolvedDownload(URI.create(target.source), target.source);
            }
            String project = firstNonBlank(target.project, inferGeyserProject(target), "geyser");
            String platform = normalizeGeyserPlatform(firstNonBlank(target.platform, inferPlatform(target), "velocity"));
            String url = "https://download.geysermc.org/v2/projects/" + project
                + "/versions/latest/builds/latest/downloads/" + platform;
            String version = "";
            String build = "";
            Instant publishedAt = null;
            try {
                URI metadataUri = URI.create("https://download.geysermc.org/v2/projects/" + project
                    + "/versions/latest/builds/latest");
                HttpRequest request = HttpRequest.newBuilder(metadataUri)
                    .timeout(Duration.ofSeconds(45))
                    .header("User-Agent", config.userAgent)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    Map<String, Object> metadata = asMap(new JsonParser(response.body()).parse());
                    version = stringValue(metadata.get("version"));
                    build = stringValue(metadata.get("build"));
                    publishedAt = firstNonNull(
                        instantFromJson(metadata.get("time")),
                        instantFromJson(metadata.get("createdAt")),
                        instantFromJson(metadata.get("timestamp"))
                    );
                }
            } catch (Exception ignored) {
                // The latest download endpoint is enough to update; metadata only improves logs and failure memory.
            }
            String label = "GeyserMC " + project + " " + firstNonBlank(version, "latest")
                + " build " + firstNonBlank(build, "latest") + " " + platform;
            return new ResolvedDownload(URI.create(url), label, "geysermc", project, "", build, firstNonBlank(version, build), publishedAt);
        }

        private String inferGeyserProject(TargetConfig target) {
            String text = lower(firstNonBlank(target.name, target.installAs, target.source, ""));
            if (text.contains("floodgate")) {
                return "floodgate";
            }
            return "geyser";
        }

        private String normalizeGeyserPlatform(String platform) {
            String value = lower(platform);
            if (value.equals("paper") || value.equals("bukkit")) {
                return "spigot";
            }
            if (value.equals("bungee")) {
                return "bungeecord";
            }
            return value.isBlank() ? "velocity" : value;
        }

        private String inferPlatform(TargetConfig target) {
            String text = lower(firstNonBlank(target.name, target.installAs, target.source, ""));
            if (text.contains("spigot") || text.contains("paper")) {
                return "spigot";
            }
            if (text.contains("bungee")) {
                return "bungeecord";
            }
            if (text.contains("fabric")) {
                return "fabric";
            }
            if (text.contains("standalone")) {
                return "standalone";
            }
            return "velocity";
        }
    }

    private static final class HangarResolver implements DownloadResolver {
        private final AppConfig config;
        private final HttpClient client;

        HangarResolver(AppConfig config, HttpClient client) {
            this.config = config;
            this.client = client;
        }

        @Override
        public ResolvedDownload resolve(TargetConfig target) throws Exception {
            HangarProject project = inferProject(target);
            String platform = firstNonBlank(target.platform, target.loader, "paper").toUpperCase(Locale.ROOT);
            String channel = firstNonBlank(target.channel, target.versionType, "Release");
            List<Map<String, Object>> versions = loadVersions(project);
            Optional<ResolvedDownload> preferred = findDownload(project, versions, platform, channel);
            if (preferred.isPresent()) {
                return preferred.get();
            }
            if (target.channel == null && target.versionType == null) {
                Optional<ResolvedDownload> any = findDownload(project, versions, platform, "");
                if (any.isPresent()) {
                    return any.get();
                }
            }
            throw new IOException("No Hangar download found for " + project.owner + "/" + project.slug + " on platform " + platform);
        }

        private List<Map<String, Object>> loadVersions(HangarProject project) throws Exception {
            URI uri = URI.create("https://hangar.papermc.io/api/v1/projects/"
                + urlEncode(project.owner) + "/" + urlEncode(project.slug) + "/versions?limit=100&offset=0");
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", config.userAgent)
                .header("Accept", "application/json")
                .GET()
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Hangar API failed with HTTP " + status + " for " + project.owner + "/" + project.slug);
            }
            Object json = new JsonParser(response.body()).parse();
            Object result = asMap(json).get("result");
            if (!(result instanceof List<?> list)) {
                throw new IOException("Hangar API returned an unexpected response for " + project.owner + "/" + project.slug);
            }
            List<Map<String, Object>> versions = new ArrayList<>();
            for (Object item : list) {
                Map<String, Object> version = asMap(item);
                if ("public".equalsIgnoreCase(stringValue(version.get("visibility")))) {
                    versions.add(version);
                }
            }
            versions.sort((a, b) -> stringValue(b.get("createdAt")).compareTo(stringValue(a.get("createdAt"))));
            return versions;
        }

        private Optional<ResolvedDownload> findDownload(HangarProject project, List<Map<String, Object>> versions, String platform, String channel) {
            for (Map<String, Object> version : versions) {
                if (!channel.isBlank()) {
                    Object channelObj = version.get("channel");
                    String channelName = channelObj instanceof Map<?, ?> map ? stringValue(castStringMap(map).get("name")) : "";
                    if (!channel.equalsIgnoreCase(channelName)) {
                        continue;
                    }
                }
                Object downloadsObj = version.get("downloads");
                if (!(downloadsObj instanceof Map<?, ?> rawDownloads)) {
                    continue;
                }
                Map<String, Object> downloads = castStringMap(rawDownloads);
                Object downloadObj = downloads.get(platform);
                if (!(downloadObj instanceof Map<?, ?>)) {
                    downloadObj = downloads.get(platform.toUpperCase(Locale.ROOT));
                }
                if (!(downloadObj instanceof Map<?, ?> rawDownload)) {
                    continue;
                }
                Map<String, Object> download = castStringMap(rawDownload);
                String externalUrl = stringValue(download.get("externalUrl"));
                String downloadUrl = stringValue(download.get("downloadUrl"));
                String url = firstNonBlank(downloadUrl, externalUrl);
                if (url.isBlank()) {
                    continue;
                }
                String name = stringValue(version.get("name"));
                Instant publishedAt = parseInstantOrNull(stringValue(version.get("createdAt")));
                return Optional.of(new ResolvedDownload(URI.create(url), "Hangar " + project.owner + "/" + project.slug + " " + name + " " + platform,
                    "", "", "", "", name, publishedAt));
            }
            return Optional.empty();
        }

        private HangarProject inferProject(TargetConfig target) {
            if (target.project != null && target.project.contains("/")) {
                String[] parts = target.project.split("/", 2);
                return new HangarProject(parts[0], parts[1]);
            }
            String source = firstNonBlank(target.source, "");
            if (source.contains("hangar.papermc.io/")) {
                URI uri = URI.create(source);
                List<String> parts = pathParts(uri);
                if (parts.size() >= 2) {
                    return new HangarProject(parts.get(0), parts.get(1));
                }
            }
            throw new IllegalArgumentException("Hangar source needs a URL like https://hangar.papermc.io/Owner/Project/versions");
        }
    }

    private static final class HangarProject {
        final String owner;
        final String slug;

        HangarProject(String owner, String slug) {
            this.owner = owner;
            this.slug = slug;
        }
    }

    private static final class ModrinthResolver implements DownloadResolver {
        private final AppConfig config;
        private final HttpClient client;

        ModrinthResolver(AppConfig config, HttpClient client) {
            this.config = config;
            this.client = client;
        }

        @Override
        public ResolvedDownload resolve(TargetConfig target) throws Exception {
            String project = firstNonBlank(target.project, inferProject(target), "");
            if (project.isBlank()) {
                throw new IllegalArgumentException("Could not infer Modrinth project slug for " + target.displayName());
            }

            List<Map<String, Object>> versions = loadVersions(project, target);
            if (versions.isEmpty()) {
                throw new IOException("Modrinth returned no versions for project " + project + filterDescription(target));
            }

            Optional<ResolvedDownload> release = findDownload(project, versions, "release", target);
            if (target.versionType != null && !target.versionType.isBlank()) {
                release = findDownload(project, versions, lower(target.versionType), target);
            }
            if (release.isPresent()) {
                return release.get();
            }

            Optional<ResolvedDownload> beta = findDownload(project, versions, "beta", target);
            if (beta.isPresent()) {
                return beta.get();
            }

            Optional<ResolvedDownload> alpha = findDownload(project, versions, "alpha", target);
            if (alpha.isPresent()) {
                return alpha.get();
            }

            Optional<ResolvedDownload> any = findDownload(project, versions, "", target);
            if (any.isPresent()) {
                return any.get();
            }
            throw new IOException("No downloadable jar file found on Modrinth for project " + project + filterDescription(target));
        }

        private List<Map<String, Object>> loadVersions(String project, TargetConfig target) throws Exception {
            for (String loader : modrinthLoaderFallbacks(firstNonBlank(target.loader, inferredPluginPlatform(config.server)))) {
                List<Map<String, Object>> versions = loadVersions(project, target, loader);
                if (!versions.isEmpty()) {
                    return versions;
                }
            }
            return Collections.emptyList();
        }

        private List<Map<String, Object>> loadVersions(String project, TargetConfig target, String loader) throws Exception {
            StringBuilder url = new StringBuilder("https://api.modrinth.com/v3/project/")
                .append(urlEncode(project))
                .append("/version?include_changelog=false");
            if (!loader.isBlank()) {
                url.append("&loaders=").append(urlEncode(jsonArray(loader)));
            }
            if (target.gameVersion != null && !target.gameVersion.isBlank()) {
                url.append("&game_versions=").append(urlEncode(jsonArray(target.gameVersion)));
            }

            HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", config.userAgent)
                .GET()
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Modrinth API failed with HTTP " + status + " for project " + project);
            }

            Object json = new JsonParser(response.body()).parse();
            if (!(json instanceof List<?> list)) {
                throw new IOException("Modrinth API returned an unexpected response for project " + project);
            }

            List<Map<String, Object>> versions = new ArrayList<>();
            for (Object item : list) {
                Map<String, Object> version = asMap(item);
                if (!"listed".equalsIgnoreCase(stringValue(version.get("status")))) {
                    continue;
                }
                versions.add(version);
            }
            versions.sort((a, b) -> stringValue(b.get("date_published")).compareTo(stringValue(a.get("date_published"))));
            return versions;
        }

        private List<String> modrinthLoaderFallbacks(String loader) {
            String normalized = lower(loader);
            List<String> loaders = new ArrayList<>();
            if (!normalized.isBlank()) {
                loaders.add(normalized);
            }
            if (normalized.equals("paper") || normalized.equals("folia") || normalized.equals("spigot")) {
                loaders.add("bukkit");
            }
            loaders.add("");
            List<String> distinct = new ArrayList<>();
            for (String value : loaders) {
                if (distinct.stream().noneMatch(existing -> existing.equals(value))) {
                    distinct.add(value);
                }
            }
            return distinct;
        }

        private Optional<ResolvedDownload> findDownload(String project, List<Map<String, Object>> versions, String versionType, TargetConfig target) {
            for (Map<String, Object> version : versions) {
                if (!versionType.isBlank() && !versionType.equalsIgnoreCase(stringValue(version.get("version_type")))) {
                    continue;
                }
                Optional<Map<String, Object>> file = findFile(version, target);
                if (file.isEmpty()) {
                    continue;
                }
                String url = stringValue(file.get().get("url"));
                if (url.isBlank()) {
                    continue;
                }
                String versionNumber = stringValue(version.get("version_number"));
                String filename = stringValue(file.get().get("filename"));
                Instant publishedAt = parseInstantOrNull(stringValue(version.get("date_published")));
                return Optional.of(new ResolvedDownload(URI.create(url), "Modrinth " + project + " " + versionNumber + " " + filename,
                    "", "", "", "", versionNumber, publishedAt));
            }
            return Optional.empty();
        }

        private Optional<Map<String, Object>> findFile(Map<String, Object> version, TargetConfig target) {
            Object filesObj = version.get("files");
            if (!(filesObj instanceof List<?> files) || files.isEmpty()) {
                return Optional.empty();
            }

            List<Map<String, Object>> jars = new ArrayList<>();
            for (Object item : files) {
                Map<String, Object> file = asMap(item);
                String filename = lower(stringValue(file.get("filename")));
                String fileType = lower(stringValue(file.get("file_type")));
                if (!filename.endsWith(".jar")) {
                    continue;
                }
                if (fileType.equals("sources-jar") || fileType.equals("dev-jar") || fileType.equals("javadoc-jar")) {
                    continue;
                }
                jars.add(file);
            }

            String expected = firstNonBlank(inferredPluginPlatform(config.server), target.loader);
            jars.removeIf(jar -> modrinthFileScore(lower(stringValue(jar.get("filename"))), expected) <= -100);
            jars.sort(Comparator.comparingInt((Map<String, Object> jar) -> modrinthFileScore(lower(stringValue(jar.get("filename"))), expected)).reversed());
            for (Map<String, Object> jar : jars) {
                if (Boolean.TRUE.equals(jar.get("primary"))) {
                    return Optional.of(jar);
                }
            }
            return jars.isEmpty() ? Optional.empty() : Optional.of(jars.get(0));
        }

        private int modrinthFileScore(String filename, String expectedPlatform) {
            String expected = lower(expectedPlatform);
            int score = 0;
            if (expected.equals("paper") || expected.equals("folia")) {
                if (filename.contains("velocity") || filename.contains("fabric") || filename.contains("neoforge") || filename.contains("forge")) {
                    return -100;
                }
                if (filename.contains("paper") || filename.contains("bukkit") || filename.contains("spigot") || filename.contains("folia")) {
                    score += 25;
                }
            } else if (expected.equals("velocity")) {
                if (filename.contains("paper") || filename.contains("bukkit") || filename.contains("spigot") || filename.contains("folia")) {
                    return -100;
                }
                if (filename.contains("velocity")) {
                    score += 25;
                }
            }
            return score;
        }

        private String inferProject(TargetConfig target) {
            String source = firstNonBlank(target.source, target.name, "");
            if (source.contains("modrinth.com/")) {
                URI uri = URI.create(source);
                String[] parts = uri.getPath().split("/");
                for (int i = 0; i < parts.length - 1; i++) {
                    if (parts[i].equals("plugin") || parts[i].equals("mod") || parts[i].equals("datapack")) {
                        return parts[i + 1];
                    }
                    if (parts[i].equals("project")) {
                        return parts[i + 1];
                    }
                }
            }
            if (source.contains("api.modrinth.com/")) {
                URI uri = URI.create(source);
                String[] parts = uri.getPath().split("/");
                for (int i = 0; i < parts.length - 1; i++) {
                    if (parts[i].equals("project")) {
                        return parts[i + 1];
                    }
                }
            }
            return source;
        }

        private String filterDescription(TargetConfig target) {
            List<String> filters = new ArrayList<>();
            if (target.loader != null && !target.loader.isBlank()) {
                filters.add("loader=" + target.loader);
            }
            if (target.gameVersion != null && !target.gameVersion.isBlank()) {
                filters.add("gameVersion=" + target.gameVersion);
            }
            return filters.isEmpty() ? "" : " (" + String.join(", ", filters) + ")";
        }
    }

    private static final class PaperMcResolver implements DownloadResolver {
        private final AppConfig config;
        private final HttpClient client;

        PaperMcResolver(AppConfig config, HttpClient client) {
            this.config = config;
            this.client = client;
        }

        @Override
        public ResolvedDownload resolve(TargetConfig target) throws Exception {
            String project = firstNonBlank(target.project, inferPaperMcProject(target), "paper");
            boolean allowVersionChange = target.changeVersion != null && target.changeVersion;
            String configuredVersion = lower(target.gameVersion).equals("auto") ? "" : firstNonBlank(target.gameVersion, "");
            String lockVersion = readLockValue(config, "serverGameVersion");
            String lockProject = readLockValue(config, "serverProject");
            if (!lockProject.isBlank() && !lockProject.equalsIgnoreCase(project)) {
                lockVersion = "";
            }
            String lockedVersion = allowVersionChange ? "" : firstNonBlank(configuredVersion, lockVersion, "");
            if (!allowVersionChange && lockedVersion.isBlank()) {
                Log.info("changeVersion is false and no server version is locked yet. Resolving latest " + project + " once, then writing updater.lock.yml.");
            }
            if (!lockedVersion.isBlank()) {
                Log.info("Using locked/configured " + project + " version: " + lockedVersion);
                Optional<ResolvedDownload> download = loadBuild(project, lockedVersion, target, firstNonBlank(target.pinBuild, ""));
                if (download.isPresent()) {
                    return download.get();
                }
                throw new IOException("No downloadable PaperMC build found for " + project + " version " + lockedVersion);
            }

            List<String> versions = loadVersions(project);
            if (versions.isEmpty()) {
                throw new IOException("PaperMC returned no versions for project " + project);
            }
            versions.sort(AutoUpdater::compareVersionsNewestFirst);
            int attempts = Math.min(versions.size(), 15);
            for (int i = 0; i < attempts; i++) {
                String version = versions.get(i);
                Optional<ResolvedDownload> resolved = loadBuild(project, version, target, firstNonBlank(target.pinBuild, ""));
                if (resolved.isPresent()) {
                    return resolved.get();
                }
            }
            throw new IOException("No downloadable PaperMC build found for " + project);
        }

        private String inferProject(TargetConfig target) {
            return inferPaperMcProject(target);
        }

        private List<String> loadVersions(String project) throws Exception {
            URI uri = URI.create("https://fill.papermc.io/v3/projects/" + project);
            Object json = getJson(uri);
            Object versions = asMap(json).get("versions");
            List<String> result = new ArrayList<>();
            if (versions instanceof Map<?, ?> map) {
                for (Object value : map.values()) {
                    if (value instanceof List<?> list) {
                        for (Object item : list) {
                            if (item != null) {
                                result.add(String.valueOf(item));
                            }
                        }
                    } else if (value != null) {
                        result.add(String.valueOf(value));
                    }
                }
            } else if (versions instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null) {
                        result.add(String.valueOf(item));
                    }
                }
            }
            return result;
        }

        private Optional<ResolvedDownload> loadBuild(String project, String version, TargetConfig target, String pinnedBuild) throws Exception {
            URI uri = URI.create("https://fill.papermc.io/v3/projects/" + project + "/versions/" + version + "/builds");
            Object json = getJson(uri);
            if (!(json instanceof List<?> builds) || builds.isEmpty()) {
                return Optional.empty();
            }

            List<Map<String, Object>> maps = new ArrayList<>();
            for (Object build : builds) {
                maps.add(asMap(build));
            }
            maps.sort(this::compareBuildsNewestFirst);
            return selectBuild(project, version, target, pinnedBuild, maps);
        }

        private Optional<ResolvedDownload> selectBuild(String project, String version, TargetConfig target,
                                                       String pinnedBuild, List<Map<String, Object>> maps) {
            String explicitBuild = firstNonBlank(pinnedBuild, "");
            if (!explicitBuild.isBlank()) {
                for (Map<String, Object> build : maps) {
                    if (explicitBuild.equalsIgnoreCase(buildNumber(build))) {
                        if (isKnownBadServerBuild(project, version, buildNumber(build))) {
                            Log.warn("Pinned " + project + " build " + explicitBuild
                                + " is remembered as known-bad; refusing to reinstall it until failureMemory.retryBadAfter allows retry.");
                            return Optional.empty();
                        }
                        Optional<ResolvedDownload> download = downloadFromBuild(project, version, build);
                        if (download.isPresent()) {
                            Log.info("Using pinned " + project + " build: " + explicitBuild);
                            return download;
                        }
                    }
                }
                Log.warn("Pinned " + project + " build " + explicitBuild + " was not downloadable.");
                return Optional.empty();
            }

            List<String> channels = preferredChannels(target);
            for (String channel : channels) {
                for (Map<String, Object> build : maps) {
                    String buildChannel = stringValue(build.get("channel"));
                    if (channel.equalsIgnoreCase(buildChannel)) {
                        if (isKnownBadServerBuild(project, version, buildNumber(build))) {
                            continue;
                        }
                        Optional<ResolvedDownload> download = downloadFromBuild(project, version, build);
                        if (download.isPresent()) {
                            return download;
                        }
                    }
                }
            }

            for (Map<String, Object> build : maps) {
                if (isKnownBadServerBuild(project, version, buildNumber(build))) {
                    continue;
                }
                Optional<ResolvedDownload> download = downloadFromBuild(project, version, build);
                if (download.isPresent()) {
                    return download;
                }
            }
            return Optional.empty();
        }

        private List<String> preferredChannels(TargetConfig target) {
            if (target.channel != null && !target.channel.isBlank()) {
                return List.of(target.channel);
            }
            return List.of("RECOMMENDED", "STABLE", "DEFAULT", "BETA", "ALPHA", "EXPERIMENTAL");
        }

        private Optional<ResolvedDownload> downloadFromBuild(String project, String version, Map<String, Object> build) {
            Object downloadsObj = build.get("downloads");
            if (!(downloadsObj instanceof Map<?, ?> rawDownloads)) {
                return Optional.empty();
            }
            Map<String, Object> downloads = castStringMap(rawDownloads);
            Object preferred = downloads.get("server:default");
            if (preferred instanceof Map<?, ?> map) {
                String url = stringValue(castStringMap(map).get("url"));
                if (!url.isBlank()) {
                    return Optional.of(new ResolvedDownload(URI.create(url), label(project, version, build), "papermc", project, version, buildNumber(build)));
                }
            }
            for (Map.Entry<String, Object> entry : downloads.entrySet()) {
                if (!entry.getKey().toLowerCase(Locale.ROOT).contains("server")) {
                    continue;
                }
                if (entry.getValue() instanceof Map<?, ?> map) {
                    String url = stringValue(castStringMap(map).get("url"));
                    if (!url.isBlank()) {
                        return Optional.of(new ResolvedDownload(URI.create(url), label(project, version, build), "papermc", project, version, buildNumber(build)));
                    }
                }
            }
            for (Object value : downloads.values()) {
                if (value instanceof Map<?, ?> map) {
                    String url = stringValue(castStringMap(map).get("url"));
                    if (!url.isBlank()) {
                        return Optional.of(new ResolvedDownload(URI.create(url), label(project, version, build), "papermc", project, version, buildNumber(build)));
                    }
                }
            }
            return Optional.empty();
        }

        private String label(String project, String version, Map<String, Object> build) {
            String number = buildNumber(build);
            String channel = stringValue(build.get("channel"));
            return project + " " + version + " build " + number + (channel.isBlank() ? "" : " (" + channel + ")");
        }

        private String buildNumber(Map<String, Object> build) {
            return firstNonBlank(stringValue(build.get("number")), stringValue(build.get("id")), "?");
        }

        private boolean isKnownBadServerBuild(String project, String version, String build) {
            Optional<BadServerBuild> bad = LockState.read(config).activeBadServerBuild(config, project, version, build);
            bad.ifPresent(value -> Log.warn("Skipping known-bad " + project + " " + version + " build " + build + "."));
            return bad.isPresent();
        }

        private int compareBuildsNewestFirst(Map<String, Object> left, Map<String, Object> right) {
            String a = buildNumber(left);
            String b = buildNumber(right);
            try {
                return Long.compare(Long.parseLong(b), Long.parseLong(a));
            } catch (NumberFormatException ignored) {
                return compareVersionsNewestFirst(a, b);
            }
        }

        private Object getJson(URI uri) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", config.userAgent)
                .GET()
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new IOException("PaperMC API failed with HTTP " + status + " for " + uri);
            }
            return new JsonParser(response.body()).parse();
        }
    }

    private static final class ServerRunner {
        private final AppConfig config;
        private final Updater updater;

        ServerRunner(AppConfig config, Updater updater) {
            this.config = config;
            this.updater = updater;
        }

        int runServerLoop(List<InstalledUpdate> startupUpdates) throws Exception {
            Path serverJar = config.resolve(Paths.get(config.server.installAs));
            if (!Files.exists(serverJar)) {
                throw new IOException("Server jar does not exist: " + serverJar);
            }

            List<InstalledUpdate> pendingStartupUpdates = new ArrayList<>(startupUpdates);
            updater.pathDebug(null, "startup.loop", "pending startup updates=" + pendingStartupUpdates.size());
            while (true) {
                updater.quarantineAllDuplicatePluginJars();
                Process process = startServer(serverJar);
                StartupHealthMonitor startupHealth = new StartupHealthMonitor(pendingStartupUpdates);
                updater.pathDebug(null, "startup.monitor", "health monitor enabled=" + startupHealth.enabled()
                    + "; watchedUpdates=" + pendingStartupUpdates.size());
                Thread outputThread = pipeOutput(process, startupHealth);
                Thread inputThread = pipeInput(process);

                StartupResult startupResult = monitorStartupHealth(process, startupHealth);
                if (startupResult.rollbackAndRestart) {
                    updater.pathDebug(null, "startup.rollback", "reason=" + startupResult.reason
                        + "; rollbackUpdates=" + startupResult.rollbackUpdates.size()
                        + "; failureMemoryUpdates=" + startupResult.failureMemoryUpdates.size());
                    outputThread.join(TimeUnit.SECONDS.toMillis(5));
                    inputThread.interrupt();
                    updater.rememberStartupFailures(startupResult.failureMemoryUpdates, startupResult.reason);
                    rollbackUpdates(startupResult.rollbackUpdates);
                    pendingStartupUpdates = Collections.emptyList();
                    Log.warn("Restarting once with the previous known-good jar(s).");
                    continue;
                }
                if (startupResult.processExited) {
                    outputThread.join(TimeUnit.SECONDS.toMillis(5));
                    inputThread.interrupt();
                    Log.info("Server exited with code " + startupResult.exitCode + ".");
                    updater.pathDebug(null, "startup.exit", "server exited with code " + startupResult.exitCode);
                    updater.pathTraceEnd("run", "serverExited=" + startupResult.exitCode);
                    return startupResult.exitCode;
                }

                Thread deferredDiscoveryThread = startDeferredDiscoveryRetryWorker(process);
                RunResult result = config.restart.enabled
                    ? monitorWithRestart(process)
                    : waitForExit(process);

                stopDeferredDiscoveryRetryWorker(deferredDiscoveryThread);
                outputThread.join(TimeUnit.SECONDS.toMillis(5));
                inputThread.interrupt();

                if (!result.scheduledRestart) {
                    Log.info("Server exited with code " + result.exitCode + ".");
                    updater.pathTraceEnd("run", "serverExited=" + result.exitCode);
                    return result.exitCode;
                }

                Log.info("Scheduled restart completed. Updating before next start.");
                updater.pathDebug(null, "restart.update", "scheduled restart complete; running updateAll before next start");
                pendingStartupUpdates = updater.updateAll();
            }
        }

        private Process startServer(Path serverJar) throws IOException {
            List<String> command = new ArrayList<>();
            command.add(config.server.java == null || config.server.java.isBlank() ? "java" : config.server.java);
            command.addAll(splitCommand(config.server.javaArgs));
            command.add("-jar");
            command.add(serverJar.getFileName().toString());
            command.addAll(splitCommand(config.server.args));

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(serverJar.getParent() == null ? config.baseDir.toFile() : serverJar.getParent().toFile());
            builder.redirectErrorStream(true);
            Log.info("Starting server: " + String.join(" ", command));
            updater.pathDebug(config.server, "startup.start-server", "command=" + String.join(" ", command));
            return builder.start();
        }

        private Thread pipeOutput(Process process, StartupHealthMonitor startupHealth) {
            Thread thread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println(line);
                        startupHealth.observe(line);
                    }
                } catch (IOException ignored) {
                    // Process streams close during normal shutdown.
                }
            }, "velocity-output");
            thread.setDaemon(true);
            thread.start();
            return thread;
        }

        private Thread pipeInput(Process process) {
            Thread thread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(process.getOutputStream(), true, StandardCharsets.UTF_8)) {
                    String line;
                    while (!Thread.currentThread().isInterrupted() && (line = reader.readLine()) != null) {
                        writer.println(line);
                    }
                } catch (IOException ignored) {
                    // Console input may close when the host stops the process.
                }
            }, "velocity-input");
            thread.setDaemon(true);
            thread.start();
            return thread;
        }

        private RunResult waitForExit(Process process) throws InterruptedException {
            int exitCode = process.waitFor();
            return new RunResult(false, exitCode);
        }

        private Thread startDeferredDiscoveryRetryWorker(Process process) {
            Optional<Instant> next = updater.nextDeferredDiscoveryRetryAfter();
            if (next.isEmpty()) {
                updater.pathDebug(null, "startup.deferred-discovery.schedule", "no deferred source discovery retry is pending");
                return null;
            }
            Thread thread = new Thread(() -> runDeferredDiscoveryRetryWorker(process), "auto-updater-deferred-discovery");
            thread.setDaemon(true);
            thread.start();
            Log.info("Deferred source discovery will retry after startup at " + next.get() + ".");
            updater.pathDebug(null, "startup.deferred-discovery.schedule", "firstRetry=" + next.get());
            return thread;
        }

        private void runDeferredDiscoveryRetryWorker(Process process) {
            while (process.isAlive() && !Thread.currentThread().isInterrupted()) {
                Optional<Instant> next = updater.nextDeferredDiscoveryRetryAfter();
                if (next.isEmpty()) {
                    return;
                }
                if (!sleepUntilDeferredDiscoveryRetry(process, next.get())) {
                    return;
                }
                if (!process.isAlive() || Thread.currentThread().isInterrupted()) {
                    return;
                }
                try {
                    updater.retryDeferredDiscoverySourcesAfterStartup();
                } catch (Exception ex) {
                    Log.warn("Deferred source discovery retry failed: " + safeExceptionMessage(ex));
                    updater.pathDebug(null, "startup.deferred-discovery.failed", safeExceptionMessage(ex));
                    return;
                }
            }
        }

        private boolean sleepUntilDeferredDiscoveryRetry(Process process, Instant retryAt) {
            while (process.isAlive() && !Thread.currentThread().isInterrupted()) {
                long millis = Duration.between(Instant.now(), retryAt).toMillis();
                if (millis <= 0) {
                    return true;
                }
                try {
                    Thread.sleep(Math.min(millis, TimeUnit.MINUTES.toMillis(1)));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return false;
        }

        private void stopDeferredDiscoveryRetryWorker(Thread thread) {
            if (thread == null) {
                return;
            }
            thread.interrupt();
            try {
                thread.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        private StartupResult monitorStartupHealth(Process process, StartupHealthMonitor startupHealth) throws Exception {
            if (!startupHealth.enabled()) {
                updater.pathDebug(null, "startup.health.skip", "no updated jars to monitor");
                return StartupResult.continueRunning();
            }
            Instant deadline = Instant.now().plusSeconds(120);
            updater.pathDebug(null, "startup.health.window", "monitoring updated jars until " + deadline);
            while (process.isAlive() && Instant.now().isBefore(deadline)) {
                if (startupHealth.hasFailures()) {
                    Log.warn("Detected plugin load failure after update; stopping server to roll back.");
                    updater.pathDebug(null, "startup.health.failure", "matched plugin load failure while process alive; failedUpdates="
                        + startupHealth.failedUpdates().stream()
                            .map(update -> update.target.displayName())
                            .collect(Collectors.joining(", ")));
                    stopProcess(process);
                    return StartupResult.rollback(
                        startupRollbackTargets(startupHealth, true),
                        startupHealth.failedUpdates(),
                        "startup-load-failed"
                    );
                }
                process.waitFor(1, TimeUnit.SECONDS);
            }
            if (!process.isAlive()) {
                int exitCode = process.exitValue();
                if (startupHealth.hasFailures()) {
                    Log.warn("Detected plugin load failure during startup; rolling back recent updated jar(s).");
                    updater.pathDebug(null, "startup.health.failure", "process exited with matched plugin load failure; exitCode=" + exitCode
                        + "; failedUpdates=" + startupHealth.failedUpdates().stream()
                            .map(update -> update.target.displayName())
                            .collect(Collectors.joining(", ")));
                    return StartupResult.rollback(
                        startupRollbackTargets(startupHealth, true),
                        startupHealth.failedUpdates(),
                        "startup-load-failed"
                    );
                }
                if (exitCode != 0 && startupHealth.hasUpdatedJars()) {
                    Log.warn("Server exited during startup after updates; rolling back recent updated jar(s).");
                    updater.pathDebug(null, "startup.health.exit-failed", "exitCode=" + exitCode
                        + "; rolling back all updated jars=" + startupHealth.allUpdatedJars().size());
                    return StartupResult.rollback(startupHealth.allUpdatedJars(), startupHealth.allUpdatedJars(), "startup-exit-failed");
                }
                return StartupResult.exited(exitCode);
            }
            Log.info("Startup health window passed for updated jar(s).");
            updater.pathDebug(null, "startup.health.pass", "no matched startup failures within health window");
            return StartupResult.continueRunning();
        }

        private List<InstalledUpdate> startupRollbackTargets(StartupHealthMonitor startupHealth, boolean matchedPluginFailure) {
            if (matchedPluginFailure && lower(config.restart.startupRollbackPolicy).equals("rollbackmatchedonly")) {
                updater.pathDebug(null, "startup.rollback.policy", "rollbackMatchedOnly -> "
                    + startupHealth.failedUpdates().stream()
                        .map(update -> update.target.displayName())
                        .collect(Collectors.joining(", ")));
                return startupHealth.failedUpdates();
            }
            if (matchedPluginFailure) {
                Log.warn("Startup rollback policy is rollbackBatch; rolling back every jar updated in this startup batch.");
                updater.pathDebug(null, "startup.rollback.policy", "rollbackBatch -> "
                    + startupHealth.allUpdatedJars().stream()
                        .map(update -> update.target.displayName())
                        .collect(Collectors.joining(", ")));
            }
            return startupHealth.allUpdatedJars();
        }

        private void stopProcess(Process process) throws Exception {
            if (!process.isAlive()) {
                return;
            }
            try {
                sendCommand(process, config.restart.stopCommand);
                updater.pathDebug(null, "startup.rollback.stop", "sent command=" + config.restart.stopCommand);
            } catch (IOException ex) {
                updater.pathDebug(null, "startup.rollback.stop.failed", ex.getMessage());
                Log.warn("Could not send stop command before rollback: " + ex.getMessage());
            }
            boolean stopped = process.waitFor(config.restart.gracefulStopSeconds, TimeUnit.SECONDS);
            if (!stopped) {
                Log.warn("Server did not stop before rollback; terminating process.");
                updater.pathDebug(null, "startup.rollback.kill", "process did not stop after "
                    + config.restart.gracefulStopSeconds + " seconds");
                process.destroy();
                stopped = process.waitFor(15, TimeUnit.SECONDS);
                if (!stopped) {
                    updater.pathDebug(null, "startup.rollback.kill-forcibly", "process ignored normal destroy");
                    process.destroyForcibly();
                    process.waitFor();
                }
            }
        }

        private void rollbackUpdates(List<InstalledUpdate> updates) throws IOException {
            updater.pathDebug(null, "startup.rollback.apply", "applying rollback for " + updates.size() + " update(s)");
            for (InstalledUpdate update : updates) {
                if (update.hasBackup()) {
                    Files.copy(update.backupPath, update.targetPath, StandardCopyOption.REPLACE_EXISTING);
                    Log.warn("Rolled back " + update.target.displayName() + " -> " + update.targetPath.getFileName());
                    updater.pathDebug(update.target, "startup.rollback.restore", "restored backup=" + update.backupPath
                        + " -> " + update.targetPath);
                } else {
                    Files.deleteIfExists(update.targetPath);
                    Log.warn("Removed failed new jar for " + update.target.displayName() + " because no previous jar existed.");
                    updater.pathDebug(update.target, "startup.rollback.remove", "removed failed new jar=" + update.targetPath);
                }
                updater.restoreServerLockAfterRollback(update);
            }
        }

        private RunResult monitorWithRestart(Process process) throws Exception {
            Instant restartAt = Instant.now().plus(config.restart.interval);
            Set<RestartWarning> sent = new HashSet<>();
            Log.info("Next scheduled restart at " + restartAt + ".");

            while (process.isAlive()) {
                Instant now = Instant.now();
                for (RestartWarning warning : config.restart.warnings) {
                    if (warning.command == null || warning.command.isBlank() || sent.contains(warning)) {
                        continue;
                    }
                    if (!now.isBefore(restartAt.minus(warning.before))) {
                        sendCommand(process, warning.command);
                        sent.add(warning);
                    }
                }
                if (!now.isBefore(restartAt)) {
                    Log.info("Restart interval reached; stopping server.");
                    sendCommand(process, config.restart.stopCommand);
                    boolean stopped = process.waitFor(config.restart.gracefulStopSeconds, TimeUnit.SECONDS);
                    if (!stopped) {
                        Log.warn("Server did not stop within " + config.restart.gracefulStopSeconds + " seconds; terminating process.");
                        process.destroy();
                        stopped = process.waitFor(15, TimeUnit.SECONDS);
                        if (!stopped) {
                            process.destroyForcibly();
                            process.waitFor();
                        }
                    }
                    return new RunResult(true, process.exitValue());
                }
                process.waitFor(1, TimeUnit.SECONDS);
            }
            return new RunResult(false, process.exitValue());
        }

        private void sendCommand(Process process, String command) throws IOException {
            Log.info("Console command -> " + command);
            OutputStream out = process.getOutputStream();
            out.write((command + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private static final class RunResult {
        final boolean scheduledRestart;
        final int exitCode;

        RunResult(boolean scheduledRestart, int exitCode) {
            this.scheduledRestart = scheduledRestart;
            this.exitCode = exitCode;
        }
    }

    private static final class StartupResult {
        final boolean rollbackAndRestart;
        final boolean processExited;
        final int exitCode;
        final List<InstalledUpdate> rollbackUpdates;
        final List<InstalledUpdate> failureMemoryUpdates;
        final String reason;

        private StartupResult(boolean rollbackAndRestart, boolean processExited, int exitCode,
                              List<InstalledUpdate> rollbackUpdates, List<InstalledUpdate> failureMemoryUpdates, String reason) {
            this.rollbackAndRestart = rollbackAndRestart;
            this.processExited = processExited;
            this.exitCode = exitCode;
            this.rollbackUpdates = rollbackUpdates;
            this.failureMemoryUpdates = failureMemoryUpdates;
            this.reason = reason;
        }

        static StartupResult continueRunning() {
            return new StartupResult(false, false, 0, Collections.emptyList(), Collections.emptyList(), "");
        }

        static StartupResult exited(int exitCode) {
            return new StartupResult(false, true, exitCode, Collections.emptyList(), Collections.emptyList(), "");
        }

        static StartupResult rollback(List<InstalledUpdate> rollbackUpdates, List<InstalledUpdate> failureMemoryUpdates, String reason) {
            return new StartupResult(true, false, 0, rollbackUpdates, failureMemoryUpdates, reason);
        }
    }

    private static final class StartupHealthMonitor {
        private final List<InstalledUpdate> updatedJars;
        private final List<InstalledUpdate> failedUpdates = Collections.synchronizedList(new ArrayList<>());

        StartupHealthMonitor(List<InstalledUpdate> updatedJars) {
            this.updatedJars = updatedJars.stream()
                .filter(Objects::nonNull)
                .toList();
        }

        boolean enabled() {
            return !updatedJars.isEmpty();
        }

        boolean hasUpdatedJars() {
            return !updatedJars.isEmpty();
        }

        void observe(String line) {
            if (!enabled()) {
                return;
            }
            String lowerLine = normalizeLogLine(line);
            if (!looksLikePluginLoadFailure(lowerLine)) {
                return;
            }
            for (InstalledUpdate update : updatedJars) {
                if (update.target.server) {
                    continue;
                }
                if (lineMentionsUpdate(lowerLine, update) && !failedUpdates.contains(update)) {
                    failedUpdates.add(update);
                    Log.warn("Startup failure appears to mention updated jar " + update.target.displayName() + ".");
                }
            }
        }

        boolean hasFailures() {
            return !failedUpdates.isEmpty();
        }

        List<InstalledUpdate> failedUpdates() {
            synchronized (failedUpdates) {
                return new ArrayList<>(failedUpdates);
            }
        }

        List<InstalledUpdate> allUpdatedJars() {
            return new ArrayList<>(updatedJars);
        }

        private boolean lineMentionsUpdate(String lowerLine, InstalledUpdate update) {
            for (String token : updateTokens(update)) {
                if (!token.isBlank() && lineContainsToken(lowerLine, token)) {
                    return true;
                }
            }
            return false;
        }

        private boolean lineContainsToken(String line, String token) {
            int from = 0;
            while (from <= line.length() - token.length()) {
                int idx = line.indexOf(token, from);
                if (idx < 0) {
                    return false;
                }
                int before = idx - 1;
                int after = idx + token.length();
                boolean leftBoundary = before < 0 || !Character.isLetterOrDigit(line.charAt(before));
                boolean rightBoundary = after >= line.length() || !Character.isLetterOrDigit(line.charAt(after));
                if (leftBoundary && rightBoundary) {
                    return true;
                }
                from = idx + 1;
            }
            return false;
        }

        private List<String> updateTokens(InstalledUpdate update) {
            List<String> tokens = new ArrayList<>();
            tokens.add(normalizeLogLine(update.target.displayName()));
            tokens.add(normalizeLogLine(update.target.detectedPluginId));
            tokens.add(normalizeLogLine(update.target.installAs));
            if (update.targetPath.getFileName() != null) {
                tokens.add(normalizeLogLine(update.targetPath.getFileName().toString()));
                String filename = update.targetPath.getFileName().toString();
                if (lower(filename).endsWith(".jar")) {
                    tokens.add(normalizeLogLine(filename.substring(0, filename.length() - 4)));
                }
            }
            return tokens.stream().filter(token -> token.length() >= 3).distinct().toList();
        }

        private boolean looksLikePluginLoadFailure(String lowerLine) {
            return lowerLine.contains("could not load")
                || lowerLine.contains("failed to load")
                || lowerLine.contains("error occurred while enabling")
                || lowerLine.contains("error occurred while loading")
                || lowerLine.contains("exception loading")
                || lowerLine.contains("invalid plugin.yml")
                || lowerLine.contains("invalid plugin descriptor")
                || lowerLine.contains("unknown dependency")
                || lowerLine.contains("missing dependency")
                || lowerLine.contains("is not a valid plugin")
                || (lowerLine.contains("disabling") && lowerLine.contains("error"));
        }

        private String normalizeLogLine(String value) {
            return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('\\', '/');
        }
    }


    private static boolean parseBoolean(String value) {
        String v = lower(value);
        return v.equals("true") || v.equals("yes") || v.equals("on") || v.equals("1");
    }

    private static List<String> parseList(String value) {
        if (value == null || value.isBlank()) {
            return new ArrayList<>();
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        List<String> result = new ArrayList<>();
        for (String part : trimmed.split(",")) {
            String item = part.trim();
            if (!item.isEmpty()) {
                result.add(ConfigParser.unquote(item));
            }
        }
        return result;
    }

    private static Duration parseDuration(String value) {
        String v = lower(value).trim();
        if (v.isEmpty()) {
            throw new IllegalArgumentException("Duration cannot be empty");
        }
        long total = 0L;
        int start = 0;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (Character.isLetter(c)) {
                if (start == i) {
                    throw new IllegalArgumentException("Bad duration: " + value);
                }
                long amount = Long.parseLong(v.substring(start, i));
                switch (c) {
                    case 'd':
                        total += Duration.ofDays(amount).toSeconds();
                        break;
                    case 'h':
                        total += Duration.ofHours(amount).toSeconds();
                        break;
                    case 'm':
                        total += Duration.ofMinutes(amount).toSeconds();
                        break;
                    case 's':
                        total += amount;
                        break;
                    default:
                        throw new IllegalArgumentException("Bad duration unit '" + c + "' in " + value);
                }
                start = i + 1;
            }
        }
        if (start != v.length()) {
            total += Long.parseLong(v.substring(start));
        }
        return Duration.ofSeconds(total);
    }

    private static String prettyDuration(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds % 86400 == 0) {
            return (seconds / 86400) + "d";
        }
        if (seconds % 3600 == 0) {
            return (seconds / 3600) + "h";
        }
        if (seconds % 60 == 0) {
            return (seconds / 60) + "m";
        }
        return seconds + "s";
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new DigestInputStream(Files.newInputStream(path), digest)) {
            byte[] buffer = new byte[8192];
            while (in.read(buffer) >= 0) {
                // DigestInputStream updates the digest.
            }
        }
        byte[] hash = digest.digest();
        return hex(hash);
    }

    private static String sha512(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-512");
        try (InputStream in = new DigestInputStream(Files.newInputStream(path), digest)) {
            byte[] buffer = new byte[8192];
            while (in.read(buffer) >= 0) {
                // DigestInputStream updates the digest.
            }
        }
        return hex(digest.digest());
    }

    private static String sha256Text(String value) {
        try {
            return hex(MessageDigest.getInstance("SHA-256")
                .digest(firstNonBlank(value, "").getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            return safeName(value);
        }
    }

    private static String hex(byte[] hash) {
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String shortHash(String hash) {
        return hash == null || hash.length() < 12 ? String.valueOf(hash) : hash.substring(0, 12);
    }

    private static String safeName(String value) {
        String cleaned = value == null ? "unnamed" : value.replaceAll("[^A-Za-z0-9._-]+", "-");
        cleaned = cleaned.replaceAll("-+", "-");
        if (cleaned.isBlank()) {
            return "unnamed";
        }
        return cleaned;
    }

    private static String cleanVersion(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim();
        cleaned = cleaned.replaceFirst("(?i)^version[:=]", "");
        cleaned = cleaned.replaceFirst("(?i)^release[-_ ]?", "");
        cleaned = cleaned.replaceFirst("(?i)^v(?=\\d)", "");
        cleaned = cleaned.replaceAll("[^A-Za-z0-9._+-]+", "");
        return cleaned;
    }

    private static Instant parseInstantOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = ConfigParser.unquote(value.trim());
        try {
            return Instant.parse(trimmed);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Optional<Instant> parseInstantOptional(String value) {
        return Optional.ofNullable(parseInstantOrNull(value));
    }

    private static Instant instantFromJson(Object value) {
        if (value instanceof Number number) {
            long raw = number.longValue();
            if (raw <= 0) {
                return null;
            }
            return raw > 100_000_000_000L ? Instant.ofEpochMilli(raw) : Instant.ofEpochSecond(raw);
        }
        return parseInstantOrNull(stringValue(value));
    }

    private static String quoteYaml(String value) {
        if (value == null || value.isBlank()) {
            return "\"\"";
        }
        value = value.replace("\r", "\\r").replace("\n", "\\n");
        if (value.matches("[A-Za-z0-9_./:@?=&%+,-]+")) {
            return value;
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String lockText(String value, int maxChars) {
        String cleaned = firstNonBlank(value, "")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replaceAll("\\s+", " ")
            .trim();
        return abbreviate(cleaned, maxChars);
    }

    private static String quoteYamlKey(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String safeExceptionMessage(Throwable ex) {
        if (ex == null) {
            return "unknown error";
        }
        String message = firstNonBlank(ex.getMessage(), ex.getLocalizedMessage());
        if (!message.isBlank()) {
            return message;
        }
        return ex.getClass().getSimpleName().isBlank() ? ex.getClass().getName() : ex.getClass().getSimpleName();
    }

    private static String abbreviate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return firstNonBlank(value, "");
        }
        return value.substring(0, Math.max(0, maxChars)) + "...";
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static boolean isAutoValue(String value) {
        return value != null && value.trim().equalsIgnoreCase("auto");
    }

    private static boolean isNotFoundSourceValue(String value) {
        String normalized = lower(firstNonBlank(value, "")).replaceAll("[^a-z0-9]+", "");
        return normalized.equals("notfound") || normalized.equals("nonefound") || normalized.equals("notavailable");
    }

    private static boolean isMissingSourceValue(String value) {
        return value == null || value.isBlank() || isAutoValue(value) || isNotFoundSourceValue(value);
    }

    private static boolean isLikelySyncedPath(Path path) {
        String normalized = normalizeSlashes(path.toAbsolutePath().normalize().toString()).toLowerCase(Locale.ROOT);
        return normalized.contains("/onedrive/")
            || normalized.contains("/onedrive - ")
            || normalized.contains("/dropbox/")
            || normalized.contains("/google drive/");
    }

    private static boolean sourcesMatchLoosely(String a, String b) {
        String left = canonicalSourceMatchKey(a);
        String right = canonicalSourceMatchKey(b);
        if (left.isBlank() || right.isBlank()) {
            return false;
        }
        return left.equals(right);
    }

    private static String canonicalSourceMatchKey(String source) {
        String value = normalizeSlashes(firstNonBlank(source, "").trim());
        if (value.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(value);
            String host = lower(firstNonBlank(uri.getHost(), ""));
            List<String> parts = pathParts(uri);
            if (host.equals("github.com") && parts.size() >= 2) {
                return "github:" + lower(cleanSourceKeyPart(parts.get(0))) + "/"
                    + lower(cleanSourceKeyPart(parts.get(1).replace(".git", "")));
            }
            if (host.equals("modrinth.com") && parts.size() >= 2
                && (parts.get(0).equalsIgnoreCase("plugin") || parts.get(0).equalsIgnoreCase("mod"))) {
                return "modrinth:" + lower(parts.get(1));
            }
            if (host.equals("hangar.papermc.io") && parts.size() >= 2) {
                return "hangar:" + lower(cleanSourceKeyPart(parts.get(0))) + "/"
                    + lower(cleanSourceKeyPart(parts.get(1)));
            }
        } catch (RuntimeException ignored) {
            // Fall through to normalized text equality for non-URI source values.
        }
        if (value.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            String[] parts = value.split("/", 2);
            return "github:" + lower(cleanSourceKeyPart(parts[0])) + "/"
                + lower(cleanSourceKeyPart(parts[1].replace(".git", "")));
        }
        return lower(value).replaceAll("/+$", "");
    }

    private static String cleanSourceKeyPart(String value) {
        return firstNonBlank(value, "").replaceAll("[^A-Za-z0-9_.-]+", "");
    }

    private static String sourceProofProject(String type, String projectHint) {
        String project = firstNonBlank(projectHint, "");
        return lower(type).equals("modrinth") && !project.isBlank()
            ? "modrinth/" + project
            : project;
    }

    private static void applyGithubAuth(HttpRequest.Builder builder, AppConfig config, URI uri) {
        if (uri == null || !lower(uri.getHost()).equals("api.github.com")) {
            return;
        }
        if (config != null && config.githubTokenDisabled) {
            return;
        }
        String token = githubTokenValue(config);
        if (!token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
            builder.header("X-GitHub-Api-Version", "2022-11-28");
        }
    }

    private static Optional<HttpResponse<String>> retryGithubUnauthenticated(
        AppConfig config,
        HttpClient client,
        URI uri,
        Duration timeout,
        String accept,
        String context
    ) throws IOException, InterruptedException {
        if (config == null || client == null || uri == null || !lower(uri.getHost()).equals("api.github.com")) {
            return Optional.empty();
        }

        if (!githubTokenStatus(config).hasToken() || config.githubTokenDisabled) {
            return Optional.empty();
        }
        config.githubTokenDisabled = true;
        if (!config.githubTokenRejectedLogged) {
            Log.warn("GitHub rejected the configured token with HTTP 401; retrying public GitHub API calls without the token for this run.");
            config.githubTokenRejectedLogged = true;
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("User-Agent", config.userAgent)
            .header("Accept", firstNonBlank(accept, "application/json"))
            .GET()
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            Log.info("GitHub API retry without token succeeded for " + context + ".");
            return Optional.of(response);
        }
        return Optional.empty();
    }

    private static String githubTokenValue(AppConfig config) {
        return githubTokenStatus(config).value;
    }

    private static GithubTokenStatus githubTokenStatus(AppConfig config) {
        String configured = config == null ? "" : firstNonBlank(config.githubToken, "").trim();
        if (!configured.isBlank()) {
            if (lower(configured).startsWith("env:")) {
                String envName = configured.substring(4).trim();
                String value = firstNonBlank(System.getenv(envName), "");
                if (!value.isBlank()) {
                    return new GithubTokenStatus(value, "environment variable " + envName, "", true);
                }
                if (looksLikeGithubToken(envName)) {
                    return new GithubTokenStatus(
                        envName,
                        "literal token after env: prefix",
                        "githubToken starts with env:, but the suffix looks like a GitHub token instead of an environment variable name; using it this run. Prefer githubToken: env:GITHUB_TOKEN or remove env:.",
                        true
                    );
                }
                Optional<GithubTokenStatus> localToken = githubTokenFromLocalFiles(config, envName);
                if (localToken.isPresent()) {
                    return localToken.get();
                }
                return new GithubTokenStatus("", "environment variable " + envName,
                    "githubToken points at environment variable " + envName + ", but that variable is not visible to this Java process. On Linux, run export "
                        + envName + "=... before Java, or put " + envName + "=... in .env / .auto-updater.env, or put the token in github.token.",
                    true);
            }
            return new GithubTokenStatus(configured, "literal config value", "", true);
        }
        String githubToken = firstNonBlank(System.getenv("GITHUB_TOKEN"), "");
        if (!githubToken.isBlank()) {
            return new GithubTokenStatus(githubToken, "environment variable GITHUB_TOKEN", "", false);
        }
        String ghToken = firstNonBlank(System.getenv("GH_TOKEN"), "");
        if (!ghToken.isBlank()) {
            return new GithubTokenStatus(ghToken, "environment variable GH_TOKEN", "", false);
        }
        Optional<GithubTokenStatus> localToken = githubTokenFromLocalFiles(config, "GITHUB_TOKEN");
        if (localToken.isPresent()) {
            return localToken.get();
        }
        return new GithubTokenStatus("", "", "", false);
    }

    private static Optional<GithubTokenStatus> githubTokenFromLocalFiles(AppConfig config, String envName) {
        if (config == null) {
            return Optional.empty();
        }
        for (String filename : List.of(".auto-updater.env", ".env")) {
            Path path = config.resolve(Paths.get(filename));
            Optional<String> value = tokenFromEnvFile(path, envName);
            if (value.isPresent()) {
                return Optional.of(new GithubTokenStatus(value.get(), filename + " entry " + envName, "", true));
            }
        }
        Path tokenFile = config.resolve(Paths.get("github.token"));
        Optional<String> value = tokenFromPlainFile(tokenFile);
        if (value.isPresent()) {
            return Optional.of(new GithubTokenStatus(value.get(), "github.token file", "", true));
        }
        return Optional.empty();
    }

    private static Optional<String> tokenFromEnvFile(Path path, String envName) {
        try {
            if (!Files.isRegularFile(path)) {
                return Optional.empty();
            }
            String expected = firstNonBlank(envName, "GITHUB_TOKEN");
            for (String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String line = rawLine.trim();
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("export ")) {
                    line = line.substring("export ".length()).trim();
                }
                int equals = line.indexOf('=');
                if (equals <= 0) {
                    continue;
                }
                String key = line.substring(0, equals).trim();
                if (!key.equals(expected)) {
                    continue;
                }
                String value = unquoteShellValue(line.substring(equals + 1).trim());
                return usableGithubToken(value);
            }
        } catch (IOException ignored) {
            // Token files are optional. Missing/unreadable files simply mean no token.
        }
        return Optional.empty();
    }

    private static Optional<String> tokenFromPlainFile(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                return Optional.empty();
            }
            return usableGithubToken(Files.readString(path, StandardCharsets.UTF_8).trim());
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<String> usableGithubToken(String value) {
        String token = firstNonBlank(value, "").trim();
        if (token.isBlank() || token.contains("\n") || token.contains("\r")) {
            return Optional.empty();
        }
        return Optional.of(token);
    }

    private static String unquoteShellValue(String value) {
        String cleaned = firstNonBlank(value, "").trim();
        int comment = cleaned.indexOf(" #");
        if (comment >= 0) {
            cleaned = cleaned.substring(0, comment).trim();
        }
        if (cleaned.length() >= 2
            && ((cleaned.startsWith("\"") && cleaned.endsWith("\""))
                || (cleaned.startsWith("'") && cleaned.endsWith("'")))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        return cleaned.trim();
    }

    private static boolean looksLikeGithubToken(String value) {
        String trimmed = firstNonBlank(value, "").trim();
        return trimmed.startsWith("ghp_")
            || trimmed.startsWith("github_pat_")
            || trimmed.startsWith("gho_")
            || trimmed.startsWith("ghu_")
            || trimmed.startsWith("ghs_")
            || trimmed.startsWith("ghr_");
    }

    private static Optional<String> readGithubApiCache(AppConfig config, URI uri, Duration maxAge) {
        if (config == null || uri == null || !lower(uri.getHost()).equals("api.github.com")) {
            return Optional.empty();
        }
        try {
            Path path = githubApiCachePath(config, uri);
            if (!Files.isRegularFile(path)) {
                return Optional.empty();
            }
            Instant modified = Files.getLastModifiedTime(path).toInstant();
            if (modified.plus(maxAge).isBefore(Instant.now())) {
                return Optional.empty();
            }
            return Optional.of(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    private static void writeGithubApiCache(AppConfig config, URI uri, String body) {
        if (config == null || uri == null || body == null || !lower(uri.getHost()).equals("api.github.com")) {
            return;
        }
        try {
            Path path = githubApiCachePath(config, uri);
            Files.createDirectories(path.getParent());
            Files.writeString(path, body, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ignored) {
            // Cache is best-effort; never fail an update because metadata could not be cached.
        }
    }

    private static Path githubApiCachePath(AppConfig config, URI uri) {
        return config.resolve(config.cacheDir)
            .resolve("discovery")
            .resolve("github")
            .resolve(sha256Text(uri.toString()) + ".json");
    }

    private static void pauseGithubFromResponse(AppConfig config, HttpResponse<String> response) {
        if (config == null || response == null) {
            return;
        }
        String reset = response.headers().firstValue("x-ratelimit-reset").orElse("");
        config.githubRateLimit.pauseUntil(githubResetInstant(reset));
    }

    private static Instant githubResetInstant(String reset) {
        try {
            String value = firstNonBlank(reset, "0");
            if (value.equals("0")) {
                return Instant.now().plus(Duration.ofMinutes(15));
            }
            return Instant.ofEpochSecond(Long.parseLong(value));
        } catch (NumberFormatException ex) {
            return Instant.now().plus(Duration.ofMinutes(15));
        }
    }

    private static int maxIdentitySimilarity(PluginJarInfo current, PluginJarInfo incoming) {
        List<String> currentIds = pluginIdentityValues(current);
        List<String> incomingIds = pluginIdentityValues(incoming);
        int best = 0;
        for (String left : currentIds) {
            for (String right : incomingIds) {
                best = Math.max(best, similarityPercent(left, right));
            }
        }
        return best;
    }

    private static boolean pluginNamesConflict(PluginJarInfo current, PluginJarInfo incoming) {
        String currentName = normalizeIdentity(firstNonBlank(current.name, current.id));
        String incomingName = normalizeIdentity(firstNonBlank(incoming.name, incoming.id));
        if (currentName.isBlank() || incomingName.isBlank() || currentName.equals(incomingName)) {
            return false;
        }
        String currentId = normalizeIdentity(current.id);
        String incomingId = normalizeIdentity(incoming.id);
        return currentId.isBlank() || incomingId.isBlank() || !currentId.equals(incomingId);
    }

    private static int maxIdentitySimilarity(TargetConfig target, PluginJarInfo incoming) {
        List<String> expected = new ArrayList<>();
        expected.add(target.name);
        expected.add(target.detectedPluginId);
        expected.add(jarIdentityHint(target.installAs));
        expected.add(sourceNameHint(target.source));
        List<String> incomingIds = pluginIdentityValues(incoming);
        int best = 0;
        for (String left : expected) {
            for (String right : incomingIds) {
                best = Math.max(best, similarityPercent(left, right));
            }
        }
        return best;
    }

    private static List<String> pluginIdentityValues(PluginJarInfo info) {
        if (info == null) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<>();
        values.add(info.id);
        values.add(info.name);
        return values;
    }

    private static Set<String> pluginIdentityKeys(PluginJarInfo info) {
        Set<String> keys = new HashSet<>();
        for (String value : pluginIdentityValues(info)) {
            String normalized = normalizeIdentity(value);
            if (!normalized.isBlank()) {
                keys.add(normalized);
            }
        }
        return keys;
    }

    private static Set<String> pluginIdentityKeys(TargetConfig target) {
        if (target == null) {
            return Collections.emptySet();
        }
        Set<String> keys = new HashSet<>();
        for (String value : List.of(
            firstNonBlank(target.detectedPluginId, ""),
            firstNonBlank(target.name, "")
        )) {
            String normalized = normalizeIdentity(value);
            if (!normalized.isBlank()) {
                keys.add(normalized);
            }
        }
        return keys;
    }

    private static int similarityPercent(String left, String right) {
        String a = normalizeIdentity(left);
        String b = normalizeIdentity(right);
        if (a.isBlank() || b.isBlank()) {
            return 0;
        }
        if (a.equals(b)) {
            return 100;
        }
        if (a.contains(b) || b.contains(a)) {
            int shorter = Math.min(a.length(), b.length());
            int longer = Math.max(a.length(), b.length());
            return Math.max(70, (int) Math.round(shorter * 100.0 / longer));
        }
        int distance = levenshtein(a, b);
        int max = Math.max(a.length(), b.length());
        return Math.max(0, (int) Math.round((max - distance) * 100.0 / max));
    }

    private static String normalizeIdentity(String value) {
        return lower(firstNonBlank(value, "")).replaceAll("[^a-z0-9]+", "");
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }

    private static int packageSimilarityScore(String left, String right) {
        String a = lower(left);
        String b = lower(right);
        int lastA = a.lastIndexOf('.');
        int lastB = b.lastIndexOf('.');
        String packageA = lastA > 0 ? a.substring(0, lastA) : a;
        String packageB = lastB > 0 ? b.substring(0, lastB) : b;
        if (packageA.equals(packageB)) {
            return 12;
        }
        if (packageA.startsWith(packageB) || packageB.startsWith(packageA)) {
            return 8;
        }
        return 0;
    }

    private static boolean normalizedTokensOverlap(String left, String right) {
        Set<String> a = normalizedTokenSet(left);
        Set<String> b = normalizedTokenSet(right);
        for (String token : a) {
            if (b.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean normalizedAuthorTokensOverlap(String left, String right) {
        Set<String> a = authorOwnerTokens(left);
        Set<String> b = authorOwnerTokens(right);
        for (String token : a) {
            if (b.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean descriptionsNearlyMatch(String left, String right) {
        Set<String> a = meaningfulDescriptionTokens(left);
        Set<String> b = meaningfulDescriptionTokens(right);
        if (a.size() < 3 || b.size() < 3) {
            return false;
        }
        int overlap = 0;
        for (String token : a) {
            if (b.contains(token)) {
                overlap++;
            }
        }
        double dice = (2.0 * overlap) / (a.size() + b.size());
        return dice >= 0.72;
    }

    private static Set<String> meaningfulDescriptionTokens(String value) {
        Set<String> result = new HashSet<>();
        for (String token : firstNonBlank(value, "")
            .replaceAll("(?i)&[0-9a-fk-or]", " ")
            .replaceAll("https?://\\S+", " ")
            .split("[^A-Za-z0-9]+")) {
            String normalized = normalizeIdentity(token);
            if (normalized.length() < 3 || DESCRIPTION_STOP_WORDS.contains(normalized)) {
                continue;
            }
            result.add(normalized);
        }
        return result;
    }

    private static final Set<String> DESCRIPTION_STOP_WORDS = Set.of(
        "minecraft", "plugin", "plugins", "server", "servers", "paper", "spigot", "bukkit",
        "folia", "velocity", "support", "supports", "with", "and", "the", "for", "your",
        "you", "this", "that", "from", "simple", "best", "free", "new", "official"
    );

    private static Set<String> normalizedTokenSet(String value) {
        Set<String> result = new HashSet<>();
        for (String token : firstNonBlank(value, "").split("[^A-Za-z0-9_.-]+")) {
            String normalized = normalizeIdentity(token);
            if (!normalized.isBlank()) {
                result.add(normalized);
            }
        }
        return result;
    }

    private static boolean sameHost(String left, String right) {
        try {
            URI a = URI.create(left);
            URI b = URI.create(right);
            return firstNonBlank(a.getHost(), "").equalsIgnoreCase(firstNonBlank(b.getHost(), ""));
        } catch (RuntimeException ex) {
            return normalizeIdentity(left).equals(normalizeIdentity(right));
        }
    }

    private static boolean sourceOwnerConflictsWithInstalledAuthor(TargetConfig target, ResolvedDownload download, PluginJarInfo current) {
        if (current == null || current.authors.isBlank()) {
            return false;
        }
        String type = download == null ? "" : download.sourceType;
        String project = firstNonBlank(
            download == null ? "" : download.project,
            target == null ? "" : target.githubRepo,
            target == null ? "" : target.project
        );
        String label = download == null ? "" : download.label;
        SourceOwnerSignal signal = sourceOwnerSignal(
            current.authors,
            firstNonBlank(type, target == null ? "" : target.type),
            target == null ? "" : target.source,
            project,
            label
        );
        return signal.conflict;
    }

    private static SourceOwnerSignal sourceOwnerSignal(String authors, String type, String source, String projectHint, String label) {
        Set<String> authorTokens = authorOwnerTokens(authors);
        if (authorTokens.isEmpty()) {
            return new SourceOwnerSignal(0, false, "");
        }
        Set<String> ownerTokens = sourceOwnerTokens(type, source, projectHint, label);
        if (ownerTokens.isEmpty()) {
            return new SourceOwnerSignal(0, false, "");
        }
        for (String token : ownerTokens) {
            if (authorTokens.contains(token)) {
                return new SourceOwnerSignal(35, false, "source owner matches installed author");
            }
        }
        return new SourceOwnerSignal(-45, true,
            "source owner " + String.join("/", ownerTokens) + " does not match installed author " + authors);
    }

    private static Set<String> authorOwnerTokens(String authors) {
        Set<String> tokens = new HashSet<>(normalizedTokenSet(authors));
        if (tokens.contains("superronancraft")) {
            tokens.add("ronanplugins");
        }
        if (tokens.contains("kaspian")) {
            tokens.add("kaspiandev");
        }
        if (tokens.contains("tcoded")) {
            tokens.add("technicallycoded");
        }
        if (tokens.contains("luck")) {
            tokens.add("luckperms");
        }
        if (tokens.contains("loohp")) {
            tokens.add("loohp");
            tokens.add("foliainquisitors");
        }
        if (tokens.contains("hsgamer")) {
            tokens.add("foliainquisitors");
        }
        if (tokens.contains("grimac")) {
            tokens.add("grimanticheat");
        }
        if (tokens.contains("empire92") || tokens.contains("mattbdev") || tokens.contains("ironapollo")
            || tokens.contains("dordsor21") || tokens.contains("notmyfault")) {
            tokens.add("intellectualsites");
        }
        if (tokens.contains("dniym")) {
            tokens.add("dniym");
        }
        return tokens;
    }

    private static Set<String> sourceOwnerTokens(String type, String source, String projectHint, String label) {
        Set<String> owners = new HashSet<>();
        String normalizedType = lower(type);
        boolean githubLike = isGithubLikeSource(normalizedType, source, projectHint);
        boolean hangarLike = normalizedType.equals("hangar")
            || lower(source).contains("hangar.papermc.io/");
        if (githubLike) {
            addOwnerFromGithubLike(owners, projectHint);
            addOwnerFromGithubLike(owners, source);
            addOwnerFromSlashPair(owners, label);
        } else if (hangarLike) {
            addOwnerFromSlashPair(owners, projectHint);
            addOwnerFromHangarUrl(owners, source);
            addOwnerFromSlashPair(owners, label);
        }
        return owners;
    }

    private static boolean isGithubLikeSource(String type, String source, String projectHint) {
        String normalizedType = lower(type);
        return normalizedType.equals("github")
            || normalizedType.equals("github-release")
            || normalizedType.equals("github-source")
            || lower(source).contains("github.com/")
            || lower(projectHint).matches("[a-z0-9_.-]+/[a-z0-9_.-]+");
    }

    private static boolean isPluginDescriptorPath(String path) {
        String normalized = normalizeSlashes(firstNonBlank(path, "")).toLowerCase(Locale.ROOT);
        return normalized.endsWith("plugin.yml")
            || normalized.endsWith("paper-plugin.yml")
            || normalized.endsWith("bungee.yml")
            || normalized.endsWith("velocity-plugin.json");
    }

    private static int descriptorPathPriority(String path) {
        String normalized = normalizeSlashes(firstNonBlank(path, "")).toLowerCase(Locale.ROOT);
        int score = 100;
        if (normalized.contains("src/main/resources/")) {
            score -= 40;
        }
        if (normalized.endsWith("paper-plugin.yml") || normalized.endsWith("plugin.yml")) {
            score -= 20;
        }
        if (normalized.contains("test") || normalized.contains("example") || normalized.contains("template")) {
            score += 60;
        }
        return score;
    }

    private static String encodePath(String path) {
        return Arrays.stream(normalizeSlashes(firstNonBlank(path, "")).split("/"))
            .map(AutoUpdater::urlEncode)
            .collect(Collectors.joining("/"));
    }

    private static String cleanGithubRef(String value) {
        String cleaned = firstNonBlank(value, "").trim().replace('\\', '/');
        if (cleaned.equalsIgnoreCase("HEAD")) {
            return "";
        }
        cleaned = cleaned.replaceAll("^/+", "").replaceAll("/+$", "");
        return cleaned.replaceAll("[^A-Za-z0-9_./-]+", "");
    }

    private static PluginJarInfo parsePluginDescriptor(String path, String text) {
        String lowerPath = lower(path);
        PluginJarInfo info;
        if (lowerPath.endsWith("velocity-plugin.json")) {
            info = parseVelocityPluginInfo(text);
        } else {
            String entryName = lowerPath.endsWith("paper-plugin.yml") ? "paper-plugin.yml"
                : lowerPath.endsWith("bungee.yml") ? "bungee.yml"
                : "plugin.yml";
            info = parseYamlPluginInfo(entryName, text);
        }
        return info.withDescriptorPath(normalizeSlashes(path));
    }

    private static boolean pluginDescriptorMatchesTarget(PluginJarInfo installed, TargetConfig target, PluginJarInfo candidate) {
        if (candidate == null || !candidate.hasDescriptor) {
            return false;
        }
        Set<String> installedNames = new HashSet<>();
        if (installed != null) {
            installedNames.addAll(pluginIdentityValues(installed).stream().map(AutoUpdater::normalizeIdentity).toList());
        }
        installedNames.add(normalizeIdentity(firstNonBlank(target.detectedPluginId, "")));
        installedNames.add(normalizeIdentity(firstNonBlank(target.name, "")));
        installedNames.remove("");

        Set<String> candidateNames = pluginIdentityValues(candidate).stream()
            .map(AutoUpdater::normalizeIdentity)
            .filter(value -> !value.isBlank())
            .collect(Collectors.toSet());
        boolean nameMatch = false;
        for (String name : installedNames) {
            if (candidateNames.contains(name)) {
                nameMatch = true;
                break;
            }
        }
        if (!nameMatch) {
            return false;
        }

        String installedMain = installed == null ? "" : firstNonBlank(installed.mainClass, target.detectedMainClass);
        if (!installedMain.isBlank() && !candidate.mainClass.isBlank()
            && !installedMain.equalsIgnoreCase(candidate.mainClass)
            && packageSimilarityScore(installedMain, candidate.mainClass) == 0) {
            return false;
        }
        String installedAuthors = installed == null ? "" : firstNonBlank(installed.authors, target.detectedAuthors);
        if (!installedAuthors.isBlank() && !candidate.authors.isBlank()
            && !normalizedAuthorTokensOverlap(installedAuthors, candidate.authors)
            && !descriptionsNearlyMatch(installed == null ? "" : installed.description, candidate.description)) {
            return false;
        }
        if (installed != null && Boolean.TRUE.equals(installed.foliaSupported)
            && candidate.foliaSupported != null
            && !Boolean.TRUE.equals(candidate.foliaSupported)) {
            return false;
        }
        return true;
    }

    private static boolean proofMatchesTarget(SourceProof proof, TargetConfig target) {
        if (proof == null || target == null) {
            return false;
        }
        Set<String> targetNames = new HashSet<>();
        targetNames.add(normalizeIdentity(firstNonBlank(target.detectedPluginId, "")));
        targetNames.add(normalizeIdentity(firstNonBlank(target.name, "")));
        targetNames.add(normalizeIdentity(jarIdentityHint(target.installAs)));
        targetNames.remove("");
        String proofId = normalizeIdentity(proof.pluginId);
        if (!proofId.isBlank() && !targetNames.isEmpty() && !targetNames.contains(proofId)) {
            return false;
        }
        String targetMain = firstNonBlank(target.detectedMainClass, "");
        if (!targetMain.isBlank() && !proof.mainClass.isBlank()
            && !targetMain.equalsIgnoreCase(proof.mainClass)
            && packageSimilarityScore(targetMain, proof.mainClass) == 0) {
            return false;
        }
        return true;
    }

    private static boolean proofMatchesTarget(RejectedSourceProof proof, TargetConfig target) {
        if (proof == null || target == null) {
            return false;
        }
        if (!proof.installAs.isBlank()
            && !normalizedConfigPath(proof.installAs).equals(normalizedConfigPath(target.installAs))) {
            return false;
        }
        Set<String> targetNames = new HashSet<>();
        targetNames.add(normalizeIdentity(firstNonBlank(target.detectedPluginId, "")));
        targetNames.add(normalizeIdentity(firstNonBlank(target.name, "")));
        targetNames.add(normalizeIdentity(jarIdentityHint(target.installAs)));
        targetNames.remove("");
        String proofId = normalizeIdentity(proof.pluginId);
        boolean sameId = !proofId.isBlank() && !targetNames.isEmpty() && targetNames.contains(proofId);
        String targetMain = firstNonBlank(target.detectedMainClass, "");
        boolean sameMain = targetMain.isBlank()
            || proof.mainClass.isBlank()
            || targetMain.equalsIgnoreCase(proof.mainClass)
            || packageSimilarityScore(targetMain, proof.mainClass) > 0;
        if (sameId && sameMain) {
            return false;
        }
        return true;
    }

    private static String rejectedSourceKey(TargetConfig target, String source, String type, String project) {
        return rejectedSourceKeyRaw(normalizedConfigPath(target == null ? "" : target.installAs)
            + "|"
            + lower(firstNonBlank(type, detectTypeStatic(source)))
            + "|"
            + lower(firstNonBlank(project, ""))
            + "|"
            + canonicalSourceKey(source));
    }

    private static String rejectedSourceKeyRaw(String value) {
        return lower(firstNonBlank(value, "")).replace("\\", "/");
    }

    private static String summarizeFailure(String message) {
        String text = firstNonBlank(message, "source-build-failed")
            .replace('\r', '\n')
            .replace('\t', ' ');
        List<String> lines = Arrays.stream(text.split("\\n"))
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .toList();
        for (String line : lines) {
            String lower = lower(line);
            if (lower.contains("command failed")
                || lower.contains("command timed out")
                || lower.contains("cannot find symbol")
                || lower.contains("zip end header not found")
                || lower.contains("failed to execute goal")
                || lower.contains("build failed")) {
                return shortenForLock(line);
            }
        }
        return lines.isEmpty() ? "source-build-failed" : shortenForLock(lines.get(0));
    }

    private static String shortenForLock(String value) {
        String cleaned = firstNonBlank(value, "")
            .replaceAll("\\s+", " ")
            .trim();
        return cleaned.length() <= 240 ? cleaned : cleaned.substring(0, 237) + "...";
    }

    private static String canonicalSourceKey(String source) {
        String value = firstNonBlank(source, "").trim();
        if (value.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(value);
            String host = lower(uri.getHost());
            List<String> parts = pathParts(uri);
            if (host.equals("github.com") && parts.size() >= 2) {
                return "https://github.com/" + parts.get(0) + "/" + parts.get(1).replace(".git", "");
            }
            if (host.equals("modrinth.com") && parts.size() >= 2) {
                return "https://modrinth.com/" + parts.get(0) + "/" + parts.get(1);
            }
            if (host.equals("hangar.papermc.io") && parts.size() >= 2) {
                return "https://hangar.papermc.io/" + parts.get(0) + "/" + parts.get(1);
            }
        } catch (RuntimeException ignored) {
            // Keep normalized text below.
        }
        return normalizeSlashes(value).toLowerCase(Locale.ROOT);
    }

    private static String detectTypeStatic(String source) {
        String lowerSource = lower(source);
        if (lowerSource.contains("modrinth.com/") || lowerSource.contains("api.modrinth.com/")) {
            return "modrinth";
        }
        if (lowerSource.contains("hangar.papermc.io/")) {
            return "hangar";
        }
        if (lowerSource.contains("github.com/")) {
            return "github-release";
        }
        if (lowerSource.contains("/job/")) {
            return "jenkins";
        }
        if (lowerSource.contains("geysermc.org") || lowerSource.contains("download.geysermc.org")) {
            return "geysermc";
        }
        if (lowerSource.contains("spigotmc.org/") || lowerSource.contains("api.spiget.org/")) {
            return "spigot";
        }
        return "";
    }

    private static SourceDescriptorEvidence sourceTreeDescriptorEvidence(Path sourceDir, PluginJarInfo installed, TargetConfig target) throws IOException {
        List<PluginJarInfo> descriptors = pluginDescriptorsInSourceTree(sourceDir);
        if (descriptors.isEmpty()) {
            return SourceDescriptorEvidence.UNKNOWN;
        }
        for (PluginJarInfo descriptor : descriptors) {
            if (pluginDescriptorMatchesTarget(installed, target, descriptor)) {
                return SourceDescriptorEvidence.MATCH;
            }
        }
        return SourceDescriptorEvidence.MISMATCH;
    }

    private static List<PluginJarInfo> pluginDescriptorsInSourceTree(Path sourceDir) throws IOException {
        List<PluginJarInfo> descriptors = new ArrayList<>();
        if (!Files.isDirectory(sourceDir)) {
            return descriptors;
        }
        try (var stream = Files.walk(sourceDir, 10)) {
            List<Path> paths = stream
                .filter(Files::isRegularFile)
                .filter(path -> isPluginDescriptorPath(normalizeSlashes(sourceDir.relativize(path).toString())))
                .sorted(Comparator
                    .comparingInt((Path path) -> descriptorPathPriority(normalizeSlashes(sourceDir.relativize(path).toString())))
                    .thenComparing(path -> normalizeSlashes(sourceDir.relativize(path).toString()).length()))
                .limit(60)
                .toList();
            for (Path path : paths) {
                String relative = normalizeSlashes(sourceDir.relativize(path).toString());
                try {
                    PluginJarInfo info = parsePluginDescriptor(relative, Files.readString(path, StandardCharsets.UTF_8));
                    if (info.hasDescriptor) {
                        descriptors.add(info);
                    }
                } catch (IOException ignored) {
                    // Ignore unreadable descriptor candidates and keep scanning.
                }
            }
        }
        return descriptors;
    }

    private static void unzipSafely(Path zip, Path destination) throws IOException {
        Path normalizedDestination = destination.toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                Path output = normalizedDestination.resolve(entry.getName()).normalize();
                if (!output.startsWith(normalizedDestination)) {
                    throw new IOException("Refusing to extract GitHub source archive entry outside cache: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Files.createDirectories(output.getParent());
                    Files.copy(in, output, StandardCopyOption.REPLACE_EXISTING);
                }
                in.closeEntry();
            }
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path item : paths) {
                Files.deleteIfExists(item);
            }
        }
    }

    private static void addOwnerFromGithubLike(Set<String> owners, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            if (value.contains("github.com/")) {
                URI uri = URI.create(value);
                List<String> parts = pathParts(uri);
                if (parts.size() >= 2) {
                    addOwnerToken(owners, parts.get(0));
                    return;
                }
            }
        } catch (RuntimeException ignored) {
            // Fall through to owner/repo handling.
        }
        addOwnerFromSlashPair(owners, value);
    }

    private static void addOwnerFromHangarUrl(Set<String> owners, String value) {
        if (value == null || value.isBlank() || !lower(value).contains("hangar.papermc.io/")) {
            return;
        }
        try {
            List<String> parts = pathParts(URI.create(value));
            if (parts.size() >= 2) {
                addOwnerToken(owners, parts.get(0));
            }
        } catch (RuntimeException ignored) {
            // Ignore malformed metadata URLs.
        }
    }

    private static void addOwnerFromSlashPair(Set<String> owners, String value) {
        if (value == null || value.isBlank() || !value.contains("/")) {
            return;
        }
        String cleaned = value.trim();
        if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) {
            return;
        }
        for (String part : cleaned.split("\\s+")) {
            int slash = part.indexOf('/');
            if (slash > 0 && slash < part.length() - 1) {
                addOwnerToken(owners, part.substring(0, slash));
                return;
            }
        }
    }

    private static void addOwnerToken(Set<String> owners, String value) {
        String normalized = normalizeIdentity(value);
        if (!normalized.isBlank()
            && !normalized.equals("plugins")
            && !normalized.equals("plugin")
            && !normalized.equals("versions")) {
            owners.add(normalized);
        }
    }

    private static boolean sourceTextMatchesPlugin(TargetConfig target, PluginJarInfo incoming) {
        String sourceText = normalizeIdentity(String.join(" ",
            firstNonBlank(target.source, ""),
            firstNonBlank(target.githubRepo, ""),
            firstNonBlank(target.project, ""),
            firstNonBlank(target.installAs, ""),
            firstNonBlank(target.name, "")
        ));
        if (sourceText.isBlank()) {
            return false;
        }
        for (String identity : pluginIdentityValues(incoming)) {
            String normalized = normalizeIdentity(identity);
            if (!normalized.isBlank() && sourceText.contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static String sourceNameHint(String source) {
        if (source == null || source.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(source);
            List<String> parts = pathParts(uri);
            for (int i = parts.size() - 1; i >= 0; i--) {
                String part = parts.get(i);
                if (!part.isBlank() && !part.equalsIgnoreCase("versions") && !part.equalsIgnoreCase("resources")) {
                    return part.replace(".git", "");
                }
            }
        } catch (RuntimeException ignored) {
            // Fall through to the raw value.
        }
        return source;
    }

    private static String jarIdentityHint(String value) {
        String cleaned = value == null ? "" : value.replace('\\', '/');
        int slash = cleaned.lastIndexOf('/');
        if (slash >= 0) {
            cleaned = cleaned.substring(slash + 1);
        }
        if (lower(cleaned).endsWith(".jar")) {
            cleaned = cleaned.substring(0, cleaned.length() - 4);
        }
        cleaned = cleaned.replaceAll("(?i)[-_ ]?(bukkit|paper|spigot|folia|velocity|plugin)$", "");
        cleaned = cleaned.replaceAll("(?i)[-_ ]?v?\\d+(\\.\\d+){0,4}.*$", "");
        return cleaned.trim();
    }

    private static ServerJarDetection detectExistingServerJar(Path baseDir, TargetConfig target) {
        List<Path> candidates = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        if (target.installAs != null && !target.installAs.isBlank() && !isAutoValue(target.installAs)) {
            addJarCandidate(baseDir, candidates, seen, Paths.get(target.installAs));
        }
        for (String filename : List.of("folia.jar", "paper.jar", "velocity.jar", "waterfall.jar", "server.jar")) {
            addJarCandidate(baseDir, candidates, seen, Paths.get(filename));
        }
        for (Path jar : listJarFiles(baseDir)) {
            String filename = lower(jar.getFileName().toString());
            if (filename.equals("auto-updater.jar") || filename.equals("velocity-auto-updater.jar")) {
                continue;
            }
            if (inferPaperMcProjectFromText(filename).isBlank() && !filename.equals("server.jar")) {
                continue;
            }
            addJarCandidate(baseDir, candidates, seen, jar);
        }

        Path firstExisting = null;
        for (Path candidate : candidates) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            if (firstExisting == null) {
                firstExisting = candidate;
            }
            String project = inferPaperMcProjectFromText(candidate.getFileName().toString());
            if (!project.isBlank()) {
                return new ServerJarDetection(candidate, project);
            }
        }
        for (Path candidate : candidates) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            String project = readServerProjectFromJar(candidate);
            if (!project.isBlank()) {
                return new ServerJarDetection(candidate, project);
            }
        }
        return new ServerJarDetection(firstExisting, "");
    }

    private static void addJarCandidate(Path baseDir, List<Path> candidates, Set<Path> seen, Path path) {
        Path candidate = path.isAbsolute() ? path.normalize() : baseDir.resolve(path).normalize();
        if (seen.add(candidate)) {
            candidates.add(candidate);
        }
    }

    private static List<Path> listJarFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return Collections.emptyList();
        }
        try (var stream = Files.list(dir)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(path -> lower(path.getFileName().toString()).endsWith(".jar"))
                .sorted(Comparator.comparing(path -> lower(path.getFileName().toString())))
                .toList();
        } catch (IOException ex) {
            Log.warn("Could not scan jar directory " + dir + ": " + ex.getMessage());
            return Collections.emptyList();
        }
    }

    private static String readServerProjectFromJar(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            if (file.getManifest() != null) {
                String mainClass = lower(file.getManifest().getMainAttributes().getValue("Main-Class"));
                String title = lower(file.getManifest().getMainAttributes().getValue("Implementation-Title"));
                String manifestText = mainClass + " " + title;
                if (manifestText.contains("velocity")) {
                    return "velocity";
                }
                if (manifestText.contains("folia")) {
                    return "folia";
                }
                if (manifestText.contains("paper")) {
                    return "paper";
                }
            }
            Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                String name = lower(entries.nextElement().getName());
                if (name.contains("com/velocitypowered/proxy/")) {
                    return "velocity";
                }
                if (name.contains("io/papermc/folia/")) {
                    return "folia";
                }
                if (name.contains("io/papermc/paperclip/") || name.contains("io/papermc/paper/")) {
                    return "paper";
                }
            }
        } catch (IOException ex) {
            Log.warn("Could not inspect server jar " + jar.getFileName() + ": " + ex.getMessage());
        }
        return "";
    }

    private static PluginJarInfo readPluginJarInfo(Path jar) {
        String filename = jar.getFileName().toString();
        String fallbackName = filename.substring(0, filename.length() - ".jar".length());
        try (JarFile file = new JarFile(jar.toFile())) {
            List<PluginJarInfo> descriptors = new ArrayList<>();
            PluginJarInfo velocityInfo = readVelocityPluginInfo(file);
            if (velocityInfo.hasDescriptor) {
                descriptors.add(velocityInfo);
            }
            for (String entry : List.of("paper-plugin.yml", "plugin.yml", "bungee.yml")) {
                PluginJarInfo yamlInfo = readYamlPluginInfo(file, entry);
                if (yamlInfo.hasDescriptor) {
                    descriptors.add(yamlInfo);
                }
            }
            if (!descriptors.isEmpty()) {
                PluginJarInfo primary = descriptors.stream()
                    .filter(info -> info.descriptorTypes.contains("paper") || info.descriptorTypes.contains("bukkit"))
                    .findFirst()
                    .orElse(descriptors.get(0));
                Set<String> descriptorTypes = new HashSet<>();
                for (PluginJarInfo descriptor : descriptors) {
                    descriptorTypes.addAll(descriptor.descriptorTypes);
                }
                return new PluginJarInfo(
                    primary.id,
                    firstNonBlank(primary.name, primary.id),
                    primary.version,
                    primary.website,
                    primary.mainClass,
                    primary.authors,
                    primary.dependencies,
                    descriptorTypes,
                    primary.foliaSupported,
                    true,
                    primary.descriptorPath,
                    primary.description
                );
            }
        } catch (IOException ex) {
            Log.warn("Could not inspect plugin jar " + jar.getFileName() + ": " + ex.getMessage());
        }
        return new PluginJarInfo(fallbackName, fallbackName, "", "");
    }

    private static PluginJarInfo readYamlPluginInfo(JarFile file, String entryName) throws IOException {
        String text = readJarEntry(file, entryName);
        return parseYamlPluginInfo(entryName, text).withDescriptorPath(entryName);
    }

    private static PluginJarInfo parseYamlPluginInfo(String entryName, String text) {
        if (text.isBlank()) {
            return new PluginJarInfo("", "", "", "");
        }
        String name = readYamlEntryValue(text, "name");
        if (name.isBlank()) {
            return new PluginJarInfo("", "", "", "");
        }
        String type = switch (entryName) {
            case "paper-plugin.yml" -> "paper";
            case "bungee.yml" -> "bungee";
            default -> "bukkit";
        };
        Boolean foliaSupported = parseOptionalBoolean(firstNonBlank(
            readYamlEntryValue(text, "folia-supported"),
            readYamlEntryValue(text, "foliaSupported")
        ));
        String authors = firstNonBlank(readYamlEntryValue(text, "authors"), readYamlEntryValue(text, "author"));
        String dependencies = firstNonBlank(
            readYamlEntryValue(text, "dependencies"),
            readYamlEntryValue(text, "depend"),
            readYamlEntryValue(text, "softdepend")
        );
        return new PluginJarInfo(
            name,
            name,
            readYamlEntryValue(text, "version"),
            readYamlEntryValue(text, "website"),
            readYamlEntryValue(text, "main"),
            authors,
            dependencies,
            Set.of(type),
            foliaSupported,
            true,
            "",
            readYamlEntryValue(text, "description")
        );
    }

    private static PluginJarInfo readVelocityPluginInfo(JarFile file) throws IOException {
        String text = readJarEntry(file, "velocity-plugin.json");
        return parseVelocityPluginInfo(text).withDescriptorPath("velocity-plugin.json");
    }

    private static PluginJarInfo parseVelocityPluginInfo(String text) {
        if (text.isBlank()) {
            return new PluginJarInfo("", "", "", "");
        }
        try {
            Map<String, Object> json = asMap(new JsonParser(text).parse());
            String id = stringValue(json.get("id"));
            String name = stringValue(json.get("name"));
            String version = stringValue(json.get("version"));
            String website = firstNonBlank(stringValue(json.get("url")), stringValue(json.get("website")));
            return new PluginJarInfo(
                id,
                name,
                version,
                website,
                stringValue(json.get("main")),
                collectionText(json.get("authors")),
                collectionText(json.get("dependencies")),
                Set.of("velocity"),
                null,
                !id.isBlank() || !name.isBlank(),
                "",
                stringValue(json.get("description"))
            );
        } catch (RuntimeException ex) {
            return new PluginJarInfo("", "", "", "");
        }
    }

    private static String readYamlEntryValue(JarFile file, String entryName, String key) throws IOException {
        String text = readJarEntry(file, entryName);
        return readYamlEntryValue(text, key);
    }

    private static String readYamlEntryValue(String text, String key) {
        if (text.isBlank()) {
            return "";
        }
        String[] lines = text.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String noComment = ConfigParser.stripComment(raw);
            String line = noComment.trim();
            if (line.isEmpty()) {
                continue;
            }
            int colon = ConfigParser.findColon(line);
            if (colon < 0) {
                continue;
            }
            String foundKey = line.substring(0, colon).trim();
            if (foundKey.equalsIgnoreCase(key)) {
                String value = ConfigParser.unquote(line.substring(colon + 1).trim());
                if (!value.isBlank()) {
                    return value;
                }
                List<String> items = new ArrayList<>();
                int keyIndent = ConfigParser.countIndent(noComment);
                for (int j = i + 1; j < lines.length; j++) {
                    String childNoComment = ConfigParser.stripComment(lines[j]);
                    String child = childNoComment.trim();
                    if (child.isEmpty()) {
                        continue;
                    }
                    int childIndent = ConfigParser.countIndent(childNoComment);
                    if (childIndent <= keyIndent) {
                        break;
                    }
                    if (child.startsWith("- ")) {
                        String item = ConfigParser.unquote(child.substring(2).trim());
                        if (!item.isBlank()) {
                            items.add(item);
                        }
                    }
                }
                return String.join(", ", items);
            }
        }
        return "";
    }

    private static Boolean parseOptionalBoolean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return parseBoolean(value);
    }

    private static String collectionText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof List<?> list) {
            List<String> values = new ArrayList<>();
            for (Object item : list) {
                String text = stringValue(item);
                if (!text.isBlank()) {
                    values.add(text);
                }
            }
            return String.join(",", values);
        }
        if (value instanceof Map<?, ?> map) {
            List<String> values = new ArrayList<>();
            for (Object key : map.keySet()) {
                String text = stringValue(key);
                if (!text.isBlank()) {
                    values.add(text);
                }
            }
            return String.join(",", values);
        }
        return stringValue(value);
    }

    private static String readJarEntry(JarFile file, String entryName) throws IOException {
        JarEntry entry = file.getJarEntry(entryName);
        if (entry == null || entry.isDirectory()) {
            return "";
        }
        try (InputStream in = file.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String relativeConfigPath(Path baseDir, Path path) {
        Path absoluteBase = baseDir.toAbsolutePath().normalize();
        Path absolutePath = path.toAbsolutePath().normalize();
        try {
            return normalizeSlashes(absoluteBase.relativize(absolutePath).toString());
        } catch (IllegalArgumentException ex) {
            return normalizeSlashes(absolutePath.toString());
        }
    }

    private static String normalizedConfigPath(String path) {
        String normalized = normalizeSlashes(path).trim();
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return lower(normalized);
    }

    private static String normalizeSlashes(String value) {
        return value.replace('\\', '/');
    }

    private static String inferredPluginPlatform(TargetConfig server) {
        String project = inferPaperMcProject(server);
        if (project.equals("velocity") || project.equals("waterfall")) {
            return project;
        }
        if (project.equals("paper") || project.equals("folia")) {
            return "paper";
        }
        return "";
    }

    private static String paperMcDownloadSource(String project) {
        return "https://papermc.io/downloads/" + project;
    }

    private static String inferPaperMcProjectFromText(String text) {
        String source = lower(text);
        for (String project : List.of("folia", "velocity", "waterfall", "paper")) {
            if (source.contains(project)) {
                return project;
            }
        }
        return "";
    }

    private static String inferPaperMcProject(TargetConfig target) {
        String source = lower(firstNonBlank(target.source, target.project, target.name, target.installAs, ""));
        if (source.contains("papermc.io/downloads/")) {
            String[] parts = source.split("/");
            String last = parts.length == 0 ? "" : parts[parts.length - 1];
            if (isPaperMcProject(last)) {
                return last;
            }
        }
        return inferPaperMcProjectFromText(source);
    }

    private static boolean isPaperMcProject(String value) {
        return value.equals("paper") || value.equals("folia") || value.equals("velocity") || value.equals("waterfall");
    }

    private static String title(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.equalsIgnoreCase("papermc")) {
            return "PaperMC";
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1).toLowerCase(Locale.ROOT);
    }

    private static URI sourceUri(String source, AppConfig config) {
        try {
            URI uri = URI.create(source);
            if (uri.getScheme() != null && uri.getScheme().length() > 1) {
                return uri;
            }
        } catch (IllegalArgumentException ignored) {
            // Treat non-URI values as local paths.
        }
        return config.resolve(Paths.get(source)).toUri();
    }

    private static List<String> pathParts(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/")) {
            if (!part.isBlank()) {
                parts.add(part);
            }
        }
        return parts;
    }

    private static String readLockValue(AppConfig config, String key) {
        Path lock = config.resolve(Paths.get("updater.lock.yml"));
        if (!Files.exists(lock)) {
            return "";
        }
        try {
            for (String raw : Files.readAllLines(lock, StandardCharsets.UTF_8)) {
                String line = ConfigParser.stripComment(raw).trim();
                if (line.isEmpty() || !line.contains(":")) {
                    continue;
                }
                KeyValue kv = ConfigParser.keyValue(line, 0);
                if (kv.key.equals(key)) {
                    return kv.value;
                }
            }
        } catch (Exception ex) {
            Log.warn("Could not read updater.lock.yml: " + ex.getMessage());
        }
        return "";
    }

    private static int compareVersionsNewestFirst(String a, String b) {
        return compareVersionValues(b, a);
    }

    private static VersionOrder comparePluginVersions(String candidate, String installed) {
        String left = comparableVersion(candidate);
        String right = comparableVersion(installed);
        if (left.isBlank() || right.isBlank() || !left.matches(".*\\d.*") || !right.matches(".*\\d.*")) {
            return VersionOrder.UNKNOWN;
        }
        List<String> a = versionTokens(left);
        List<String> b = versionTokens(right);
        int max = Math.max(a.size(), b.size());
        for (int i = 0; i < max; i++) {
            String av = i < a.size() ? a.get(i) : "";
            String bv = i < b.size() ? b.get(i) : "";
            if (av.isBlank() && bv.isBlank()) {
                return VersionOrder.SAME;
            }
            if (av.isBlank()) {
                VersionOrder order = compareMissingToken(bv, false);
                if (order != VersionOrder.SAME) {
                    return order;
                }
                continue;
            }
            if (bv.isBlank()) {
                VersionOrder order = compareMissingToken(av, true);
                if (order != VersionOrder.SAME) {
                    return order;
                }
                continue;
            }
            boolean an = av.matches("\\d+");
            boolean bn = bv.matches("\\d+");
            if (an && bn) {
                int cmp = Long.compare(Long.parseLong(av), Long.parseLong(bv));
                if (cmp < 0) {
                    return VersionOrder.OLDER;
                }
                if (cmp > 0) {
                    return VersionOrder.NEWER;
                }
                continue;
            }
            if (!an && !bn) {
                int ar = qualifierRank(av);
                int br = qualifierRank(bv);
                if (ar >= 0 && br >= 0) {
                    if (ar < br) {
                        return VersionOrder.OLDER;
                    }
                    if (ar > br) {
                        return VersionOrder.NEWER;
                    }
                    continue;
                }
                if (av.equalsIgnoreCase(bv)) {
                    continue;
                }
                return VersionOrder.UNKNOWN;
            }
            return VersionOrder.UNKNOWN;
        }
        return VersionOrder.SAME;
    }

    private static String comparableVersion(String value) {
        String cleaned = cleanVersion(value);
        int build = cleaned.indexOf('+');
        if (build >= 0) {
            cleaned = cleaned.substring(0, build);
        }
        return cleaned;
    }

    private static VersionOrder compareMissingToken(String token, boolean candidateHasExtra) {
        if (token.matches("\\d+")) {
            return Long.parseLong(token) == 0 ? VersionOrder.SAME : VersionOrder.UNKNOWN;
        }
        int rank = qualifierRank(token);
        if (rank < 0) {
            return VersionOrder.UNKNOWN;
        }
        int releaseRank = qualifierRank("release");
        if (rank == releaseRank) {
            return VersionOrder.SAME;
        }
        if (rank < releaseRank) {
            return candidateHasExtra ? VersionOrder.OLDER : VersionOrder.NEWER;
        }
        return candidateHasExtra ? VersionOrder.NEWER : VersionOrder.OLDER;
    }

    private static int qualifierRank(String token) {
        String value = lower(token);
        return switch (value) {
            case "snapshot", "dev", "devel", "development", "nightly", "canary" -> 0;
            case "alpha", "a" -> 1;
            case "beta", "b" -> 2;
            case "pre", "preview" -> 3;
            case "rc", "cr" -> 4;
            case "release", "stable", "final", "ga" -> 5;
            default -> -1;
        };
    }

    private static int compareVersionValues(String a, String b) {
        List<String> aa = versionTokens(a);
        List<String> bb = versionTokens(b);
        int max = Math.max(aa.size(), bb.size());
        for (int i = 0; i < max; i++) {
            String av = i < aa.size() ? aa.get(i) : "0";
            String bv = i < bb.size() ? bb.get(i) : "0";
            boolean an = av.matches("\\d+");
            boolean bn = bv.matches("\\d+");
            int cmp;
            if (an && bn) {
                cmp = Long.compare(Long.parseLong(av), Long.parseLong(bv));
            } else {
                cmp = av.compareToIgnoreCase(bv);
            }
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private static List<String> versionTokens(String value) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean numeric = false;
        boolean started = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                started = false;
                continue;
            }
            boolean cNumeric = Character.isDigit(c);
            if (started && cNumeric != numeric) {
                tokens.add(current.toString());
                current.setLength(0);
            }
            current.append(c);
            numeric = cNumeric;
            started = true;
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String jsonArray(String csv) {
        List<String> values = new ArrayList<>();
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add("\"" + trimmed.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
            }
        }
        return "[" + String.join(",", values) + "]";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return castStringMap(map);
        }
        throw new IllegalArgumentException("Expected JSON object but found " + Objects.toString(value));
    }

    private static Map<String, Object> castStringMap(Map<?, ?> raw) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            map.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return map;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            String text = stringValue(value).trim();
            return text.isBlank() ? fallback : Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static List<String> splitCommand(String command) {
        if (command == null || command.isBlank()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean single = false;
        boolean dbl = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '\'' && !dbl) {
                single = !single;
            } else if (c == '"' && !single) {
                dbl = !dbl;
            } else if (Character.isWhitespace(c) && !single && !dbl) {
                if (current.length() > 0) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

}
