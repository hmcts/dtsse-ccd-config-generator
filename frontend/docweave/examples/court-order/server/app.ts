import { createRequire } from "node:module";
import path from "node:path";

import express, { type Express, type RequestHandler } from "express";
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
  assetPath?: string;
  templateProxy?: RequestHandler;
  templatesMode?: "standalone" | "backend";
}

export function createApp({
  development = false,
  projectRoot = process.cwd(),
  vite,
  assetPath = "/assets",
  templateProxy,
  templatesMode = "standalone",
}: AppOptions = {}): Express {
  const app = express();
  const exampleRoot = path.join(projectRoot, "examples", "court-order");

  app.disable("x-powered-by");
  app.set("view engine", "njk");

  nunjucks.configure(
    [
      path.join(exampleRoot, "views"),
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

  if (vite) app.use(vite.middlewares);
  if (templateProxy) {
    app.use(express.json());
    app.use("/docweave/templates", templateProxy);
  }

  const today = new Date();
  Object.assign(app.locals, {
    assetPath,
    development,
    templatesMode,
    orderDate: {
      day: today.getDate(),
      month: today.getMonth() + 1,
      year: today.getFullYear(),
    },
    initialPaymentDate: {
      day: 14,
      month: 9,
      year: 2026,
    },
    firstMonthlyPaymentDate: {
      day: 28,
      month: 9,
      year: 2026,
    },
  });

  app.get("/", (_request, response) => {
    response.render("index.njk");
  });

  return app;
}
