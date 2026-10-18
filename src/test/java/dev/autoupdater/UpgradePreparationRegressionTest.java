package dev.autoupdater;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public final class UpgradePreparationRegressionTest {
    public static void main(String[] args) throws Exception {
        stagedCommit();
        rejectedGate();
        pluginSelection();
        retryContext();
        failedScheduledTestKeepsChildOnline();
        configSchema();
        preparedDiscoverySurvivesPromotion();
        System.out.println("UpgradePreparationRegressionTest passed");
    }

    private static void stagedCommit() throws Exception {
        Path live = Files.createTempDirectory("upgrade-staged-commit");
        Path old = live.resolve("server.jar");
        Path candidate = live.resolve("candidate.jar");
        jar(old, "old");
        jar(candidate, "new");
        byte[] oldBytes = Files.readAllBytes(old);
        Object config = config(live, candidate);
        Object settings = get(config, "upgradeTest");
        set(settings, "enabled", false);
        Object updater = create("Updater", config);
        Object batch = call(updater, "prepareUpdates");
        check(Arrays.equals(Files.readAllBytes(old), oldBytes), "preparation replaced live jar");
        check(((List<?>) get(batch, "updates")).size() == 1, "candidate missing");
        call(updater, "installPrepared", batch);
        check(Arrays.equals(Files.readAllBytes(old), Files.readAllBytes(candidate)), "commit did not install candidate");
        try (var backups = Files.list(live.resolve("backups"))) {
            check(backups.findAny().isPresent(), "backup missing");
        }
    }

    private static void rejectedGate() throws Exception {
        Path live = Files.createTempDirectory("upgrade-rejected-gate");
        Path old = live.resolve("server.jar");
        Path candidate = live.resolve("candidate.jar");
        jar(old, "old");
        jar(candidate, "new");
        Files.writeString(live.resolve("eula.txt"), "eula=true\n");
        byte[] oldBytes = Files.readAllBytes(old);
        String lock = "serverProject: folia\nserverGameVersion: 26.2\nserverBuild: 7\n";
        Files.writeString(live.resolve("updater.lock.yml"), lock);
        Object config = config(live, candidate);
        // Missing copied runtime/EULA, unavailable sandbox, or no Done message all reject this fake server.
        Object settings = get(config, "upgradeTest");
        set(settings, "startupSeconds", 1);
        Object updater = create("Updater", config);
        Object batch = call(updater, "prepareUpdates");
        check(!(boolean) get(batch, "approved"), "fake server incorrectly approved");
        call(updater, "installPrepared", batch);
        check(Arrays.equals(Files.readAllBytes(old), oldBytes), "rejected test replaced live jar");
        check(Files.readString(live.resolve("updater.lock.yml")).equals(lock), "rejected test changed version lock");
        check(!(boolean) get(config, "preparingServerChange"), "retry context leaked after preparation");
    }

    private static Object config(Path live, Path candidate) throws Exception {
        Object config = create("AppConfig");
        set(config, "baseDir", live);
        set(get(config, "discovery"), "enabled", false);
        TargetConfig server = new TargetConfig("Server", true);
        server.source = candidate.toUri().toString();
        server.type = "direct";
        server.installAs = "server.jar";
        set(config, "server", server);
        return config;
    }

    private static void configSchema() throws Exception {
        Path live = Files.createTempDirectory("upgrade-schema");
        Path path = live.resolve("updater.yml");
        Files.writeString(path, "server:\n  installAs: server.jar\nplugins:\n");
        Object config = config(live, live.resolve("candidate.jar"));
        set(config, "configPath", path);
        Class<?> rewriter = Class.forName("dev.autoupdater.AutoUpdater$ConfigRewriter");
        Method refresh = rewriter.getDeclaredMethod("refreshConfigSchema", config.getClass());
        refresh.setAccessible(true);
        refresh.invoke(null, config);
        String first = Files.readString(path);
        check(first.contains("upgradeTest:") && first.contains("excludePlugins: []"), "schema defaults missing");
        refresh.invoke(null, config);
        check(Files.readString(path).equals(first), "schema refresh not idempotent");
        Class<?> parser = Class.forName("dev.autoupdater.AutoUpdater$ConfigParser");
        Method parse = parser.getDeclaredMethod("parse", Path.class);
        parse.setAccessible(true);
        Object parsed = parse.invoke(null, path);
        check((boolean) get(get(parsed, "upgradeTest"), "enabled"), "new settings did not parse");
    }

    private static void preparedDiscoverySurvivesPromotion() throws Exception {
        Path live = Files.createTempDirectory("upgrade-discovery-promotion");
        Files.writeString(live.resolve("updater.lock.yml"),
            "serverProject: folia\nserverGameVersion: 26.2\nserverBuild: 7\n");
        Object config = config(live, live.resolve("candidate.jar"));
        Object updater = create("Updater", config);
        Object lock = get(updater, "lockState");
        TargetConfig plugin = new TargetConfig("Missing", false);
        plugin.installAs = "plugins/Missing.jar";
        call(lock, "rememberDiscoveryDeferred", plugin, "GitHub rate limited", java.time.Instant.now().plusSeconds(3600));
        call(lock, "write", config);
        ResolvedDownload proposed = new ResolvedDownload(java.net.URI.create("https://example.invalid/server.jar"),
            "Folia", "papermc", "folia", "26.3", "3");
        call(updater, "updateLockIfNeeded", get(config, "server"), proposed, true);
        check(!((java.util.Map<?, ?>) get(lock, "discoveryStates")).isEmpty(), "promotion erased prepared discovery retries");
        check(Files.readString(live.resolve("updater.lock.yml")).contains("Missing.jar"), "deferred retry was not persisted");
    }

    @SuppressWarnings("unchecked")
    private static void pluginSelection() throws Exception {
        Path live = Files.createTempDirectory("upgrade-plugin-selection");
        Files.createDirectories(live.resolve("plugins"));
        jar(live.resolve("server.jar"), "old");
        jar(live.resolve("candidate.jar"), "new");
        pluginJar(live.resolve("plugins/Updated.jar"), "Updated", "1.0");
        pluginJar(live.resolve("plugins/Pinned.jar"), "Pinned", "1.0");
        pluginJar(live.resolve("updated-candidate.jar"), "Updated", "2.0");
        Object config = config(live, live.resolve("candidate.jar"));
        set(get(config, "upgradeTest"), "enabled", false);
        set(get(config, "validation"), "enabled", false);
        TargetConfig updated = new TargetConfig("Updated", false);
        updated.source = live.resolve("updated-candidate.jar").toUri().toString();
        updated.type = "direct";
        updated.installAs = "plugins/Updated.jar";
        TargetConfig pinned = new TargetConfig("Pinned", false);
        pinned.autoUpdate = false;
        pinned.installAs = "plugins/Pinned.jar";
        set(config, "plugins", List.of(updated, pinned));
        byte[] previous = Files.readAllBytes(live.resolve(updated.installAs));
        Object updater = create("Updater", config);
        Object batch = call(updater, "prepareUpdates");
        @SuppressWarnings("unchecked")
        List<UpgradeSmokeTest.Plugin> selected = (List<UpgradeSmokeTest.Plugin>) call(updater, "testPlugins", batch);
        check(selected.size() == 2, "pinned/updated plugin missing from test");
        check(selected.stream().anyMatch(p -> p.name().equals("Pinned") && p.jar().equals(live.resolve(pinned.installAs))),
            "pinned plugin not tested using installed jar");
        check(selected.stream().anyMatch(p -> p.name().equals("Updated") && !p.jar().equals(live.resolve(updated.installAs))),
            "updated candidate not selected");
        check(Arrays.equals(previous, Files.readAllBytes(live.resolve(updated.installAs))), "plugin changed during preparation");
        check((boolean) call(updater, "revertFailedCandidates", batch, Set.of("Updated")), "failed candidate not reverted");
        selected = (List<UpgradeSmokeTest.Plugin>) call(updater, "testPlugins", batch);
        check(selected.stream().anyMatch(p -> p.name().equals("Updated") && p.jar().equals(live.resolve(updated.installAs))),
            "previous jar not selected for second test");
        call(updater, "installPrepared", batch);
        check(Arrays.equals(previous, Files.readAllBytes(live.resolve(updated.installAs))), "rejected candidate installed");
    }

    private static void retryContext() throws Exception {
        Path live = Files.createTempDirectory("upgrade-retry-context");
        Object config = config(live, live.resolve("candidate.jar"));
        TargetConfig plugin = new TargetConfig("Example", false);
        plugin.installAs = "plugins/Example.jar";
        plugin.source = "https://example.invalid/Example.jar";
        Files.writeString(live.resolve("updater.lock.yml"), """
            badPluginVersions:
              plugins/Example.jar:
                source: https://example.invalid/Example.jar
                version: 2.0
                sha256: rejected-hash
                failedAt: 2026-10-17T00:00:00Z
                reason: startup-load-failed
            """);
        Object updater = create("Updater", config);
        ResolvedDownload download = new ResolvedDownload(java.net.URI.create(plugin.source), "Example");
        java.util.Optional<?> rejected = (java.util.Optional<?>) call(updater, "knownBadPlugin", plugin, download, "rejected-hash");
        check(rejected.isPresent(), "previous failure not recognized");
        set(config, "preparingServerChange", true);
        java.util.Optional<?> retry = (java.util.Optional<?>) call(updater, "knownBadPlugin", plugin, download, "rejected-hash");
        check(retry.isEmpty(), "new server context did not retry failed jar");
        set(config, "preparingServerChange", false);
        check(((java.util.Optional<?>) call(updater, "knownBadPlugin", plugin, download, "rejected-hash")).isPresent(),
            "test erased live failure memory");
    }

    private static void pluginJar(Path path, String name, String version) throws Exception {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(path))) {
            out.putNextEntry(new JarEntry("plugin.yml"));
            out.write(("name: " + name + "\nversion: " + version + "\nmain: example." + name + "\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    private static void failedScheduledTestKeepsChildOnline() throws Exception {
        Path live = Files.createTempDirectory("upgrade-online-gate");
        jar(live.resolve("server.jar"), "old");
        jar(live.resolve("candidate.jar"), "new");
        Files.writeString(live.resolve("eula.txt"), "eula=true\n");
        Object config = config(live, live.resolve("candidate.jar"));
        set(get(config, "upgradeTest"), "startupSeconds", 1);
        set(get(config, "restart"), "interval", java.time.Duration.ofMillis(250));
        Object updater = create("Updater", config);
        Object runner = create("ServerRunner", config, updater);
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"), UpgradeSmokeTestRegressionTest.class.getName(), "pass")
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var monitored = executor.submit(() -> call(runner, "monitorWithRestart", child));
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(8).toNanos();
            boolean report = false;
            while (System.nanoTime() < deadline) {
                Path tests = live.resolve("cache/upgrade-tests");
                if (Files.isDirectory(tests)) {
                    try (var files = Files.walk(tests)) {
                        report = files.anyMatch(file -> file.getFileName().toString().equals("result.txt"));
                    }
                }
                if (report) { break; }
                Thread.sleep(50);
            }
            check(report, "scheduled test did not run");
            check(child.isAlive(), "failed preflight stopped live process");
            child.destroyForcibly();
            child.waitFor();
            Object result = monitored.get(8, java.util.concurrent.TimeUnit.SECONDS);
            check(!(boolean) get(result, "scheduledRestart"), "failed test was marked as scheduled shutdown");
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); child.waitFor(); }
            executor.shutdownNow();
        }
    }

    private static void jar(Path path, String value) throws Exception {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(path))) {
            out.putNextEntry(new JarEntry("marker.txt"));
            out.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    private static Object create(String name, Object... args) throws Exception {
        for (Constructor<?> c : Class.forName("dev.autoupdater.AutoUpdater$" + name).getDeclaredConstructors()) {
            if (c.getParameterCount() == args.length) { c.setAccessible(true); return c.newInstance(args); }
        }
        throw new AssertionError(name);
    }
    private static Object get(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    private static void set(Object object, String name, Object value) throws Exception {
        Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); f.set(object, value);
    }
    private static Object call(Object object, String name, Object... args) throws Exception {
        for (Method m : object.getClass().getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                m.setAccessible(true); return m.invoke(object, args);
            }
        }
        throw new AssertionError(name);
    }
    private static void check(boolean value, String message) {
        if (!value) { throw new AssertionError(message); }
    }
}
