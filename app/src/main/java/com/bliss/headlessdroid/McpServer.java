package com.bliss.headlessdroid;

import android.graphics.Bitmap;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.util.List;
import java.util.Locale;

/**
 * The Model Context Protocol endpoint, served over HTTP.
 *
 * <p>An AI client connects to the phone and gets nine real apps to drive:
 * launch them, read their screens, tap and type into them, record them. Every
 * tool is a thin, checked wrapper around {@link Engine}, and every failure is a
 * readable message rather than an exception, because a client that gets a stack
 * trace cannot recover.
 *
 * <p>Auth: the token from Settings, in the {@code X-Droid-Token} header or as
 * {@code ?token=}. Health and the frame endpoints stay open so a shell script
 * can still grab a screenshot.
 */
public final class McpServer {
    private static final String TAG = "HeadlessDroid.MCP";
    private static final String PROTOCOL_VERSION = "2025-06-18";

    private static volatile Engine engine;
    private static volatile HttpServer http;
    private static volatile String token = "";
    private static volatile String lastError = "";

    private McpServer() {
    }

    public static void start(Engine engine, int port, String token) {
        stop();
        McpServer.engine = engine;
        McpServer.token = token == null ? "" : token.trim();
        HttpServer server = new HttpServer(port, (request, response) -> {
            // Only reached for unmatched requests; the router answers 404.
            Log.w(TAG, "unrouted request " + request.method + " " + request.path);
        });
        routes(server, engine);
        if (!server.start(port)) {
            lastError = server.lastError();
            Log.w(TAG, "endpoint did not start: " + lastError);
            return;
        }
        lastError = "";
        http = server;
        Log.i(TAG, "endpoint ready on " + endpoint());
    }

    public static void stop() {
        HttpServer server = http;
        http = null;
        if (server != null) {
            server.stop();
        }
    }

    public static void restart(Engine engine, int port, String token) {
        stop();
        start(engine, port, token);
    }

    public static boolean running() {
        HttpServer server = http;
        return server != null && server.running();
    }

    public static int port() {
        HttpServer server = http;
        return server == null ? -1 : server.port();
    }

    public static String lastError() {
        return lastError;
    }

    /** The address a client should use, once the server is listening. */
    public static String endpoint() {
        HttpServer server = http;
        if (server == null || server.port() < 0) {
            return null;
        }
        return "http://" + Network.anyAddress(engine == null ? null : engine.context())
                + ":" + server.port();
    }

    // ------------------------------------------------------------------- routes

    private static void routes(HttpServer server, Engine engine) {
        server.route("GET", "/", (request, response) ->
                response.text(statusPage()));
        server.route("GET", "/mcp/health", (request, response) ->
                response.json(health()));
        server.route("POST", "/mcp", (request, response) -> handleMcp(request, response));

        server.route("GET", "/mcp/sse", (request, response) -> {
            if (!authorized(request)) {
                response.status = 401;
                response.json("{\"error\":\"bad token\"}");
                return;
            }
            response.stream = true;
            response.contentType = "text/event-stream";
            response.extra.put("Cache-Control", "no-cache");
        });
        server.route("GET", "/shot/<slot>.jpg", (request, response) -> {
            int slot = number(request.routeParam("slot", "0"), 0);
            byte[] jpeg = slot > 0 ? engine.jpeg(slot, 85, 1500) : null;
            if (jpeg == null) {
                response.status = 404;
                response.json("{\"error\":\"no frame for slot " + slot + "\"}");
                return;
            }
            response.bytes(jpeg, "image/jpeg");
        });
        server.route("GET", "/shot/grid.jpg", (request, response) -> {
            Bitmap composite = engine.gridComposite(0);
            byte[] jpeg = Engine.encode(composite, Bitmap.CompressFormat.JPEG, 80);
            if (composite != null && !composite.isRecycled()) {
                composite.recycle();
            }
            if (jpeg == null) {
                response.status = 404;
                response.json("{\"error\":\"the grid is empty\"}");
                return;
            }
            response.bytes(jpeg, "image/jpeg");
        });
        server.route("GET", "/files/<kind>/<name>", (request, response) -> {
            String kind = request.routeParam("kind", "shots");
            String folder = "videos".equals(kind) ? Droid.DIR_VIDEOS : Droid.DIR_SHOTS;
            String name = request.routeParam("name", "");
            byte[] data = Captures.read(engine.context(), folder, name);
            if (data == null) {
                response.status = 404;
                response.json("{\"error\":\"no such capture\"}");
                return;
            }
            response.bytes(data, Captures.mime(name));
        });
        server.route("GET", "/tile/<slot>/state", (request, response) ->
                response.json(tileState(number(request.routeParam("slot", "0"), 0))));
    }

    /**
     * Answers one JSON-RPC message.
     *
     * <p>A notification has no id and gets no body: HTTP 202 with an empty
     * payload, which is what the spec asks for. Everything else is a normal
     * JSON reply with the same id.
     */
    private static void handleMcp(HttpServer.Request request, HttpServer.Response response)
            throws Exception {
        JSONObject message;
        try {
            message = new JSONObject(request.bodyText());
        } catch (Throwable e) {
            response.status = 400;
            response.json(error(null, -32700, "parse error: " + e));
            return;
        }
        if (!"2.0".equals(message.optString("jsonrpc", ""))) {
            response.status = 400;
            response.json(error(idOf(message), -32600, "expected jsonrpc 2.0"));
            return;
        }
        Object id = idOf(message);
        boolean notification = id == null;
        String method = message.optString("method", "");
        JSONObject params = message.optJSONObject("params");
        if (params == null) {
            params = new JSONObject();
        }

        if (method.isEmpty()) {
            response.status = 400;
            response.json(error(id, -32600, "missing method"));
            return;
        }
        if ("initialize".equals(method) || "ping".equals(method)) {
            // No token needed to start the handshake.
        } else if (!authorized(request)) {
            if (notification) {
                response.status = 202;
                response.contentType = "application/json";
                response.body = new byte[0];
                return;
            }
            response.status = 401;
            response.json(unauthorized(id));
            return;
        }

        if ("notifications/initialized".equals(method) || "initialized".equals(method)
                || method.startsWith("notifications/")) {
            response.status = 202;
            response.contentType = "application/json";
            response.body = new byte[0];
            return;
        }

        switch (method) {
            case "initialize":
                response.json(reply(id, initialize()));
                return;
            case "ping":
                response.json(reply(id, new JSONObject()));
                return;
            case "tools/list":
                response.json(reply(id, tools()));
                return;
            case "tools/call":
                response.json(reply(id, tool(params, request)));
                return;
            case "resources/list":
                response.json(reply(id, resources()));
                return;
            case "resources/read":
                response.json(reply(id, resource(params)));
                return;
            case "prompts/list":
                response.json(reply(id, prompts()));
                return;
            default:
                response.status = 404;
                response.json(error(id, -32601, "unknown method " + method));
        }
    }

    // ------------------------------------------------------------------- health

    private static String health() {
        try {
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("apps", engine.count());
            out.put("shell", engine.shellState());
            out.put("uptime_ms", engine.uptimeMs());
            out.put("port", port());
            return out.toString();
        } catch (Throwable e) {
            return "{\"ok\":false}";
        }
    }

    private static String statusPage() {
        StringBuilder out = new StringBuilder(512);
        out.append("Headless Droid\n");
        out.append("================\n");
        try {
            JSONObject status = new JSONObject(engine.statusJson());
            int used = status.optInt("used", 0);
            int max = status.optInt("max", 9);
            out.append("apps running : ").append(used).append('/').append(max).append('\n');
            out.append("shell        : ").append(status.optString("shell")).append('\n');
            out.append("endpoint     : ").append(endpoint()).append("/mcp\n");
            out.append("token        : send it as X-Droid-Token\n");
            JSONArray slots = status.optJSONArray("slots");
            if (slots != null) {
                for (int i = 0; i < slots.length(); i++) {
                    JSONObject tile = slots.optJSONObject(i);
                    if (tile == null) {
                        continue;
                    }
                    out.append(String.format(Locale.US, "  slot %d  %-16s %-8s %s\n",
                            tile.optInt("slot"), tile.optString("label"),
                            tile.optString("state"), tile.optString("package")));
                }
            }
        } catch (Throwable e) {
            out.append("status unavailable\n");
        }
        out.append("\nPOST a JSON-RPC message to /mcp; a frame is /shot/<slot>.jpg\n");
        return out.toString();
    }

    private static String tileState(int slot) {
        Tile tile = engine.tile(slot);
        if (tile == null) {
            return "{\"slot\":" + slot + ",\"state\":\"free\"}";
        }
        return "{\"slot\":" + slot + ",\"state\":\"" + tile.state + "\",\"package\":\""
                + HttpServer.jsonEscape(tile.pkg) + "\",\"frames\":" + tile.frames + "}";
    }

    // ------------------------------------------------------------------- jsonrpc

    private static String dispatch(HttpServer.Request request) {
        try {
            JSONObject message = new JSONObject(request.bodyText());
            String jsonrpc = message.optString("jsonrpc", "");
            if (!"2.0".equals(jsonrpc)) {
                return error(idOf(message), -32600, "expected jsonrpc 2.0");
            }
            Object id = message.opt("id");
            boolean notification = message.isNull("id");
            String method = message.optString("method", "");
            JSONObject params = message.optJSONObject("params");
            if (params == null) {
                params = new JSONObject();
            }

            if (method.isEmpty()) {
                return error(id, -32600, "missing method");
            }
            if ("notifications/initialized".equals(method) || "initialized".equals(method)) {
                return "";
            }
            if (!toolsFree(method)) {
                if (!authorized(request)) {
                    if (notification) {
                        return "";
                    }
                    return unauthorized(id);
                }
            }

            switch (method) {
                case "initialize":
                    return reply(id, initialize());
                case "ping":
                    return reply(id, new JSONObject());
                case "tools/list":
                    return reply(id, tools());
                case "tools/call":
                    return reply(id, tool(params, request));
                case "resources/list":
                    return reply(id, resources());
                case "resources/read":
                    return reply(id, resource(params));
                case "prompts/list":
                    return reply(id, prompts());
                default:
                    return error(id, -32601, "unknown method " + method);
            }
        } catch (Throwable e) {
            Log.w(TAG, "dispatch failed: " + e);
            return error(null, -32700, "parse error: " + e);
        }
    }

    private static boolean toolsFree(String method) {
        return "initialize".equals(method) || "ping".equals(method);
    }

    private static Object idOf(JSONObject message) {
        return message.has("id") && !message.isNull("id") ? message.opt("id") : null;
    }

    private static boolean authorized(HttpServer.Request request) {
        String configured = token;
        if (configured == null || configured.isEmpty()) {
            // No token configured: the endpoint is open by explicit choice.
            return true;
        }
        String header = request.header("x-droid-token");
        if (!header.isEmpty() && configured.equals(header.trim())) {
            return true;
        }
        return configured.equals(request.param("token", "").trim());
    }

    private static String unauthorized(Object id) {
        try {
            JSONObject error = new JSONObject();
            error.put("code", -32001);
            error.put("message", "unauthorized: send the X-Droid-Token header");
            return new JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", id == null ? JSONObject.NULL : id)
                    .put("error", error)
                    .toString();
        } catch (Throwable e) {
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32001}}";
        }
    }

    private static String reply(Object id, JSONObject result) {
        try {
            JSONObject out = new JSONObject();
            out.put("jsonrpc", "2.0");
            out.put("id", id == null ? JSONObject.NULL : id);
            out.put("result", result);
            return out.toString();
        } catch (Throwable e) {
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603}}";
        }
    }

    private static String error(Object id, int code, String message) {
        try {
            JSONObject error = new JSONObject();
            error.put("code", code);
            error.put("message", message);
            return new JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", id == null ? JSONObject.NULL : id)
                    .put("error", error)
                    .toString();
        } catch (Throwable e) {
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":"
                    + code + ",\"message\":\"internal\"}}";
        }
    }

    private static JSONObject initialize() throws Exception {
        JSONObject capabilities = new JSONObject();
        capabilities.put("tools", new JSONObject().put("listChanged", false));
        capabilities.put("resources", new JSONObject().put("listChanged", false));
        capabilities.put("prompts", new JSONObject().put("listChanged", false));
        JSONObject info = new JSONObject();
        info.put("name", "Headless Droid");
        info.put("version", "1.0.0");
        return new JSONObject()
                .put("protocolVersion", PROTOCOL_VERSION)
                .put("capabilities", capabilities)
                .put("serverInfo", info)
                .put("instructions", instructions());
    }

    /** What a client needs to know before it starts calling tools. */
    private static String instructions() {
        return "This server drives real Android apps on this phone, up to nine at once"
                + " on virtual displays. Use launch_app to add an app, screenshot to see"
                + " what it renders, and tap/swipe/type_text/key to drive it. Coordinates"
                + " are normalized 0..1 by default; pass coordinate:\"pixels\" for display"
                + " pixels. The phone's own screen is never touched by input. Use"
                + " wait_for_change after an action instead of sleeping.";
    }

    // -------------------------------------------------------------------- tools

    private static final String[][] TOOLS = {
            {"grid_status", "Every slot: package, state, display id, frame count"},
            {"list_apps", "Every launchable app on this phone"},
            {"launch_app", "Start an installed app on a virtual display, in the next"
                    + " free slot or a given one"},
            {"close_app", "Close one slot and stop its app"},
            {"close_all", "Close every slot"},
            {"restart_app", "Relaunch one slot"},
            {"tap", "Tap one slot, normalized or pixel coordinates"},
            {"long_press", "Long press one slot"},
            {"swipe", "Swipe inside one slot"},
            {"type_text", "Type text into the focused field of one slot"},
            {"key", "Send a key: back, home, recents, enter, tab, delete, power, volume"},
            {"screenshot", "A frame of one slot as a data URI, optionally saved"},
            {"screenshot_grid", "One image of every running app, laid out like the grid"},
            {"record_video", "Start or stop recording one slot to an MP4"},
            {"wait_for_change", "Block until one slot renders a new frame"},
            {"list_files", "Saved screenshots and recordings"},
            {"grid_layout", "Set the grid columns, 1 to 3"},
            {"app_status", "Shell state, endpoint and device facts"},
    };

    private static JSONObject tools() throws Exception {
        JSONArray list = new JSONArray();
        for (String[] tool : TOOLS) {
            JSONObject object = new JSONObject();
            object.put("name", tool[0]);
            object.put("description", tool[1]);
            object.put("inputSchema", schema(tool[0]));
            list.put(object);
        }
        return new JSONObject().put("tools", list);
    }

    private static JSONObject schema(String name) throws Exception {
        JSONObject properties = new JSONObject();
        JSONArray required = new JSONArray();
        switch (name) {
            case "launch_app": {
                properties.put("package", jsonType("string", "Package name, e.g. com.android.settings"));
                properties.put("slot", jsonType("integer", "Optional slot 1-9"));
                required.put("package");
                break;
            }
            case "close_app": {
                properties.put("slot", jsonType("integer", "Slot 1-9"));
                properties.put("package", jsonType("string", "Package name instead of a slot"));
                break;
            }
            case "restart_app": {
                properties.put("slot", jsonType("integer", "Slot 1-9"));
                required.put("slot");
                break;
            }
            case "tap":
            case "long_press": {
                properties.put("slot", jsonType("integer", "Slot 1-9"));
                properties.put("x", jsonType("number", "Normalized 0-1, or pixels"));
                properties.put("y", jsonType("number", "Normalized 0-1, or pixels"));
                properties.put("coordinate", jsonType("string", "\"normalized\" (default) or \"pixels\""));
                properties.put("duration_ms", jsonType("integer", "Only for long_press"));
                required.put("slot");
                required.put("x");
                required.put("y");
                break;
            }
            case "swipe": {
                properties.put("slot", jsonType("integer", "Slot 1-9"));
                properties.put("x1", jsonType("number", "Start x"));
                properties.put("y1", jsonType("number", "Start y"));
                properties.put("x2", jsonType("number", "End x"));
                properties.put("y2", jsonType("number", "End y"));
                properties.put("duration_ms", jsonType("integer", "Default 300"));
                properties.put("coordinate", jsonType("string", "\"normalized\" or \"pixels\""));
                required.put("slot");
                required.put("x1");
                required.put("y1");
                required.put("x2");
                required.put("y2");
                break;
            }
            case "type_text": {
                properties.put("slot", jsonType("integer", "Slot 1-9"));
                properties.put("text", jsonType("string", "Text to type"));
                required.put("slot");
                required.put("text");
                break;
            }
            case "key": {
                properties.put("slot", jsonType("integer", "Slot 1-9"));
                properties.put("key", jsonType("string", "back, home, recents, enter, tab,"
                        + " delete, space, power, menu, search, volume_up, volume_down,"
                        + " or a numeric keycode"));
                required.put("slot");
                required.put("key");
                break;
            }
            case "screenshot": {
                properties.put("slot", jsonType("integer", "Slot 1-9"));
                properties.put("scale", jsonType("number", "0.25 to 1, default 1"));
                properties.put("format", jsonType("string", "\"jpeg\" (default) or \"png\""));
                properties.put("save", jsonType("boolean", "Also write to Downloads"));
                required.put("slot");
                break;
            }
            case "screenshot_grid": {
                properties.put("scale", jsonType("number", "0.25 to 1, default 0.4"));
                properties.put("format", jsonType("string", "\"jpeg\" or \"png\""));
                properties.put("save", jsonType("boolean", "Also write to Downloads"));
                break;
            }
            case "record_video": {
                properties.put("slot", jsonType("integer", "Slot 1-9"));
                properties.put("action", jsonType("string", "\"start\" or \"stop\""));
                properties.put("fps", jsonType("integer", "1-30, default 12"));
                required.put("slot");
                required.put("action");
                break;
            }
            case "wait_for_change": {
                properties.put("slot", jsonType("integer", "Slot 1-9"));
                properties.put("timeout_ms", jsonType("integer", "Default 4000"));
                required.put("slot");
                break;
            }
            case "list_files": {
                properties.put("kind", jsonType("string", "\"shots\" (default) or \"videos\""));
                break;
            }
            case "grid_layout": {
                properties.put("columns", jsonType("integer", "1, 2 or 3"));
                required.put("columns");
                break;
            }
            default:
                break;
        }
        JSONObject object = new JSONObject();
        object.put("type", "object");
        object.put("properties", properties);
        object.put("required", required);
        return object;
    }

    private static JSONObject jsonType(String type, String description) throws Exception {
        JSONObject object = new JSONObject();
        object.put("type", type);
        object.put("description", description);
        return object;
    }

    private static JSONObject tool(JSONObject params, HttpServer.Request request)
            throws Exception {
        String name = params.optString("name", "");
        JSONObject arguments = params.optJSONObject("arguments");
        if (arguments == null) {
            arguments = new JSONObject();
        }
        try {
            switch (name) {
                case "grid_status":
                    return textResult(new JSONObject(engine.statusJson()).toString(2));
                case "list_apps":
                    return textResult(engine.appsJson());
                case "launch_app": {
                    String packageName = arguments.optString("package", "");
                    if (packageName.isEmpty()) {
                        return errorResult("launch_app needs a package name");
                    }
                    Tile tile = engine.launch(packageName,
                            arguments.optInt("slot", 0), false);
                    if (tile == null) {
                        return errorResult("cannot launch " + packageName
                                + ": " + (engine.freeSlots() <= 0
                                ? "the grid is full (9 apps)" : "not launchable"));
                    }
                    JSONObject out = new JSONObject();
                    out.put("slot", tile.slot);
                    out.put("package", tile.pkg);
                    out.put("display_id", tile.displayId);
                    out.put("state", tile.state);
                    out.put("width", tile.width);
                    out.put("height", tile.height);
                    return textResult("launched " + tile.label + " in slot " + tile.slot
                            + " on display " + tile.displayId + "\n" + out.toString(2));
                }
                case "close_app": {
                    if (arguments.has("package")) {
                        engine.close(arguments.optString("package", ""));
                        return textResult("{\"closed\":\"" + arguments.optString("package") + "\"}");
                    }
                    boolean closed = engine.close(arguments.optInt("slot", 0));
                    return closed ? textResult("closed slot " + arguments.optInt("slot", 0))
                            : errorResult("slot " + arguments.optInt("slot", 0) + " is not running");
                }
                case "close_all":
                    engine.closeAll();
                    return textResult("{\"closed\":\"all\"}");
                case "restart_app": {
                    int slot = arguments.optInt("slot", 0);
                    return engine.restart(slot) ? textResult("restarting slot " + slot)
                            : errorResult("slot " + slot + " is not running");
                }
                case "tap":
                    return input(tap(arguments));
                case "long_press":
                    return input(longPress(arguments));
                case "swipe":
                    return input(swipe(arguments));
                case "type_text": {
                    int slot = arguments.optInt("slot", 0);
                    String text = arguments.optString("text", "");
                    return engine.text(slot, text) ? textResult("typed " + text.length()
                            + " characters into slot " + slot)
                            : errorResult("input needs the shell service");
                }
                case "key": {
                    int slot = arguments.optInt("slot", 0);
                    int code = keyCode(arguments.optString("key", ""));
                    return engine.key(slot, code) ? textResult("sent key to slot " + slot)
                            : errorResult("input needs the shell service");
                }
                case "screenshot":
                    return screenshot(arguments, false);
                case "screenshot_grid":
                    return screenshot(arguments, true);
                case "record_video":
                    return record(arguments);
                case "wait_for_change":
                    return waitForChange(arguments);
                case "list_files":
                    return listFiles(arguments);
                case "grid_layout": {
                    int columns = Droid.clamp(arguments.optInt("columns", 3),
                            1, Droid.MAX_COLUMNS, 3);
                    engine.setColumns(columns);
                    return textResult("{\"columns\":" + columns + "}");
                }
                case "app_status": {
                    JSONObject out = new JSONObject();
                    out.put("shell", engine.shellState());
                    out.put("endpoint", endpoint());
                    out.put("model", android.os.Build.MODEL);
                    out.put("android", android.os.Build.VERSION.RELEASE);
                    out.put("sdk", android.os.Build.VERSION.SDK_INT);
                    out.put("version", "1.0.0");
                    out.put("grid", new JSONObject(engine.statusJson()));
                    return textResult(out.toString(2));
                }
                default:
                    return errorResult("unknown tool " + name);
            }
        } catch (Throwable e) {
            Log.w(TAG, "tool " + name + " failed: " + e);
            return errorResult("tool " + name + " failed: " + e);
        }
    }

    private static boolean tap(JSONObject arguments) throws Exception {
        int slot = arguments.optInt("slot", 0);
        Coordinate coordinate = coordinate(arguments);
        return engine.tap(slot, coordinate.x, coordinate.y);
    }

    private static boolean longPress(JSONObject arguments) throws Exception {
        int slot = arguments.optInt("slot", 0);
        Coordinate coordinate = coordinate(arguments);
        int duration = Droid.clamp(arguments.optInt("duration_ms", 500), 100, 10_000, 500);
        return engine.longPress(slot, coordinate.x, coordinate.y, duration);
    }

    private static boolean swipe(JSONObject arguments) throws Exception {
        int slot = arguments.optInt("slot", 0);
        Coordinate from = coordinate(arguments, slot, "x1", "y1");
        Coordinate to = coordinate(arguments, slot, "x2", "y2");
        int duration = Droid.clamp(arguments.optInt("duration_ms", 300), 50, 10_000, 300);
        return engine.swipe(slot, from.x, from.y, to.x, to.y, duration);
    }

    /** Coordinates may be normalized 0..1 or display pixels. */
    private static final class Coordinate {
        final float x;
        final float y;

        Coordinate(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    private static Coordinate coordinate(JSONObject arguments, int slot, String xKey, String yKey)
            throws Exception {
        float x = (float) arguments.optDouble(xKey, 0);
        float y = (float) arguments.optDouble(yKey, 0);
        if ("pixels".equals(arguments.optString("coordinate", "normalized")
                .toLowerCase(Locale.US))) {
            // Display pixels are converted with the slot's own geometry, so a
            // coordinate keeps meaning the same thing however the tile is sized.
            Tile tile = engine.tile(slot);
            if (tile != null) {
                x = x / tile.width;
                y = y / tile.height;
            }
        }
        return new Coordinate(clamp01(x), clamp01(y));
    }

    private static Coordinate coordinate(JSONObject arguments) throws Exception {
        return coordinate(arguments, arguments.optInt("slot", 0), "x", "y");
    }

    private static float clamp01(float value) {
        if (Float.isNaN(value) || value < 0f) {
            return 0f;
        }
        return Math.min(value, 1f);
    }

    private static int keyCode(String key) {
        switch (key.toLowerCase(Locale.US)) {
            case "back":
                return android.view.KeyEvent.KEYCODE_BACK;
            case "home":
                return android.view.KeyEvent.KEYCODE_HOME;
            case "recents":
            case "app_switch":
                return android.view.KeyEvent.KEYCODE_APP_SWITCH;
            case "enter":
                return android.view.KeyEvent.KEYCODE_ENTER;
            case "space":
                return android.view.KeyEvent.KEYCODE_SPACE;
            case "tab":
                return android.view.KeyEvent.KEYCODE_TAB;
            case "delete":
            case "backspace":
                return android.view.KeyEvent.KEYCODE_DEL;
            case "power":
                return android.view.KeyEvent.KEYCODE_POWER;
            case "menu":
                return android.view.KeyEvent.KEYCODE_MENU;
            case "search":
                return android.view.KeyEvent.KEYCODE_SEARCH;
            case "volume_up":
                return android.view.KeyEvent.KEYCODE_VOLUME_UP;
            case "volume_down":
                return android.view.KeyEvent.KEYCODE_VOLUME_DOWN;
            default:
                try {
                    return Integer.parseInt(key.trim());
                } catch (Throwable e) {
                    return android.view.KeyEvent.KEYCODE_BACK;
                }
        }
    }

    private static JSONObject input(boolean ok) throws Exception {
        return ok ? textResult("input delivered")
                : errorResult("input needs the shell service");
    }

    private static JSONObject screenshot(JSONObject arguments, boolean grid) throws Exception {
        double scale = clampNumber(arguments.optDouble("scale", grid ? 0.4 : 1), 0.25, 1, 1);
        boolean png = "png".equals(arguments.optString("format", "jpeg").toLowerCase(Locale.US));
        boolean save = arguments.optBoolean("save", false);
        int slot = arguments.optInt("slot", 0);
        Bitmap frame;
        boolean shared;
        String label;
        if (grid) {
            int target = 0;
            for (Tile tile : engine.tiles()) {
                target = Math.max(target, tile.width);
            }
            target = Math.round(target * Math.max(1, engine.columns()) * (float) scale);
            frame = engine.gridComposite(Math.max(1, Math.min(1080, target)));
            shared = false;
            label = "grid";
        } else {
            Bitmap source = engine.frameBlocking(slot, 4000);
            if (source == null) {
                return errorResult("slot " + slot + " has no frame yet");
            }
            label = "slot" + slot;
            if (scale >= 1) {
                // The engine's own frame: reading it is fine, recycling it is
                // not, because the UI is still drawing it.
                frame = source;
                shared = true;
            } else {
                frame = Bitmap.createScaledBitmap(source,
                        Math.max(1, (int) Math.round(source.getWidth() * scale)),
                        Math.max(1, (int) Math.round(source.getHeight() * scale)), true);
                shared = false;
            }
        }
        byte[] data = Engine.encode(frame,
                png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG,
                png ? 100 : 88);
        if (!shared && frame != null && !frame.isRecycled()) {
            frame.recycle();
        }
        if (data == null) {
            return errorResult("capture failed");
        }
        if (save) {
            Tile tile = grid ? null : engine.tile(slot);
            String stem = grid ? "grid" : "slot" + (tile == null ? 0 : tile.slot)
                    + "-" + (tile == null ? "app" : safeName(tile.label));
            String name = Captures.publish(engine.context(), Droid.DIR_SHOTS,
                    Captures.fileName(stem, png ? ".png" : ".jpg"), data);
            if (name != null) {
                return textResult("saved " + name + "\n" + payload(label, data));
            }
        }
        return textResult(payload(label, data));
    }

    private static double clampNumber(double value, double min, double max, double fallback) {
        if (value <= 0 || Double.isNaN(value)) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static String payload(String label, byte[] data) {
        StringBuilder out = new StringBuilder(128 + data.length * 2);
        out.append("{\"label\":\"").append(HttpServer.jsonEscape(label))
                .append("\",\"bytes\":").append(data.length)
                .append(",\"data\":\"data:image/");
        out.append(data.length > 0 && (data[0] == (byte) 0x89) ? "png" : "jpeg");
        out.append(";base64,");
        try {
            out.append(android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP));
        } catch (Throwable e) {
            out.append("");
        }
        out.append("\"}");
        return out.toString();
    }

    private static JSONObject record(JSONObject arguments) throws Exception {
        int slot = arguments.optInt("slot", 0);
        String action = arguments.optString("action", "start");
        Tile tile = engine.tile(slot);
        if (tile == null) {
            return errorResult("slot " + slot + " is not running");
        }
        if ("stop".equalsIgnoreCase(action)) {
            tile.recording = false;
            String path = Recorder.stop(slot);
            return path == null
                    ? errorResult("recording failed; nothing was saved")
                    : textResult("recording saved: " + path);
        }
        if (tile.recording) {
            return errorResult("slot " + slot + " is already recording");
        }
        int fps = Droid.clamp(arguments.optInt("fps", 12), 1, 30, 12);
        boolean started = Recorder.start(slot, tile.width, tile.height, fps);
        tile.recording = started;
        return started
                ? textResult("recording slot " + slot + " at " + fps + " fps")
                : errorResult("recorder could not start (no H.264 encoder?)");
    }

    private static JSONObject waitForChange(JSONObject arguments) throws Exception {
        int slot = arguments.optInt("slot", 0);
        long timeout = Droid.clamp(arguments.optInt("timeout_ms", 4000), 100, 60_000, 4000);
        Tile tile = engine.tile(slot);
        if (tile == null) {
            return errorResult("slot " + slot + " is not running");
        }
        long before = tile.frames;
        long deadline = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
            Tile current = engine.tile(slot);
            if (current == null) {
                return textResult("{\"changed\":true,\"reason\":\"slot closed\"}");
            }
            if (current.frames != before) {
                JSONObject out = new JSONObject();
                out.put("changed", true);
                out.put("frames", current.frames);
                out.put("state", current.state);
                return textResult(out.toString());
            }
        }
        JSONObject out = new JSONObject();
        out.put("changed", false);
        out.put("frames", tile.frames);
        out.put("state", tile.state);
        return textResult(out.toString());
    }

    private static JSONObject listFiles(JSONObject arguments) throws Exception {
        String kind = arguments.optString("kind", "shots");
        String folder = "videos".equals(kind) ? Droid.DIR_VIDEOS : Droid.DIR_SHOTS;
        List<Captures.Entry> entries = Captures.list(engine.context(), folder);
        JSONArray out = new JSONArray();
        for (Captures.Entry entry : entries) {
            JSONObject object = new JSONObject();
            object.put("name", entry.name);
            object.put("bytes", entry.size);
            object.put("modified", entry.modified);
            out.put(object);
        }
        return textResult(out.toString());
    }

    // ---------------------------------------------------------------- resources

    private static final String[][] RESOURCES = {
            {"screenshot://{slot}", "One frame of a slot, image/jpeg"},
            {"grid://preview", "A composite of every running app"},
            {"status://grid", "The whole grid as JSON"},
            {"files://shots", "Saved screenshots"},
            {"files://videos", "Saved recordings"},
            {"shizuku://status", "Whether the shell service is ready"},
    };

    private static JSONObject resources() throws Exception {
        JSONArray list = new JSONArray();
        for (String[] resource : RESOURCES) {
            JSONObject object = new JSONObject();
            object.put("uri", resource[0]);
            object.put("name", resource[0]);
            object.put("description", resource[1]);
            list.put(object);
        }
        return new JSONObject().put("resources", list);
    }

    private static JSONObject resource(JSONObject params) throws Exception {
        String uri = params.optString("uri", "");
        if (uri.startsWith("screenshot://")) {
            int slot = number(uri.substring("screenshot://".length()).trim(), 1);
            byte[] jpeg = engine.jpeg(slot, 85, 1500);
            if (jpeg == null) {
                return errorResult("slot " + slot + " has no frame yet");
            }
            JSONObject out = new JSONObject();
            out.put("contents", new JSONArray().put(blob(uri, "image/jpeg", jpeg)));
            return out;
        }
        if ("grid://preview".equals(uri)) {
            Bitmap composite = engine.gridComposite(0);
            byte[] jpeg = Engine.encode(composite, Bitmap.CompressFormat.JPEG, 80);
            if (composite != null && !composite.isRecycled()) {
                composite.recycle();
            }
            if (jpeg == null) {
                return errorResult("the grid is empty");
            }
            JSONObject out = new JSONObject();
            out.put("contents", new JSONArray().put(blob(uri, "image/jpeg", jpeg)));
            return out;
        }
        if ("status://grid".equals(uri)) {
            JSONObject out = new JSONObject();
            out.put("contents", new JSONArray().put(
                    text(uri, "application/json", engine.statusJson())));
            return out;
        }
        if ("shizuku://status".equals(uri)) {
            JSONObject out = new JSONObject();
            out.put("contents", new JSONArray().put(text(uri, "application/json",
                    "{\"shell\":\"" + engine.shellState() + "\"}")));
            return out;
        }
        if ("files://shots".equals(uri) || "files://videos".equals(uri)) {
            String folder = "files://shots".equals(uri) ? Droid.DIR_SHOTS : Droid.DIR_VIDEOS;
            List<Captures.Entry> entries = Captures.list(engine.context(), folder);
            JSONArray names = new JSONArray();
            for (Captures.Entry entry : entries) {
                names.put(entry.name);
            }
            JSONObject out = new JSONObject();
            out.put("contents", new JSONArray().put(text(uri, "application/json",
                    names.toString())));
            return out;
        }
        return errorResult("unknown resource " + uri);
    }

    private static JSONObject blob(String uri, String mimeType, byte[] data) throws Exception {
        JSONObject object = new JSONObject();
        object.put("uri", uri);
        object.put("mimeType", mimeType);
        object.put("blob", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP));
        return object;
    }

    private static JSONObject text(String uri, String mimeType, String content) throws Exception {
        JSONObject object = new JSONObject();
        object.put("uri", uri);
        object.put("mimeType", mimeType);
        object.put("text", content);
        return object;
    }

    // ------------------------------------------------------------------- prompts

    private static final String[][] PROMPTS = {
            {"inspect_grid", "Look at every app on the grid and say what is running",
            "Report what each running app is showing, one line per slot."},
            {"drive_app", "Open an app, wait for it to render and act on it",
            "Launch the app, wait_for_change, take a screenshot, then do what the user"
            + " asked."},
            {"check_grid_health", "Report whether the grid and the shell service are healthy",
            "Call app_status and grid_status and answer in two sentences."},
    };

    private static JSONObject prompts() throws Exception {
        JSONArray list = new JSONArray();
        for (String[] prompt : PROMPTS) {
            JSONObject object = new JSONObject();
            object.put("name", prompt[0]);
            object.put("description", prompt[1]);
            JSONArray arguments = new JSONArray();
            JSONObject argument = new JSONObject();
            argument.put("name", "app");
            argument.put("description", "Optional app package or a task to do");
            argument.put("required", false);
            arguments.put(argument);
            object.put("arguments", arguments);
            JSONArray messages = new JSONArray();
            JSONObject message = new JSONObject();
            message.put("role", "user");
            JSONObject content = new JSONObject();
            content.put("type", "text");
            content.put("text", prompt[2]);
            message.put("content", content);
            messages.put(message);
            object.put("messages", messages);
            list.put(object);
        }
        return new JSONObject().put("prompts", list);
    }

    // ------------------------------------------------------------------- helpers

    private static JSONObject textResult(String text) throws Exception {
        JSONObject content = new JSONObject();
        content.put("type", "text");
        content.put("text", text);
        JSONArray array = new JSONArray();
        array.put(content);
        JSONObject out = new JSONObject();
        out.put("content", array);
        out.put("isError", false);
        return out;
    }

    private static JSONObject errorResult(String message) throws Exception {
        JSONObject content = new JSONObject();
        content.put("type", "text");
        content.put("text", message);
        JSONArray array = new JSONArray();
        array.put(content);
        JSONObject out = new JSONObject();
        out.put("content", array);
        out.put("isError", true);
        return out;
    }

    private static int number(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Throwable e) {
            return fallback;
        }
    }

    private static String safeName(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "app";
        }
        return value.trim().toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "-");
    }
}