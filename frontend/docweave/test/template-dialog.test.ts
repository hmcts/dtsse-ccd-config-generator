import assert from "node:assert/strict";
import { afterEach, beforeEach, describe, it } from "node:test";

import { JSDOM } from "jsdom";

import { createInMemoryTemplateProvider } from "../examples/court-order/template-provider.js";

import { polyfillBrowserApis } from "./jsdom-polyfills.js";
import { createTemplateDialog } from "../src/templates/dialog/index.js";
import {
  parseTemplateFragment,
  TemplateRequestError,
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
  polyfillBrowserApis(dom.window);
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
    .find((candidate) => !candidate.closest("[hidden]") &&
      (candidate.getAttribute("aria-label") === text || candidate.textContent === text));
  assert.ok(result, `Button not found: ${text}`);
  return result;
}

function tick(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

function key(target: Element, value: string): void {
  target.dispatchEvent(new dom.window.KeyboardEvent("keydown", {
    key: value, bubbles: true, cancelable: true,
  }));
}

function searchInput(): HTMLInputElement {
  return dom.window.document.querySelector<HTMLInputElement>('input[type="search"]')!;
}

async function searchFor(query: string): Promise<void> {
  searchInput().value = query;
  searchInput().dispatchEvent(new dom.window.Event("input"));
  await tick();
}

function selectedTitle(): string | null | undefined {
  return dom.window.document.querySelector('[aria-current="true"]')?.getAttribute("aria-label");
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
  for (const action of ["create", "edit"] as const) {
    it(`keeps the saved template selected after ${action} changes the active search`, async () => {
      const store = createInMemoryTemplateProvider();
      await store.create({ title: "Possession", content: template.content });
      let inserted: Template | undefined;
      const dialog = createTemplateDialog({
        ownerDocument: dom.window.document, provider: store,
        insert(value) { inserted = value; },
      });
      dialog.open();
      await searchFor("Possession");
      button(action === "create" ? "Create template" : "Edit").click();
      const title = dom.window.document.querySelector<HTMLInputElement>(".docweave-templates__form input")!;
      title.value = "Costs";
      title.dispatchEvent(new dom.window.Event("input"));
      button("Save template").click();
      await tick();
      assert.equal(searchInput().value, "Costs");
      assert.equal(selectedTitle(), "Costs");
      assert.equal(dom.window.document.querySelector(".docweave-templates__preview h3")!.textContent, "Costs");
      key(searchInput(), "Enter");
      assert.equal(inserted?.id, (await store.search("Costs")).items[0]!.id);
      assert.equal(inserted?.title, "Costs");
      dialog.destroy();
    });
  }

  it("searches immediately when idle and sends only the latest queued query after success or failure", async () => {
    const requests: Array<{
      query: string;
      resolve(value: { items: Template[] }): void;
      reject(error: Error): void;
    }> = [];
    const dialog = createTemplateDialog({
      ownerDocument: dom.window.document,
      provider: provider({ search(query) {
        return new Promise((resolve, reject) => { requests.push({ query, resolve, reject }); });
      } }),
      insert() { assert.fail("Must not insert stale results"); },
    });
    dialog.open();
    assert.deepEqual(requests.map(({ query }) => query), [""]);
    requests[0]!.resolve({ items: [template] });
    await tick();
    const first = searchFor("w");
    assert.deepEqual(requests.map(({ query }) => query), ["", "w"]);
    await first;
    await searchFor("wi");
    await searchFor("wit");
    await searchFor("witness");
    assert.equal(requests.length, 2);
    requests[1]!.resolve({ items: [{ ...template, title: "Stale wording" }] });
    await tick();
    assert.deepEqual(requests.map(({ query }) => query), ["", "w", "witness"]);
    assert.equal(selectedTitle(), undefined);
    assert.doesNotMatch(dom.window.document.querySelector(".docweave-templates__results")!.textContent, /Stale wording/);
    await searchFor("pos");
    await searchFor("possession");
    requests[2]!.reject(new Error("Obsolete failure"));
    await tick();
    assert.deepEqual(requests.map(({ query }) => query), ["", "w", "witness", "possession"]);
    assert.doesNotMatch(dom.window.document.querySelector('[role="status"]')!.textContent, /Obsolete failure/);
    requests[3]!.resolve({ items: [{ ...template, title: "Possession" }] });
    await tick();
    assert.equal(selectedTitle(), "Possession");
    assert.equal(button("Insert template").disabled, false);
    dialog.destroy();
  });

  for (const action of ["close", "destroy", "edit"] as const) {
    it(`drops queued searches on ${action}`, async () => {
      const queries: string[] = [];
      let finish!: (value: { items: Template[] }) => void;
      const dialog = createTemplateDialog({
        ownerDocument: dom.window.document,
        provider: provider({ search(query) {
          queries.push(query);
          return new Promise((resolve) => { finish = resolve; });
        } }),
        insert() {},
      });
      dialog.open();
      await searchFor("queued");
      if (action === "close") button("Cancel").click();
      else if (action === "edit") button("Create template").click();
      else dialog.destroy();
      finish({ items: [template] });
      await tick();
      assert.deepEqual(queries, [""]);
      assert.equal(selectedTitle(), undefined);
      dialog.destroy();
    });
  }

  it("waits for an old request to finish when reopened, then searches again", async () => {
    const requests: Array<{ query: string; resolve(value: { items: Template[] }): void }> = [];
    const dialog = createTemplateDialog({
      ownerDocument: dom.window.document,
      provider: provider({ search(query) {
        return new Promise((resolve) => { requests.push({ query, resolve }); });
      } }),
      insert() {},
    });
    dialog.open();
    button("Cancel").click();
    dialog.open();
    await searchFor("reopened");
    assert.equal(requests.length, 1);
    requests[0]!.resolve({ items: [template] });
    await tick();
    assert.deepEqual(requests.map(({ query }) => query), ["", "reopened"]);
    assert.equal(selectedTitle(), undefined);
    requests[1]!.resolve({ items: [{ ...template, title: "Reopened result" }] });
    await tick();
    assert.equal(selectedTitle(), "Reopened result");
    dialog.destroy();
  });

  it("previews the first result, navigates with wrapping and inserts from search once", async () => {
    const store = createInMemoryTemplateProvider();
    await store.create({ title: "First wording", content: template.content });
    await store.create({ title: "Second wording", content: template.content });
    const matches = (await store.search("")).items;
    const inserted: Template[] = [];
    const dialog = createTemplateDialog({
      ownerDocument: dom.window.document, provider: store,
      insert(value) { inserted.push(value); },
    });
    dialog.open();
    await tick();
    const search = searchInput();
    assert.equal(dom.window.document.activeElement, search);
    assert.equal(selectedTitle(), matches[0]!.title);
    assert.match(dom.window.document.querySelector(".docweave-templates__preview")!.textContent, /Existing content/);
    assert.equal(dom.window.document.querySelector(".docweave-templates__snippet")!.textContent, "Existing content.");
    key(search, "ArrowUp");
    assert.equal(selectedTitle(), matches[1]!.title);
    key(search, "ArrowDown");
    assert.equal(selectedTitle(), matches[0]!.title);
    key(search, "ArrowDown");
    assert.equal(selectedTitle(), matches[1]!.title);
    assert.equal(dom.window.document.activeElement, search);
    key(search, "Enter");
    key(search, "Enter");
    assert.deepEqual(inserted.map((value) => value.id), [matches[1]!.id]);
    assert.equal(dom.window.document.querySelector("dialog")!.open, false);
    dialog.destroy();
  });

  it("moves focus between result buttons and inserts the focused result", async () => {
    const store = createInMemoryTemplateProvider();
    await store.create({ title: "First", content: template.content });
    await store.create({ title: "Second", content: template.content });
    let inserted: Template | undefined;
    const dialog = createTemplateDialog({
      ownerDocument: dom.window.document, provider: store,
      insert(value) { inserted = value; },
    });
    dialog.open();
    await tick();
    const results = [...dom.window.document.querySelectorAll<HTMLButtonElement>(".docweave-templates__result")];
    results[0]!.focus();
    key(results[0]!, "ArrowUp");
    assert.equal(dom.window.document.activeElement, results[1]);
    assert.equal(selectedTitle(), results[1]!.getAttribute("aria-label"));
    key(results[1]!, "Enter");
    assert.equal(inserted?.title, results[1]!.getAttribute("aria-label"));
    dialog.destroy();
  });

  it("clears selection during a new query and selects its first result or disables insertion", async () => {
    const store = createInMemoryTemplateProvider();
    await store.create({ title: "Witness statements", content: template.content });
    await store.create({ title: "Possession", content: template.content });
    let insertions = 0;
    const dialog = createTemplateDialog({
      ownerDocument: dom.window.document, provider: store,
      insert() { insertions++; },
    });
    dialog.open();
    await tick();
    const previousResults = dom.window.document.querySelector(".docweave-templates__results")!.textContent;
    const previousPreview = dom.window.document.querySelector(".docweave-templates__preview")!.textContent;
    const pending = searchFor("Witness");
    assert.equal(selectedTitle(), undefined);
    assert.equal(dom.window.document.querySelector(".docweave-templates__results")!.textContent, previousResults);
    assert.equal(dom.window.document.querySelector(".docweave-templates__preview")!.textContent, previousPreview);
    assert.equal(button("Insert template").disabled, true);
    assert.equal(button("Edit").disabled, true);
    button("Witness statements").click();
    key(searchInput(), "ArrowDown");
    key(searchInput(), "Enter");
    assert.equal(insertions, 0);
    await pending;
    assert.equal(selectedTitle(), "Witness statements");
    await searchFor("No matching wording");
    assert.equal(selectedTitle(), undefined);
    assert.equal(button("Insert template").disabled, true);
    key(searchInput(), "ArrowDown");
    key(searchInput(), "Enter");
    assert.equal(insertions, 0);
    assert.match(dom.window.document.querySelector(".docweave-templates__results")!.textContent, /No templates found/);
    dialog.destroy();
  });

  it("ignores stale and closed searches and keeps failures non-insertable", async () => {
    const pending = new Map<string, (value: { items: Template[] }) => void>();
    const dialog = createTemplateDialog({
      ownerDocument: dom.window.document,
      provider: provider({ search(query) {
        if (query === "failure") {
          return Promise.reject(new TemplateRequestError("Search failed", 500));
        }
        return new Promise((resolve) => { pending.set(query, resolve); });
      } }),
      insert() { assert.fail("Must not insert stale results"); },
    });
    dialog.open();
    await searchFor("new");
    assert.equal(pending.has("new"), false);
    pending.get("")!({ items: [template] });
    await tick();
    assert.equal(selectedTitle(), undefined);
    pending.get("new")!({ items: [{ ...template, title: "New result" }] });
    await tick();
    assert.equal(selectedTitle(), "New result");
    await searchFor("failure");
    assert.equal(button("Insert template").disabled, true);
    key(searchInput(), "Enter");
    assert.match(dom.window.document.querySelector('[role="status"]')!.textContent, /Search failed/);
    await searchFor("closed");
    button("Cancel").click();
    pending.get("closed")!({ items: [template] });
    await tick();
    assert.equal(selectedTitle(), undefined);
    assert.equal(dom.window.document.querySelector("dialog")!.open, false);
    dialog.destroy();
  });

  it("restores focus after cancel, close and Escape", async () => {
    const opener = button("Open");
    const dialog = createTemplateDialog({
      ownerDocument: dom.window.document, provider: createInMemoryTemplateProvider(), insert() {},
    });
    for (const action of ["Cancel", "Close saved templates", "Escape"]) {
      opener.focus();
      dialog.open();
      await tick();
      if (action === "Escape") {
        dom.window.document.querySelector("dialog")!.dispatchEvent(new dom.window.Event("cancel", { cancelable: true }));
      } else button(action).click();
      assert.equal(dom.window.document.querySelector("dialog")!.open, false);
      assert.equal(dom.window.document.activeElement, opener);
    }
    dialog.destroy();
  });

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
    assert.equal(editor.getAttribute("role"), "textbox");
    assert.equal(editor.getAttribute("aria-multiline"), "true");
    assert.equal(editor.getAttribute("aria-label"), "Template wording");
    title.value = "Unsaved title";
    title.dispatchEvent(new dom.window.Event("input"));
    button("Save template").click();

    assert.equal(title.disabled, true);
    assert.equal(editor.getAttribute("contenteditable"), "false");
    assert.equal(
      editor.closest(".docweave-templates__editor")!.hasAttribute("inert"),
      true,
    );
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
    assert.equal(
      editor.closest(".docweave-templates__editor")!.hasAttribute("inert"),
      false,
    );
    assert.equal(button("Save template").disabled, false);

    // The draft's toolbar is one Tab stop, reached from the wording by Alt+F10.
    editor.focus();
    const altF10 = new dom.window.KeyboardEvent("keydown", { key: "F10", altKey: true, bubbles: true, cancelable: true });
    editor.dispatchEvent(altF10);
    assert.equal(altF10.defaultPrevented, true);
    assert.ok(
      dom.window.document.activeElement?.closest(".docweave-templates__editor [role=toolbar]"),
      "focus moved into the draft toolbar",
    );
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

  it("offers a copy of someone else's template rather than an edit that cannot be saved", async () => {
    const theirs: Template = { ...template, id: "theirs", title: "Their wording", ownedByCurrentUser: false };
    const mine: Template = { ...template, title: "My wording", ownedByCurrentUser: true };
    const calls: string[] = [];
    let created: string | undefined;
    const dialog = createTemplateDialog({
      ownerDocument: dom.window.document,
      provider: provider({
        async search() {
          return { items: [theirs, mine] };
        },
        async create(input) {
          calls.push("create");
          created = input.title;
          return { ...template, id: "copied", title: input.title, ownedByCurrentUser: true };
        },
        async update(_id, input) {
          calls.push("update");
          return { ...template, title: input.title };
        },
      }),
      insert() {},
    });

    dialog.open();
    await tick();
    const labels = [...dom.window.document.querySelectorAll(".docweave-templates__result-actions button")]
      .map((action) => action.getAttribute("aria-label"));
    assert.deepEqual(labels, [
      "Copy Their wording to my templates",
      "Edit My wording",
      "Delete My wording",
    ]);

    button("Copy Their wording to my templates").click();
    const title = dom.window.document.querySelector<HTMLInputElement>(".docweave-templates__form input")!;
    assert.equal(title.value, "Their wording");
    assert.match(
      dom.window.document.querySelector(".docweave-templates__editor")!.textContent,
      /Existing content\./,
    );
    button("Save template").click();
    await tick();

    assert.deepEqual(calls, ["create"]);
    assert.equal(created, "Their wording");
    dialog.destroy();
  });

  it("finds a template by what it says, before and after it is edited", async () => {
    const store = createInMemoryTemplateProvider();
    const dialog = createTemplateDialog({
      ownerDocument: dom.window.document, provider: store, insert() {},
    });
    await store.create({ title: "Costs", content: template.content });

    dialog.open();
    await searchFor("existing content");
    assert.equal(selectedTitle(), "Costs");

    button("Edit Costs").click();
    button("Save template").click();
    await tick();
    await searchFor("existing content");
    assert.equal(selectedTitle(), "Costs");
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
    assert.equal(button("Delete").disabled, false);
    dialog.destroy();
  });

  it("renders provider titles as text, never as markup", async () => {
    const hostile = '<img src="x" onerror="alert(1)">Costs & "quoted"';
    const dialog = createTemplateDialog({
      ownerDocument: dom.window.document,
      provider: provider({
        async search() { return { items: [{ ...template, title: hostile }] }; },
      }),
      insert() {},
    });

    dialog.open();
    await tick();

    const results = dom.window.document.querySelector(".docweave-templates__results")!;
    assert.equal(results.querySelector("img"), null);
    assert.equal(selectedTitle(), hostile);
    assert.equal(
      results.querySelector(".docweave-templates__result span")!.textContent,
      hostile,
    );
    assert.equal(
      dom.window.document.querySelector(".docweave-templates__preview h3")!.textContent,
      hostile,
    );
    dialog.destroy();
  });

  it("keeps the result list to a single tab stop", async () => {
    const store = createInMemoryTemplateProvider();
    for (const title of ["Alpha", "Beta", "Gamma"]) {
      await store.create({ title, content: template.content });
    }
    const dialog = createTemplateDialog({
      ownerDocument: dom.window.document, provider: store, insert() {},
    });

    dialog.open();
    await tick();
    const tabbable = () => [...dom.window.document.querySelectorAll<HTMLElement>(
      ".docweave-templates__results button",
    )].filter((control) => control.tabIndex === 0);

    assert.equal(
      dom.window.document.querySelectorAll(".docweave-templates__results button").length,
      9,
      "three rows of select, edit and delete",
    );
    assert.equal(tabbable().length, 3, "only the active row is reachable by Tab");
    assert.equal(tabbable()[0]!.getAttribute("aria-label"), "Alpha");

    key(searchInput(), "ArrowDown");
    assert.equal(selectedTitle(), "Beta");
    assert.equal(tabbable().length, 3);
    assert.equal(tabbable()[0]!.getAttribute("aria-label"), "Beta");
    dialog.destroy();
  });

  it("reports an empty title on the field rather than the status region", async () => {
    const dialog = createTemplateDialog({
      ownerDocument: dom.window.document,
      provider: provider({}),
      insert() {},
    });

    dialog.open();
    await tick();
    button("Create template").click();
    const title = dom.window.document.querySelector<HTMLInputElement>(
      ".docweave-templates__form input",
    )!;
    title.value = "   ";
    button("Save template").click();

    const describedBy = title.getAttribute("aria-describedby")!;
    const error = dom.window.document.getElementById(describedBy)!;
    assert.equal(error.textContent, "Enter a template title.");
    assert.equal(title.getAttribute("aria-invalid"), "true");
    assert.equal(dom.window.document.activeElement, title);
    assert.equal(
      dom.window.document.querySelector<HTMLLabelElement>(
        `label[for="${title.id}"]`,
      )!.textContent,
      "Template title",
      "the error must sit outside the label, or it becomes part of the field's name",
    );
    assert.equal(
      dom.window.document.querySelector('[role="status"]')!.textContent,
      "",
    );

    title.value = "Costs";
    button("Save template").click();
    await tick();
    assert.equal(error.textContent, "");
    assert.equal(title.getAttribute("aria-invalid"), "false");
    dialog.destroy();
  });

  describe("a template with dates", () => {
    const dated = (content: unknown[]): Template => ({
      ...template,
      title: "Directions",
      content: {
        ...template.content,
        content: { type: "doc", content: [{ type: "paragraph", content }] },
      },
    });
    const date = (name: string, offset = 0, unit = "days", label: string | null = null) =>
      ({ type: "template_date", attrs: { name, label, offset, unit } });
    const directions = dated([
      { type: "text", text: "File by " },
      date("date", 14),
      { type: "text", text: ", serve by " },
      date("date2", 0, "days", "Service date"),
      { type: "text", text: ", heard " },
      date("date", 0, "days", "Hearing date"),
      { type: "text", text: ", made " },
      date("today"),
    ]);

    function open(value: Template) {
      const inserted: string[] = [];
      const dialog = createTemplateDialog({
        ownerDocument: dom.window.document,
        provider: provider({ async search() { return { items: [value] }; } }),
        insert(result) {
          // The editor refuses wording whose dates have not been written out.
          inserted.push(parseTemplateFragment(result.content, { dates: false })
            .document.textContent);
        },
        now: () => new Date(2026, 8, 20, 23, 30),
      });
      dialog.open();
      return { dialog, inserted };
    }

    function dateGroups(): HTMLFieldSetElement[] {
      return [...dom.window.document.querySelectorAll<HTMLFieldSetElement>(
        ".docweave-templates__dates:not([hidden]) fieldset",
      )];
    }

    function enter(group: HTMLFieldSetElement, day: string, month: string, year: string): void {
      [day, month, year].forEach((value, index) => {
        const input = group.querySelectorAll("input")[index]!;
        input.value = value;
        input.dispatchEvent(new dom.window.Event("input", { bubbles: true }));
      });
    }

    function typed(group: HTMLFieldSetElement): string[] {
      return [...group.querySelectorAll("input")].map((input) => input.value);
    }

    it("inserts straight away when it needs nothing but today", async () => {
      const { dialog, inserted } = open(dated([{ type: "text", text: "By " }, date("today", 2, "weeks")]));
      await tick();
      button("Insert template").click();

      assert.deepEqual(inserted, ["By 4 October 2026"]);
      assert.equal(dom.window.document.querySelector("dialog")!.open, false);
      dialog.destroy();
    });

    it("asks for each date once, and writes every date out from the answers", async () => {
      const { dialog, inserted } = open(directions);
      await tick();
      // The results and the preview show a date as the author wrote it.
      assert.match(
        dom.window.document.querySelector("[data-snippet]")!.textContent!,
        /^File by \[date\+14d\], serve by \[date2: Service date\]/u,
      );
      assert.equal(
        dom.window.document.querySelector(".docweave-templates__preview .docweave-template-date")!
          .textContent,
        "date+14d",
      );

      key(searchInput(), "Enter");
      assert.deepEqual(inserted, []);
      const groups = dateGroups();
      assert.deepEqual(
        groups.map((group) => group.querySelector("legend")!.textContent),
        ["Hearing date", "Service date"],
      );
      assert.equal(dom.window.document.activeElement, groups[0]!.querySelector("input"));

      enter(groups[0]!, "19", "9", "2026");
      enter(groups[1]!, "30", "9", "2026");

      // Enter in a date field inserts, as it does from the search box.
      key(groups[0]!.querySelector("input")!, "Enter");
      assert.deepEqual(inserted, [
        "File by 3 October 2026, serve by 30 September 2026, heard 19 September 2026, " +
        "made 20 September 2026",
      ]);
      assert.equal(dom.window.document.querySelector("dialog")!.open, false);
      dialog.destroy();
    });

    it("fills a date in from a pill, or from shorthand typed as the day, counted from today", async () => {
      const { dialog, inserted } = open(directions);
      await tick();
      button("Insert template").click();
      const [hearing, service] = dateGroups();
      const pills = hearing!.parentElement!.querySelector('[role="group"]')!;
      assert.equal(pills.getAttribute("aria-label"), "Quick date for Hearing date");
      assert.deepEqual(
        [...pills.querySelectorAll("button")].map((pill) => pill.textContent),
        ["Today", "14 days", "28 days", "42 days"],
      );

      // Enter on a pill presses the pill; it is not the Enter that inserts.
      enter(hearing!, "1", "1", "2026");
      enter(service!, "1", "1", "2026");
      key(pills.querySelector("button")!, "Enter");
      assert.deepEqual(inserted, []);
      assert.equal(dom.window.document.querySelector("dialog")!.open, true);
      enter(service!, "", "", "");

      [...pills.querySelectorAll("button")][2]!.click();
      assert.deepEqual(typed(hearing!), ["18", "10", "2026"]);
      assert.equal(dom.window.document.activeElement, hearing!.querySelector("input"));
      // The pill belongs to its own date alone.
      assert.deepEqual(typed(service!), ["", "", ""]);

      const day = service!.querySelector("input")!;
      for (const [shorthand, filled] of [
        ["3M", ["20", "12", "2026"]], ["2w", ["4", "10", "2026"]],
      ] as const) {
        day.value = shorthand;
        day.dispatchEvent(new dom.window.Event("input", { bubbles: true }));
        assert.deepEqual(typed(service!), filled);
      }

      button("Insert template").click();
      assert.deepEqual(inserted, [
        "File by 1 November 2026, serve by 4 October 2026, heard 18 October 2026, " +
        "made 20 September 2026",
      ]);
      dialog.destroy();
    });

    it("keeps the reader on a date that is missing or not real, and starts afresh after going back", async () => {
      const { dialog, inserted } = open(directions);
      await tick();
      button("Insert template").click();
      const [hearing, service] = dateGroups();
      enter(service!, "31", "9", "2026");
      button("Insert template").click();

      assert.deepEqual(inserted, []);
      const error = service!.querySelector(".docweave-templates__error")!;
      assert.equal(error.textContent, "Service date must be a real date");
      assert.equal(service!.getAttribute("aria-describedby"), error.id);
      assert.equal(service!.querySelector("input")!.getAttribute("aria-invalid"), "true");
      // Every date is needed, and focus goes to the first that is wrong.
      assert.equal(
        hearing!.querySelector(".docweave-templates__error")!.textContent,
        "Enter a date for Hearing date",
      );
      assert.equal(dom.window.document.activeElement, hearing!.querySelector("input"));

      button("Back").click();
      assert.deepEqual(dateGroups(), []);
      assert.equal(selectedTitle(), "Directions");
      assert.equal(dom.window.document.activeElement, searchInput());

      button("Insert template").click();
      assert.deepEqual(typed(dateGroups()[1]!), ["", "", ""]);
      enter(dateGroups()[0]!, "2", "10", "2026");
      enter(dateGroups()[1]!, "30", "9", "2026");
      button("Insert template").click();
      assert.match(inserted[0]!, /^File by 16 October 2026, serve by 30 September 2026/u);
      dialog.destroy();
    });

    it("has no axe violations while writing a date or asking for dates, with or without an error", async () => {
      const { dialog } = open(directions);
      await tick();
      const axe = (await import("axe-core")).default;
      // jsdom does no layout, so contrast cannot be measured here.
      const options = { rules: { "color-contrast": { enabled: false } } };
      const violations = async () =>
        (await axe.run(dom.window.document.querySelector("dialog")!, options)).violations
          .map((violation) => `${violation.id}: ${violation.nodes.map((node) => node.target.join(" ")).join(", ")}`);

      button("Create template").click();
      button("Insert date").click();
      assert.deepEqual(await violations(), []);
      button("Cancel").click();
      await tick();

      button("Insert template").click();
      enter(dateGroups()[0]!, "31", "2", "2026");
      button("Insert template").click();
      assert.deepEqual(await violations(), []);
      dialog.destroy();
    });

    it("stores the dates an author writes, gives them back as text, and will not save one it cannot read", async () => {
      const store = createInMemoryTemplateProvider();
      const dialog = createTemplateDialog({
        ownerDocument: dom.window.document, provider: store, insert() {},
      });
      const wording = () =>
        dom.window.document.querySelector(".docweave-templates__editor .ProseMirror")!.textContent;
      const stored = async () =>
        JSON.parse(JSON.stringify((await store.search("")).items[0]!.content.content))
          .content[0].content;
      dialog.open();
      await tick();
      button("Create template").click();
      dom.window.document.querySelector<HTMLInputElement>(".docweave-templates__form input")!
        .value = "Directions";
      button("Insert date").click();
      assert.equal(wording(), "[date: Hearing date]");
      button("Save template").click();
      await tick();
      assert.deepEqual(await stored(), [date("date", 0, "days", "Hearing date")]);

      button("Edit Directions").click();
      assert.equal(wording(), "[date: Hearing date]");
      dialog.destroy();

      // A draft too large to validate says so, rather than Save doing nothing.
      const nearlyTooLarge = dated([{ type: "text", text: "x".repeat(64 * 1024 - 200) }]);
      const large = createTemplateDialog({
        ownerDocument: dom.window.document,
        provider: provider({
          async search() { return { items: [nearlyTooLarge] }; },
          async update() { assert.fail("Must not save a draft that is too large"); },
        }),
        insert() {},
      });
      large.open();
      await tick();
      button("Edit Directions").click();
      for (let pressed = 0; pressed < 40; pressed++) button("Insert date").click();
      button("Save template").click();
      await tick();
      assert.equal(
        dom.window.document.querySelector("dialog[open] .docweave-templates__status")!.textContent,
        "Sorry, there is a problem with saved templates. Try again later.",
      );
      large.destroy();

      // A mistyped date is pointed out instead of being saved as wording.
      const mistyped = dated([{ type: "text", text: "By [date: Hearing date] or [date+3 fortnights]" }]);
      const checked = createTemplateDialog({
        ownerDocument: dom.window.document,
        provider: provider({
          async search() { return { items: [mistyped] }; },
          async update() { assert.fail("Must not save a date that cannot be read"); },
        }),
        insert() {},
      });
      checked.open();
      await tick();
      button("Edit Directions").click();
      button("Save template").click();
      await tick();
      const status = dom.window.document.querySelector("dialog[open] .docweave-templates__status")!;
      assert.match(status.textContent!, /^Check \[date\+3 fortnights\]\. Write a date like/u);
      assert.ok(status.classList.contains("docweave-templates__status--error"));
      checked.destroy();
    });
  });

  it("does not show transport failures to the user", async () => {
    const dialog = createTemplateDialog({
      ownerDocument: dom.window.document,
      provider: provider({
        search: () => Promise.reject(new TypeError("fetch failed")),
      }),
      insert() {},
    });

    dialog.open();
    await tick();

    const status = dom.window.document.querySelector('[role="status"]')!.textContent;
    assert.doesNotMatch(status, /fetch failed/);
    assert.match(status, /problem with saved templates/);
    dialog.destroy();
  });
});
