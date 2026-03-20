import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * A lightweight HTTP server that provides mathematical computation services.
 * Supports multiplication of large numbers with debugging and testing capabilities.
 */
public class WebFServer {
    private static final Logger LOGGER = Logger.getLogger(WebServer.class.getName());

    /* Endpoint configuration constants */
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

    private int port;
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
        int serverPort = Optional.of(args)
                .filter(arguments -> arguments.length == 1)
                .map(arguments -> arguments[0])
                .flatMap(WebFServer::parsePort)
                .orElse(DEFAULT_PORT);

        /* Create and start server with error handling */
        Optional.of(new WebFServer(serverPort))
                .ifPresentOrElse(
                        WebFServer::startServerSafely,
                        () -> {
                            LOGGER.severe("Failed to create server instance");
                            System.exit(1);
                        }
                );
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

    /**
     * Constructor with port validation using functional predicate
     */
    public WebFServer(int port) {
        if (!IS_VALID_PORT.test(port)) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        this.port = port;
    }

    /**
     * Safely start server with functional error handling
     */
//    private void startServerSafely() {
//        try {
//            startServer();
//            LOGGER.info("Server started successfully on port " + port);
//
//            /* Add shutdown hook using method reference */
//            Runtime.getRuntime().addShutdownHook(new Thread(this::stopServer));
//        } catch (Exception e) {
//            LOGGER.severe("Failed to start server: " + e.getMessage());
//            System.exit(1);
//        }
//    }

    /**
     * Start Server
     */
    private void startServerSafely() {
        /* Functional composition: attempt operation -> handle result */
        attemptServerStart()
                .ifPresentOrElse(
                        this::onServerStartSuccess,
                        this::onServerStartFailure
                );
    }

    /**
     * Attempt server startup returning Optional based on success/failure
     * Pure function that encapsulates the risky operation
     */
    private Optional<Void> attemptServerStart() {
        return executeWithExceptionHandling(() -> {
            try {
                startServer();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return null; /* Void return wrapped in Optional */
        });
    }

    /**
     * Generic functional wrapper for exception-prone operations
     * Higher-order function that accepts a Supplier and returns Optional
     */
    private <T> Optional<T> executeWithExceptionHandling(Supplier<T> operation) {
        try {
            return Optional.of(operation.get());
        } catch (Exception e) {
            /* Log error and return empty Optional instead of throwing */
            LOGGER.severe("Operation failed: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Success handler - pure function for successful server start
     * Separated concerns: logging and shutdown hook registration
     */
    private void onServerStartSuccess(Void ignored) {
        LOGGER.info("Server started successfully on port " + port);
        registerShutdownHook();
    }

    /**
     * Shutdown hook registration as a separate functional operation
     * Method reference composition for clean separation
     */
    private void registerShutdownHook() {
        Optional.of(Runtime.getRuntime())
                .map(runtime -> new Thread(this::stopServer))
                .ifPresent(Runtime.getRuntime()::addShutdownHook);
    }

    /**
     * Failure handler - functional approach to error handling
     * Pure function that handles the failure case
     */
    private void onServerStartFailure() {
        /* Functional composition of failure actions */
        Consumer<String> logAndExit = message -> {
            LOGGER.severe(message);
            System.exit(1);
        };

        logAndExit.accept("Failed to start server");
    }

    /* Alternative even more functional approach using Result monad pattern */

    /**
     * Result monad for better functional error handling
     * Encapsulates success/failure without exceptions
     */
    private static class Result<T> {
        private final T value;
        private final Exception error;
        private final boolean isSuccess;

        private Result(T value, Exception error, boolean isSuccess) {
            this.value = value;
            this.error = error;
            this.isSuccess = isSuccess;
        }

        public static <T> Result<T> success(T value) {
            return new Result<>(value, null, true);
        }

        public static <T> Result<T> failure(Exception error) {
            return new Result<>(null, error, false);
        }

        public <R> Result<R> map(Function<T, R> mapper) {
            return isSuccess ?
                    Result.success(mapper.apply(value)) :
                    Result.failure(error);
        }

        public <R> Result<R> flatMap(Function<T, Result<R>> mapper) {
            return isSuccess ? mapper.apply(value) : Result.failure(error);
        }

        public void fold(Consumer<T> onSuccess, Consumer<Exception> onFailure) {
            if (isSuccess) {
                onSuccess.accept(value);
            } else {
                onFailure.accept(error);
            }
        }
    }

    /**
     * Most functional approach using Result monad
     * Completely eliminates exception handling from control flow
     */
    private void startServerSafelyWithResult() {
        attemptServerStartResult()
                .map(this::addSuccessLogging)
                .map(this::addShutdownHook)
                .fold(
                        success -> {/* Already handled in pipeline */},
                        this::handleServerStartError
                );
    }

    /**
     * Server start with Result monad - no exceptions in return type
     */
    private Result<Void> attemptServerStartResult() {
        try {
            startServer();
            return Result.success(null);
        } catch (Exception e) {
            return Result.failure(e);
        }
    }

    /**
     * Pure function that adds logging and returns input unchanged
     * Functional side-effect handling
     */
    private Void addSuccessLogging(Void input) {
        LOGGER.info("Server started successfully on port " + port);
        return input;
    }

    /**
     * Pure function that adds shutdown hook and returns input unchanged
     * Composable side-effect in functional pipeline
     */
    private Void addShutdownHook(Void input) {
        Runtime.getRuntime().addShutdownHook(new Thread(this::stopServer));
        return input;
    }

    /**
     * Functional error handler
     * Pure function for handling server start failures
     */
    private void handleServerStartError(Exception error) {
        Optional.of(error)
                .map(Exception::getMessage)
                .map(msg -> "Failed to start server: " + msg)
                .ifPresent(LOGGER::severe);

        System.exit(1);
    }

    /**
     * Initialize and start the HTTP server
     */
    public void startServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), 0);

        configureEndpoints();

        /* Set thread pool executor and start */
        server.setExecutor(Executors.newFixedThreadPool(THREAD_POOL_SIZE));
        server.start();

        LOGGER.info("Server is listening on port " + port);
    }

    /**
     * Gracefully stop the server with timeout
     */
    public void stopServer() {
        Optional.ofNullable(server)
                .ifPresent(s -> {
                    LOGGER.info("Stopping server...");
                    s.stop(5); /* 5 second grace period */
                });
    }

    /**
     * Configure HTTP endpoints
     * Each endpoint is configured as a separate operation
     */
    private void configureEndpoints() {
        /* Status endpoint configuration */
        Optional.of(server.createContext(STATUS_ENDPOINT))
                .ifPresent(context -> context.setHandler(this::handleStatusCheckRequest));

        /* Task endpoint configuration */
        Optional.of(server.createContext(TASK_ENDPOINT))
                .ifPresent(context -> context.setHandler(this::handleTaskRequest));
    }

    /**
     * Handle task requests
     * Processes mathematical operations with optional debugging and testing modes
     */
    private void handleTaskRequest(HttpExchange exchange) throws IOException {
        /* Validate HTTP method using  predicate */
        if (!IS_POST_METHOD.test(exchange.getRequestMethod())) {
            sendErrorResponse(HTTP_METHOD_NOT_ALLOWED, "Method not allowed. Use POST.", exchange);
            return;
        }

        try {
            Headers headers = exchange.getRequestHeaders();

            /* Handle test mode using  composition */
            if (isHeaderEnabled(headers, HEADER_TEST)) {
                sendResponse("123\n".getBytes(StandardCharsets.UTF_8), exchange);
                return;
            }

            /* Process request with  pipeline */
            processTaskRequest(exchange, headers);

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error handling task request", e);
            sendErrorResponse(HTTP_INTERNAL_ERROR, "Internal server error", exchange);
        }
    }

    /**
     * Process task request
     */
    private void processTaskRequest(HttpExchange exchange, Headers headers) throws IOException {
        boolean isDebugMode = isHeaderEnabled(headers, HEADER_DEBUG);
        long startTime = isDebugMode ? System.nanoTime() : 0;

        /* read -> calculate -> respond */
        readRequestBody(exchange)
                .flatMap(this::calculateResponse)
                .ifPresentOrElse(
                        responseBytes -> {
                            try {
                                /* Add debug information if enabled */
                                if (isDebugMode) {
                                    addDebugHeader(exchange, startTime);
                                }
                                sendResponse(responseBytes, exchange);
                            } catch (IOException e) {
                                LOGGER.log(Level.SEVERE, "Error sending response", e);
                            }
                        },
                        () -> {
                            try {
                                sendErrorResponse(HTTP_BAD_REQUEST,
                                        "Invalid input format. Use comma-separated numbers.", exchange);
                            } catch (IOException e) {
                                LOGGER.log(Level.SEVERE, "Error sending error response", e);
                            }
                        }
                );
    }

    /**
     * Check if header is enabled
     */
    private boolean isHeaderEnabled(Headers headers, String headerName) {
        return Optional.ofNullable(headers.get(headerName))
                .filter(values -> !values.isEmpty())
                .map(values -> values.get(0))
                .filter(IS_TRUE_HEADER)
                .isPresent();
    }

    /**
     * Add debug timing header using functional approach
     */
    private void addDebugHeader(HttpExchange exchange, long startTime) {
        long finishTime = System.nanoTime();
        String debugMessage = String.format("Operation took %d ns", finishTime - startTime);
        exchange.getResponseHeaders().put(HEADER_DEBUG_INFO, Arrays.asList(debugMessage));
    }

    /**
     * Read request body with functional error handling
     * Returns Optional to handle potential IO failures
     */
    private Optional<byte[]> readRequestBody(HttpExchange exchange) {
        try (InputStream inputStream = exchange.getRequestBody()) {
            return Optional.of(inputStream.readAllBytes());
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to read request body", e);
            return Optional.empty();
        }
    }

    /**
     * Calculate multiplication result using functional stream processing
     * Implements a functional pipeline: parse -> validate -> multiply -> format
     */
    private Optional<byte[]> calculateResponse(byte[] requestBytes) {
        try {
            return Optional.of(new String(requestBytes, StandardCharsets.UTF_8))
                    .map(TRIM_STRING)
                    .filter(IS_NON_EMPTY)
                    .map(bodyString -> bodyString.split(","))
                    .map(Arrays::stream)
                    .map(this::multiplyNumbers)
                    .map(this::formatResult);

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error calculating response", e);
            return Optional.empty();
        }
    }

    /**
     * Multiply numbers using functional stream operations
     * Uses reduce with BigInteger multiplication for arbitrary precision
     */
    private BigInteger multiplyNumbers(Stream<String> numberStream) {
        return numberStream
                .map(TRIM_STRING)
                .filter(IS_NON_EMPTY)
                .map(this::safeParseBigInteger)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .reduce(BigInteger.ONE, BigInteger::multiply);
    }

    /**
     * Safely parse BigInteger with functional error handling
     */
    private Optional<BigInteger> safeParseBigInteger(String numberStr) {
        try {
            return Optional.of(PARSE_BIG_INTEGER.apply(numberStr));
        } catch (NumberFormatException e) {
            LOGGER.warning("Invalid number format: " + numberStr);
            return Optional.empty();
        }
    }

    /**
     * Format result as byte array using functional transformation
     */
    private byte[] formatResult(BigInteger result) {
        return String.format("Result of the multiplication is %s\n", result)
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Handle status check requests using functional validation
     * Provides health check endpoint for monitoring systems
     */
    private void handleStatusCheckRequest(HttpExchange exchange) throws IOException {
        /* Validate HTTP method using functional predicate */
        if (!IS_GET_METHOD.test(exchange.getRequestMethod())) {
            sendErrorResponse(HTTP_METHOD_NOT_ALLOWED, "Method not allowed. Use GET.", exchange);
            return;
        }

        try {
            /* Simple functional response generation */
            Optional.of("Server is alive\n")
                    .map(msg -> msg.getBytes(StandardCharsets.UTF_8))
                    .ifPresent(responseBytes -> {
                        try {
                            sendResponse(responseBytes, exchange);
                        } catch (IOException e) {
                            LOGGER.log(Level.SEVERE, "Error sending status response", e);
                        }
                    });

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error handling status request", e);
            sendErrorResponse(HTTP_INTERNAL_ERROR, "Internal server error", exchange);
        }
    }

    /**
     * Send successful response with proper resource management
     * Uses try-with-resources for automatic cleanup
     */
    private void sendResponse(byte[] responseBytes, HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(HTTP_OK, responseBytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(responseBytes);
            outputStream.flush();
        } finally {
            exchange.close();
        }
    }

    /**
     * Send error response with proper HTTP status codes
     * Ensures consistent error handling across all endpoints
     */
    private void sendErrorResponse(int statusCode, String message, HttpExchange exchange) throws IOException {
        byte[] responseBytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(responseBytes);
            outputStream.flush();
        } finally {
            exchange.close();
        }
    }
}