import assert from "node:assert/strict";
import { afterEach, beforeEach, describe, it } from "node:test";

import { JSDOM } from "jsdom";

import { createTemplateDialog } from "../src/templates/dialog.js";
import {
  type Template,
  type TemplateProvider,
} from "../src/templates/provider.js";

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
] as const;

const template: Template = {
  id: "11111111-1111-1111-1111-111111111111",
  title: "Existing wording",
  revision: 1,
  updatedAt: "2026-09-07T09:00:00Z",
  content: {
    schema: "docweave-template",
    version: 1,
    content: {
      type: "doc",
      content: [{
        type: "paragraph",
        content: [{ type: "text", text: "Existing content." }],
      }],
    },
  },
};

let dom: JSDOM;
let originalGlobals: Map<string, PropertyDescriptor | undefined>;

beforeEach(() => {
  dom = new JSDOM("<!doctype html><button id='return-focus'>Open</button>", {
    pretendToBeVisual: true,
  });
  originalGlobals = new Map(
    globalNames.map((name) => [
      name,
      Object.getOwnPropertyDescriptor(globalThis, name),
    ]),
  );
  const window = dom.window;
  const values: Record<(typeof globalNames)[number], unknown> = {
    window,
    document: window.document,
    navigator: window.navigator,
    Node: window.Node,
    Text: window.Text,
    Element: window.Element,
    HTMLElement: window.HTMLElement,
    MutationObserver: window.MutationObserver,
    DOMParser: window.DOMParser,
  };
  for (const name of globalNames) {
    Object.defineProperty(globalThis, name, {
      configurable: true,
      writable: true,
      value: values[name],
    });
  }
  dom.window.confirm = () => true;
});

afterEach(() => {
  dom.window.close();
  for (const [name, descriptor] of originalGlobals) {
    if (descriptor) Object.defineProperty(globalThis, name, descriptor);
    else Reflect.deleteProperty(globalThis, name);
  }
});

function button(text: string): HTMLButtonElement {
  const result = [...dom.window.document.querySelectorAll("button")]
    .find((candidate) => candidate.textContent === text);
  assert.ok(result, `Button not found: ${text}`);
  return result;
}

function tick(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

function provider(
  overrides: Partial<TemplateProvider>,
): TemplateProvider {
  return {
    async search() {
      return { items: [] };
    },
    async create(input) {
      return { ...template, id: "created", title: input.title };
    },
    async update(_id, input) {
      return { ...template, title: input.title };
    },
    async delete() {},
    ...overrides,
  };
}

describe("template dialog", () => {
  it("locks the draft while saving and restores editing after failure", async () => {
    let rejectSave!: (error: Error) => void;
    const dialog = createTemplateDialog({
      ownerDocument: dom.window.document,
      provider: provider({
        async search() { return { items: [template] }; },
        update: () => new Promise((_resolve, reject) => { rejectSave = reject; }),
      }),
      insert() {},
    });
    dialog.open();
    await tick();
    button("Existing wording").click();
    button("Edit").click();
    const title = dom.window.document.querySelector<HTMLInputElement>(
      ".docweave-templates__form input",
    )!;
    const editor = dom.window.document.querySelector<HTMLElement>(
      ".docweave-templates__editor .ProseMirror",
    )!;
    title.value = "Unsaved title";
    title.dispatchEvent(new dom.window.Event("input"));
    button("Save template").click();

    assert.equal(title.disabled, true);
    assert.equal(editor.getAttribute("contenteditable"), "false");
    assert.equal(editor.closest<HTMLElement>(".docweave-templates__editor")!.inert, true);
    // Formatting must not bypass the save lock, even if a command is dispatched.
    dom.window.document.querySelector<HTMLButtonElement>(
      '.docweave-templates__editor [aria-label="Numbered clause"]',
    )!.click();
    assert.equal(editor.querySelector("ol"), null);

    rejectSave(new Error("Save failed"));
    await tick();
    assert.equal(title.disabled, false);
    assert.equal(title.value, "Unsaved title");
    assert.equal(editor.getAttribute("contenteditable"), "true");
    assert.equal(editor.textContent, "Existing content.");
    assert.equal(editor.closest<HTMLElement>(".docweave-templates__editor")!.inert, false);
    assert.equal(button("Save template").disabled, false);
    dialog.destroy();
  });

  it("notifies insertion only after closing, and keeps failed insertions open", async () => {
    let shouldFail = true;
    let notifications = 0;
    const dialog = createTemplateDialog({
      ownerDocument: dom.window.document,
      provider: provider({ async search() { return { items: [template] }; } }),
      insert() { if (shouldFail) throw new Error("Cannot insert here"); },
      onInserted() {
        assert.equal(dom.window.document.querySelector("dialog")!.open, false);
        notifications++;
      },
    });
    dialog.open();
    await tick();
    button("Existing wording").click();
    button("Insert template").click();
    assert.equal(dom.window.document.querySelector("dialog")!.open, true);
    assert.equal(notifications, 0);
    shouldFail = false;
    button("Insert template").click();
    assert.equal(notifications, 1);
    dialog.destroy();
  });

  it("previews search content and edits the selected template without fetching it", async () => {
    let updatedId: string | undefined;
    let revision: number | undefined;
    const dialog = createTemplateDialog({
      ownerDocument: dom.window.document,
      provider: provider({
        async search() {
          return { items: [template] };
        },
        async update(id, input) {
          updatedId = id;
          revision = input.expectedRevision;
          return { ...template, ...input, revision: 2 };
        },
      }),
      insert() {},
    });

    dialog.open();
    await tick();
    button("Existing wording").click();
    assert.match(
      dom.window.document.querySelector(".docweave-templates__preview")!.textContent,
      /Existing content\./,
    );
    button("Edit").click();
    button("Save template").click();
    await tick();

    assert.equal(updatedId, template.id);
    assert.equal(revision, template.revision);
    dialog.destroy();
  });

  it("does not close a new draft when a cancelled save finishes", async () => {
    let resolveCreate: (value: Template) => void = () => {};
    const pendingCreate = new Promise<Template>((resolve) => {
      resolveCreate = resolve;
    });
    const dialog = createTemplateDialog({
      ownerDocument: dom.window.document,
      provider: provider({ create: () => pendingCreate }),
      insert() {},
    });

    dialog.open();
    await tick();
    button("Create template").click();
    let title = dom.window.document.querySelector<HTMLInputElement>(
      ".docweave-templates__form input",
    )!;
    title.value = "First draft";
    title.dispatchEvent(new dom.window.Event("input"));
    button("Save template").click();
    button("Cancel").click();
    button("Create template").click();
    title = dom.window.document.querySelector<HTMLInputElement>(
      ".docweave-templates__form input",
    )!;
    title.value = "Second draft";
    assert.equal(title.disabled, false);

    resolveCreate({ ...template, id: "created", title: "First draft" });
    await tick();

    assert.equal(title.value, "Second draft");
    assert.equal(
      title.closest<HTMLElement>(".docweave-templates__form")!.hidden,
      false,
    );
    dialog.destroy();
  });

  it("loads additional search results from the returned cursor", async () => {
    const cursors: Array<string | undefined> = [];
    let finishPage: (() => void) | undefined;
    const dialog = createTemplateDialog({
      ownerDocument: dom.window.document,
      provider: provider({
        async search(_query, cursor) {
          const page = cursor ? 1 : 0;
          cursors.push(cursor);
          if (cursor) {
            await new Promise<void>((resolve) => { finishPage = resolve; });
          }
          return {
            items: [{ ...template, id: String(page), title: `Page ${page}` }],
            nextCursor: page === 0 ? "next-page" : undefined,
          };
        },
      }),
      insert() {},
    });

    dialog.open();
    await tick();
    button("Load more").click();
    button("Page 0").click();
    assert.match(
      dom.window.document.querySelector(".docweave-templates__preview")!.textContent,
      /Existing content\./,
    );
    finishPage!();
    await tick();

    assert.deepEqual(cursors, [undefined, "next-page"]);
    assert.deepEqual(
      [...dom.window.document.querySelectorAll(
        ".docweave-templates__results .docweave-templates__result",
      )].map((result) => result.textContent),
      ["Page 0", "Page 1"],
    );
    dialog.destroy();
  });

  it("prevents duplicate deletes while a request is pending", async () => {
    let finishDelete: (() => void) | undefined;
    let deletes = 0;
    const dialog = createTemplateDialog({
      ownerDocument: dom.window.document,
      provider: provider({
        async search() { return { items: [template] }; },
        async delete() {
          deletes++;
          await new Promise<void>((resolve) => { finishDelete = resolve; });
        },
      }),
      insert() {},
    });

    dialog.open();
    await tick();
    button("Existing wording").click();
    const deleteButton = button("Delete");
    deleteButton.click();
    deleteButton.click();

    assert.equal(deleteButton.disabled, true);
    assert.equal(deletes, 1);

    finishDelete!();
    await tick();
    assert.equal(deleteButton.disabled, false);
    dialog.destroy();
  });
});
