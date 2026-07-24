import path from "node:path";

import { defineConfig, type Plugin } from "vite";

function reloadNunjucks(): Plugin {
  return {
    name: "reload-nunjucks",
    configureServer(server) {
      const viewsDirectory = path.resolve("views");

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
  plugins: [reloadNunjucks()],
  publicDir: false,
  build: {
    cssCodeSplit: false,
    cssMinify: "esbuild",
    emptyOutDir: true,
    outDir: "dist/public",
    rollupOptions: {
      input: path.resolve("src/client.ts"),
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
