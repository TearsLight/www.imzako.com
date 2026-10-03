package com.imzako.rcon;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small authenticated line-oriented control server. Prefer an SSH tunnel for remote access. */
@Component
public class RconServer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RconServer.class);
    private static final int MAX_LINE_BYTES = 8192;

    private final RconCommandService commands;
    private final boolean enabled;
    private final String host;
    private final int port;
    private final String token;
    private final ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean running;
    private ServerSocket server;
    private Thread acceptThread;

    public RconServer(
            RconCommandService commands,
            @Value("${app.rcon.enabled:true}") boolean enabled,
            @Value("${app.rcon.host:127.0.0.1}") String host,
            @Value("${app.rcon.port:8090}") int port,
            @Value("${app.rcon.token:}") String token) {
        this.commands = commands;
        this.enabled = enabled;
        this.host = host.strip();
        this.port = port;
        this.token = token;
    }

    @PostConstruct
    void start() throws IOException {
        if (!enabled) {
            log.info("RCON is disabled");
            return;
        }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("RCON_PORT must be between 1 and 65535");
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(host, port), 32);
        running = true;
        acceptThread = Thread.ofPlatform().name("imzako-rcon-accept").daemon(true).start(this::acceptLoop);
        if (token.isBlank()) {
            log.warn("RCON is listening on {}:{} but refuses authentication until RCON_TOKEN is configured", host, port);
        } else {
            log.info("RCON listening on {}:{}", host, port);
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();
                clients.submit(() -> handle(socket));
            } catch (IOException error) {
                if (running) log.warn("RCON accept failed", error);
            }
        }
    }

    private void handle(Socket socket) {
        String actor = String.valueOf(socket.getRemoteSocketAddress());
        try (socket;
             InputStream input = new BufferedInputStream(socket.getInputStream());
             BufferedWriter output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            socket.setSoTimeout((int) Duration.ofMinutes(2).toMillis());
            write(output, "OK imzako RCON; authenticate with: auth <token>");
            boolean authenticated = false;
            int failures = 0;
            while (running) {
                String line = readLine(input);
                if (line == null) return;
                if (!authenticated) {
                    if (line.regionMatches(true, 0, "auth ", 0, 5)
                            && secureEquals(token, line.substring(5)) && !token.isBlank()) {
                        authenticated = true;
                        write(output, "OK authenticated");
                    } else if (++failures >= 3) {
                        write(output, "ERR authentication failed");
                        return;
                    } else {
                        write(output, "ERR authentication required");
                    }
                    continue;
                }
                if ("quit".equalsIgnoreCase(line) || "exit".equalsIgnoreCase(line)) {
                    write(output, "OK bye");
                    return;
                }
                try {
                    write(output, "OK " + commands.execute(line, actor));
                } catch (IllegalArgumentException | IllegalStateException error) {
                    write(output, "ERR " + oneLine(error.getMessage()));
                } catch (RuntimeException error) {
                    log.warn("RCON command failed for {}", actor, error);
                    write(output, "ERR command failed");
                }
            }
        } catch (IOException error) {
            log.debug("RCON client disconnected: {}", actor);
        }
    }

    private static String readLine(InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        while (bytes.size() <= MAX_LINE_BYTES) {
            int value = input.read();
            if (value == -1) return bytes.size() == 0 ? null : bytes.toString(StandardCharsets.UTF_8);
            if (value == '\n') return bytes.toString(StandardCharsets.UTF_8).replaceFirst("\\r$", "");
            bytes.write(value);
        }
        throw new IOException("RCON line exceeds " + MAX_LINE_BYTES + " bytes");
    }

    private static void write(BufferedWriter output, String line) throws IOException {
        output.write(oneLine(line));
        output.write('\n');
        output.flush();
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ');
    }

    private static boolean secureEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    @PreDestroy
    public void close() throws IOException {
        running = false;
        if (server != null) server.close();
        clients.shutdownNow();
    }
}
