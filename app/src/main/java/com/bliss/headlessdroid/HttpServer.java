package com.bliss.headlessdroid;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The HTTP half of the MCP endpoint.
 *
 * <p>Small on purpose: the phone is the host and the app is the server, so this
 * server only has to speak HTTP/1.1 correctly, keep a handful of clients
 * talking (an AI agent, curl, a browser) and stream one long-lived SSE
 * connection. There is no dependency to pull in and no file tree to serve.
 */
final class HttpServer {
    /** CORS is permissive: the token is the gate, not the origin. */
    static final String[] CORS_HEADERS = {
            "Access-Control-Allow-Origin: *",
            "Access-Control-Allow-Headers: Content-Type, Authorization, X-Droid-Token",
            "Access-Control-Allow-Methods: GET, POST, OPTIONS",
            "Access-Control-Expose-Headers: Mcp-Session-Id",
    };

    private static final long SSE_PING_MS = 15_000;
    private static final long SSE_POLL_MS = 250;
    private static final int MAX_BODY = 16 * 1024 * 1024;
    private static final int MAX_HEADERS = 64;
    private static final int MAX_HEADER_LINE = 8 * 1024;
    private static final int SO_TIMEOUT_MS = 30_000;
    private static final int MAX_CONNECTIONS = 16;

    /** One request: method, path, query, headers, body. */
    static final class Request {
        final String method;
        final String path;
        final String query;
        final String version;
        final Map<String, String> headers;
        final byte[] body;
        final Map<String, String> params = new LinkedHashMap<>();

        Request(String method, String path, String query, String version,
                Map<String, String> headers, byte[] body) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.version = version == null || version.isEmpty() ? "HTTP/1.1" : version;
            this.headers = headers;
            this.body = body == null ? new byte[0] : body;
        }

        String header(String name) {
            String value = headers.get(name.toLowerCase(Locale.US));
            return value == null ? "" : value;
        }

        /** A query parameter, or the fallback. */
        String param(String name, String fallback) {
            if (query == null) {
                return fallback;
            }
            for (String pair : query.split("&")) {
                int split = pair.indexOf('=');
                if (split <= 0 || !pair.substring(0, split).equals(name)) {
                    continue;
                }
                return decode(pair.substring(split + 1));
            }
            return fallback;
        }

        /** A route parameter captured from the path. */
        String routeParam(String name, String fallback) {
            String value = params.get(name);
            return value == null || value.isEmpty() ? fallback : value;
        }

        String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }

        static String decode(String value) {
            StringBuilder out = new StringBuilder(value.length());
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '+') {
                    out.append(' ');
                } else if (c == '%' && i + 2 < value.length()) {
                    try {
                        out.append((char) Integer.parseInt(value.substring(i + 1, i + 3), 16));
                    } catch (Throwable ignored) {
                        out.append(c);
                    }
                    i += 2;
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }
    }

    /** One response: buffered bytes, or an open stream for server-sent events. */
    static final class Response {
        int status = 200;
        String contentType = "text/plain; charset=utf-8";
        byte[] body = new byte[0];
        final Map<String, String> extra = new LinkedHashMap<>();
        volatile boolean stream;
        volatile OutputStream out;

        /** Adds a response header, e.g. the MCP session id. */
        Response header(String name, String value) {
            extra.put(name, value);
            return this;
        }

        Response json(String text) {
            contentType = "application/json";
            body = text.getBytes(StandardCharsets.UTF_8);
            return this;
        }

        Response text(String text) {
            contentType = "text/plain; charset=utf-8";
            body = text.getBytes(StandardCharsets.UTF_8);
            return this;
        }

        Response bytes(byte[] data, String contentType) {
            this.contentType = contentType;
            this.body = data == null ? new byte[0] : data;
            return this;
        }
    }

    /** A route: method, path pattern, handler. */
    private static final class Route {
        final String method;
        final String[] segments;
        final Handler handler;

        Route(String method, String pattern, Handler handler) {
            this.method = method;
            this.segments = pattern.split("/");
            this.handler = handler;
        }

        /** Returns the route params, or null when the path does not match. */
        Map<String, String> match(String method, String path) {
            if (!this.method.equals(method)) {
                return null;
            }
            String[] parts = path.split("/");
            if (parts.length != segments.length) {
                return null;
            }
            Map<String, String> params = new LinkedHashMap<>();
            for (int i = 0; i < segments.length; i++) {
                String want = segments[i];
                if (want.length() > 1 && want.charAt(0) == '<'
                        && want.charAt(want.length() - 1) == '>') {
                    params.put(want.substring(1, want.length() - 1), parts[i]);
                    continue;
                }
                if (!want.equals(parts[i])) {
                    return null;
                }
            }
            return params;
        }
    }

    /** Handler: do the work and fill the response. */
    public interface Handler {
        void handle(Request request, Response response) throws Exception;
    }

    private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            8, MAX_CONNECTIONS, 30, TimeUnit.SECONDS, queue, new ThreadFactory() {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "HeadlessDroid-http-"
                    + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });

    private final LinkedBlockingQueue<Route> routes = new LinkedBlockingQueue<>();
    private volatile ServerSocket server;
    private volatile Thread acceptor;
    private volatile boolean running;
    private volatile int boundPort = -1;
    private volatile String lastError = "";

    HttpServer(int preferredPort, Handler fallback) {
        // The fallback is not used: every path that reaches this server either
        // matches a route or gets a 404, and unmatched requests are logged by
        // the router itself.
    }

    void route(String method, String pattern, Handler handler) {
        routes.add(new Route(method, pattern, handler));
    }

    /** Binds the first free port from preferredPort upwards. */
    boolean start(int preferredPort) {
        lastError = "";
        Throwable last = null;
        for (int port = preferredPort; port < preferredPort + 4; port++) {
            try {
                ServerSocket socket = new ServerSocket();
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress("0.0.0.0", port), 32);
                server = socket;
                boundPort = port;
                break;
            } catch (Throwable e) {
                last = e;
                boundPort = -1;
            }
        }
        if (server == null) {
            lastError = "cannot bind a port near " + preferredPort + ": " + last;
            Log.w(Droid.TAG, "http start failed: " + last);
            return false;
        }
        running = true;
        acceptor = new Thread(this::acceptLoop, "HeadlessDroid-http");
        acceptor.setDaemon(true);
        acceptor.start();
        Log.i(Droid.TAG, "mcp endpoint listening on " + boundPort);
        return true;
    }

    private void acceptLoop() {
        ServerSocket socket = server;
        int failures = 0;
        while (running && socket != null && !socket.isClosed()) {
            try {
                Socket connection = socket.accept();
                failures = 0;
                executor.submit(() -> serve(connection));
            } catch (Throwable e) {
                if (!running) {
                    return;
                }
                failures++;
                Log.w(Droid.TAG, "accept failed (" + failures + "): " + e);
                if (failures >= 50) {
                    // Nothing useful to do but wait for the host to recover;
                    // giving up entirely would leave the endpoint dead silently.
                    return;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }
    }

    boolean running() {
        return running;
    }

    int port() {
        return boundPort;
    }

    String lastError() {
        return lastError;
    }

    void stop() {
        running = false;
        try {
            if (server != null) {
                server.close();
            }
        } catch (Throwable ignored) {
        }
        executor.shutdownNow();
        Log.i(Droid.TAG, "mcp endpoint stopped");
    }

    /** One connection: read requests until either side closes. */
    private void serve(Socket socket) {
        OutputStream out = null;
        try {
            socket.setSoTimeout(SO_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            out = socket.getOutputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            while (running) {
                Request request = readRequest(reader, out);
                if (request == null) {
                    return;
                }
                if (!expectContinue(request, out)) {
                    return;
                }
                Response response = dispatch(request);
                if (response.stream) {
                    stream(out, response);
                    return;
                }
                if (!write(out, response, request)) {
                    return;
                }
            }
        } catch (Throwable e) {
            // A dropped connection is normal for an agent polling frames.
        } finally {
            try {
                if (out != null) {
                    out.flush();
                }
            } catch (Throwable ignored) {
            }
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Answers 100-continue so a client waiting on it can send its body. */
    private boolean expectContinue(Request request, OutputStream out) throws IOException {
        if (!"100-continue".equalsIgnoreCase(request.header("expect"))) {
            return true;
        }
        out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
        return true;
    }

    private Request readRequest(BufferedReader reader, OutputStream out) throws IOException {
        String line = reader.readLine();
        if (line == null || line.isEmpty()) {
            return null;
        }
        String[] parts = line.split(" ");
        if (parts.length < 3) {
            reject(out, 400, "malformed request line");
            return null;
        }
        String method = parts[0].toUpperCase(Locale.US);
        String target = parts[1];
        String version = parts[2];
        String path = target;
        String query = null;
        int question = target.indexOf('?');
        if (question > 0) {
            path = target.substring(0, question);
            query = target.substring(question + 1);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        int contentLength = 0;
        boolean tooLarge = false;
        String header;
        int seen = 0;
        while ((header = reader.readLine()) != null && !header.isEmpty()) {
            if (header.length() > MAX_HEADER_LINE || ++seen > MAX_HEADERS) {
                reject(out, 431, "header section too large");
                return null;
            }
            int colon = header.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = header.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = header.substring(colon + 1).trim();
            headers.put(name, value);
            if ("content-length".equals(name)) {
                try {
                    contentLength = Integer.parseInt(value);
                } catch (Throwable ignored) {
                    contentLength = 0;
                }
                if (contentLength < 0 || contentLength > MAX_BODY) {
                    tooLarge = true;
                }
            }
        }
        byte[] body = new byte[0];
        if (tooLarge) {
            reject(out, 413, "body too large");
            return null;
        }
        if (contentLength > 0) {
            int limit = Math.min(contentLength, MAX_BODY);
            body = new byte[limit];
            int offset = 0;
            while (offset < limit) {
                int read = reader.read();
                if (read < 0) {
                    break;
                }
                body[offset++] = (byte) read;
            }
        }
        return new Request(method, path, query, version, headers, body);
    }

    /** Rejects a request the reader could not parse: a status and a close. */
    private void reject(OutputStream out, int status, String message) throws IOException {
        byte[] bytes = ("{\"error\":\"" + jsonEscape(message) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder(192);
        head.append("HTTP/1.1 ").append(status).append(' ')
                .append(statusText(status)).append("\r\n");
        appendCors(head);
        head.append("Content-Type: application/json\r\n");
        head.append("Content-Length: ").append(bytes.length).append("\r\n");
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private Response dispatch(Request request) {
        try {
            if ("OPTIONS".equals(request.method)) {
                Response response = new Response();
                response.status = 204;
                response.contentType = "text/plain";
                return response;
            }
            for (Route route : routes) {
                Map<String, String> params = route.match(request.method, request.path);
                if (params == null) {
                    continue;
                }
                Request routed = request;
                routed.params.putAll(params);
                Response response = new Response();
                route.handler.handle(routed, response);
                return response;
            }
            Response response = new Response();
            response.status = 404;
            response.json("{\"error\":\"no route for " + HttpServer.jsonEscape(request.path)
                    + "\"}");
            return response;
        } catch (Throwable e) {
            Log.w(Droid.TAG, "handler failed for " + request.path + ": " + e);
            Response response = new Response();
            response.status = 500;
            response.json("{\"error\":\"" + jsonEscape(String.valueOf(e)) + "\"}");
            return response;
        }
    }

    /** Writes one buffered response; returns false when the connection ends. */
    private boolean write(OutputStream out, Response response, Request request)
            throws IOException {
        boolean keepAlive = running
                && !"close".equalsIgnoreCase(request.header("connection"))
                && "HTTP/1.1".equals(request.version);
        StringBuilder head = new StringBuilder(256);
        head.append(request.version).append(' ').append(response.status).append(' ')
                .append(statusText(response.status)).append("\r\n");
        appendCors(head);
        head.append("Content-Type: ").append(response.contentType).append("\r\n");
        for (Map.Entry<String, String> entry : response.extra.entrySet()) {
            head.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        head.append("Content-Length: ").append(response.body.length).append("\r\n");
        head.append("Date: ").append(DATE.get()).append("\r\n");
        head.append(keepAlive && response.body.length < 1_000_000
                ? "Connection: keep-alive\r\n" : "Connection: close\r\n");
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.UTF_8));
        out.write(response.body);
        out.flush();
        return keepAlive;
    }

    private static final ThreadLocal<String> DATE = new ThreadLocal<String>() {
        @Override
        protected String initialValue() {
            return new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
                    .format(new Date());
        }
    };

    private static void appendCors(StringBuilder head) {
        for (String header : CORS_HEADERS) {
            head.append(header).append("\r\n");
        }
    }

    /** Opens the event stream, sends the endpoint hint, then keeps it warm. */
    private void stream(OutputStream out, Response response) throws IOException {
        StringBuilder head = new StringBuilder(256);
        head.append("HTTP/1.1 200 OK\r\n");
        appendCors(head);
        head.append("Content-Type: ").append(response.contentType).append("\r\n");
        for (Map.Entry<String, String> entry : response.extra.entrySet()) {
            head.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        head.append("Cache-Control: no-cache\r\n");
        head.append("Transfer-Encoding: chunked\r\n");
        head.append("Date: ").append(DATE.get()).append("\r\n");
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
        response.out = out;

        // Tell the client where to POST, then keep the stream warm with a
        // comment, which is what a keepalive is in server-sent events.
        writeEvent(out, "endpoint", "{\"uri\":\"/mcp\"}");
        long lastPing = System.currentTimeMillis();
        while (running) {
            try {
                Thread.sleep(SSE_POLL_MS);
            } catch (InterruptedException ignored) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastPing >= SSE_PING_MS) {
                lastPing = now;
                writeComment(out, "ping");
            }
        }
        out.write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Writes one SSE event: `event:` then `data:` lines, chunked. */
    static void writeEvent(OutputStream out, String event, String data) throws IOException {
        StringBuilder frame = new StringBuilder(128);
        if (event != null) {
            frame.append("event: ").append(event).append('\n');
        }
        for (String line : data.split("\n", -1)) {
            frame.append("data: ").append(line).append('\n');
        }
        frame.append('\n');
        writeChunk(out, frame.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Writes an SSE comment, which is the keepalive form. */
    private static void writeComment(OutputStream out, String data) throws IOException {
        writeChunk(out, (": " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void writeChunk(OutputStream out, byte[] bytes) throws IOException {
        out.write((Integer.toHexString(bytes.length) + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String statusText(int status) {
        switch (status) {
            case 200:
                return "OK";
            case 202:
                return "Accepted";
            case 204:
                return "No Content";
            case 400:
                return "Bad Request";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            case 413:
                return "Payload Too Large";
            case 431:
                return "Request Header Fields Too Large";
            case 500:
                return "Internal Server Error";
            default:
                return "Status " + status;
        }
    }

    /** Escapes a string for embedding in a JSON string literal. */
    static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }
}