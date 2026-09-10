import assert from "node:assert/strict";
import { afterEach, beforeEach, describe, it } from "node:test";

import { JSDOM } from "jsdom";

import { getDocumentNode } from "../src/builder.js";
import { createInMemoryTemplateProvider } from "../examples/court-order/template-provider.js";
import { polyfillBrowserApis } from "./jsdom-polyfills.js";

interface TestDocumentJSON {
  content: Array<{
    content?: Array<{
      text?: string;
    }>;
  }>;
}

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
  dom = new JSDOM(
    `<!doctype html>
      <div id="editor"></div>
      <div id="restored-editor"></div>`,
    { pretendToBeVisual: true },
  );
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
    if (descriptor) {
      Object.defineProperty(globalThis, name, descriptor);
    } else {
      Reflect.deleteProperty(globalThis, name);
    }
  }
});

describe("public order editor API", () => {
  for (const numbered of [false, true]) {
    it(`opens with slash in an empty ${numbered ? "numbered clause" : "paragraph"}, cancels cleanly and inserts with undo`, async () => {
      const { createOrderEditor } = await import("../src/index.js");
      const store = createInMemoryTemplateProvider();
      await store.create({ title: "Costs wording", content: {
        schema: "docweave-template", version: 1,
        content: { type: "doc", content: [{ type: "paragraph", content: [{ type: "text", text: "Costs in the case." }] }] },
      } });
      const controller = createOrderEditor({ mount: "#editor", templates: { provider: store } });
      const surface = dom.window.document.querySelector<HTMLElement>("#editor .ProseMirror")!;
      surface.focus();
      if (numbered) dom.window.document.querySelector<HTMLButtonElement>('[aria-label="Numbered clause"]')!.click();
      assert.equal(!!surface.querySelector("ol"), numbered);
      const original = controller.getSnapshot().current;
      const slash = () => {
        const event = new dom.window.KeyboardEvent("keydown", { key: "/", bubbles: true, cancelable: true });
        surface.dispatchEvent(event);
        assert.equal(event.defaultPrevented, true);
      };
      slash();
      await new Promise((resolve) => setTimeout(resolve, 0));
      assert.equal(dom.window.document.querySelector("dialog")!.open, true);
      assert.deepEqual(controller.getSnapshot().current, original);
      dom.window.document.querySelector("dialog")!.dispatchEvent(new dom.window.Event("cancel", { cancelable: true }));
      assert.equal(dom.window.document.activeElement, surface);
      assert.deepEqual(controller.getSnapshot().current, original);
      slash();
      await new Promise((resolve) => setTimeout(resolve, 0));
      dom.window.document.querySelector('input[type="search"]')!.dispatchEvent(new dom.window.KeyboardEvent("keydown", { key: "Enter", bubbles: true, cancelable: true }));
      assert.equal(surface.textContent, "Costs in the case.");
      assert.equal(dom.window.document.querySelector("dialog")!.open, false);
      assert.equal(dom.window.document.activeElement, surface);
      const inserted = controller.getSnapshot().current;
      // Let ProseMirror observe typing immediately after insertion, without a
      // history timeout separating the two edits.
      const text = dom.window.document.createTreeWalker(surface, dom.window.NodeFilter.SHOW_TEXT).nextNode() as Text;
      text.appendData(" More wording.");
      await new Promise((resolve) => setTimeout(resolve, 0));
      assert.equal(surface.textContent, "Costs in the case. More wording.");
      dom.window.document.querySelector<HTMLButtonElement>('[aria-label="Undo"]')!.click();
      assert.deepEqual(controller.getSnapshot().current, inserted);
      dom.window.document.querySelector<HTMLButtonElement>('[aria-label="Undo"]')!.click();
      assert.deepEqual(controller.getSnapshot().current, original);
      controller.destroy();
    });
  }

  it("leaves slash typing alone without templates, in existing text, and during composition or modifier shortcuts", async () => {
    const { createOrderEditor, buildOrder } = await import("../src/index.js");
    for (const configured of [false, true]) {
      const controller = createOrderEditor({ mount: "#editor", ...(configured ? { templates: { provider: createInMemoryTemplateProvider() } } : {}) });
      const surface = dom.window.document.querySelector<HTMLElement>("#editor .ProseMirror")!;
      surface.focus();
      const ignored = (extra: KeyboardEventInit = {}) => {
        const event = new dom.window.KeyboardEvent("keydown", { key: "/", bubbles: true, cancelable: true, ...extra });
        surface.dispatchEvent(event);
        assert.equal(event.defaultPrevented, false);
        assert.equal(dom.window.document.querySelector("dialog")?.open ?? false, false);
      };
      if (!configured) ignored();
      for (const extra of [{ ctrlKey: true }, { altKey: true }, { metaKey: true }, { shiftKey: true }, { isComposing: true }]) ignored(extra);
      controller.render(buildOrder((order) => order.paragraph("existing", "Existing text")));
      surface.focus();
      ignored();
      controller.destroy();
    }
  });

  it("refuses a mount point that already has an editor", async () => {
    const { createOrderEditor } = await import("../src/index.js");
    const controller = createOrderEditor({ mount: "#editor" });

    assert.throws(
      () => createOrderEditor({ mount: "#editor" }),
      /already has an editor/,
    );

    controller.destroy();
    assert.doesNotThrow(() => createOrderEditor({ mount: "#editor" }).destroy());
  });

  it("rejects incomplete template configuration before mounting", async () => {
    const { createOrderEditor } = await import("../src/index.js");

    assert.throws(
      () => createOrderEditor({ mount: "#editor", templates: {} }),
      /Templates require either a provider or URL/,
    );
    assert.equal(
      dom.window.document.querySelector("#editor")!.childElementCount,
      0,
    );
  });

  it("rejects unsupported snapshot envelopes before mounting", async () => {
    const { createOrderEditor } = await import("../src/index.js");
    const document = { type: "doc" };

    for (const snapshot of [
      {
        schema: "another-document",
        version: 1,
        current: document,
        generated: document,
      },
      {
        schema: "docweave-document",
        version: 2,
        current: document,
        generated: document,
      },
    ]) {
      assert.throws(
        () => createOrderEditor({
          mount: "#editor",
          initialSnapshot: snapshot as never,
        }),
        /Unsupported Docweave snapshot version/,
      );
    }
    assert.equal(
      dom.window.document.querySelector("#editor")!.childElementCount,
      0,
    );
  });

  it("renders, serializes and destroys an editor", async () => {
    const { buildOrder, createOrderEditor } = await import("../src/index.js");
    const target = buildOrder((order) => {
      order.paragraph("heading", "IT IS ORDERED THAT:");
      order.orderedList("clauses", (list) => {
        list.item("possession", (content) => {
          content
            .text("Give up possession by ")
            .fact("deadline", "1 October 2026");
        });
      });
    });
    const controller = createOrderEditor({ mount: "#editor" });

    controller.render(target);
    assert.equal(controller.getDocument(), target);

    const mount = dom.window.document.querySelector<HTMLElement>("#editor")!;
    assert.equal(mount.classList.contains("docweave-editor"), true);
    const toolbar = mount.querySelector<HTMLElement>(
      ".docweave-editor__toolbar",
    );
    assert.ok(toolbar);
    assert.equal(toolbar.getAttribute("role"), "toolbar");
    assert.equal(
      toolbar.getAttribute("aria-label"),
      "Order editor formatting",
    );
    const toolbarButtons = [
      ...toolbar.querySelectorAll<HTMLButtonElement>(
        "[data-editor-command]",
      ),
    ];
    assert.deepEqual(
      toolbarButtons.map((button) => button.dataset.editorCommand),
      ["undo", "redo", "bold", "italic", "numbered", "outdent", "indent"],
    );
    assert.deepEqual(
      toolbarButtons.map((button) => button.getAttribute("aria-label")),
      [
        "Undo",
        "Redo",
        "Bold",
        "Italic",
        "Numbered clause",
        "Outdent paragraph",
        "Indent paragraph",
      ],
    );
    assert.equal(
      toolbarButtons.every((button) => button.type === "button"),
      true,
    );
    assert.equal(toolbarButtons[0]!.disabled, true);
    assert.equal(toolbarButtons[2]!.disabled, false);
    toolbarButtons[2]!.click();
    assert.match(mount.textContent, /IT IS ORDERED THAT:/);
    assert.match(mount.textContent, /Give up possession by 1 October 2026/);
    assert.equal(
      mount.querySelector("[data-generated-text]")?.getAttribute(
        "contenteditable",
      ),
      "false",
    );
    const saved = controller.getSnapshot();
    assert.equal(saved.schema, "docweave-document");
    assert.equal(saved.version, 1);
    assert.deepEqual(saved.current, getDocumentNode(target).toJSON());
    assert.deepEqual(saved.generated, getDocumentNode(target).toJSON());

    controller.destroy();
    assert.equal(mount.querySelector(".ProseMirror"), null);
    assert.equal(mount.querySelector(".docweave-editor__toolbar"), null);
    assert.equal(mount.querySelector(".docweave-editor__surface"), null);
    assert.equal(mount.classList.contains("docweave-editor"), false);
  });

  it("restores edits and reconciles new generated values", async () => {
    const { buildOrder, createOrderEditor } = await import("../src/index.js");
    const orderWithDate = (date: string) =>
      buildOrder((order) => {
        order.paragraph("deadline", (content) => {
          content
            .text("Payment is due by ")
            .fact("date", date)
            .text(".");
        });
      });
    const generated = orderWithDate("1 October 2026");
    const current = structuredClone(
      getDocumentNode(generated).toJSON(),
    ) as TestDocumentJSON;
    current.content[0]!.content![0]!.text = "The judge requires payment by ";
    const controller = createOrderEditor({
      mount: "#restored-editor",
      initialSnapshot: {
        schema: "docweave-document",
        version: 1,
        current: current as unknown as Record<string, unknown>,
        generated: getDocumentNode(generated).toJSON() as Record<
          string,
          unknown
        >,
      },
    });

    controller.render(orderWithDate("8 October 2026"));

    const mount = dom.window.document.querySelector("#restored-editor")!;
    assert.match(
      mount.textContent ?? "",
      /The judge requires payment by 8 October 2026\./,
    );
    const saved = controller.getSnapshot();
    assert.equal(
      ((saved.current.content as TestDocumentJSON["content"])[0]!
        .content![0]!).text,
      "The judge requires payment by ",
    );
    assert.equal(
      ((saved.current.content as TestDocumentJSON["content"])[0]!
        .content![1] as { attrs: { text: string } }).attrs.text,
      "8 October 2026",
    );

    controller.destroy();
  });

  it("focuses and scrolls to the source control for a generated fact", async () => {
    const { buildOrder, createOrderEditor } = await import("../src/index.js");
    const source = dom.window.document.createElement("input");
    source.id = "amount-input";
    let scrolls = 0;
    source.scrollIntoView = () => {
      scrolls += 1;
    };
    dom.window.document.body.append(source);

    const target = buildOrder((order) => {
      order.paragraph("payment", (content) => {
        content.text("Must pay £").fact("amount", "2342.00", {
          sourceId: "amount-input",
        });
      });
    });
    const controller = createOrderEditor({ mount: "#editor" });

    controller.render(target);
    const fact = dom.window.document.querySelector<HTMLElement>(
      "[data-generated-text]",
    )!;
    assert.equal(fact.getAttribute("role"), "link");
    fact.click();

    assert.equal(dom.window.document.activeElement, source);
    assert.equal(scrolls, 1);
    assert.doesNotMatch(
      JSON.stringify(controller.getSnapshot()),
      /amount-input/,
    );
    controller.destroy();
  });

  it("navigates a fact to the first enabled control in a composite source", async () => {
    const { buildOrder, createOrderEditor } = await import("../src/index.js");
    const group = dom.window.document.createElement("div");
    group.id = "adj-defence-date";
    const day = dom.window.document.createElement("input");
    day.disabled = true;
    const month = dom.window.document.createElement("input");
    const year = dom.window.document.createElement("input");
    group.append(day, month, year);
    let scrolls = 0;
    group.scrollIntoView = () => {
      scrolls += 1;
    };
    dom.window.document.body.append(group);

    const target = buildOrder((order) => {
      order.paragraph("adjournment", (content) => {
        content.fact("defence-date", "1 October 2026", {
          sourceId: "adj-defence-date",
        });
      });
    });
    const controller = createOrderEditor({ mount: "#editor" });
    controller.render(target);

    const fact = dom.window.document.querySelector<HTMLElement>(
      "[data-generated-text]",
    )!;
    fact.focus();
    fact.dispatchEvent(new dom.window.KeyboardEvent("keydown", {
      bubbles: true,
      cancelable: true,
      key: " ",
    }));
    assert.equal(dom.window.document.activeElement, fact);
    assert.equal(scrolls, 0);

    fact.dispatchEvent(new dom.window.KeyboardEvent("keydown", {
      bubbles: true,
      cancelable: true,
      key: "Enter",
    }));

    assert.equal(dom.window.document.activeElement, month);
    assert.equal(scrolls, 1);
    controller.destroy();
  });

  it("leaves a fact inert when its source does not exist", async () => {
    const { buildOrder, createOrderEditor } = await import("../src/index.js");
    const target = buildOrder((order) => {
      order.paragraph("payment", (content) => {
        content.fact("amount", "£1", { sourceId: "missing-input" });
      });
    });
    const controller = createOrderEditor({ mount: "#editor" });
    controller.render(target);

    const fact = dom.window.document.querySelector<HTMLElement>(
      "[data-generated-text]",
    )!;
    assert.equal(fact.getAttribute("role"), null);
    assert.equal(fact.getAttribute("tabindex"), null);
    assert.doesNotThrow(() => fact.click());
    controller.destroy();
  });

  it("replaces fact source wiring even when its content is unchanged", async () => {
    const { buildOrder, createOrderEditor } = await import("../src/index.js");
    const firstSource = dom.window.document.createElement("input");
    firstSource.id = "first-amount";
    firstSource.scrollIntoView = () => {};
    const secondSource = dom.window.document.createElement("input");
    secondSource.id = "second-amount";
    secondSource.scrollIntoView = () => {};
    dom.window.document.body.append(firstSource, secondSource);
    const orderWithSource = (sourceId: string) =>
      buildOrder((order) => {
        order.paragraph("payment", (content) => {
          content.fact("amount", "£1", { sourceId });
        });
      });
    const controller = createOrderEditor({ mount: "#editor" });

    controller.render(orderWithSource("first-amount"));
    controller.render(orderWithSource("second-amount"));
    dom.window.document.querySelector<HTMLElement>(
      "[data-generated-text]",
    )!.click();

    assert.equal(dom.window.document.activeElement, secondSource);
    controller.destroy();
  });

  it("creates editor controls in the mount's document", async () => {
    const { buildOrder, createOrderEditor } = await import("../src/index.js");
    const generated = buildOrder((order) => {
      order.paragraph("heading", "Generated heading");
      order.paragraph("payment", (content) => {
        content.fact("amount", "£1", { sourceId: "shared-source" });
      });
    });
    const current = structuredClone(
      getDocumentNode(generated).toJSON(),
    ) as TestDocumentJSON;
    current.content[0]!.content![0]!.text = "Edited heading";
    const ownerDom = new JSDOM('<div id="editor"></div>', {
      pretendToBeVisual: true,
    });

    try {
      const mount = ownerDom.window.document.querySelector<HTMLElement>(
        "#editor",
      )!;
      const ownerSource = ownerDom.window.document.createElement("input");
      ownerSource.id = "shared-source";
      ownerSource.scrollIntoView = () => {};
      ownerDom.window.document.body.prepend(ownerSource);
      const globalSource = dom.window.document.createElement("input");
      globalSource.id = "shared-source";
      dom.window.document.body.append(globalSource);
      const controller = createOrderEditor({
        mount,
        initialSnapshot: {
          schema: "docweave-document",
          version: 1,
          current: current as unknown as Record<string, unknown>,
          generated: getDocumentNode(generated).toJSON() as Record<
            string,
            unknown
          >,
        },
      });

      assert.equal(
        mount.querySelector(".docweave-editor__toolbar")?.ownerDocument,
        ownerDom.window.document,
      );
      assert.equal(
        mount.querySelector(".docweave-editor__revert")?.ownerDocument,
        ownerDom.window.document,
      );
      controller.render(generated);
      const fact = mount.querySelector<HTMLElement>("[data-generated-text]")!;
      assert.equal(fact.getAttribute("role"), "link");
      fact.click();
      assert.equal(ownerDom.window.document.activeElement, ownerSource);
      assert.notEqual(dom.window.document.activeElement, globalSource);
      controller.destroy();
    } finally {
      ownerDom.window.close();
    }
  });

  it("keeps template insertion at the selected clause across render as one undoable edit", async () => {
    const { buildOrder, createOrderEditor } = await import("../src/index.js");
    const template = {
      id: "11111111-1111-1111-1111-111111111111",
      title: "Standard costs wording",
      revision: 1,
      updatedAt: "2026-09-06T12:00:00Z",
      content: {
        schema: "docweave-template" as const,
        version: 1 as const,
        content: {
          type: "doc",
          content: [{
            type: "paragraph",
            content: [{ type: "text", text: "Costs in the case." }],
          }],
        },
      },
    };
    const controller = createOrderEditor({
      mount: "#editor",
      templates: {
        provider: {
          async search() {
            return { items: [template] };
          },
          async create() {
            return template;
          },
          async update() {
            return template;
          },
          async delete() {},
        },
      },
    });
    const orderWithHeading = (heading: string) => buildOrder((order) => {
      order.paragraph("heading", heading);
      order.paragraph("managed", "A generated paragraph.");
    });
    controller.render(orderWithHeading("Short heading."));

    const surface = dom.window.document.querySelector<HTMLElement>(
      "#editor .ProseMirror",
    )!;
    surface.focus();
    const generatedText = surface.querySelectorAll("p")[1]!.firstChild!;
    const range = dom.window.document.createRange();
    range.setStart(generatedText, 5);
    range.collapse(true);
    dom.window.getSelection()!.removeAllRanges();
    dom.window.getSelection()!.addRange(range);
    dom.window.document.dispatchEvent(new dom.window.Event("selectionchange"));
    await new Promise((resolve) => setTimeout(resolve, 0));

    dom.window.document.querySelector<HTMLButtonElement>(
      '[aria-label="Insert template"]',
    )!.click();
    await new Promise((resolve) => setTimeout(resolve, 0));
    dom.window.document.querySelector<HTMLButtonElement>(
      ".docweave-templates__result",
    )!.click();
    await new Promise((resolve) => setTimeout(resolve, 0));
    controller.render(orderWithHeading(
      "A considerably longer generated heading moves the selected paragraph down.",
    ));
    const beforeInsertion = controller.getSnapshot().current;
    dom.window.document.querySelector<HTMLButtonElement>(
      ".docweave-templates__actions button",
    )!.click();

    const mount = dom.window.document.querySelector<HTMLElement>("#editor")!;
    assert.match(mount.textContent, /Costs in the case\./);
    assert.equal(dom.window.document.querySelector("dialog")!.open, false);
    assert.equal(dom.window.document.activeElement, mount.querySelector(".ProseMirror"));
    const current = controller.getSnapshot().current as {
      content: Array<{ attrs?: { id?: string | null } }>;
    };
    assert.deepEqual(
      current.content.map((node) => node.attrs?.id),
      ["paragraph:heading", "paragraph:managed", null],
    );
    mount.querySelector<HTMLButtonElement>('[aria-label="Undo"]')!.click();
    assert.doesNotMatch(mount.textContent, /Costs in the case\./);
    assert.deepEqual(controller.getSnapshot().current, beforeInsertion);
    controller.destroy();
  });
});
