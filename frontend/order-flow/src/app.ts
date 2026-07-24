import path from "node:path";
import { createRequire } from "node:module";

import express, { type Express } from "express";
import nunjucks from "nunjucks";
import type { ViteDevServer } from "vite";

const require = createRequire(import.meta.url);
const govukFrontendRoot = path.dirname(
  require.resolve("govuk-frontend/package.json"),
);

export interface AppOptions {
  development?: boolean;
  projectRoot?: string;
  vite?: ViteDevServer;
}

export function createApp({
  development = false,
  projectRoot = process.cwd(),
  vite,
}: AppOptions = {}): Express {
  const app = express();

  app.disable("x-powered-by");
  app.set("view engine", "njk");

  nunjucks.configure(
    [
      path.join(projectRoot, "views"),
      path.join(govukFrontendRoot, "dist"),
    ],
    {
      autoescape: true,
      express: app,
      noCache: development,
      watch: development,
    },
  );

  app.use(
    "/assets",
    express.static(path.join(projectRoot, "dist", "public", "assets")),
  );
  app.use(
    "/assets",
    express.static(
      path.join(govukFrontendRoot, "dist", "govuk", "assets"),
    ),
  );

  if (vite) {
    app.use(vite.middlewares);
  }

  app.get("/", (_request, response) => {
    response.render("index.njk", {
      assetPath: "/assets",
      development,
    });
  });

  return app;
}
