import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { afterEach, beforeEach, describe, it } from "node:test";

import { JSDOM } from "jsdom";

const fixtures = [
  {
    source: "Word",
    path: "./fixtures/word-paste/paragraph-and-lists",
    expectedPath: "./fixtures/clipboard-paste/paragraph-and-lists.json",
  },
  {
    source: "Google Docs with a list starting at 5",
    path: "./fixtures/google-docs-paste/paragraph-and-lists",
    expectedPath: "./fixtures/clipboard-paste/paragraph-and-lists-starting-at-5.json",
  },
] as const;

const expectedHTMLPath = "./fixtures/clipboard-paste/paragraph-and-lists.html";

const globalNames = [
  "window",
  "document",
  "navigator",
  "Node",
  "Text",
  "Element",
  "HTMLElement",
  "MutationObserver",
  "DOMParser",
  "KeyboardEvent",
  "MouseEvent",
  "Event",
  "requestAnimationFrame",
  "cancelAnimationFrame",
] as const;

let dom: JSDOM;
let originalGlobals: Map<string, PropertyDescriptor | undefined>;

beforeEach(() => {
  dom = new JSDOM('<!doctype html><div id="editor"></div>', {
    pretendToBeVisual: true,
  });
  originalGlobals = new Map(
    globalNames.map((name) => [
      name,
      Object.getOwnPropertyDescriptor(globalThis, name),
    ]),
  );

  const window = dom.window;
  const testGlobals: Record<(typeof globalNames)[number], unknown> = {
    window,
    document: window.document,
    navigator: window.navigator,
    Node: window.Node,
    Text: window.Text,
    Element: window.Element,
    HTMLElement: window.HTMLElement,
    MutationObserver: window.MutationObserver,
    DOMParser: window.DOMParser,
    KeyboardEvent: window.KeyboardEvent,
    MouseEvent: window.MouseEvent,
    Event: window.Event,
    requestAnimationFrame: window.requestAnimationFrame.bind(window),
    cancelAnimationFrame: window.cancelAnimationFrame.bind(window),
  };

  for (const name of globalNames) {
    Object.defineProperty(globalThis, name, {
      configurable: true,
      writable: true,
      value: testGlobals[name],
    });
  }
});

afterEach(() => {
  dom.window.close();
  for (const name of globalNames) {
    const descriptor = originalGlobals.get(name);
    if (descriptor) {
      Object.defineProperty(globalThis, name, descriptor);
    } else {
      Reflect.deleteProperty(globalThis, name);
    }
  }
});

describe("rich-text clipboard paste", () => {
  for (const fixture of fixtures) {
    it(
      `parses captured ${fixture.source} HTML into the expected ProseMirror document`,
      async () => {
        const [{ createOrderEditor }, html, text, expected, expectedHTML] =
          await Promise.all([
            import("../src/index.js"),
            readFile(new URL(`${fixture.path}.html`, import.meta.url), "utf8"),
            readFile(new URL(`${fixture.path}.txt`, import.meta.url), "utf8"),
            readFile(new URL(fixture.expectedPath, import.meta.url), "utf8")
              .then((content) => JSON.parse(content) as unknown),
            readFile(new URL(expectedHTMLPath, import.meta.url), "utf8"),
          ]);
        const controller = createOrderEditor({ mount: "#editor" });
        const paste = new dom.window.Event("paste", {
          bubbles: true,
          cancelable: true,
        });
        Object.defineProperty(paste, "clipboardData", {
          value: {
            getData(type: string): string {
              if (type === "text/html") return html;
              if (type === "text/plain" || type === "Text") return text;
              return "";
            },
          },
        });

        const editor = dom.window.document.querySelector(".ProseMirror");
        assert.ok(editor);
        editor.dispatchEvent(paste);

        assert.equal(paste.defaultPrevented, true);
        const actual = JSON.parse(
          JSON.stringify(controller.getSnapshot().current),
        ) as unknown;
        assert.deepEqual(actual, expected);

        const semanticHTML = editor.cloneNode(true) as HTMLElement;
        for (
          const widget of semanticHTML.querySelectorAll(".ProseMirror-widget")
        ) {
          widget.remove();
        }
        for (const element of semanticHTML.querySelectorAll("[class]")) {
          element.removeAttribute("class");
        }
        assert.equal(semanticHTML.innerHTML, expectedHTML.trim());
        controller.destroy();
      },
    );
  }
});
