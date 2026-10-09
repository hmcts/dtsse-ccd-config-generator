import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { after, describe, it } from "node:test";
import { JSDOM } from "jsdom";
import { renderDomHtml } from "./fixtures/dom-html.js";
import { htmlCases } from "./fixtures/html-cases.js";

// Also detect schema changes that invalidate goldens, without rewriting them during tests.
describe("HTML goldens match ProseMirror's DOM renderer", () => {
  const dom = new JSDOM("<!doctype html>");
  after(() => dom.window.close());
  for (const { name, snapshot } of htmlCases()) {
    for (const changes of [false, true]) {
      const filename = `${name}${changes ? "-changes" : ""}.html`;
      it(filename, () => {
        assert.equal(renderDomHtml(snapshot, { document: dom.window.document, changes }),
          readFileSync(new URL(`./fixtures/html-goldens/${filename}`, import.meta.url), "utf8"));
      });
    }
  }
});
