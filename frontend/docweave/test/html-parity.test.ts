import assert from "node:assert/strict";
import { after, describe, it } from "node:test";
import { JSDOM } from "jsdom";
import { renderHtml } from "../src/index.js";
import { renderDomHtml } from "./fixtures/dom-html.js";
import { htmlCases } from "./fixtures/html-cases.js";

// Generate expected HTML with the original ProseMirror DOM renderer on each run.
// jsdom supplies its local document only to the reference, never the renderer under test.
describe("DOM-free HTML exactly matches ProseMirror's DOM renderer", () => {
  const dom = new JSDOM("<!doctype html>");
  after(() => dom.window.close());
  for (const { name, snapshot } of htmlCases()) {
    for (const changes of [false, true]) {
      it(`${name}${changes ? " with changes" : ""}`, () => {
        assert.equal(globalThis.document, undefined);
        const actual = renderHtml(snapshot, { changes });
        const expected = renderDomHtml(snapshot, { document: dom.window.document, changes });
        assert.equal(actual, expected);
      });
    }
  }
});
