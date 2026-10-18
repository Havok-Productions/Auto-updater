package dev.autoupdater;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public final class UpgradeSmokeTestRegressionTest {
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            child(args[0]);
            return;
        }
        healthChecks();
        configurationIsolation();
        sandboxArguments();
        processChecks();
        System.out.println("UpgradeSmokeTestRegressionTest passed");
    }

    private static void healthChecks() {
        UpgradeSmokeTest.Health healthy = new UpgradeSmokeTest.Health(Set.of("Example"));
        healthy.observe("[Example] Enabling Example v1.0");
        healthy.observe("Done (1.0s)! For help, type help");
        check(healthy.result().passed(), "healthy startup rejected");
        UpgradeSmokeTest.Health missing = new UpgradeSmokeTest.Health(Set.of("Example"));
        missing.observe("Done (1.0s)! For help, type help");
        check(!missing.result().passed(), "missing plugin accepted");
        UpgradeSmokeTest.Health failed = new UpgradeSmokeTest.Health(Set.of("FastAsyncWorldEdit"));
        failed.observe("Error occurred while enabling FastAsyncWorldEdit v2.0");
        failed.observe("java.lang.NoSuchFieldError: CONFIGURED_FEATURE");
        check(!failed.result().passed() && failed.failed().contains("FastAsyncWorldEdit"), "FAWE failure missed");
        healthy.observe("[Example] Disabling Example v1.0");
        check(!healthy.result().passed(), "plugin disabling during observation was accepted");
        UpgradeSmokeTest.Health warning = new UpgradeSmokeTest.Health(Set.of());
        warning.observe("SLF4J: Failed to load class org.slf4j.impl.StaticLoggerBinder");
        check(warning.failure() == null, "optional SLF4J binding warning treated as plugin failure");
    }

    private static void configurationIsolation() throws Exception {
        Path root = Files.createTempDirectory("upgrade-copy-test");
        Path live = Files.createDirectories(root.resolve("live"));
        Path data = Files.createDirectories(live.resolve("plugins/Example"));
        Files.writeString(data.resolve("config.yml"), "setting: special\n");
        Files.writeString(data.resolve("users.db"), "production data");
        Files.createDirectories(data.resolve("logs"));
        Files.writeString(data.resolve("logs/old.json"), "do not copy");
        Files.createDirectories(live.resolve("world"));
        Files.writeString(live.resolve("world/level.dat"), "world must stay untouched");
        Files.writeString(live.resolve("eula.txt"), "eula=true\n");
        Path server = root.resolve("proposed.jar");
        Files.writeString(server, "proposed server");
        Path workspace = UpgradeSmokeTest.createWorkspace(live.resolve("cache"));
        UpgradeSmokeTest.prepare(live, workspace, server, List.of());
        check(Files.readString(workspace.resolve("plugins/Example/config.yml")).contains("special"), "config missing");
        check(!Files.exists(workspace.resolve("plugins/Example/users.db")), "database copied");
        check(!Files.exists(workspace.resolve("plugins/Example/logs")), "logs copied");
        check(!Files.exists(workspace.resolve("world")), "live world copied");
        Files.writeString(workspace.resolve("plugins/Example/config.yml"), "changed in test");
        check(Files.readString(data.resolve("config.yml")).contains("special"), "live config changed");
        String properties = Files.readString(workspace.resolve("server.properties"));
        check(properties.contains("level-name=smoke-world") && properties.contains("enable-rcon=false"), "unsafe properties");
        Files.writeString(workspace.resolve("result.txt"), "FAIL");
        UpgradeSmokeTest.removeTestPayload(workspace);
        check(Files.exists(workspace.resolve("result.txt")) && !Files.exists(workspace.resolve("plugins")), "cleanup failed");
        check(Files.readString(live.resolve("world/level.dat")).contains("untouched"), "live world touched");
        try {
            UpgradeSmokeTest.removeTestPayload(live);
            throw new AssertionError("unsafe cleanup accepted");
        } catch (java.io.IOException expected) { }
    }

    private static void processChecks() throws Exception {
        UpgradeSmokeTest.Settings settings = new UpgradeSmokeTest.Settings();
        settings.startupSeconds = 3;
        settings.observeSeconds = 0;
        settings.stopSeconds = 1;
        for (String mode : List.of("pass", "crash", "failure", "timeout", "bad-stop")) {
            Path directory = Files.createTempDirectory("upgrade-process-test");
            List<String> command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", absoluteClasspath(), UpgradeSmokeTestRegressionTest.class.getName(), mode);
            UpgradeSmokeTest.Result result = UpgradeSmokeTest.run(directory, settings, Set.of("Example"), command,
                ProcessBuilder::start);
            check(result.passed() == mode.equals("pass"), "unexpected result for " + mode + ": " + result.reason());
            check(Files.exists(directory.resolve("console.log")), "missing test output");
        }
    }

    private static void sandboxArguments() throws Exception {
        String original = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Linux");
            String java = Path.of(System.getProperty("java.home"), "bin",
                original.toLowerCase().contains("windows") ? "java.exe" : "java").toString();
            List<String> command = UpgradeSmokeTest.sandboxCommand(Path.of("/tmp/upgrade-tests/test-example"),
                List.of(java, "-jar", "server.jar"));
            check(command.contains("--unshare-net") && command.contains("--ro-bind")
                && command.contains("--unshare-pid") && command.contains("--cap-drop"), "missing sandbox boundary");
            check(command.subList(command.size() - 2, command.size()).equals(List.of("-jar", "server.jar")),
                "server command changed by sandbox");
            check(!command.contains("/home/server/Havok"), "sandbox exposed live server folder");
            System.setProperty("os.name", "Windows");
            try {
                UpgradeSmokeTest.sandboxCommand(Path.of("test"), List.of("java"));
                throw new AssertionError("unsupported host launched unisolated test");
            } catch (java.io.IOException expected) { }
        } finally {
            System.setProperty("os.name", original);
        }
    }

    private static String absoluteClasspath() {
        return java.util.Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
            .map(value -> Path.of(value).toAbsolutePath().toString())
            .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
    }

    private static void child(String mode) throws Exception {
        if (mode.equals("crash")) { System.exit(1); }
        if (mode.equals("failure")) {
            System.out.println("Error occurred while enabling Example v1.0");
        } else if (!mode.equals("timeout")) {
            System.out.println("[Example] Enabling Example v1.0");
            System.out.println("Done (1.0s)! For help, type help");
        }
        new BufferedReader(new InputStreamReader(System.in)).readLine();
        if (mode.equals("bad-stop")) { Thread.sleep(10000); }
    }

    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
