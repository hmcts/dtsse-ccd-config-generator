import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { describe, it } from "node:test";
import { renderHtml } from "../src/index.js";
import { htmlCases } from "./fixtures/html-cases.js";

const directory = new URL("./fixtures/html-goldens/", import.meta.url);

describe("HTML matches the ProseMirror DOM goldens without a DOM", () => {
  const cases = htmlCases();
  it("covers every committed golden", () => {
    assert.deepEqual(readdirSync(directory).filter(name => name.endsWith(".html")).sort(), cases.flatMap(({ name }) =>
      [`${name}.html`, `${name}-changes.html`]).sort());
  });
  for (const { name, snapshot } of cases) {
    for (const changes of [false, true]) {
      const filename = `${name}${changes ? "-changes" : ""}.html`;
      it(filename, () => {
        assert.equal(globalThis.document, undefined);
        assert.equal(renderHtml(snapshot, { changes }), readFileSync(new URL(filename, directory), "utf8"));
      });
    }
  }
});
