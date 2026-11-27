// pnpm preview: serves the build (dist/client) as Cloudflare does, so the CSP and redirects are
// tried locally: addresses get their trailing slash, an unknown one gets 404.html, every response
// carries _headers' /* block, and _redirects' redirects apply.
//
//   pnpm preview            # http://localhost:4321
//   PORT=8080 pnpm preview
import fs from "node:fs";
import http from "node:http";
import path from "node:path";

const DIST = path.resolve(import.meta.dirname, "../dist/client");
const PORT = Number(process.env.PORT ?? 4321);
const TYPES: Record<string, string> = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript",
  ".css": "text/css",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".json": "application/json",
  ".xml": "application/xml",
  ".txt": "text/plain; charset=utf-8",
  ".woff2": "font/woff2",
  ".wasm": "application/wasm",
};

const headers: Record<string, string> = {};
let block: string | null = null;
for (const line of fs.readFileSync(path.join(DIST, "_headers"), "utf8").split("\n")) {
  if (/^\S/.test(line) && !line.startsWith("#")) block = line.trim();
  else if (block === "/*" && /^\s+\S/.test(line)) {
    const i = line.indexOf(":");
    headers[line.slice(0, i).trim()] = line.slice(i + 1).trim();
  }
}
const redirects = new Map<string, string>();
for (const line of fs.readFileSync(path.join(DIST, "_redirects"), "utf8").split("\n")) {
  const [from, to] = line.trim().split(/\s+/);
  if (from && to) redirects.set(from, to);
}

http
  .createServer((req, res) => {
    const url = new URL(req.url ?? "/", "http://localhost");
    const p = decodeURIComponent(url.pathname);
    const send = (file: string, status = 200) => {
      res.writeHead(status, { "Content-Type": TYPES[path.extname(file)] ?? "application/octet-stream", ...headers });
      fs.createReadStream(file).pipe(res);
    };
    const to = redirects.get(p);
    if (to) return void res.writeHead(301, { Location: to }).end();
    const file = path.join(DIST, p);
    if (!file.startsWith(DIST)) return void res.writeHead(400).end();
    const index = path.join(file, "index.html");
    if (fs.existsSync(index)) return p.endsWith("/") ? send(index) : void res.writeHead(307, { Location: `${p}/${url.search}` }).end();
    if (fs.existsSync(file) && fs.statSync(file).isFile()) return send(file);
    send(path.join(DIST, "404.html"), 404);
  })
  .listen(PORT, () => console.log(`dist/client on http://localhost:${PORT}`));
