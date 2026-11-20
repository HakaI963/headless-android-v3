# MCP smoke test

`mcp-smoke.mjs` is a zero-dependency client for the MCP endpoint built into
Headless Android. It checks authentication, initialization, tool listing and
one real tool call. Pass a slot number to also read that display's accessibility
tree through Shizuku UiAutomation.

```sh
# Verify the client itself, without a phone
node scripts/mcp-smoke.mjs --self-test

# Against the app (token from Settings → Agent endpoint)
HEADLESS_TOKEN=<token> node scripts/mcp-smoke.mjs

# Also verify real UI automation on display slot 1
HEADLESS_TOKEN=<token> node scripts/mcp-smoke.mjs 1
```

Set `HEADLESS_URL` when the endpoint is not at `http://127.0.0.1:8765`.
The app itself contains the server; this script is only a test client and is
not needed to use the app.
