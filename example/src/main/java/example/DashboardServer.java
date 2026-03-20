package example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Lightweight HTTP API for controlling a {@link WorkerManager} and viewing
 * worker status.  Intended to be consumed by the React dashboard.
 *
 * <pre>
 * POST /api/manager/start   — start the manager (spawns workers)
 * POST /api/manager/stop    — stop the manager (kills workers)
 * GET  /api/manager/status   — JSON snapshot of manager + worker state
 * </pre>
 */
public class DashboardServer {

    private static final int DEFAULT_PORT = 8085;
    private static final int DEFAULT_WORKERS = 3;
    private static final String DEFAULT_ZK = "localhost:2181";

    private final int port;
    private final int targetWorkers;
    private final String zookeeperAddress;
    private final ObjectMapper mapper = new ObjectMapper();

    private HttpServer httpServer;
    private volatile WorkerManager manager;

    public DashboardServer(int port, int targetWorkers, String zookeeperAddress) {
        this.port = port;
        this.targetWorkers = targetWorkers;
        this.zookeeperAddress = zookeeperAddress;
    }

    public void start() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.createContext("/api/manager/start", this::handleStart);
        httpServer.createContext("/api/manager/stop", this::handleStop);
        httpServer.createContext("/api/manager/status", this::handleStatus);
        httpServer.setExecutor(Executors.newFixedThreadPool(4));
        httpServer.start();
        System.out.println("Dashboard API listening on http://localhost:" + port);
    }

    // --- POST /api/manager/start ---

    private void handleStart(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!requireMethod(exchange, "POST")) return;

        if (manager != null && manager.isRunning()) {
            sendJson(exchange, 200, Map.of("status", "already_running"));
            return;
        }

        try {
            manager = new WorkerManager(targetWorkers, zookeeperAddress);
            // Start on a background thread — reconcileWorkers blocks briefly
            Thread bgThread = new Thread(() -> {
                try {
                    manager.start();
                } catch (Exception e) {
                    System.err.println("Failed to start manager: " + e.getMessage());
                    manager = null;
                }
            });
            bgThread.setDaemon(true);
            bgThread.start();
            // Give it a moment to connect
            Thread.sleep(1000);
            sendJson(exchange, 200, Map.of("status", "started"));
        } catch (Exception e) {
            sendJson(exchange, 500, Map.of("status", "error", "message", e.getMessage()));
        }
    }

    // --- POST /api/manager/stop ---

    private void handleStop(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!requireMethod(exchange, "POST")) return;

        if (manager == null || !manager.isRunning()) {
            sendJson(exchange, 200, Map.of("status", "not_running"));
            return;
        }

        try {
            manager.stop();
            manager = null;
            sendJson(exchange, 200, Map.of("status", "stopped"));
        } catch (Exception e) {
            sendJson(exchange, 500, Map.of("status", "error", "message", e.getMessage()));
        }
    }

    // --- GET /api/manager/status ---

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!requireMethod(exchange, "GET")) return;

        Map<String, Object> body = new LinkedHashMap<>();
        boolean running = manager != null && manager.isRunning();
        body.put("managerRunning", running);
        body.put("targetWorkers", targetWorkers);
        body.put("workers", running ? manager.getWorkerInfo() : java.util.List.of());
        sendJson(exchange, 200, body);
    }

    // --- helpers ---

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Returns true if the request was an OPTIONS preflight (already handled). */
    private boolean handleCors(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    /** Sends 405 if the method doesn't match. Returns false when rejected. */
    private boolean requireMethod(HttpExchange exchange, String method) throws IOException {
        if (method.equalsIgnoreCase(exchange.getRequestMethod())) {
            return true;
        }
        sendJson(exchange, 405, Map.of("error", "Method not allowed. Use " + method + "."));
        return false;
    }

    // --- main ---

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        int workers = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_WORKERS;
        String zk = args.length > 2 ? args[2] : DEFAULT_ZK;

        DashboardServer server = new DashboardServer(port, workers, zk);
        server.start();
    }
}
