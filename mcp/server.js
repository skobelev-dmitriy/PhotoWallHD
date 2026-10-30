#!/usr/bin/env node
/**
 * MCP server for PhotoWall HD.
 *
 * Talks to the HTTP API that the Android app runs on the projector (see ../docs/API.md) and exposes it as tools,
 * so you can say "Claude, turn on the disco on the projector".
 *
 *   PHOTOWALL_URL   projector address, e.g. http://192.168.1.50:8080   (required)
 *   PHOTOWALL_PIN   PIN set in the app, if any
 *
 *   node server.js                   stdio transport (Claude Code, Claude Desktop)
 *   node server.js --http [port]     Streamable HTTP on 127.0.0.1:port/mcp, protected by MCP_TOKEN (bearer)
 */
import { readFile } from "node:fs/promises";
import { createServer } from "node:http";
import { basename } from "node:path";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { z } from "zod";

const BASE = (process.env.PHOTOWALL_URL || "").replace(/\/+$/, "");
const PIN = process.env.PHOTOWALL_PIN || "";
const HTTP_MODE = process.argv.includes("--http");
const MAX_UPLOAD = 300 * 1024 * 1024;

if (!BASE) {
  console.error("PHOTOWALL_URL is not set, e.g. PHOTOWALL_URL=http://192.168.1.50:8080");
  process.exit(1);
}

// ---------- projector API ----------

async function call(path, { method = "GET", json, body, headers = {} } = {}) {
  const h = { ...headers };
  if (PIN) h["X-Pin"] = PIN;
  if (json !== undefined) {
    h["Content-Type"] = "application/json";
    body = JSON.stringify(json);
  }
  let res;
  try {
    res = await fetch(BASE + path, { method, headers: h, body, signal: AbortSignal.timeout(120_000) });
  } catch (e) {
    throw new Error(`Cannot reach the projector at ${BASE} (${e.cause?.code || e.message}). Is it on, in the same network, and is the PhotoWall HD app running?`);
  }
  const text = await res.text();
  let data;
  try {
    data = text ? JSON.parse(text) : {};
  } catch {
    data = { raw: text };
  }
  if (res.status === 401) throw new Error("The projector asks for a PIN: set PHOTOWALL_PIN.");
  if (!res.ok) throw new Error(`Projector answered ${res.status}: ${data.error || text}`);
  return data;
}

const control = (action) => call("/api/control", { method: "POST", json: { action } });
const setSettings = (s) => call("/api/settings", { method: "POST", json: s });

const ok = (text, structured) => ({
  content: [{ type: "text", text }],
  ...(structured ? { structuredContent: structured } : {}),
});

function describeMode(s) {
  return { slideshow: "photo slideshow", disco: "disco mode", idle: "nothing on screen (home screen / other app)" }[s.mode] || "unknown";
}

// ---------- tools ----------

const settingsShape = {
  intervalSec: z.number().int().min(3).max(86400).describe("seconds per photo"),
  transition: z.enum(["fade", "slide", "none"]),
  fit: z.enum(["fit", "fill", "auto"]).describe("fit = whole picture, fill = crop to screen"),
  kenBurns: z.boolean().describe("slow zoom"),
  shuffle: z.boolean(),
  clock: z.boolean().describe("show the clock over photos"),
  clockPos: z.enum(["tl", "tr", "bl", "br", "bc"]),
  clockSize: z.enum(["s", "m", "l"]),
  clock24: z.boolean(),
  clockDate: z.boolean(),
  dim: z.number().int().min(0).max(80).describe("percent of black over the picture"),
  scheduleEnabled: z.boolean().describe("black screen outside onMinutes..offMinutes"),
  onMinutes: z.number().int().min(0).max(1439).describe("minutes from midnight"),
  offMinutes: z.number().int().min(0).max(1439).describe("minutes from midnight"),
  autostart: z.boolean(),
  discoSource: z.enum(["mic", "output", "demo"]).describe("sound for disco mode: projector microphone, device audio, or built-in demo beat"),
  discoVisual: z.enum(["auto", "bars", "circle", "rings", "wave", "lamps", "fireworks", "warp", "hills"]),
  discoAutoSec: z.number().int().min(5).max(600),
  discoSens: z.number().int().min(40).max(400).describe("percent"),
  discoFlash: z.boolean().describe("soft flashes on the beat"),
};

function buildServer() {
  const server = new McpServer({ name: "photowall", version: "1.0.0" });

  server.registerTool(
    "projector_status",
    {
      title: "Projector status",
      description: "Show what the PhotoWall HD projector is doing now: mode (slideshow / disco / idle), number of photos, free space, address and all settings. Call this first if unsure.",
      annotations: { readOnlyHint: true },
    },
    async () => {
      const s = await call("/api/state");
      const text = [
        `Mode: ${describeMode(s)}`,
        `Photos: ${s.photos.length} (${(s.totalBytes / 1e6).toFixed(0)} MB, ${(s.freeBytes / 1e9).toFixed(1)} GB free)`,
        `Address: ${s.url}`,
        `Settings: ${JSON.stringify(s.settings)}`,
      ].join("\n");
      return ok(text, { mode: s.mode, photoCount: s.photos.length, url: s.url, settings: s.settings });
    },
  );

  server.registerTool(
    "list_photos",
    {
      title: "List photos",
      description: "List the photos stored on the projector (oldest first, which is the slideshow order).",
      annotations: { readOnlyHint: true },
    },
    async () => {
      const s = await call("/api/state");
      const lines = s.photos.map((p) => `${p.name}  ${(p.size / 1e6).toFixed(1)} MB  ${new Date(p.modified).toISOString().slice(0, 10)}`);
      return ok(lines.length ? lines.join("\n") : "No photos yet.", { photos: s.photos });
    },
  );

  server.registerTool(
    "add_photo",
    {
      title: "Add photo",
      description: "Upload a picture to the projector so it joins the slideshow. Give either an http(s) URL of the image or (only when this server runs on the user's own computer) a local file path.",
      inputSchema: {
        source: z.string().describe("http(s) URL of the image, or a local file path"),
        name: z.string().optional().describe("file name to store it under, e.g. monet.jpg"),
      },
    },
    async ({ source, name }) => {
      let bytes;
      let fileName = name;
      if (/^https?:\/\//i.test(source)) {
        const r = await fetch(source, { signal: AbortSignal.timeout(60_000) });
        if (!r.ok) throw new Error(`Could not download the image: HTTP ${r.status}`);
        bytes = Buffer.from(await r.arrayBuffer());
        if (!fileName) {
          const fromUrl = decodeURIComponent(basename(new URL(source).pathname));
          const ext = (r.headers.get("content-type") || "").split("/")[1]?.split(";")[0];
          fileName = /\.(jpe?g|png|webp|bmp|gif|heic|heif)$/i.test(fromUrl) ? fromUrl : `${fromUrl || "photo"}.${ext === "jpeg" || !ext ? "jpg" : ext}`;
        }
      } else {
        if (HTTP_MODE) throw new Error("Local file paths are disabled when the server runs in HTTP mode; pass an image URL.");
        bytes = await readFile(source);
        fileName ||= basename(source);
      }
      if (bytes.length === 0 || bytes.length > MAX_UPLOAD) throw new Error("The image is empty or larger than 300 MB.");
      const r = await call("/api/upload", { method: "POST", body: bytes, headers: { "X-Filename": encodeURIComponent(fileName) } });
      return ok(`Added ${r.name} (${(r.size / 1e6).toFixed(1)} MB) to the projector.`, r);
    },
  );

  server.registerTool(
    "delete_photos",
    {
      title: "Delete photos",
      description: "Permanently delete photos from the projector by file name (see list_photos). Confirm with the user before deleting.",
      inputSchema: { names: z.array(z.string()).min(1).describe("exact file names") },
      annotations: { destructiveHint: true },
    },
    async ({ names }) => {
      const r = await call("/api/delete", { method: "POST", json: { names } });
      return ok(`Deleted ${r.deleted} of ${names.length} photo(s).`, r);
    },
  );

  server.registerTool(
    "slideshow",
    {
      title: "Control the slideshow",
      description: "Start the photo slideshow on the projector, stop it (close it), go to the next/previous photo, or pause/resume. 'start' needs the app to be allowed to open over other apps.",
      inputSchema: { action: z.enum(["start", "stop", "next", "prev", "pause", "resume"]) },
    },
    async ({ action }) => {
      await control(action);
      await new Promise((r) => setTimeout(r, 1200));
      const s = await call("/api/state");
      return ok(`Sent "${action}". Projector mode now: ${describeMode(s)}.`, { mode: s.mode });
    },
  );

  server.registerTool(
    "disco",
    {
      title: "Disco mode",
      description:
        "Control the disco / music-visualizer mode (colour music reacting to sound). 'start' turns it on, 'stop' closes it, 'next_effect'/'prev_effect' switch the effect. " +
        "Optionally set effect (bars, circle, rings, wave, lamps=colour-music lamps, fireworks, warp=starfield, hills, or auto to rotate), sound source (mic, output, demo), sensitivity (percent) and beat flashes before starting. " +
        "If the projector has no microphone use source=demo.",
      inputSchema: {
        action: z.enum(["start", "stop", "next_effect", "prev_effect"]),
        effect: settingsShape.discoVisual.optional(),
        source: settingsShape.discoSource.optional(),
        sensitivity: settingsShape.discoSens.optional(),
        flashes: z.boolean().optional().describe("soft flashes on the beat"),
      },
    },
    async ({ action, effect, source, sensitivity, flashes }) => {
      const s = {};
      if (effect) s.discoVisual = effect;
      if (source) s.discoSource = source;
      if (sensitivity !== undefined) s.discoSens = sensitivity;
      if (flashes !== undefined) s.discoFlash = flashes;
      if (Object.keys(s).length) await setSettings(s);
      const act = { start: "disco", stop: "stop", next_effect: "dnext", prev_effect: "dprev" }[action];
      await control(act);
      await new Promise((r) => setTimeout(r, 1500));
      const st = await call("/api/state");
      return ok(`Sent "${action}". Projector mode now: ${describeMode(st)}. Effect: ${st.settings.discoVisual}, sound: ${st.settings.discoSource}.`, { mode: st.mode, settings: st.settings });
    },
  );

  server.registerTool(
    "update_settings",
    {
      title: "Change settings",
      description: "Change any projector settings (only the fields you pass are changed): photo interval, transition, scaling, clock, dimming, night schedule, disco options. Times are minutes from midnight (18:00 = 1080).",
      inputSchema: Object.fromEntries(Object.entries(settingsShape).map(([k, v]) => [k, v.optional()])),
    },
    async (args) => {
      const patch = Object.fromEntries(Object.entries(args).filter(([, v]) => v !== undefined));
      if (!Object.keys(patch).length) throw new Error("Pass at least one setting to change.");
      const r = await setSettings(patch);
      return ok(`Updated: ${Object.keys(patch).join(", ")}.`, r);
    },
  );

  return server;
}

// ---------- transports ----------

if (!HTTP_MODE) {
  await buildServer().connect(new StdioServerTransport());
  console.error(`photowall-mcp: stdio, projector ${BASE}`);
} else {
  const token = process.env.MCP_TOKEN || "";
  if (!token) {
    console.error("HTTP mode needs MCP_TOKEN (a long random string clients send as 'Authorization: Bearer ...').");
    process.exit(1);
  }
  const arg = process.argv[process.argv.indexOf("--http") + 1];
  const port = Number(arg) || Number(process.env.PORT) || 3333;
  const host = process.env.MCP_HOST || "127.0.0.1";

  const http = createServer(async (req, res) => {
    const send = (code, obj) => {
      res.writeHead(code, { "Content-Type": "application/json" });
      res.end(JSON.stringify(obj));
    };
    try {
      if (req.url?.split("?")[0] !== "/mcp") return send(404, { error: "not found" });
      if (req.headers.authorization !== `Bearer ${token}`) return send(401, { error: "unauthorized" });
      if (req.method !== "POST") return send(405, { error: "use POST" });
      const chunks = [];
      let size = 0;
      for await (const c of req) {
        size += c.length;
        if (size > 1024 * 1024) return send(413, { error: "too large" });
        chunks.push(c);
      }
      const body = JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}");
      // stateless: a fresh server + transport per request
      const server = buildServer();
      const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined, enableJsonResponse: true });
      res.on("close", () => {
        transport.close();
        server.close();
      });
      await server.connect(transport);
      await transport.handleRequest(req, res, body);
    } catch (e) {
      if (!res.headersSent) send(500, { error: String(e.message || e) });
    }
  });
  http.listen(port, host, () => console.error(`photowall-mcp: http://${host}:${port}/mcp, projector ${BASE}`));
}
