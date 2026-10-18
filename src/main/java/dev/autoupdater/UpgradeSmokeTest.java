package dev.autoupdater;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

/** Runs proposed jars in a disposable world. Only a sandboxed child may execute plugins. */
final class UpgradeSmokeTest {
    static final class Settings {
        boolean enabled = true;
        int startupSeconds = 180;
        int observeSeconds = 15;
        int stopSeconds = 30;
        String javaArgs = "-Xms256M -Xmx2G";
        List<String> excludePlugins = new ArrayList<>();
    }

    record Result(boolean passed, String reason, Set<String> failedPlugins) { }
    record Plugin(Path jar, String name) { }

    private UpgradeSmokeTest() { }

    static boolean excluded(Settings settings, String name) {
        return settings.excludePlugins.stream().anyMatch(name::equalsIgnoreCase);
    }

    static Path createWorkspace(Path cache) throws IOException {
        Path root = cache.resolve("upgrade-tests").toAbsolutePath().normalize();
        Files.createDirectories(root);
        return Files.createTempDirectory(root, "test-");
    }

    static void prepare(Path live, Path workspace, Path server, List<Plugin> plugins) throws IOException {
        Files.copy(server, workspace.resolve("server.jar"), StandardCopyOption.REPLACE_EXISTING);
        Path pluginDir = Files.createDirectories(workspace.resolve("plugins"));
        Set<String> names = new LinkedHashSet<>();
        for (Plugin plugin : plugins) {
            if (!names.add(plugin.name.toLowerCase(Locale.ROOT))) {
                throw new IOException("Duplicate plugin in upgrade test: " + plugin.name);
            }
            String filename = String.format("%03d.jar", names.size());
            Files.copy(plugin.jar, pluginDir.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
        }
        copyConfigurations(live.resolve("plugins"), pluginDir);
        // Paper/Folia configuration also affects plugin initialization; worlds and databases stay out.
        for (String name : List.of("config", "bukkit.yml", "spigot.yml", "commands.yml", "permissions.yml")) {
            Path source = live.resolve(name);
            if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                copyConfigurations(source, workspace.resolve(name));
            } else if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                copyConfiguration(source, workspace.resolve(name));
            }
        }
        Properties properties = new Properties();
        // Never inherit level-name, bind addresses, RCON credentials, resource packs or live ports.
        properties.setProperty("level-name", "smoke-world");
        properties.setProperty("level-type", "minecraft:flat");
        properties.setProperty("generate-structures", "false");
        properties.setProperty("server-ip", "127.0.0.1");
        properties.setProperty("server-port", "25566");
        properties.setProperty("online-mode", "false");
        properties.setProperty("enable-rcon", "false");
        properties.setProperty("enable-query", "false");
        properties.setProperty("enable-status", "false");
        properties.setProperty("view-distance", "2");
        properties.setProperty("simulation-distance", "2");
        properties.setProperty("max-players", "1");
        properties.setProperty("sync-chunk-writes", "false");
        StringBuilder rendered = new StringBuilder();
        for (String key : properties.stringPropertyNames().stream().sorted().toList()) {
            rendered.append(key).append('=').append(properties.getProperty(key)).append('\n');
        }
        Files.writeString(workspace.resolve("server.properties"), rendered);
        if (!Files.isRegularFile(live.resolve("eula.txt"))
            || !Files.readString(live.resolve("eula.txt")).matches("(?s).*\\beula\\s*=\\s*true\\b.*")) {
            throw new IOException("Upgrade test requires the existing server's accepted eula.txt");
        }
        Files.writeString(workspace.resolve("eula.txt"), "eula=true\n");
        // Paperclip can reuse its downloaded runtime without reaching the network.
        for (String directory : List.of("libraries", "versions", "cache")) {
            copyRuntime(live.resolve(directory), workspace.resolve(directory));
        }
    }

    static void copyConfigurations(Path source, Path destination) throws IOException {
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        long[] total = {0};
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!dir.equals(source) && Set.of("cache", "caches", "logs", "backups", "reports",
                    "tiles", "web", ".git", "libraries", ".paper-remapped").contains(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile() && isConfiguration(file)) {
                    total[0] += attrs.size();
                    if (total[0] > 64L * 1024 * 1024) {
                        throw new IOException("Plugin configurations exceed the 64 MiB test copy limit");
                    }
                    copyConfiguration(file, destination.resolve(source.relativize(file)));
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean isConfiguration(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return List.of(".yml", ".yaml", ".json", ".toml", ".conf", ".properties", ".xml")
            .stream().anyMatch(name::endsWith);
    }

    private static void copyConfiguration(Path from, Path to) throws IOException {
        if (Files.size(from) > 2L * 1024 * 1024) {
            throw new IOException("Configuration too large for startup test: " + from.getFileName());
        }
        Files.createDirectories(to.getParent());
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void copyRuntime(Path source, Path destination) throws IOException {
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                // The updater cache may contain this workspace; never recursively copy it into itself.
                if (dir.toAbsolutePath().normalize().startsWith(destination.getParent().toAbsolutePath().normalize())
                    || (!dir.equals(source) && Set.of("upgrade-tests", "source", "tools", "staging")
                        .contains(dir.getFileName().toString()))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                // Reuse only bootstrap artifacts, never arbitrary cache data or symlinks.
                if (attrs.isRegularFile() && file.getFileName().toString().endsWith(".jar")) {
                    Path to = destination.resolve(source.relativize(file));
                    Files.createDirectories(to.getParent());
                    Files.copy(file, to, StandardCopyOption.REPLACE_EXISTING);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static List<String> sandboxCommand(Path workspace, List<String> javaCommand) throws IOException {
        return sandboxCommand(workspace, javaCommand, Path.of("bwrap"));
    }

    static List<String> sandboxCommand(Path workspace, List<String> javaCommand, Path sandboxTool) throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
            throw new IOException("Upgrade test requires Linux bubblewrap (bwrap); live upgrade postponed");
        }
        List<String> command = new ArrayList<>(List.of(sandboxTool.toString(), "--die-with-parent", "--new-session",
            "--unshare-user", "--unshare-net", "--unshare-pid", "--unshare-ipc", "--cap-drop", "ALL",
            "--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp", "--tmpfs", "/run"));
        // Expose system runtimes, not host homes or live server folders (including Unix database sockets).
        for (String directory : List.of("/usr", "/bin", "/sbin", "/lib", "/lib64", "/etc")) {
            if (Files.isDirectory(Path.of(directory))) {
                command.addAll(List.of("--ro-bind", directory, directory));
            }
        }
        Path java = findJava(javaCommand.get(0));
        Path javaHome = java.getParent().getParent();
        command.addAll(List.of("--ro-bind", javaHome.toString(), javaHome.toString()));
        Path resolv = Path.of("/etc/resolv.conf");
        if (Files.exists(resolv) && Files.isSymbolicLink(resolv)) {
            Path actual = resolv.toRealPath();
            command.addAll(List.of("--ro-bind", actual.toString(), actual.toString()));
        }
        command.addAll(List.of("--bind", workspace.toString(), workspace.toString(),
            "--setenv", "HOME", workspace.toString(), "--chdir", workspace.toString(), "--"));
        List<String> resolved = new ArrayList<>(javaCommand);
        resolved.set(0, java.toString());
        command.addAll(resolved);
        return command;
    }

    private static Path findJava(String executable) throws IOException {
        Path supplied = Path.of(executable);
        if (supplied.isAbsolute() && Files.isRegularFile(supplied)) { return supplied.toRealPath(); }
        for (String directory : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(directory).resolve(executable);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) { return candidate.toRealPath(); }
        }
        throw new IOException("Cannot locate test Java executable: " + executable);
    }

    static void bootstrapPaperclip(Path workspace, String java, Settings settings, Path sandboxTool) throws IOException, InterruptedException {
        try (JarFile jar = new JarFile(workspace.resolve("server.jar").toFile())) {
            if (jar.getJarEntry("io/papermc/paperclip/Paperclip.class") == null) {
                throw new IOException("PaperMC bootstrap is not recognized; upgrade test postponed");
            }
        }
        // Paperclip's patch-only mode prepares the runtime and exits before loading any plugins.
        // This trusted-server bootstrap may download Mojang artifacts; the actual test has no network.
        List<String> command = sandboxCommand(workspace, List.of(java == null || java.isBlank() ? "java" : java,
            "-Xmx1G", "-Dpaperclip.patchonly=true", "-jar", "server.jar"), sandboxTool);
        command.remove("--unshare-net");
        ProcessBuilder builder = new ProcessBuilder(command).directory(workspace.toFile())
            .redirectErrorStream(true).redirectOutput(workspace.resolve("bootstrap.log").toFile());
        scrubJavaEnvironment(builder);
        ManagedSandboxTool.configureEnvironment(builder);
        Process process = builder.start();
        try {
            if (!process.waitFor(settings.startupSeconds, TimeUnit.SECONDS)) {
                throw new IOException("PaperMC runtime preparation timed out; see bootstrap.log");
            }
            if (process.exitValue() != 0) {
                throw new IOException("PaperMC runtime preparation failed; see bootstrap.log (host isolation may be restricted)");
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor();
            }
        }
    }

    private static void scrubJavaEnvironment(ProcessBuilder builder) {
        for (String key : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH")) {
            builder.environment().remove(key);
        }
    }

    static void removeTestPayload(Path workspace) throws IOException {
        Path checked = workspace.toAbsolutePath().normalize();
        if (!checked.getFileName().toString().startsWith("test-")
            || !checked.getParent().getFileName().toString().equals("upgrade-tests")
            || Files.isSymbolicLink(checked)) {
            throw new IOException("Refusing cleanup outside an upgrade test workspace");
        }
        Files.walkFileTree(checked, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!file.equals(checked.resolve("console.log")) && !file.equals(checked.resolve("result.txt"))
                    && !file.equals(checked.resolve("bootstrap.log"))) {
                    Files.delete(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) { throw error; }
                if (!dir.equals(checked)) { Files.delete(dir); }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static Result run(Path workspace, Settings settings, Set<String> expected,
                      List<String> command) throws IOException, InterruptedException {
        return run(workspace, settings, expected, command, ProcessBuilder::start);
    }

    // The launcher is injected only by process-lifecycle regression tests.
    static Result run(Path workspace, Settings settings, Set<String> expected, List<String> command,
                      Launcher launcher) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true);
        scrubJavaEnvironment(builder);
        ManagedSandboxTool.configureEnvironment(builder);
        Health health = new Health(expected);
        Process process = launcher.start(builder);
        Path log = workspace.resolve("console.log");
        Thread reader = new Thread(() -> {
            try (var input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                 var output = Files.newBufferedWriter(log)) {
                String line;
                while ((line = input.readLine()) != null) {
                    health.observe(line);
                    output.write(line);
                    output.newLine();
                    output.flush();
                }
            } catch (IOException ex) {
                health.fail("Test console could not be read");
            }
        }, "upgrade-test-output");
        reader.setDaemon(true);
        reader.start();
        Result result;
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(settings.startupSeconds).toNanos();
            long readyAt = 0;
            result = new Result(false, "Startup timed out", Set.of());
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) {
                    reader.join(2000);
                    result = new Result(false, health.failure() == null
                        ? "Test process exited before approval (code " + process.exitValue() + ")"
                        : health.failure(), health.failed());
                    break;
                }
                if (health.failure() != null) {
                    result = new Result(false, health.failure(), health.failed());
                    break;
                }
                if (health.ready && readyAt == 0) {
                    readyAt = System.nanoTime();
                }
                if (readyAt != 0 && System.nanoTime() - readyAt >= Duration.ofSeconds(settings.observeSeconds).toNanos()) {
                    result = health.result();
                    break;
                }
                process.waitFor(100, TimeUnit.MILLISECONDS);
            }
            if (process.isAlive()) {
                health.stopping = true;
                process.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
                process.getOutputStream().flush();
                if (!process.waitFor(settings.stopSeconds, TimeUnit.SECONDS)) {
                    result = new Result(false, "Test server did not stop cleanly", health.failed());
                } else if (process.exitValue() != 0) {
                    result = new Result(false, "Test server exited with code " + process.exitValue(), health.failed());
                }
            }
        } finally {
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor();
                }
            }
            reader.join(5000);
        }
        if (result.passed() && health.failure() != null) {
            result = new Result(false, health.failure(), health.failed());
        }
        return result;
    }

    interface Launcher {
        Process start(ProcessBuilder builder) throws IOException;
    }

    static final class Health {
        private final Set<String> expected;
        private final Set<String> enabled = new LinkedHashSet<>();
        private final Set<String> failed = new LinkedHashSet<>();
        private String failure;
        volatile boolean ready;
        volatile boolean stopping;

        Health(Set<String> expected) {
            this.expected = Set.copyOf(expected);
        }

        synchronized void observe(String line) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("done (") && lower.contains("for help")) {
                ready = true;
            }
            boolean error = lower.contains("error occurred while enabling") || lower.contains("error occurred while loading")
                || lower.contains("could not load") || lower.contains("failed to load")
                || lower.contains("encountered an unexpected exception") || lower.contains("exception in server tick")
                || lower.contains("noclassdeffounderror") || lower.contains("nosuchfielderror")
                || lower.contains("nosuchmethoderror") || lower.contains("unsupportedoperationexception");
            // SLF4J's optional logger-binding warning does not indicate a plugin load failure.
            if (lower.contains("slf4j:") && lower.contains("staticloggerbinder")) {
                error = false;
            }
            for (String name : expected) {
                String token = name.toLowerCase(Locale.ROOT);
                if (lower.contains("enabling " + token + " v")) {
                    enabled.add(name);
                }
                if (lower.contains("disabling " + token + " v") && !stopping) {
                    error = true;
                    failed.add(name);
                }
                if (error && (lower.contains("[" + token + "]") || lower.contains(" " + token + " ")
                    || lower.contains("'" + token) || lower.contains(token + ".jar"))) {
                    failed.add(name);
                }
            }
            if (error) {
                fail("Plugin/server startup failure; inspect console.log");
            }
        }

        synchronized void fail(String reason) { failure = reason; }
        synchronized String failure() { return failure; }
        synchronized Set<String> failed() { return Set.copyOf(failed); }

        synchronized Result result() {
            if (failure != null) {
                return new Result(false, failure, failed());
            }
            Set<String> missing = new LinkedHashSet<>(expected);
            missing.removeAll(enabled);
            return missing.isEmpty()
                ? new Result(true, "Startup and observation passed", Set.of())
                : new Result(false, "Expected plugins did not enable: " + String.join(", ", missing), missing);
        }
    }
}
