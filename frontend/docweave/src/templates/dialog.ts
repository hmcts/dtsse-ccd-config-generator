import { toggleMark } from "prosemirror-commands";
import { history, redo, undo } from "prosemirror-history";
import { DOMSerializer } from "prosemirror-model";
import { wrapInList } from "prosemirror-schema-list";
import { EditorState } from "prosemirror-state";
import { EditorView } from "prosemirror-view";

import { createKeymapPlugins, indentListItem, outdentListItem } from "../keymap.js";
import {
  connectEditorToolbar,
  createEditorToolbar,
  type ConnectedEditorToolbar,
  type EditorToolbarCommands,
} from "../editor-toolbar.js";
import { editorSchema } from "../schema.js";
import {
  createTemplateFragment,
  parseTemplateFragment,
  TEMPLATE_MAX_TITLE_LENGTH,
  TemplateRequestError,
  type Template,
  type TemplateProvider,
} from "./provider.js";

export interface TemplateDialog {
  open(): void;
  destroy(): void;
}

interface TemplateDialogOptions {
  ownerDocument: Document;
  provider: TemplateProvider;
  insert(template: Template): void;
  onInserted?(): void;
}

function button(
  document: Document,
  text: string,
  className = "docweave-templates__button",
): HTMLButtonElement {
  const element = document.createElement("button");
  element.type = "button";
  element.className = className;
  element.textContent = text;
  return element;
}

function emptyTemplateDocument() {
  return editorSchema.nodes.doc!.create(
    null,
    editorSchema.nodes.paragraph!.create(),
  );
}

function createTemplateEditor(
  mount: HTMLElement,
  template?: Template,
): EditorView {
  const document = template
    ? parseTemplateFragment(template.content).document
    : emptyTemplateDocument();
  return new EditorView(mount, {
    state: EditorState.create({
      schema: editorSchema,
      doc: document,
      plugins: [
        ...createKeymapPlugins(),
        history(),
      ],
    }),
  });
}

const templateEditorCommands = {
  undo,
  redo,
  bold: toggleMark(editorSchema.marks.strong!),
  italic: toggleMark(editorSchema.marks.em!),
  numbered: wrapInList(editorSchema.nodes.ordered_list!),
  outdent: outdentListItem,
  indent: indentListItem,
} satisfies EditorToolbarCommands;

function errorMessage(error: unknown): string {
  if (error instanceof TemplateRequestError && error.status === 409) {
    return "This template was changed elsewhere. Reload it before saving.";
  }
  return error instanceof Error ? error.message : "The template request failed.";
}

let dialogId = 0;

export function createTemplateDialog(
  options: TemplateDialogOptions,
): TemplateDialog {
  const document = options.ownerDocument;
  const modal = document.createElement("dialog");
  modal.className = "docweave-templates";
  const id = `docweave-templates-${++dialogId}`;
  modal.setAttribute("aria-labelledby", `${id}-heading`);

  const panel = document.createElement("section");
  panel.className = "docweave-templates__dialog";

  const heading = document.createElement("h2");
  heading.id = `${id}-heading`;
  heading.className = "docweave-templates__visually-hidden";
  heading.textContent = "Insert template";
  const create = button(document, "+", "docweave-templates__button docweave-templates__button--secondary docweave-templates__add");
  create.setAttribute("aria-label", "Create template");
  const close = button(document, "×", "docweave-templates__close");
  close.setAttribute("aria-label", "Close saved templates");
  const header = document.createElement("div");
  header.className = "docweave-templates__header";
  header.append(heading, create, close);

  const status = document.createElement("p");
  status.className = "docweave-templates__status";
  status.setAttribute("role", "status");
  status.setAttribute("aria-live", "polite");

  const search = document.createElement("input");
  search.type = "search";
  search.className = "docweave-templates__search";
  search.id = `${id}-search`;
  search.setAttribute("aria-describedby", `${id}-hint`);
  search.setAttribute("aria-controls", `${id}-results`);
  const searchLabel = document.createElement("label");
  searchLabel.htmlFor = search.id;
  searchLabel.className = "docweave-templates__label";
  searchLabel.textContent = "Search by keyword";
  const hint = document.createElement("div");
  hint.id = `${id}-hint`;
  hint.className = "docweave-templates__hint";
  hint.textContent = "For example, witness statements, possession, transcript";
  const searchRow = document.createElement("div");
  searchRow.className = "docweave-templates__search-row";
  searchRow.append(searchLabel, hint, search);

  const results = document.createElement("ul");
  results.className = "docweave-templates__results";
  results.id = `${id}-results`;
  results.setAttribute("aria-label", "Templates");
  const loadMore = button(
    document,
    "Load more",
    "docweave-templates__load-more",
  );
  loadMore.hidden = true;
  const preview = document.createElement("div");
  preview.className = "docweave-templates__preview";
  preview.tabIndex = 0;
  preview.setAttribute("role", "region");
  preview.setAttribute("aria-label", "Template preview");
  const content = document.createElement("div");
  content.className = "docweave-templates__content";
  const resultsPanel = document.createElement("div");
  resultsPanel.className = "docweave-templates__results-panel";
  resultsPanel.append(results, loadMore);
  content.append(resultsPanel, preview);

  const insert = button(document, "Insert template");
  const dismiss = button(document, "Cancel", "docweave-templates__button docweave-templates__button--secondary");
  const actions = document.createElement("div");
  actions.className = "docweave-templates__actions";
  actions.append(insert, dismiss);

  const form = document.createElement("div");
  form.className = "docweave-templates__form";
  form.hidden = true;
  const titleLabel = document.createElement("label");
  titleLabel.textContent = "Template title";
  const title = document.createElement("input");
  title.type = "text";
  title.maxLength = TEMPLATE_MAX_TITLE_LENGTH;
  titleLabel.append(title);
  const editorShell = document.createElement("div");
  editorShell.className = "docweave-editor docweave-templates__editor";
  const editorToolbar = createEditorToolbar(
    document,
    "Template editor formatting",
  );
  const editorMount = document.createElement("div");
  editorMount.className = "docweave-editor__surface";
  editorShell.append(editorToolbar, editorMount);
  const save = button(document, "Save template");
  const cancel = button(document, "Cancel", "docweave-templates__button docweave-templates__button--secondary");
  const formActions = document.createElement("div");
  formActions.className = "docweave-templates__actions";
  formActions.append(save, cancel);
  form.append(titleLabel, editorShell, formActions);

  panel.append(header, status, searchRow, content, actions, form);
  modal.append(panel);
  document.body.append(modal);

  let selected: Template | undefined;
  let rows: Array<{ template: Template; item: HTMLLIElement; select: HTMLButtonElement }> = [];
  type Draft = { kind: "create" } | { kind: "edit"; template: Template };
  type Mode = { kind: "browse" } | Draft |
    { kind: "saving"; draft: Draft } |
    { kind: "deleting" };
  let mode: Mode = { kind: "browse" };
  let editorView: EditorView | undefined;
  let connectedEditorToolbar: ConnectedEditorToolbar | undefined;
  let dirty = false;
  let operationGeneration = 0;
  let nextCursor: string | undefined;
  type SearchRequest = { query: string; cursor?: string; selectedId?: string; generation: number };
  let searchPending = false;
  let queuedSearch: SearchRequest | undefined;
  let resultsCurrent = false;
  let returnFocus: HTMLElement | null = null;

  function beginOperation(): number {
    queuedSearch = undefined;
    return ++operationGeneration;
  }

  function isCurrent(generation: number): boolean {
    return generation === operationGeneration;
  }

  function showStatus(message: string, error = false): void {
    status.textContent = message;
    status.classList.toggle("docweave-templates__status--error", error);
  }

  function setSaving(saving: boolean): void {
    save.disabled = saving;
    title.disabled = saving;
    editorShell.inert = saving;
    editorView?.setProps({ editable: () => !saving });
  }

  function setDeleting(deleting: boolean): void {
    search.disabled = deleting;
    create.disabled = deleting;
    loadMore.disabled = deleting;
    insert.disabled = deleting || !resultsCurrent || !selected;
    results.querySelectorAll("button").forEach((control) => {
      control.disabled = deleting || !resultsCurrent;
    });
    content.inert = deleting;
  }

  function renderPreview(template?: Template): void {
    preview.replaceChildren();
    selected = undefined;
    insert.disabled = true;
    rows.forEach((row) => {
      const active = row.template.id === template?.id;
      row.item.classList.toggle("docweave-templates__result--selected", active);
      row.select.setAttribute("aria-pressed", String(active));
    });
    if (!template) return;

    const previewHeading = document.createElement("h3");
    previewHeading.textContent = template.title;
    const parsed = parseTemplateFragment(template.content);
    const body = DOMSerializer.fromSchema(editorSchema).serializeFragment(
      parsed.document.content,
      { document },
    );
    preview.append(previewHeading, body);
    preview.scrollTop = 0;
    selected = template;
    insert.disabled = mode.kind !== "browse";
  }

  function selectTemplate(template: Template): void {
    if (mode.kind !== "browse" || !resultsCurrent) return;
    try {
      renderPreview(template);
      showStatus(`${rows.length} templates shown. Selected: ${template.title}`);
      rows.find((row) => row.template.id === template.id)
        ?.item.scrollIntoView?.({ block: "nearest" });
    } catch (error) {
      showStatus(errorMessage(error), true);
    }
  }

  function clearResults(): void {
    rows = [];
    results.replaceChildren();
    renderPreview();
  }

  function runSearch(cursor?: string, selectedId?: string): void {
    if (mode.kind !== "browse" || !modal.open) return;
    const generation = beginOperation();
    if (!cursor) {
      // Keep the previous page visible, but never act on a stale result.
      resultsCurrent = false;
      selected = undefined;
      insert.disabled = true;
      nextCursor = undefined;
      loadMore.hidden = true;
      rows.forEach(({ item, select }) => {
        item.classList.remove("docweave-templates__result--selected");
        select.setAttribute("aria-pressed", "false");
      });
      results.querySelectorAll("button").forEach((control) => {
        control.disabled = true;
      });
    }
    loadMore.disabled = true;
    showStatus("Searching...");
    const request = { query: search.value, cursor, selectedId, generation };
    if (searchPending) {
      queuedSearch = request;
      return;
    }
    void performSearch(request);
  }

  async function performSearch({ query, cursor, selectedId, generation }: SearchRequest): Promise<void> {
    searchPending = true;
    try {
      const response = await options.provider.search(query, cursor);
      if (!isCurrent(generation) || mode.kind !== "browse") return;
      if (!cursor) clearResults();
      resultsCurrent = true;
      if (!cursor && response.items.length === 0) {
        const item = document.createElement("li");
        item.className = "docweave-templates__empty";
        item.textContent = "No templates found.";
        results.append(item);
      } else {
        for (const template of response.items) {
          const item = document.createElement("li");
          item.className = "docweave-templates__result-row";
          const select = button(
            document,
            template.title,
            "docweave-templates__result",
          );
          select.setAttribute("aria-label", template.title);
          select.setAttribute("aria-pressed", "false");
          const snippet = document.createElement("span");
          snippet.className = "docweave-templates__snippet";
          try {
            const parsed = parseTemplateFragment(template.content).document;
            snippet.textContent = parsed.textBetween(0, parsed.content.size, " ")
              .replace(/\s+/gu, " ").trim();
          } catch {
            snippet.textContent = "Preview unavailable";
          }
          select.append(snippet);
          select.addEventListener("click", () => selectTemplate(template));
          const rowActions = document.createElement("div");
          rowActions.className = "docweave-templates__result-actions";
          const edit = button(document, "Edit", "docweave-templates__result-action");
          const remove = button(document, "Delete", "docweave-templates__result-action");
          edit.setAttribute("aria-label", `Edit ${template.title}`);
          remove.setAttribute("aria-label", `Delete ${template.title}`);
          edit.addEventListener("click", () => beginEdit(template));
          remove.addEventListener("click", () => void deleteTemplate(template));
          rowActions.append(edit, remove);
          item.append(select, rowActions);
          rows.push({ template, item, select });
          results.append(item);
        }
      }
      nextCursor = response.nextCursor ?? undefined;
      loadMore.hidden = !nextCursor;
      loadMore.disabled = false;
      showStatus(`${rows.length} template${rows.length === 1 ? "" : "s"} shown`);
      const initial = selectedId ? rows.find((row) => row.template.id === selectedId) : rows[0];
      if (!cursor && initial) selectTemplate(initial.template);
    } catch (error) {
      if (!isCurrent(generation) || mode.kind !== "browse") return;
      loadMore.disabled = false;
      showStatus(errorMessage(error), true);
    } finally {
      searchPending = false;
      const next = queuedSearch;
      queuedSearch = undefined;
      if (next && isCurrent(next.generation) && mode.kind === "browse" && modal.open) {
        void performSearch(next);
      }
    }
  }

  function beginEdit(template?: Template): void {
    beginOperation();
    mode = template ? { kind: "edit", template } : { kind: "create" };
    form.hidden = false;
    content.hidden = true;
    actions.hidden = true;
    searchRow.hidden = true;
    create.hidden = true;
    showStatus("");
    title.value = template?.title ?? "";
    connectedEditorToolbar?.destroy();
    editorView?.destroy();
    editorMount.replaceChildren();
    editorView = createTemplateEditor(editorMount, template);
    setSaving(false);
    connectedEditorToolbar = connectEditorToolbar(
      editorToolbar,
      editorView,
      templateEditorCommands,
    );
    const originalDispatch = editorView.dispatch.bind(editorView);
    editorView.dispatch = (transaction) => {
      if (mode.kind === "saving") return;
      originalDispatch(transaction);
      connectedEditorToolbar?.update();
      if (transaction.docChanged) dirty = true;
    };
    dirty = false;
    title.focus();
  }

  function endEdit(): void {
    beginOperation();
    mode = { kind: "browse" };
    setSaving(false);
    setDeleting(false);
    connectedEditorToolbar?.destroy();
    connectedEditorToolbar = undefined;
    editorView?.destroy();
    editorView = undefined;
    editorMount.replaceChildren();
    form.hidden = true;
    content.hidden = false;
    searchRow.hidden = false;
    actions.hidden = false;
    create.hidden = false;
    dirty = false;
  }

  function closeDialog(restoreFocus = true): void {
    if (dirty && !document.defaultView?.confirm(
      "Discard your unsaved template changes?",
    )) return;
    endEdit();
    if (typeof modal.close === "function") modal.close();
    else modal.removeAttribute("open");
    if (restoreFocus) returnFocus?.focus();
  }

  close.addEventListener("click", () => closeDialog());
  dismiss.addEventListener("click", () => closeDialog());
  cancel.addEventListener("click", () => {
    if (!dirty || document.defaultView?.confirm(
      "Discard your unsaved template changes?",
    )) {
      endEdit();
      search.focus();
      runSearch();
    }
  });
  create.addEventListener("click", () => beginEdit());
  function insertSelected(): void {
    if (!modal.open || mode.kind !== "browse" || !selected || insert.disabled) return;
    try {
      options.insert(selected);
      dirty = false;
      closeDialog(false);
      options.onInserted?.();
    } catch (error) {
      showStatus(errorMessage(error), true);
    }
  }
  insert.addEventListener("click", insertSelected);

  function navigate(event: KeyboardEvent): void {
    if (mode.kind !== "browse" || !resultsCurrent || event.isComposing || event.altKey ||
      event.ctrlKey || event.metaKey || event.shiftKey) return;
    const focusedRow = rows.find((row) => row.select === event.target);
    if (event.target !== search && !focusedRow) return;
    if (event.key === "Enter") {
      event.preventDefault();
      if (focusedRow) selectTemplate(focusedRow.template);
      insertSelected();
    } else if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      if (!rows.length) return;
      const index = rows.findIndex((row) => row.template.id ===
        (focusedRow?.template.id ?? selected?.id));
      const delta = event.key === "ArrowDown" ? 1 : -1;
      const row = rows[(index + delta + rows.length) % rows.length]!;
      selectTemplate(row.template);
      if (focusedRow) row.select.focus({ preventScroll: true });
    }
  }
  search.addEventListener("keydown", navigate);
  results.addEventListener("keydown", navigate);

  async function deleteTemplate(template: Template): Promise<void> {
    if (mode.kind !== "browse" ||
      !document.defaultView?.confirm(`Delete "${template.title}"?`)) return;
    mode = { kind: "deleting" };
    const generation = beginOperation();
    setDeleting(true);
    showStatus("Deleting...");
    try {
      await options.provider.delete(template.id, template.revision);
      if (!isCurrent(generation) || mode.kind !== "deleting") return;
      mode = { kind: "browse" };
      setDeleting(false);
      renderPreview();
      runSearch();
    } catch (error) {
      if (!isCurrent(generation) || mode.kind !== "deleting") return;
      mode = { kind: "browse" };
      setDeleting(false);
      showStatus(errorMessage(error), true);
    }
  }
  title.addEventListener("input", () => {
    dirty = true;
  });
  search.addEventListener("input", () => {
    runSearch();
  });
  loadMore.addEventListener("click", () => {
    if (nextCursor) runSearch(nextCursor);
  });
  save.addEventListener("click", async () => {
    if (!editorView || (mode.kind !== "create" && mode.kind !== "edit")) {
      return;
    }
    if (!title.value.trim()) {
      showStatus("Enter a template title.", true);
      return;
    }
    const draft = mode;
    mode = { kind: "saving", draft };
    const generation = beginOperation();
    setSaving(true);
    showStatus("Saving...");
    try {
      const input = {
        title: title.value.trim(),
        content: createTemplateFragment(editorView.state.doc),
      };
      const saved = draft.kind === "edit"
        ? await options.provider.update(draft.template.id, {
          ...input,
          expectedRevision: draft.template.revision,
        })
        : await options.provider.create(input);
      if (!isCurrent(generation) || mode.kind !== "saving") return;
      dirty = false;
      endEdit();
      search.value = saved.title;
      search.focus();
      runSearch(undefined, saved.id);
    } catch (error) {
      if (!isCurrent(generation) || mode.kind !== "saving") return;
      mode = draft;
      setSaving(false);
      showStatus(errorMessage(error), true);
    }
  });

  modal.addEventListener("cancel", (event) => {
    event.preventDefault();
    closeDialog();
  });

  return {
    open(): void {
      if (modal.open) return;
      returnFocus = document.activeElement instanceof HTMLElement
        ? document.activeElement
        : null;
      mode = { kind: "browse" };
      beginOperation();
      renderPreview();
      if (typeof modal.showModal === "function") modal.showModal();
      else modal.setAttribute("open", "");
      search.focus();
      runSearch();
    },
    destroy(): void {
      beginOperation();
      connectedEditorToolbar?.destroy();
      editorView?.destroy();
      modal.remove();
    },
  };
}
