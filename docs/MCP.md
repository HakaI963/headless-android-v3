# MCP endpoint

Headless Android serves an MCP endpoint from the phone itself, so an AI client
connects to the phone the way a person opens the app: one host, one port, one
token.

```
Base URL      http://<phone-ip>:8765
JSON-RPC      POST /mcp
SSE stream    GET  /mcp/sse
Health        GET  /mcp/health          (no token)
A frame       GET  /shot/<slot>.jpg     (no token, useful for curl)
```

## Transport

Streamable HTTP, as the MCP spec describes:

- a client `POST`s a JSON-RPC message to `/mcp` with
  `Accept: application/json, text/event-stream`
- the server answers with either `Content-Type: application/json` for a single
  reply, or an `text/event-stream` of one `message` event
- `GET /mcp/sse` opens a stream whose first event is
  `event: endpoint` / `data: {"uri":"/mcp"}`, then a keepalive every 15s
- `OPTIONS` answers 204 with the CORS headers below

Every response carries:

```
Access-Control-Allow-Origin: *
Access-Control-Allow-Headers: Content-Type, Authorization, X-Droid-Token
Access-Control-Allow-Methods: GET, POST, OPTIONS
Access-Control-Expose-Headers: Mcp-Session-Id
```

## Authentication

The token from the Settings screen must be sent as the `X-Droid-Token` header,
or as `?token=` on `/mcp` and `/mcp/sse`. A wrong or missing token answers
HTTP 401 with a JSON-RPC error body. Generate a new one in Settings (or from the
endpoint) at any time; the server restarts with the new token immediately.

## Methods

| Method | Reply |
|---|---|
| `initialize` | protocol version, capabilities, `serverInfo {name:"Headless Android", version:"1.0.0"}` |
| `notifications/initialized` | HTTP 202, empty body |
| `ping` | `{}` |
| `tools/list` | every tool with its input schema |
| `tools/call` | `{content:[{type:"text",text:"…"}], isError:false}` |
| `resources/list` / `resources/read` | the resources below |
| `prompts/list` | two or three prompt templates that fit this product |

Error codes follow JSON-RPC: `-32700` parse, `-32600` invalid request,
`-32601` unknown method, `-32602` invalid params, `-32603` internal error.

## Tools

### `grid_status`
No arguments. Every slot: `slot`, `package`, `label`, `state`, `display_id`,
`frames`, `recording`, plus `used`, `max`, `columns`, `shell`, `uptime_ms`.

### `list_apps`
No arguments. Every launchable app on the phone:
`[{label, package, component}]`.

### `launch_app`
```json
{"package":"com.android.settings","slot":3}
```
`slot` is optional; without it the next free slot is used. Starts the app the
phone already has installed, on a fresh virtual display of the current screen
preset. Returns `{slot, display_id, state}`. Fails with a clear message when the
grid is full (9 apps) or the package has no launch activity.

### `close_app` / `close_all` / `restart_app`
```json
{"slot":3}
{"package":"com.android.settings"}   // for close_app
```
Closing a slot releases its display and force-stops the app. Restarting closes
and relaunches in the same slot.

### `tap`, `long_press`, `swipe`, `type_text`, `key`
```json
{"slot":1,"x":0.5,"y":0.5}
{"slot":1,"x":0.5,"y":0.5,"coordinate":"pixels"}
{"slot":1,"x1":0.5,"y1":0.8,"x2":0.5,"y2":0.2,"duration_ms":300}
{"slot":1,"text":"hello"}
{"slot":1,"key":"back"}
```
Coordinates are **normalized 0..1** by default so they survive every screen
preset; pass `"coordinate":"pixels"` to send display pixels instead. `key`
accepts `back`, `home`, `recents`, `enter`, `space`, `tab`, `delete`, `power`,
`menu`, `search`, `volume_up`, `volume_down`, or a numeric keycode.
Every one of these is addressed to that slot's display — the phone's own screen
never sees them.

### `ui_status`, `ui_tree`, `ui_find`, `ui_click`, `ui_set_text`

These tools use Android's real `UiAutomation`, hosted by the app's own Shizuku
shell process. They are semantic — an agent finds an element and acts on it,
instead of guessing pixels. No accessibility service needs enabling and no
external controller is installed.

```json
{"name":"ui_status","arguments":{}}
{"name":"ui_tree","arguments":{"slot":1,"max_nodes":400}}
{"name":"ui_find","arguments":{"slot":1,"text":"Search","clickable":true}}
{"name":"ui_click","arguments":{"slot":1,"text":"Search","clickable":true}}
{"name":"ui_set_text","arguments":{"slot":1,"value":"hello","resource_id":"search"}}
```

`ui_tree` returns `windows`, `nodes` and `matches`. Every node has `id`, `text`,
`content_description`, `resource_id`, `class`, `bounds`, `center` and state
flags. Use `ui_find` to narrow the tree by `text`, `content_description`,
`resource_id`, `class_name`, `package`, `exact`, `clickable`, `editable`,
`scrollable`, `focused` or `checked`. `ui_click`, `ui_long_click`, `ui_focus`,
`ui_scroll_forward` and `ui_scroll_backward` act on the first match;
`ui_set_text` takes the new string in `value` and a selector for the editable
node. Read a fresh tree before each action: Android accessibility node handles
are valid only for one read. All tools are scoped to the slot's virtual display
and return a structured error if that display has no windows yet.

### `screenshot`
```json
{"slot":1,"scale":0.5,"format":"png","save":true}
```
Returns the frame as a base64 data URI plus `width` and `height`. With
`save:true` the same bytes are written to
`Download/HeadlessAndroid/shots/<slot>-<label>-<timestamp>.jpg` and the file name
comes back in the reply.

### `screenshot_grid`
```json
{"scale":0.4,"format":"jpeg","save":true}
```
A composite of every running app, laid out like the grid on screen.

### `record_video`
```json
{"slot":1,"action":"start","fps":12}
{"slot":1,"action":"stop"}
```
Recording is a screen capture, so 10–15 fps per slot is the honest range.
`stop` publishes the MP4 to `Download/HeadlessAndroid/videos/` and returns its
absolute path.

### `wait_for_change`
```json
{"slot":1,"timeout_ms":4000}
```
Returns as soon as the slot renders a new frame, or after the timeout,
whichever comes first. Useful when an agent needs to wait for a load or an
animation instead of sleeping.

### `list_files`
```json
{"kind":"shots"}
```
Newest-first list of saved captures with name, size and timestamp.

### `grid_layout`
```json
{"columns":3}
```
One, two or three columns per row.

### `app_status`
No arguments. Shell state, endpoint, device model, Android version, build
version. The first call a new client should make after `grid_status`.

## Resources

| URI | Content |
|---|---|
| `screenshot://{slot}` | one frame, `image/jpeg` as a base64 blob |
| `grid://preview` | the composite of all tiles |
| `status://grid` | `grid_status` as text |
| `files://shots` / `files://videos` | the capture lists |
| `shizuku://status` | shell state |

Resource reads never block: a missing frame answers quickly with an empty
payload rather than holding the connection.

## A full session

```sh
ENDPOINT=http://192.168.1.42:8765
TOKEN=1f0c9b7d4e2a4b8c9d0e1f2a3b4c5d6e

# health, no token
curl -s $ENDPOINT/mcp/health

# initialize
curl -s $ENDPOINT/mcp -H "X-Droid-Token: $TOKEN" -H 'Content-Type: application/json' -d '{
  "jsonrpc":"2.0","id":1,"method":"initialize",
  "params":{"protocolVersion":"2025-06-18",
            "capabilities":{},
            "clientInfo":{"name":"curl","version":"0"}}}'

# open the settings app in slot 1, type into it
curl -s $ENDPOINT/mcp -H "X-Droid-Token: $TOKEN" -H 'Content-Type: application/json' -d '{
  "jsonrpc":"2.0","id":2,"method":"tools/call",
  "params":{"name":"launch_app","arguments":{"package":"com.android.settings"}}}'

curl -s $ENDPOINT/mcp -H "X-Droid-Token: $TOKEN" -H 'Content-Type: application/json' -d '{
  "jsonrpc":"2.0","id":3,"method":"tools/call",
  "params":{"name":"wait_for_change","arguments":{"slot":1,"timeout_ms":4000}}}'

curl -s $ENDPOINT/mcp -H "X-Droid-Token: $TOKEN" -H 'Content-Type: application/json' -d '{
  "jsonrpc":"2.0","id":4,"method":"tools/call",
  "params":{"name":"screenshot","arguments":{"slot":1,"scale":0.5,"save":true}}}'

# the same frame with no MCP at all
curl -s $ENDPOINT/shot/1.jpg -o slot1.jpg
```

## Connecting a real client

Any client that speaks Streamable HTTP works:

```python
from mcp import ClientSession
from mcp.client.streamable_http import streamablehttp_client

async with streamablehttp_client("http://192.168.1.42:8765/mcp",
                                 headers={"X-Droid-Token": TOKEN}) as (r, w, _):
    async with ClientSession(r, w) as session:
        await session.initialize()
        tools = await session.list_tools()
```

Or, with the phone plugged in over USB and no Wi-Fi:

```sh
adb forward tcp:8765 tcp:8765
# then connect to http://127.0.0.1:8765/mcp
```

## Security

The endpoint binds every interface on purpose, so an AI host on the same network
can reach it. The token is the only gate: it is 16 random bytes, shown in the app
once, regenerable in one tap, and checked on every request. Treat it like a
password for the phone's screen: anyone holding it can drive whatever app is on
the grid, including apps that are logged in.