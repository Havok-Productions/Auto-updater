package dev.autoupdater;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Optional integration check: exercises automatic sandbox setup on Linux. */
public final class LinuxUpgradeSandboxRegressionTest {
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            if (Files.exists(Path.of(args[1]))) { throw new AssertionError("Live file exposed to test"); }
            for (var interfaces = java.net.NetworkInterface.getNetworkInterfaces(); interfaces.hasMoreElements();) {
                var network = interfaces.nextElement();
                if (network.isUp() && !network.isLoopback()) { throw new AssertionError("Host network exposed"); }
            }
            try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
                Files.writeString(Path.of("sandbox-writable.txt"), "temporary data");
            }
            if (args[0].equals("fail")) {
                System.out.println("Error occurred while enabling Example v1.0");
            } else {
                System.out.println("[Example] Enabling Example v1.0");
                System.out.println("Done (1.0s)! For help, type help");
            }
            new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();
            return;
        }
        if (!System.getProperty("os.name").toLowerCase().contains("linux")) {
            throw new IllegalStateException("Run this integration check on Linux");
        }
        Path root = Files.createTempDirectory("upgrade-linux-integration");
        Path sandbox = ManagedSandboxTool.resolve(root.resolve("cache"));
        if (!ManagedSandboxTool.resolve(root.resolve("cache")).equals(sandbox)) {
            throw new AssertionError("Prepared sandbox tool was not reused");
        }
        Path live = Files.writeString(root.resolve("live-canary.txt"), "unchanged");
        Path workspace = UpgradeSmokeTest.createWorkspace(root.resolve("cache"));
        Path classes = Files.createDirectories(workspace.resolve("classes/dev/autoupdater"));
        Path production = Path.of(UpgradeSmokeTest.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .resolve("dev/autoupdater");
        try (var files = Files.list(production)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                Files.copy(file, classes.resolve(file.getFileName()));
            }
        }
        try (var input = LinuxUpgradeSandboxRegressionTest.class.getResourceAsStream("LinuxUpgradeSandboxRegressionTest.class")) {
            Files.copy(input, classes.resolve("LinuxUpgradeSandboxRegressionTest.class"));
        }
        UpgradeSmokeTest.Settings settings = new UpgradeSmokeTest.Settings();
        settings.startupSeconds = 10;
        settings.observeSeconds = 1;
        settings.stopSeconds = 3;
        for (String mode : List.of("pass", "fail")) {
            List<String> command = UpgradeSmokeTest.sandboxCommand(workspace, List.of(
                Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp", workspace.resolve("classes").toString(),
                LinuxUpgradeSandboxRegressionTest.class.getName(), mode, live.toString()), sandbox);
            UpgradeSmokeTest.Result result = UpgradeSmokeTest.run(workspace, settings, Set.of("Example"), command);
            if (result.passed() != mode.equals("pass")) {
                throw new AssertionError(mode + ": " + result.reason() + "\n" + Files.readString(workspace.resolve("console.log")));
            }
        }
        if (!Files.readString(live).equals("unchanged")) { throw new AssertionError("Live canary changed"); }
        UpgradeSmokeTest.removeTestPayload(workspace);
        System.out.println("LinuxUpgradeSandboxRegressionTest passed: isolated startup, failure rejection, clean stop, live files hidden");
    }
}
