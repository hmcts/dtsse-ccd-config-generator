import { createServer as createHttpServer } from "node:http";

import type { ViteDevServer } from "vite";

import { createApp } from "./app.js";
import {
  createBackendTemplateProxy,
  isBackendModeSelected,
  resolveBackendModeConfig,
} from "./backend-mode.js";

const development = process.env.NODE_ENV !== "production";
const host = process.env.HOST ?? "127.0.0.1";
const port = Number.parseInt(process.env.PORT ?? "8000", 10);

let vite: ViteDevServer | undefined;

if (development) {
  const { createServer: createViteServer } = await import("vite");
  vite = await createViteServer({
    appType: "custom",
    server: {
      middlewareMode: true,
    },
  });
}

const backendMode = isBackendModeSelected(process.env);
const templateProxy = backendMode
  ? await createBackendTemplateProxy(resolveBackendModeConfig(process.env))
  : undefined;
const app = createApp({
  development,
  vite,
  templateProxy,
  templatesMode: backendMode ? "backend" : "standalone",
});
const server = createHttpServer(app);

server.listen(port, host, () => {
  console.log(`Docweave listening on http://${host}:${port}`);
});

async function shutDown(): Promise<void> {
  server.close();
  await vite?.close();
}

process.once("SIGINT", shutDown);
process.once("SIGTERM", shutDown);
