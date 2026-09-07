import path from "node:path";

import { defineConfig, type Plugin } from "vite";

function reloadNunjucks(): Plugin {
  return {
    name: "reload-nunjucks",
    configureServer(server) {
      const viewsDirectory = path.resolve("examples/court-order/views");

      server.watcher.add(viewsDirectory);
      server.watcher.on("change", (file) => {
        if (file.startsWith(viewsDirectory) && file.endsWith(".njk")) {
          server.ws.send({ type: "full-reload", path: "*" });
        }
      });
    },
  };
}

export default defineConfig({
  base: "./",
  plugins: [reloadNunjucks()],
  publicDir: false,
  resolve: {
    alias: [
      {
        find: "@hmcts-cft/docweave/styles/docweave.css",
        replacement: path.resolve("src/editor.scss"),
      },
      {
        find: "@hmcts-cft/docweave",
        replacement: path.resolve("src/index.ts"),
      },
    ],
  },
  build: {
    cssCodeSplit: false,
    cssMinify: "esbuild",
    emptyOutDir: true,
    outDir: "dist/public",
    rollupOptions: {
      input: path.resolve("examples/court-order/main.ts"),
      output: {
        entryFileNames: "assets/application.js",
        assetFileNames: (assetInfo) =>
          assetInfo.names.some((name) => name.endsWith(".css"))
            ? "assets/application.css"
            : "assets/[name]-[hash][extname]",
      },
    },
  },
});
