#!/usr/bin/env node
// Dependency-free stdio adapter for MCP clients that do not accept a direct HTTP endpoint.
const fs = require('node:fs');
const readline = require('node:readline');
const path = require('node:path');
const index = process.argv.indexOf('--config');
const config = index >= 0 ? process.argv[index + 1] : process.env.ARCMENU_MCP_CONFIG;
if (!config) { console.error('Pass --config <Minecraft/config/arcmenu-editor-mcp.properties>'); process.exit(1); }
let properties;
try {
  properties = Object.fromEntries(fs.readFileSync(path.resolve(config), 'utf8').split(/\r?\n/).filter(line => line && !line.startsWith('#') && line.includes('=')).map(line => { const i = line.indexOf('='); return [line.slice(0, i).trim(), line.slice(i + 1).trim()]; }));
  if (!properties.token || !/^\d+$/.test(properties.port)) throw Error('Invalid ArcMenu MCP configuration');
} catch (error) { console.error(error.message); process.exit(1); }
const endpoint = `http://127.0.0.1:${properties.port}/mcp`;
let protocol = '2025-11-25';
const lines = readline.createInterface({ input: process.stdin, terminal: false });
let queue = Promise.resolve();
lines.on('line', line => {
  queue = queue.then(async () => {
    let request;
    try {
      if (Buffer.byteLength(line) > 262144) throw Error('MCP request exceeds 256 KiB');
      request = JSON.parse(line);
      if (!request || request.jsonrpc !== '2.0') throw Error('Invalid JSON-RPC message');
      // A client response has no method; this server never issues client requests.
      if (!request.method) return;
      const response = await fetch(endpoint, { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream', Authorization: `Bearer ${properties.token}`, 'MCP-Protocol-Version': protocol }, body: line, signal: AbortSignal.timeout(request.params?.name === 'effect_capture_frames' ? 180000 : 30000) });
      if (response.status === 202 || !Object.hasOwn(request, 'id')) return;
      if (!response.ok) throw Error(`ArcMenu MCP HTTP ${response.status}; keep the mod running and verify its connection configuration`);
      const message = await response.json();
      if (request.method === 'initialize' && message.result?.protocolVersion) protocol = message.result.protocolVersion;
      process.stdout.write(JSON.stringify(message) + '\n');
    } catch (error) {
      if (request && Object.hasOwn(request, 'id')) process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: request.id, error: { code: -32000, message: error.message } }) + '\n');
      else console.error(error.message);
    }
  });
});
lines.on('close', () => queue.then(() => process.exit(0)));
