#!/usr/bin/env node
/**
 * Zero-dependency smoke test for Headless Android's built-in MCP endpoint.
 *
 * Usage:
 *   HEADLESS_TOKEN=<token> node scripts/mcp-smoke.mjs [slot]
 *   node scripts/mcp-smoke.mjs --self-test
 *
 * With a slot number it also reads the accessibility tree of that running
 * display, which is the real proof that Shizuku UiAutomation is working.
 */
let BASE = process.env.HEADLESS_URL || 'http://127.0.0.1:8765';
let TOKEN = process.env.HEADLESS_TOKEN || '';
const SLOT = process.argv.find((a) => /^\d+$/.test(a));

let failures = 0;
function check(name, ok, detail = '') {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' — ' + detail : ''}`);
  if (!ok) failures++;
  return ok;
}

async function rpc(method, params, { token = TOKEN, notification = false } = {}) {
  const body = { jsonrpc: '2.0', method };
  if (!notification) body.id = Math.floor(Math.random() * 1e9);
  if (params) body.params = params;
  const headers = { 'content-type': 'application/json' };
  if (token) headers['x-droid-token'] = token;
  const response = await fetch(`${BASE}/mcp`, {
    method: 'POST',
    headers,
    body: JSON.stringify(body),
  });
  const text = await response.text();
  return { status: response.status, body: text ? JSON.parse(text) : null, notification };
}

function textOf(reply) {
  return reply?.body?.result?.content?.[0]?.text || '';
}

async function selfTest() {
  const http = await import('node:http');
  const tools = ['grid_status', 'ui_status', 'ui_tree', 'ui_find', 'ui_click', 'ui_set_text'];
  const server = http.createServer((req, res) => {
    let raw = '';
    req.on('data', (c) => (raw += c));
    req.on('end', () => {
      const msg = raw ? JSON.parse(raw) : {};
      const token = req.headers['x-droid-token'];
      if (token !== 'fixture') {
        res.writeHead(401, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ jsonrpc: '2.0', id: msg.id ?? null, error: { code: -32001 } }));
        return;
      }
      if (msg.method.startsWith('notifications/')) {
        res.writeHead(202).end();
        return;
      }
      let result = {};
      if (msg.method === 'initialize') {
        result = {
          protocolVersion: '2025-06-18',
          serverInfo: { name: 'Headless Android', version: '1.0.0' },
          capabilities: {},
        };
      } else if (msg.method === 'tools/list') {
        result = { tools: tools.map((name) => ({ name, inputSchema: { type: 'object' } })) };
      } else if (msg.method === 'tools/call') {
        if (!tools.includes(msg.params.name)) {
          res.writeHead(200, { 'content-type': 'application/json' });
          res.end(JSON.stringify({ jsonrpc: '2.0', id: msg.id,
            error: { code: -32602, message: 'unknown tool' } }));
          return;
        }
        result = {
          content: [{ type: 'text', text: msg.params.name === 'grid_status' ? '{"used":0}' : 'fixture' }],
          isError: false,
        };
      }
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ jsonrpc: '2.0', id: msg.id, result }));
    });
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  BASE = `http://127.0.0.1:${server.address().port}`;
  TOKEN = 'fixture';
  console.log('Running against the built-in fixture server\n');
  return server;
}

(async () => {
  const fixture = process.argv.includes('--self-test') ? await selfTest() : null;
  check('endpoint configured', Boolean(BASE && TOKEN), BASE || 'set HEADLESS_TOKEN');
  if (!BASE || !TOKEN) {
    if (fixture) fixture.close();
    process.exit(1);
  }

  try {
    const denied = await rpc('tools/list', undefined, { token: '' });
    check('unauthenticated request refused', denied.status === 401 || denied.body?.error?.code === -32001,
      `status ${denied.status}`);

    const init = await rpc('initialize', { protocolVersion: '2025-06-18' });
    check('initialize succeeds', init.status === 200 &&
      init.body?.result?.serverInfo?.name === 'Headless Android');

    const note = await rpc('notifications/initialized', undefined, { notification: true });
    check('initialized notification acknowledged', note.status === 202);

    const list = await rpc('tools/list');
    const names = list.body?.result?.tools?.map((t) => t.name) || [];
    const required = ['grid_status', 'ui_status', 'ui_tree', 'ui_find', 'ui_click', 'ui_set_text'];
    check('automation tools advertised', required.every((n) => names.includes(n)),
      `${names.length} tools`);

    const status = await rpc('tools/call', { name: 'grid_status', arguments: {} });
    check('grid_status returns text', textOf(status).length > 0,
      JSON.stringify(status.body).slice(0, 200));

    if (SLOT) {
      const tree = await rpc('tools/call', { name: 'ui_tree', arguments: { slot: Number(SLOT), max_nodes: 50 } });
      const parsed = JSON.parse(textOf(tree) || '{}');
      check('ui_tree reads a display', parsed.ok === true && Array.isArray(parsed.nodes),
        `${parsed.total_returned || 0} nodes`);
      const find = await rpc('tools/call', { name: 'ui_find', arguments: { slot: Number(SLOT), clickable: true } });
      const found = JSON.parse(textOf(find) || '{}');
      check('ui_find returns matches', Array.isArray(found.matches));
    }

    const bad = await rpc('tools/call', { name: 'no_such_tool', arguments: {} });
    check('unknown tool reports an error', bad.body?.error?.code === -32602 ||
      bad.body?.result?.isError === true);
  } catch (error) {
    check('endpoint reachable', false, String(error));
  } finally {
    if (fixture) fixture.close();
  }

  console.log(`\n${failures === 0 ? 'All checks passed' : failures + ' check(s) failed'}`);
  process.exit(failures === 0 ? 0 : 1);
})();
