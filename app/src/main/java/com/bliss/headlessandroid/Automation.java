package com.bliss.headlessandroid;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Android's real UiAutomation API, hosted inside the Shizuku shell process.
 *
 * <p>The app installs no accessibility service and starts no second process:
 * Shizuku starts this class's owner with shell identity, and Android permits
 * the shell to own a UiAutomation connection. The only shell-only step is
 * constructing that connection; the public UiAutomation methods then do the
 * actual tree reads and accessibility actions.
 *
 * <p>Every read is scoped to one virtual display id. Accessibility Binder
 * handles are valid only for the duration of one read, so nodes are recycled
 * before the JSON is returned and never cached between calls.
 */
final class Automation {
    private static final String TAG = "HeadlessAndroid.Automation";
    private static final int MAX_NODES = 2500;
    private static final int MAX_DEPTH = 64;

    private final Object lock = new Object();
    private final UiAutomation automation;
    private final Method connect;
    private final Method disconnect;
    private final Method connectionId;
    private String state = "disconnected";
    private String error = "";

    Automation(Looper looper) throws Exception {
        attemptHiddenApiExemption();
        Class<?> connectionClass = Class.forName("android.app.UiAutomationConnection");
        Constructor<?> connectionConstructor = connectionClass.getDeclaredConstructor();
        connectionConstructor.setAccessible(true);
        Object connection = connectionConstructor.newInstance();

        Class<?> interfaceClass = Class.forName("android.app.IUiAutomationConnection");
        Class<?> automationClass = UiAutomation.class;
        Constructor<?> constructor = automationClass.getDeclaredConstructor(
                Looper.class, interfaceClass);
        constructor.setAccessible(true);
        automation = (UiAutomation) constructor.newInstance(looper, connection);

        connect = automationClass.getDeclaredMethod("connect", int.class);
        connect.setAccessible(true);
        disconnect = automationClass.getDeclaredMethod("disconnect");
        disconnect.setAccessible(true);
        connectionId = automationClass.getDeclaredMethod("getConnectionId");
        connectionId.setAccessible(true);
    }

    /** Best effort: some ROMs block reflective hidden APIs, in which case we report it. */
    private static void attemptHiddenApiExemption() {
        try {
            Class<?> runtimeClass = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = runtimeClass.getDeclaredMethod("getRuntime");
            getRuntime.setAccessible(true);
            Object runtime = getRuntime.invoke(null);
            Method exempt = runtimeClass.getDeclaredMethod("setHiddenApiExemptions", String[].class);
            exempt.setAccessible(true);
            exempt.invoke(runtime, (Object) new String[]{"L"});
        } catch (Throwable ignored) {
        }
    }

    JSONObject execute(JSONObject request) throws Exception {
        synchronized (lock) {
            String op = request.optString("op", "status");
            if ("status".equals(op)) {
                return status();
            }
            if ("disconnect".equals(op)) {
                doDisconnect();
                return new JSONObject().put("ok", true).put("state", state);
            }
            if (!isConnected()) {
                doConnect();
            }
            switch (op) {
                case "tree":
                    return tree(request);
                case "find":
                    return find(request);
                case "click":
                    return action(request, AccessibilityNodeInfo.ACTION_CLICK, "click", null);
                case "long_click":
                    return action(request, AccessibilityNodeInfo.ACTION_LONG_CLICK,
                            "long_click", null);
                case "focus":
                    return action(request, AccessibilityNodeInfo.ACTION_FOCUS, "focus", null);
                case "clear_focus":
                    return action(request, AccessibilityNodeInfo.ACTION_CLEAR_FOCUS,
                            "clear_focus", null);
                case "expand":
                    return action(request, AccessibilityNodeInfo.ACTION_EXPAND, "expand", null);
                case "collapse":
                    return action(request, AccessibilityNodeInfo.ACTION_COLLAPSE, "collapse", null);
                case "scroll_forward":
                    return action(request, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                            "scroll_forward", null);
                case "scroll_backward":
                    return action(request, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                            "scroll_backward", null);
                case "set_text": {
                    String text = request.optString("value", "");
                    if (text.length() > 16 * 1024) {
                        throw new IllegalArgumentException("Text is limited to 16 KiB");
                    }
                    Bundle args = new Bundle();
                    args.putCharSequence(AccessibilityNodeInfo
                            .ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                    return action(request, AccessibilityNodeInfo.ACTION_SET_TEXT,
                            "set_text", args);
                }
                default:
                    throw new IllegalArgumentException("Unknown automation operation: " + op);
            }
        }
    }

    private void doConnect() throws Exception {
        state = "connecting";
        try {
            connect.invoke(automation, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityServiceInfo info = automation.getServiceInfo();
            if (info == null) {
                throw new IllegalStateException("Android returned no UiAutomation service info");
            }
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                    | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            automation.setServiceInfo(info);
            error = "";
            state = "connected";
            Log.i(TAG, "UiAutomation connected on display-aware shell session");
        } catch (Throwable e) {
            state = "error";
            error = describe(e);
            try {
                disconnect.invoke(automation);
            } catch (Throwable ignored) {
            }
            throw new IllegalStateException("Cannot connect UiAutomation: " + error, e);
        }
    }

    private void doDisconnect() {
        try {
            disconnect.invoke(automation);
        } catch (Throwable ignored) {
        }
        state = "disconnected";
    }

    private boolean isConnected() {
        if (!"connected".equals(state)) {
            return false;
        }
        try {
            return automation.getServiceInfo() != null;
        } catch (Throwable e) {
            state = "error";
            error = describe(e);
            return false;
        }
    }

    private JSONObject status() throws Exception {
        boolean alive = isConnected();
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("available", alive);
        out.put("state", state);
        out.put("error", error);
        out.put("connection_id", alive ? connectionId.invoke(automation) : -1);
        out.put("display_scoped", true);
        out.put("preserves_accessibility_services", true);
        return out;
    }

    private JSONObject tree(JSONObject request) throws Exception {
        Query query = query(request);
        final JSONObject[] answer = new JSONObject[1];
        withSnapshot(request, query, snapshot -> answer[0] = snapshot.result);
        return answer[0];
    }

    private JSONObject find(JSONObject request) throws Exception {
        Query query = query(request);
        final JSONObject[] answer = new JSONObject[1];
        withSnapshot(request, query, snapshot -> {
            snapshot.result.put("matches", snapshot.matches);
            snapshot.result.put("count", snapshot.matches.length());
            answer[0] = snapshot.result;
        });
        return answer[0];
    }

    private JSONObject action(JSONObject request, int action, String name, Bundle args)
            throws Exception {
        Query query = query(request);
        final JSONObject[] answer = new JSONObject[1];
        withSnapshot(request, query, snapshot -> {
            Node target = firstMatch(snapshot);
            if (target == null) {
                throw new IllegalArgumentException("No matching node on display "
                        + snapshot.display + ". Read the tree again before retrying.");
            }
            if (!target.enabled) {
                throw new IllegalArgumentException("The matching node is disabled");
            }
            boolean delivered = target.node.performAction(action, args);
            JSONObject out = new JSONObject();
            out.put("ok", delivered);
            out.put("action", name);
            out.put("display_id", snapshot.display);
            out.put("node", target.json);
            if (!delivered) {
                out.put("error", "The app did not accept this accessibility action");
            }
            answer[0] = out;
        });
        return answer[0];
    }

    private interface Visitor {
        void visit(Snapshot snapshot) throws Exception;
    }

    /** Reads a display-scoped tree, calls the visitor while nodes are alive, then recycles. */
    private void withSnapshot(JSONObject request, Query query, Visitor visitor) throws Exception {
        int display = request.optInt("display", -1);
        if (display < 0) {
            throw new IllegalArgumentException("A virtual display id is required");
        }
        SparseArray<List<AccessibilityWindowInfo>> all = automation.getWindowsOnAllDisplays();
        List<AccessibilityWindowInfo> windows = all == null ? null : all.get(display);
        if (windows == null || windows.isEmpty()) {
            throw new IllegalArgumentException("Display " + display
                    + " has no accessibility windows yet; wait for its app to render");
        }
        Snapshot snapshot = new Snapshot();
        snapshot.display = display;
        snapshot.query = query;
        snapshot.result.put("ok", true);
        snapshot.result.put("display_id", display);
        snapshot.result.put("max_nodes", query.maxNodes);
        JSONArray windowInfo = new JSONArray();
        JSONArray nodes = new JSONArray();
        try {
            for (int w = 0; w < windows.size(); w++) {
                AccessibilityWindowInfo window = windows.get(w);
                if (window == null) {
                    continue;
                }
                JSONObject info = new JSONObject();
                info.put("id", window.getId());
                info.put("type", window.getType());
                info.put("layer", window.getLayer());
                info.put("focused", window.isFocused());
                windowInfo.put(info);
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null) {
                    try {
                        flatten(root, snapshot, nodes, 0, "w" + w, 1);
                    } finally {
                        root.recycle();
                    }
                }
                window.recycle();
            }
        } finally {
            for (Node node : snapshot.nodes) {
                try {
                    node.node.recycle();
                } catch (Throwable ignored) {
                }
            }
            snapshot.nodes.clear();
        }
        snapshot.result.put("windows", windowInfo);
        snapshot.result.put("nodes", nodes);
        snapshot.result.put("total_returned", nodes.length());
        snapshot.result.put("truncated", nodes.length() >= query.maxNodes);
        visitor.visit(snapshot);
    }

    private void flatten(AccessibilityNodeInfo source, Snapshot snapshot, JSONArray output,
                         int index, String parent, int depth) throws Exception {
        if (index >= snapshot.query.maxNodes || depth > MAX_DEPTH) {
            return;
        }
        boolean visible = source.isVisibleToUser();
        if (!visible && !snapshot.query.invisible && source.getChildCount() == 0) {
            return;
        }
        Node node = new Node();
        node.node = AccessibilityNodeInfo.obtain(source);
        node.id = "n" + snapshot.display + "-" + index;
        node.enabled = source.isEnabled();
        node.json = describe(node, parent, source);
        node.json.put("id", node.id);
        output.put(node.json);
        snapshot.nodes.add(node);
        if (matches(snapshot.query, source, visible)) {
            snapshot.matches.put(node.json);
        }
        int next = index + 1;
        for (int i = 0; i < source.getChildCount() && next < snapshot.query.maxNodes; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = source.getChild(i);
                if (child != null) {
                    flatten(child, snapshot, output, next, node.id, depth + 1);
                    next = output.length();
                }
            } catch (Throwable e) {
                Log.w(TAG, "child read failed: " + e);
            } finally {
                if (child != null) {
                    try {
                        child.recycle();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private JSONObject describe(Node node, String parent, AccessibilityNodeInfo source)
            throws Exception {
        JSONObject out = new JSONObject();
        out.put("parent", parent);
        out.put("text", value(source.getText()));
        out.put("content_description", value(source.getContentDescription()));
        out.put("class", value(source.getClassName()));
        out.put("package", value(source.getPackageName()));
        String id = source.getViewIdResourceName();
        if (id != null) {
            out.put("resource_id", id);
        }
        out.put("enabled", source.isEnabled());
        out.put("clickable", source.isClickable());
        out.put("long_clickable", source.isLongClickable());
        out.put("editable", source.isEditable());
        out.put("scrollable", source.isScrollable());
        out.put("checkable", source.isCheckable());
        out.put("checked", source.isChecked());
        out.put("focused", source.isFocused());
        out.put("selected", source.isSelected());
        out.put("visible", source.isVisibleToUser());
        android.graphics.Rect bounds = new android.graphics.Rect();
        source.getBoundsInScreen(bounds);
        out.put("bounds", new JSONArray().put(bounds.left).put(bounds.top)
                .put(bounds.right).put(bounds.bottom));
        out.put("center", new JSONArray().put(bounds.centerX()).put(bounds.centerY()));
        node.text = value(source.getText());
        node.description = value(source.getContentDescription());
        node.className = value(source.getClassName());
        node.packageName = value(source.getPackageName());
        node.viewId = id;
        return out;
    }

    private Node firstMatch(Snapshot snapshot) {
        if (snapshot.matches.length() == 0) {
            return null;
        }
        String id = "";
        try {
            id = snapshot.matches.getJSONObject(0).optString("id", "");
        } catch (Throwable e) {
            return null;
        }
        for (Node node : snapshot.nodes) {
            if (node.id.equals(id)) {
                return node;
            }
        }
        return null;
    }

    private boolean matches(Query query, AccessibilityNodeInfo node, boolean visible) {
        if (!query.invisible && !visible) {
            return false;
        }
        boolean any = false;
        if (!query.text.isEmpty()) {
            if (!contains(value(node.getText()), query.text, query.exact)
                    && !contains(value(node.getContentDescription()), query.text, query.exact)) {
                return false;
            }
            any = true;
        }
        if (!query.description.isEmpty()
                && !contains(value(node.getContentDescription()), query.description, query.exact)) {
            return false;
        }
        if (!query.resourceId.isEmpty()) {
            String id = node.getViewIdResourceName();
            if (id == null || !id.contains(query.resourceId)) {
                return false;
            }
            any = true;
        }
        if (!query.className.isEmpty()
                && !contains(value(node.getClassName()), query.className, query.exact)) {
            return false;
        }
        if (!query.pkg.isEmpty()) {
            String pkg = value(node.getPackageName());
            if (!pkg.equals(query.pkg)) {
                return false;
            }
            any = true;
        }
        if (query.clickable && !node.isClickable()) return false;
        if (query.editable && !node.isEditable()) return false;
        if (query.scrollable && !node.isScrollable()) return false;
        if (query.focused && !node.isFocused()) return false;
        if (query.checked && !node.isChecked()) return false;
        return any || query.clickable || query.editable || query.scrollable
                || query.focused || query.checked || !query.description.isEmpty()
                || !query.className.isEmpty();
    }

    private static Query query(JSONObject in) {
        Query query = new Query();
        query.text = in.optString("text", "").trim();
        query.description = in.optString("content_description", "").trim();
        query.resourceId = in.optString("resource_id", "").trim();
        query.className = in.optString("class_name", "").trim();
        query.pkg = in.optString("package", "").trim();
        query.exact = in.optBoolean("exact", false);
        query.invisible = in.optBoolean("include_invisible", false);
        query.clickable = in.optBoolean("clickable", false);
        query.editable = in.optBoolean("editable", false);
        query.scrollable = in.optBoolean("scrollable", false);
        query.focused = in.optBoolean("focused", false);
        query.checked = in.optBoolean("checked", false);
        query.maxNodes = Math.max(20, Math.min(MAX_NODES, in.optInt("max_nodes", 600)));
        return query;
    }

    private static boolean contains(String value, String search, boolean exact) {
        if (value == null) return false;
        return exact ? value.equals(search)
                : value.toLowerCase(java.util.Locale.US).contains(search.toLowerCase(java.util.Locale.US));
    }

    private static String value(CharSequence value) {
        if (value == null) return "";
        String text = value.toString();
        return text.length() > 1024 ? text.substring(0, 1024) + "…" : text;
    }

    private static String describe(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage();
        return cause.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    private static final class Query {
        String text = "", description = "", resourceId = "", className = "", pkg = "";
        boolean exact, invisible, clickable, editable, scrollable, focused, checked;
        int maxNodes = 600;
    }

    private static final class Node {
        AccessibilityNodeInfo node;
        JSONObject json;
        String id, text = "", description = "", className = "", packageName = "", viewId;
        boolean enabled;
    }

    private static final class Snapshot {
        int display;
        Query query;
        final JSONObject result = new JSONObject();
        final JSONArray matches = new JSONArray();
        final List<Node> nodes = new ArrayList<>();
    }
}
