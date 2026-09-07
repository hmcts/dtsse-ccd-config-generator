import assert from "node:assert/strict";
import { afterEach, beforeEach, describe, it } from "node:test";

import { JSDOM } from "jsdom";

import { createInMemoryTemplateProvider } from "../examples/court-order/template-provider.js";

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
  return dom.window.document.querySelector('[aria-pressed="true"]')?.getAttribute("aria-label");
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

  it("queues query changes behind pagination and discards the old page", async () => {
    const requests: Array<{ query: string; cursor?: string; resolve(value: { items: Template[]; nextCursor?: string }): void }> = [];
    const dialog = createTemplateDialog({
      ownerDocument: dom.window.document,
      provider: provider({ search(query, cursor) {
        return new Promise((resolve) => { requests.push({ query, cursor, resolve }); });
      } }),
      insert() {},
    });
    dialog.open();
    requests[0]!.resolve({ items: [template], nextCursor: "page-2" });
    await tick();
    button("Load more").click();
    assert.equal(selectedTitle(), template.title);
    assert.equal(button("Insert template").disabled, false);
    await searchFor("latest");
    assert.equal(requests.length, 2);
    assert.equal(button("Insert template").disabled, true);
    requests[1]!.resolve({ items: [{ ...template, title: "Old page" }] });
    await tick();
    assert.deepEqual(requests.map(({ query, cursor }) => [query, cursor]), [["", undefined], ["", "page-2"], ["latest", undefined]]);
    assert.doesNotMatch(dom.window.document.querySelector(".docweave-templates__results")!.textContent, /Old page/);
    requests[2]!.resolve({ items: [] });
    await tick();
    assert.match(dom.window.document.querySelector(".docweave-templates__results")!.textContent, /No templates found/);
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
        if (query === "failure") return Promise.reject(new Error("Search failed"));
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
      )].map((result) => result.getAttribute("aria-label")),
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
    assert.equal(button("Delete").disabled, false);
    dialog.destroy();
  });
});
