// Starts a fake projector, then talks to server.js over MCP (stdio) like a real client would.
import { createServer } from "node:http";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import assert from "node:assert/strict";

const log = [];
const state = { photos: [], settings: { discoVisual: "auto", discoSource: "mic", discoSens: 100 }, mode: "idle", url: "http://fake:8080", totalBytes: 0, freeBytes: 5e9 };
const fake = createServer((req, res) => {
  const chunks = [];
  req.on("data", (c) => chunks.push(c));
  req.on("end", () => {
    const body = Buffer.concat(chunks);
    log.push({ method: req.method, url: req.url, pin: req.headers["x-pin"], fn: req.headers["x-filename"], len: body.length, body: req.url.startsWith("/api/upload") ? null : body.toString() });
    const send = (o, code = 200) => { res.writeHead(code, { "Content-Type": "application/json" }); res.end(JSON.stringify(o)); };
    if (req.url === "/api/state") return send(state);
    if (req.url === "/api/control") {
      const a = JSON.parse(body.toString()).action;
      state.mode = a === "disco" ? "disco" : a === "stop" ? "idle" : a === "start" ? "slideshow" : state.mode;
      return send({ ok: true });
    }
    if (req.url === "/api/settings") { Object.assign(state.settings, JSON.parse(body.toString())); return send(state.settings); }
    if (req.url === "/api/upload") { state.photos.push({ name: decodeURIComponent(req.headers["x-filename"]), size: body.length, modified: Date.now() }); return send({ name: decodeURIComponent(req.headers["x-filename"]), size: body.length }); }
    if (req.url === "/api/delete") return send({ deleted: JSON.parse(body.toString()).names.length });
    send({ error: "nope" }, 404);
  });
});
await new Promise((r) => fake.listen(0, "127.0.0.1", r));
const url = `http://127.0.0.1:${fake.address().port}`;

const client = new Client({ name: "smoke", version: "1" });
await client.connect(new StdioClientTransport({ command: "node", args: ["server.js"], env: { ...process.env, PHOTOWALL_URL: url, PHOTOWALL_PIN: "1234" } }));

const tools = (await client.listTools()).tools.map((t) => t.name).sort();
assert.deepEqual(tools, ["add_photo", "delete_photos", "disco", "list_photos", "projector_status", "slideshow", "update_settings"]);

let r = await client.callTool({ name: "disco", arguments: { action: "start", effect: "lamps", source: "demo" } });
assert.match(r.content[0].text, /disco mode/);
assert.equal(state.settings.discoVisual, "lamps");
assert.equal(state.settings.discoSource, "demo");
assert.ok(log.every((l) => l.pin === "1234"), "PIN header sent on every call");

r = await client.callTool({ name: "slideshow", arguments: { action: "stop" } });
assert.match(r.content[0].text, /nothing on screen/);

// serve an image over HTTP and have the tool fetch it and upload
const img = createServer((q, s) => { s.writeHead(200, { "Content-Type": "image/jpeg" }); s.end(Buffer.alloc(1234, 7)); });
await new Promise((x) => img.listen(0, "127.0.0.1", x));
r = await client.callTool({ name: "add_photo", arguments: { source: `http://127.0.0.1:${img.address().port}/pics/%D0%BC%D0%BE%D0%BD%D0%B5.jpg` } });
assert.match(r.content[0].text, /Added/);
assert.equal(state.photos[0].name, "мое.jpg".replace("мое", "моне"));
assert.equal(state.photos[0].size, 1234);

r = await client.callTool({ name: "update_settings", arguments: { intervalSec: 60, clock: false } });
assert.equal(state.settings.intervalSec, 60);
r = await client.callTool({ name: "update_settings", arguments: {} });
assert.equal(r.isError, true);

r = await client.callTool({ name: "delete_photos", arguments: { names: ["моне.jpg"] } });
assert.match(r.content[0].text, /Deleted 1/);

fake.close(); img.close(); await client.close();
console.log("MCP smoke test passed");
process.exit(0);
