import path from "node:path";

import { defineConfig, type Plugin } from "vite";

function reloadNunjucks(): Plugin {
  return {
    name: "reload-nunjucks",
    configureServer(server) {
      const viewsDirectories = [
        path.resolve("examples/court-order/views"),
        path.resolve("examples/docs/views"),
      ];

      for (const directory of viewsDirectories) server.watcher.add(directory);
      server.watcher.on("change", (file) => {
        if (
          viewsDirectories.some((directory) => file.startsWith(directory)) &&
          file.endsWith(".njk")
        ) {
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
      input: {
        application: path.resolve("examples/court-order/main.ts"),
        docs: path.resolve("index.html"),
      },
      output: {
        entryFileNames: "assets/[name].js",
        assetFileNames: (assetInfo) =>
          assetInfo.names.some((name) => name.endsWith(".css"))
            ? "assets/application.css"
            : "assets/[name]-[hash][extname]",
      },
    },
  },
});
