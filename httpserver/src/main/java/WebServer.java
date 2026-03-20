import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Logger;

public class WebServer {
    private static final String TASK_ENDPOINT = "/task";
    private static final String STATUS_ENDPOINT = "/status";

    /* HTTP header constants for feature flags */
    private static final String HEADER_TEST = "X-Test";
    private static final String HEADER_DEBUG = "X-Debug";
    private static final String HEADER_DEBUG_INFO = "X-Debug-Info";

    /* Server configuration constants */
    private static final int DEFAULT_PORT = 8080;
    private static final int THREAD_POOL_SIZE = 8;

    /* HTTP status codes */
    private static final int HTTP_OK = 200;
    private static final int HTTP_METHOD_NOT_ALLOWED = 405;
    private static final int HTTP_BAD_REQUEST = 400;
    private static final int HTTP_INTERNAL_ERROR = 500;

    /* Functional predicates for validation */
    private static final Predicate<Integer> IS_VALID_PORT = port -> port >= 1 && port <= 65535;
    private static final Predicate<String> IS_POST_METHOD = method -> "POST".equalsIgnoreCase(method);
    private static final Predicate<String> IS_GET_METHOD = method -> "GET".equalsIgnoreCase(method);
    private static final Predicate<String> IS_TRUE_HEADER = value -> "true".equalsIgnoreCase(value);
    private static final Predicate<String> IS_NON_EMPTY = str -> str != null && !str.trim().isEmpty();

    /* Functional transformations */
    private static final Function<String, String> TRIM_STRING = String::trim;
    private static final Function<String, BigInteger> PARSE_BIG_INTEGER = BigInteger::new;
    private static final Function<BigInteger, Function<BigInteger, BigInteger>> MULTIPLY =
            acc -> num -> acc.multiply(num);
    private static final Logger LOGGER = Logger.getLogger(WebServer.class.getName());
    private final int port;
    private HttpServer server;

    public static void main(String[] args) {
        /*
         * Parse port number using functional approach with Optional.
         * Note: Optional chaining is short-circuiting - as soon as
         * any operation in the chain produces an empty Optional, it
         * skips all subsequent map(), flatMap(), filter() operations
         * and goes straight to the terminal operation (orElse(),
         * orElseGet(), etc.).
         */
        int serverPort = Optional
                .of(args)
                .filter(params -> params.length == 1)
                .map(params -> params[0])
                .flatMap(portString -> parsePort(portString))
                .orElse(8080);

        WebServer webServer = new WebServer(serverPort);
        webServer.startServer();

        System.out.println("Server is listening on port " + serverPort);
    }

    /**
     * Safely parse port number with functional error handling
     */
    private static Optional<Integer> parsePort(String portStr) {
        try {
            return Optional.of(Integer.parseInt(portStr))
                    .filter(IS_VALID_PORT)
                    .or(() -> {
                        LOGGER.severe("Port must be between 1 and 65535, got: " + portStr);
                        return Optional.empty();
                    });
        } catch (NumberFormatException e) {
            LOGGER.severe("Invalid port number format: " + portStr);
            return Optional.empty();
        }
    }
    public WebServer(int port) {
        this.port = port;
    }

    public void startServer() {
        try {
            this.server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        HttpContext statusContext = server.createContext(STATUS_ENDPOINT);
        statusContext.setHandler(this::handleStatusCheckRequest);

        HttpContext taskContext = server.createContext(TASK_ENDPOINT);
        taskContext.setHandler(this::handleTaskRequest);

        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
    }

    private void handleTaskRequest(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("post")) {
            exchange.close();
            return;
        }

        Headers headers = exchange.getRequestHeaders();
        if (headers.containsKey("X-Test") && headers.get("X-Test").get(0).equalsIgnoreCase("true")) {
            String dummyResponse = "123\n";
            sendResponse(dummyResponse.getBytes(), exchange);
            return;
        }

        boolean isDebugMode = false;
        if (headers.containsKey("X-Debug") && headers.get("X-Debug").get(0).equalsIgnoreCase("true")) {
            isDebugMode = true;
        }

        long startTime = System.nanoTime();

        byte[] requestBytes = exchange.getRequestBody().readAllBytes();
        byte[] responseBytes = calculateResponse(requestBytes);

        long finishTime = System.nanoTime();

        if (isDebugMode) {
            String debugMessage = String.format("Operation took %d ns", finishTime - startTime);
            exchange.getResponseHeaders().put("X-Debug-Info", Arrays.asList(debugMessage));
        }

        sendResponse(responseBytes, exchange);
    }

    private byte[] calculateResponse(byte[] requestBytes) {
        String bodyString = new String(requestBytes);
        String[] stringNumbers = bodyString.split(",");

        BigInteger result = BigInteger.ONE;

        for (String number : stringNumbers) {
            BigInteger bigInteger = new BigInteger(number);
            result = result.multiply(bigInteger);
        }

        return String.format("Result of the multiplication is %s\n", result).getBytes();
    }

    private void handleStatusCheckRequest(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("get")) {
            exchange.close();
            return;
        }

        String responseMessage = "Server is alive\n";
        sendResponse(responseMessage.getBytes(), exchange);
    }

    private void sendResponse(byte[] responseBytes, HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(200, responseBytes.length);
        OutputStream outputStream = exchange.getResponseBody();
        outputStream.write(responseBytes);
        outputStream.flush();
        outputStream.close();
        exchange.close();
    }
}
