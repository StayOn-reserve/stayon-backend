package com.stayon.stayon_backend.service;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Starts an already-installed binary with fresh data and --no-defaults. Existing endpoints are unsupported. */
final class OwnedMySql implements AutoCloseable {
    static final String SCHEMA = "stayon_order_mysql_tests";
    private final Path directory;
    private final int port;
    private Process process;
    private ProcessHandle server;
    private boolean ready;

    static OwnedMySql start() {
        try { return new OwnedMySql(); }
        catch (Exception e) { throw new IllegalStateException("Could not start an owned MySQL 8 instance", e); }
    }

    private OwnedMySql() throws Exception {
        String configured = System.getenv("STAYON_TEST_MYSQL_HOME");
        Path home = Path.of(configured == null ? "C:/Program Files/MySQL/MySQL Server 8.0" : configured).toAbsolutePath();
        Path executable = home.resolve("bin/mysqld.exe");
        if (!Files.isRegularFile(executable)) throw new IllegalStateException("Installed MySQL 8 binary not found");
        Path root = Files.createDirectories(Path.of("build", "mysql-integration", UUID.randomUUID().toString()).toAbsolutePath());
        directory = Files.createDirectory(root.resolve("data"));
        try (var socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
        try {
            var version = run(List.of(executable.toString(), "--no-defaults", "--version"), "version.log", 10);
            if (!version.contains("Ver 8.")) throw new IllegalStateException("MySQL 8.x required");
            run(List.of(executable.toString(), "--no-defaults", "--initialize-insecure", "--basedir=" + home,
                    "--datadir=" + directory, "--log-error=" + root.resolve("initialize.log")), "initialize-console.log", 45);
            process = new ProcessBuilder(executable.toString(), "--no-defaults", "--console", "--basedir=" + home,
                    "--datadir=" + directory, "--bind-address=127.0.0.1", "--port=" + port,
                    "--mysqlx=OFF", "--skip-log-bin", "--innodb-lock-wait-timeout=2",
                    "--pid-file=" + directory.resolve("owned.pid"))
                    .redirectErrorStream(true).redirectOutput(root.resolve("server.log").toFile()).start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { close(); } catch (Exception ignored) { /* Process cleanup is also attempted by the test lifecycle. */ }
            }, "owned-mysql-cleanup"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            while (System.nanoTime() < deadline) {
                String log = Files.readString(root.resolve("server.log"));
                if (log.contains("ready for connections") && log.contains("port: " + port)) {
                    // A unique fresh datadir's PID/ready logs identify our server before the first TCP connection.
                    long ownedPid = Long.parseLong(Files.readString(directory.resolve("owned.pid")).trim());
                    server = ProcessHandle.of(ownedPid).filter(ProcessHandle::isAlive)
                            .orElseThrow(() -> new IllegalStateException("Owned MySQL PID is not alive"));
                    ready = true;
                    try (var connection = DriverManager.getConnection(url(""), "root", ""); var statement = connection.createStatement()) {
                        try (var result = statement.executeQuery("select @@datadir, @@port, version()")) {
                            result.next();
                            Path actual = Path.of(result.getString(1)).toAbsolutePath().normalize();
                            if (!actual.equals(directory.normalize()) || result.getInt(2) != port || !result.getString(3).startsWith("8."))
                                throw new IllegalStateException("Owned MySQL identity mismatch");
                        }
                        statement.execute("create database " + SCHEMA + " character set utf8mb4 collate utf8mb4_0900_ai_ci");
                    }
                    return;
                }
                if (!process.isAlive()) throw new IllegalStateException("Owned MySQL exited; see " + root.resolve("server.log"));
                Thread.sleep(50);
            }
            throw new IllegalStateException("Owned MySQL startup timeout; see " + directory);
        } catch (Exception e) {
            try { close(); } catch (Exception cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    String jdbcUrl() { return url(SCHEMA); }
    Connection connection() throws Exception { return DriverManager.getConnection(jdbcUrl(), "root", ""); }

    private String url(String schema) {
        return "jdbc:mysql://127.0.0.1:" + port + "/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&connectTimeout=2000&socketTimeout=15000";
    }

    private String run(List<String> args, String log, int timeout) throws Exception {
        Path output = directory.resolveSibling(log);
        var child = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!child.waitFor(timeout, TimeUnit.SECONDS)) {
            child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS);
            throw new IllegalStateException("Owned MySQL command timed out; see " + output);
        }
        if (child.exitValue() != 0) throw new IllegalStateException("Owned MySQL command failed; see " + output);
        return Files.readString(output);
    }

    @Override public synchronized void close() throws Exception {
        if (process == null) return;
        try {
            if (ready) {
            try (var connection = DriverManager.getConnection(url(""), "root", ""); var statement = connection.createStatement()) {
                // Never issue shutdown without verifying the unique, test-owned data directory again.
                try (var result = statement.executeQuery("select @@datadir")) {
                    result.next();
                    if (!Path.of(result.getString(1)).toAbsolutePath().normalize().equals(directory.normalize()))
                        throw new IllegalStateException("Refusing shutdown of a foreign MySQL server");
                }
                statement.execute("shutdown");
            }
            }
        } catch (java.sql.SQLException e) {
            System.err.println("Owned MySQL graceful shutdown unavailable; stopping only the owned process.");
        } finally { ready = false; }
        if (server != null && server.isAlive()) {
            try { server.onExit().get(5, TimeUnit.SECONDS); }
            catch (java.util.concurrent.TimeoutException e) { server.destroy(); }
        }
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            var descendants = process.descendants().toList();
            descendants.forEach(ProcessHandle::destroy);
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
        }
        process = null;
    }
}
