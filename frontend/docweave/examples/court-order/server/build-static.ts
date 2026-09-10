import { cp, mkdir, writeFile } from "node:fs/promises";
import { createRequire } from "node:module";
import path from "node:path";

import { createApp } from "./app.js";

const app = createApp({ assetPath: "../assets" });
const html = await new Promise<string>((resolve, reject) => {
  app.render("index.njk", (error: Error | null, rendered: string) => {
    if (error) reject(error);
    else resolve(rendered);
  });
});
const require = createRequire(import.meta.url);
const govukRoot = path.dirname(require.resolve("govuk-frontend/package.json"));

await cp(path.join(govukRoot, "dist/govuk/assets"), "dist/public/assets", {
  recursive: true,
});
await mkdir("dist/public/playground", { recursive: true });
await writeFile("dist/public/playground/index.html", html);
