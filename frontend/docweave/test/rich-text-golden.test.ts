import assert from "node:assert/strict";
import { existsSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { afterEach, beforeEach, describe, it } from "node:test";

import { JSDOM } from "jsdom";

import { createDocEditor, type DocWeaveSnapshot, renderHtml, renderText } from "../src/index.js";
import { installJsdomGlobals } from "./jsdom-globals.js";

// Each fixtures/rich-text/<case>.input.html, inserted and pasted into an empty
// editor, gives the document in <case>.html and <case>.txt. UPDATE_GOLDEN=1
// writes them instead.
const directory = new URL("./fixtures/rich-text/", import.meta.url);
const cases = readdirSync(directory)
  .filter((name) => name.endsWith(".input.html"))
  .map((name) => ({ name: name.replace(".input.html", ""), input: new URL(name, directory) }))
  .concat(["word-paste", "google-docs-paste"].map((name) => ({
    name,
    input: new URL(`./fixtures/${name}/paragraph-and-lists.html`, import.meta.url),
  })));
// Cases read only one way. Word and Google Docs HTML comes by paste, not from a
// RichTextArea. Inserting nothing leaves the editor be, where pasting blank
// paragraphs adds them. An item that is only a list is not something
// ngx-editor writes, and ProseMirror lifts the list out at the start of a
// document differently when pasting.
const onlyBy: Record<string, string> = {
  "word-paste": "paste",
  "google-docs-paste": "paste",
  empty: "insertHtml",
  "list-only-item": "insertHtml",
};

function assertGolden(name: string, extension: string, actual: string): void {
  const path = new URL(`${name}${extension}`, directory);
  if (process.env.UPDATE_GOLDEN) {
    writeFileSync(path, `${actual}\n`);
    return;
  }
  assert.ok(existsSync(path), `Missing golden ${path.pathname}: run UPDATE_GOLDEN=1 npm test`);
  assert.equal(actual, readFileSync(path, "utf8").replace(/\n$/, ""), `${name}${extension}`);
}

describe("rich text goldens", () => {
  let dom: JSDOM;
  let restoreGlobals: () => void;

  beforeEach(() => {
    dom = new JSDOM('<!doctype html><div id="editor"></div>', { pretendToBeVisual: true });
    restoreGlobals = installJsdomGlobals(dom);
  });

  afterEach(() => {
    restoreGlobals();
    dom.window.close();
  });

  function read(addHtml: (controller: ReturnType<typeof createDocEditor>) => void): DocWeaveSnapshot {
    const controller = createDocEditor({ mount: "#editor" });
    addHtml(controller);
    const snapshot = controller.getSnapshot();
    controller.destroy();
    return snapshot;
  }

  function paste(html: string) {
    return (): void => {
      const event = new dom.window.Event("paste", { bubbles: true, cancelable: true });
      Object.defineProperty(event, "clipboardData", {
        value: { getData: (type: string) => type === "text/html" ? html : "" },
      });
      dom.window.document.querySelector(".ProseMirror")!.dispatchEvent(event);
    };
  }

  for (const { name, input } of cases) {
    const html = readFileSync(input, "utf8");
    const ways = {
      insertHtml: (controller: ReturnType<typeof createDocEditor>) => controller.insertHtml(html),
      paste: paste(html),
    };
    for (const [way, addHtml] of Object.entries(ways)) {
      if (onlyBy[name] && onlyBy[name] !== way) continue;
      it(`${name} by ${way}`, () => {
        const snapshot = read(addHtml);
        assertGolden(name, ".html", renderHtml(snapshot));
        assertGolden(name, ".txt", renderText(snapshot));
      });
    }
  }
});
