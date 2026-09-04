import assert from "node:assert/strict";
import { afterEach, beforeEach, describe, it } from "node:test";

import { JSDOM } from "jsdom";

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

  it("renders, reports changes, serializes and destroys an editor", async () => {
    const { buildOrder, createOrderEditor } = await import("../src/index.js");
    const changes: unknown[] = [];
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
    const controller = createOrderEditor({
      mount: "#editor",
      onChange(document) {
        changes.push(document);
      },
    });

    controller.render(target);

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
    assert.equal(changes.length, 1);
    const changesBeforeToolbarCommand = changes.length;
    toolbarButtons[2]!.click();
    assert.equal(changes.length, changesBeforeToolbarCommand + 1);
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
    assert.deepEqual(saved.current, target.node.toJSON());
    assert.deepEqual(saved.generated, target.node.toJSON());

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
      generated.node.toJSON(),
    ) as TestDocumentJSON;
    current.content[0]!.content![0]!.text = "The judge requires payment by ";
    const controller = createOrderEditor({
      mount: "#restored-editor",
      initialSnapshot: {
        schema: "docweave-document",
        version: 1,
        current: current as unknown as Record<string, unknown>,
        generated: generated.node.toJSON() as Record<string, unknown>,
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
      generated.node.toJSON(),
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
          generated: generated.node.toJSON() as Record<string, unknown>,
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
});
