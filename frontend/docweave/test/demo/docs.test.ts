import assert from "node:assert/strict";
import { afterEach, beforeEach, describe, it } from "node:test";

import { JSDOM } from "jsdom";
import request from "supertest";

import { createApp } from "../../examples/court-order/server/app.js";
import { createInMemoryTemplateProvider } from "../../examples/court-order/template-provider.js";
import { renderDocs } from "../../examples/docs/render.js";
import { compileBuild, compileScript } from "../../examples/docs/runner.js";
import {
  defaultInputValues,
  inputControlId,
  sections,
  type BuildSection,
  type InputValues,
  type ScriptSection,
} from "../../examples/docs/sections.js";
import { createDocEditor } from "../../src/index.js";
import { polyfillBrowserApis } from "../jsdom-polyfills.js";

const buildSections = sections.filter(
  (section): section is BuildSection => section.kind === "build",
);
const scriptSections = sections.filter(
  (section): section is ScriptSection => section.kind === "script",
);

/** Every combination of the section's checkboxes, with text inputs at their defaults. */
function inputCombinations(section: BuildSection): InputValues[] {
  const defaults = defaultInputValues(section.inputs);
  const checkboxes = section.inputs.filter((input) => input.kind === "checkbox");
  const combinations: InputValues[] = [];
  for (let mask = 0; mask < 2 ** checkboxes.length; mask += 1) {
    const values = { ...defaults };
    checkboxes.forEach((checkbox, index) => {
      values[checkbox.name] = Boolean(mask & (1 << index));
    });
    combinations.push(values);
  }
  return combinations;
}

describe("interactive documentation", () => {
  it("has unique section IDs and a preamble matching each section's parameters", () => {
    const ids = sections.map((section) => section.id);
    assert.equal(new Set(ids).size, ids.length);
    const html = renderDocs();
    for (const section of sections) {
      assert.match(html, new RegExp(`data-docs-section="${section.id}"`));
      for (const input of section.inputs) {
        assert.match(
          html,
          new RegExp(`id="${inputControlId(section.id, input.name)}"`),
        );
      }
    }
    assert.match(html, /\(buildDoc, inputs\) =&gt; DocWeaveDocument/);
    assert.match(html, /\(docweave, mount, saved, provider\) =&gt; controller/);
  });

  it("escapes the code it shows so the page cannot be broken by a snippet", () => {
    const html = renderDocs();
    assert.doesNotMatch(html, /<textarea[^>]*>[^<]*<\/script>/);
    assert.match(html, /&lt;\/?script|&gt;/);
  });

  for (const section of buildSections) {
    it(`"${section.title}" builds and reconciles under every input combination`, () => {
      const build = compileBuild(section.code);
      const controller = createDocEditor();
      for (const inputs of inputCombinations(section)) {
        const document = build(inputs);
        controller.render(document);
        assert.ok(document.textContent.length > 0);
        assert.equal(controller.getDocument(), document);
      }
      // Back to the defaults, so a document can always return to where it started.
      controller.render(build(defaultInputValues(section.inputs)));
    });
  }

  it("names a fact's source control after a real input on the page", () => {
    const facts = buildSections.find((section) => section.id === "facts");
    assert.ok(facts);
    const deadline = facts.inputs.find((input) => input.name === "deadline");
    assert.ok(deadline);
    assert.match(
      facts.code,
      new RegExp(`sourceId: "${inputControlId(facts.id, deadline.name)}"`),
    );
  });

  it("rejects code that does not return a document", () => {
    const build = compileBuild("return 42;");
    assert.throws(() => build({}), /must return the document/);
    assert.throws(() => compileBuild("this is not javascript"), SyntaxError);
  });

  it("serves the playground beside the documentation, not as the site index", async () => {
    const app = createApp();
    await request(app).get("/playground/").expect(200).expect(/Court order playground/);
    // Outside Vite the built documentation is served, which may not exist under
    // test; either way the index is no longer the playground.
    const index = await request(app).get("/");
    assert.doesNotMatch(index.text, /Court order playground/);
  });
});

describe("interactive documentation scripts", () => {
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
    "requestAnimationFrame",
    "cancelAnimationFrame",
    "getComputedStyle",
  ] as const;
  let dom: JSDOM;
  let originalGlobals: Map<string, PropertyDescriptor | undefined>;

  beforeEach(() => {
    dom = new JSDOM(`<!doctype html><div id="mount"></div>`, {
      pretendToBeVisual: true,
    });
    originalGlobals = new Map(
      globalNames.map((name) => [
        name,
        Object.getOwnPropertyDescriptor(globalThis, name),
      ]),
    );
    const window = dom.window;
    polyfillBrowserApis(window);
    Object.defineProperty(window.Range.prototype, "getClientRects", {
      configurable: true,
      value: () => [new window.DOMRect()],
    });
    for (const prototype of [window.Range.prototype, window.Text.prototype]) {
      Object.defineProperty(prototype, "getBoundingClientRect", {
        configurable: true,
        value: () => new window.DOMRect(),
      });
    }
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
      requestAnimationFrame: window.requestAnimationFrame.bind(window),
      cancelAnimationFrame: window.cancelAnimationFrame.bind(window),
      getComputedStyle: window.getComputedStyle.bind(window),
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
      if (descriptor) Object.defineProperty(globalThis, name, descriptor);
      else Reflect.deleteProperty(globalThis, name);
    }
  });

  for (const section of scriptSections) {
    it(`"${section.title}" mounts an editor and survives being run again with its snapshot`, () => {
      const mount = dom.window.document.querySelector<HTMLElement>("#mount")!;
      const script = compileScript(section.code);
      const provider = createInMemoryTemplateProvider();

      const first = script({ mount, saved: undefined, provider });
      assert.ok(mount.querySelector(".ProseMirror"));
      assert.match(mount.textContent, /IT IS ORDERED THAT:/);

      const saved = first.getSnapshot();
      first.destroy();
      const second = script({ mount, saved, provider });
      assert.deepEqual(second.getSnapshot(), saved);
      second.destroy();
      assert.equal(mount.querySelector(".ProseMirror"), null);
    });
  }
});

describe("documentation HTML readout", () => {
  it("indents block elements and leaves inline markup alone", async () => {
    const { formatHtml } = await import("../../examples/docs/format-html.js");
    const { document } = new JSDOM("<!doctype html>").window;
    const html = '<p>IT IS ORDERED THAT:</p><ol start="2"><li><p>Pay <strong>now</strong>.</p><ol><li><p>Then.</p></li></ol></li></ol>';

    assert.equal(
      formatHtml(html, document),
      [
        "<p>IT IS ORDERED THAT:</p>",
        '<ol start="2">',
        "  <li>",
        "    <p>Pay <strong>now</strong>.</p>",
        "    <ol>",
        "      <li>",
        "        <p>Then.</p>",
        "      </li>",
        "    </ol>",
        "  </li>",
        "</ol>",
      ].join("\n"),
    );
  });
});
