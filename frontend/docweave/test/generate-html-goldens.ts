import { mkdirSync, writeFileSync } from "node:fs";
import { JSDOM } from "jsdom";
import { renderDomHtml } from "./fixtures/dom-html.js";
import { htmlCases } from "./fixtures/html-cases.js";

// Deliberately uses the original ProseMirror DOM renderer, never the renderer under test.
const dom = new JSDOM("<!doctype html>");
const directory = new URL("./fixtures/html-goldens/", import.meta.url);
mkdirSync(directory, { recursive: true });
for (const { name, snapshot } of htmlCases()) {
  for (const changes of [false, true]) {
    const filename = `${name}${changes ? "-changes" : ""}.html`;
    writeFileSync(new URL(filename, directory), renderDomHtml(snapshot, { document: dom.window.document, changes }));
  }
}
dom.window.close();
console.log(`Generated ${htmlCases().length * 2} HTML goldens with ProseMirror and jsdom.`);
