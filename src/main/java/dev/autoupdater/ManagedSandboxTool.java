package dev.autoupdater;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Obtains bubblewrap through the host's signed APT repositories without installing system packages. */
final class ManagedSandboxTool {
    private ManagedSandboxTool() { }

    static synchronized Path resolve(Path cache) throws IOException, InterruptedException {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux")) {
            throw new IOException("Automatic upgrade-test isolation currently requires Linux");
        }
        Path tools = Files.createDirectories(cache.toAbsolutePath().resolve("tools/bubblewrap"));
        Path log = tools.resolve("setup.log");
        Path installed = executable("bwrap");
        if (installed != null && works(installed, tools, log)) {
            return installed;
        }
        Path local = tools.resolve("root/usr/bin/bwrap");
        if (Files.isExecutable(local) && works(local, tools, log)) {
            return local;
        }
        Path apt = executable("apt-get");
        Path dpkg = executable("dpkg-deb");
        if (apt == null || dpkg == null) {
            throw new IOException("Automatic sandbox setup needs a Debian/Ubuntu package manager; upgrade postponed");
        }
        System.out.println("[updater] Preparing isolated startup-test tooling in the local cache (no administrator access needed).");
        Path download = Files.createTempDirectory(tools, "download-");
        Path lists = Files.createDirectories(tools.resolve("apt-lists"));
        Files.createDirectories(lists.resolve("partial"));
        List<String> options = List.of(apt.toString(),
            "-o", "Dir::State::lists=" + lists,
            "-o", "Dir::Cache::pkgcache=" + tools.resolve("pkgcache.bin"),
            "-o", "Dir::Cache::srcpkgcache=" + tools.resolve("srcpkgcache.bin"),
            "-o", "Debug::NoLocking=true",
            "-o", "APT::Get::AllowUnauthenticated=false",
            "-o", "Acquire::AllowInsecureRepositories=false",
            "-o", "Acquire::AllowDowngradeToInsecureRepositories=false",
            "-o", "APT::Update::Error-Mode=any");
        // APT verifies repository signatures and package hashes; never allow unauthenticated packages.
        command(with(options, "update"), download, log, 180);
        command(with(options, "download", "bubblewrap", "libcap2"), download, log, 120);
        Path root = Files.createDirectories(tools.resolve("root"));
        try (var files = Files.list(download)) {
            List<Path> packages = files.filter(file -> file.getFileName().toString().endsWith(".deb")).sorted().toList();
            if (packages.size() != 2) {
                throw new IOException("Sandbox download did not produce the expected verified packages; see " + log);
            }
            for (Path archive : packages) {
                command(List.of(dpkg.toString(), "--extract", archive.toString(), root.toString()), download, log, 30);
            }
        }
        Files.writeString(root.resolve(".managed-bubblewrap"), "Downloaded and verified by APT\n");
        if (!Files.isExecutable(local) || !works(local, tools, log)) {
            throw new IOException("Downloaded sandbox could not run on this host; upgrade postponed. See " + log);
        }
        System.out.println("[updater] Startup-test tooling is ready in the local cache.");
        return local;
    }

    static void configureEnvironment(ProcessBuilder builder) throws IOException {
        Path binary = Path.of(builder.command().get(0));
        if (!binary.isAbsolute() || binary.getParent() == null || binary.getParent().getParent() == null) { return; }
        Path root = binary.getParent().getParent().getParent();
        if (root == null || !Files.isRegularFile(root.resolve(".managed-bubblewrap"))) { return; }
        List<String> directories = new ArrayList<>();
        for (Path lib : List.of(root.resolve("lib"), root.resolve("usr/lib"))) {
            if (!Files.isDirectory(lib)) { continue; }
            try (var paths = Files.walk(lib, 2)) {
                for (Path directory : paths.filter(Files::isDirectory).toList()) {
                    directories.add(directory.toString());
                }
            }
        }
        builder.environment().put("LD_LIBRARY_PATH", String.join(":", directories));
    }

    private static List<String> with(List<String> options, String... tail) {
        List<String> command = new ArrayList<>(options);
        command.addAll(List.of(tail));
        return command;
    }

    private static Path executable(String name) {
        for (String directory : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            Path file = Path.of(directory).resolve(name);
            if (Files.isRegularFile(file) && Files.isExecutable(file)) { return file.toAbsolutePath(); }
        }
        return null;
    }

    private static boolean works(Path tool, Path cwd, Path log) throws InterruptedException {
        try {
            command(List.of(tool.toString(), "--version"), cwd, log, 5);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    private static void command(List<String> args, Path cwd, Path log, int seconds) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(args).directory(cwd.toFile()).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        configureEnvironment(builder);
        Process process = builder.start();
        try {
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                throw new IOException("Automatic sandbox setup timed out; see " + log);
            }
            if (process.exitValue() != 0) {
                throw new IOException("Automatic sandbox setup failed during " + Path.of(args.get(0)).getFileName()
                    + "; see " + log);
            }
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor();
            }
        }
    }
}
