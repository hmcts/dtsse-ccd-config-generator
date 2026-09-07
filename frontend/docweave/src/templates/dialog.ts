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

export function createTemplateDialog(
  options: TemplateDialogOptions,
): TemplateDialog {
  const document = options.ownerDocument;
  const modal = document.createElement("dialog");
  modal.className = "docweave-templates";

  const panel = document.createElement("section");
  panel.className = "docweave-templates__dialog";
  panel.setAttribute("aria-labelledby", "docweave-templates-heading");

  const heading = document.createElement("h2");
  heading.id = "docweave-templates-heading";
  heading.textContent = "Saved templates";
  const close = button(document, "Close", "docweave-templates__close");
  close.setAttribute("aria-label", "Close saved templates");
  const header = document.createElement("div");
  header.className = "docweave-templates__header";
  header.append(heading, close);

  const status = document.createElement("p");
  status.className = "docweave-templates__status";
  status.setAttribute("role", "status");
  status.setAttribute("aria-live", "polite");

  const search = document.createElement("input");
  search.type = "search";
  search.className = "docweave-templates__search";
  search.placeholder = "Search saved templates";
  search.setAttribute("aria-label", "Search saved templates");
  const create = button(document, "Create template");
  const searchRow = document.createElement("div");
  searchRow.className = "docweave-templates__search-row";
  searchRow.append(search, create);

  const results = document.createElement("ul");
  results.className = "docweave-templates__results";
  const loadMore = button(
    document,
    "Load more",
    "docweave-templates__result",
  );
  loadMore.hidden = true;
  const preview = document.createElement("div");
  preview.className = "docweave-templates__preview";
  const content = document.createElement("div");
  content.className = "docweave-templates__content";
  const resultsPanel = document.createElement("div");
  resultsPanel.className = "docweave-templates__results-panel";
  resultsPanel.append(results, loadMore);
  content.append(resultsPanel, preview);

  const insert = button(document, "Insert template");
  const edit = button(document, "Edit");
  const remove = button(document, "Delete", "docweave-templates__button docweave-templates__button--warning");
  const actions = document.createElement("div");
  actions.className = "docweave-templates__actions";
  actions.hidden = true;
  actions.append(insert, edit, remove);

  const form = document.createElement("div");
  form.className = "docweave-templates__form";
  form.hidden = true;
  const titleLabel = document.createElement("label");
  titleLabel.textContent = "Template title";
  const title = document.createElement("input");
  title.type = "text";
  title.maxLength = 200;
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
  const cancel = button(document, "Cancel");
  const formActions = document.createElement("div");
  formActions.className = "docweave-templates__actions";
  formActions.append(save, cancel);
  form.append(titleLabel, editorShell, formActions);

  panel.append(header, status, searchRow, content, actions, form);
  modal.append(panel);
  document.body.append(modal);

  let selected: Template | undefined;
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
  let shownTemplates = 0;
  let debounce: ReturnType<typeof setTimeout> | undefined;
  let returnFocus: HTMLElement | null = null;

  function beginOperation(): number {
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
    insert.disabled = deleting;
    edit.disabled = deleting;
    remove.disabled = deleting;
    content.inert = deleting;
  }

  function renderPreview(template?: Template): void {
    preview.replaceChildren();
    selected = template;
    actions.hidden = !template;
    if (!template) return;

    const previewHeading = document.createElement("h3");
    previewHeading.textContent = template.title;
    const parsed = parseTemplateFragment(template.content);
    const body = DOMSerializer.fromSchema(editorSchema).serializeFragment(
      parsed.document.content,
      { document },
    );
    preview.append(previewHeading, body);
  }

  async function runSearch(cursor?: string): Promise<void> {
    if (mode.kind !== "browse") return;
    if (!cursor) {
      nextCursor = undefined;
      loadMore.hidden = true;
    }
    const generation = beginOperation();
    showStatus("Searching...");
    try {
      const response = await options.provider.search(search.value, cursor);
      if (!isCurrent(generation) || mode.kind !== "browse") return;
      if (!cursor) {
        results.replaceChildren();
        shownTemplates = 0;
      }
      if (!cursor && response.items.length === 0) {
        const item = document.createElement("li");
        item.textContent = "No templates found.";
        results.append(item);
      } else {
        for (const template of response.items) {
          const item = document.createElement("li");
          const select = button(
            document,
            template.title,
            "docweave-templates__result",
          );
          select.addEventListener("click", () => {
            try {
              renderPreview(template);
            } catch (error) {
              showStatus(errorMessage(error), true);
            }
          });
          item.append(select);
          results.append(item);
        }
      }
      shownTemplates += response.items.length;
      nextCursor = response.nextCursor ?? undefined;
      loadMore.hidden = !nextCursor;
      showStatus(
        `${shownTemplates} template${shownTemplates === 1 ? "" : "s"} shown`,
      );
    } catch (error) {
      if (!isCurrent(generation) || mode.kind !== "browse") return;
      showStatus(errorMessage(error), true);
    }
  }

  function beginEdit(template?: Template): void {
    clearTimeout(debounce);
    beginOperation();
    mode = template ? { kind: "edit", template } : { kind: "create" };
    form.hidden = false;
    content.hidden = true;
    actions.hidden = true;
    searchRow.hidden = true;
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
    actions.hidden = !selected;
    dirty = false;
  }

  function closeDialog(restoreFocus = true): void {
    if (dirty && !document.defaultView?.confirm(
      "Discard your unsaved template changes?",
    )) return;
    clearTimeout(debounce);
    endEdit();
    if (typeof modal.close === "function") modal.close();
    else modal.removeAttribute("open");
    if (restoreFocus) returnFocus?.focus();
  }

  close.addEventListener("click", () => closeDialog());
  cancel.addEventListener("click", () => {
    if (!dirty || document.defaultView?.confirm(
      "Discard your unsaved template changes?",
    )) endEdit();
  });
  create.addEventListener("click", () => beginEdit());
  edit.addEventListener("click", () => beginEdit(selected));
  insert.addEventListener("click", () => {
    if (!selected) return;
    try {
      options.insert(selected);
      dirty = false;
      closeDialog(false);
      options.onInserted?.();
    } catch (error) {
      showStatus(errorMessage(error), true);
    }
  });
  remove.addEventListener("click", async () => {
    if (mode.kind !== "browse" || !selected ||
      !document.defaultView?.confirm(`Delete "${selected.title}"?`)) return;
    const template = selected;
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
      await runSearch();
    } catch (error) {
      if (!isCurrent(generation) || mode.kind !== "deleting") return;
      mode = { kind: "browse" };
      setDeleting(false);
      showStatus(errorMessage(error), true);
    }
  });
  title.addEventListener("input", () => {
    dirty = true;
  });
  search.addEventListener("input", () => {
    clearTimeout(debounce);
    beginOperation();
    nextCursor = undefined;
    loadMore.hidden = true;
    debounce = setTimeout(() => void runSearch(), 250);
  });
  loadMore.addEventListener("click", () => {
    if (nextCursor) void runSearch(nextCursor);
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
      renderPreview(saved);
      await runSearch();
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
      returnFocus = document.activeElement instanceof HTMLElement
        ? document.activeElement
        : null;
      mode = { kind: "browse" };
      beginOperation();
      renderPreview();
      if (typeof modal.showModal === "function") modal.showModal();
      else modal.setAttribute("open", "");
      search.focus();
      void runSearch();
    },
    destroy(): void {
      clearTimeout(debounce);
      beginOperation();
      connectedEditorToolbar?.destroy();
      editorView?.destroy();
      modal.remove();
    },
  };
}
