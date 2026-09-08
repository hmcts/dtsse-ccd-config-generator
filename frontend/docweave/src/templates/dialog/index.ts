import {
  TemplateRequestError,
  type Template,
  type TemplateProvider,
} from "../provider.js";

import { createTemplateDraft, type TemplateDraft } from "./editor.js";
import { createTemplateDialogView } from "./view.js";

export interface TemplateDialog {
  open(): void;
  destroy(): void;
}

export interface TemplateDialogOptions {
  ownerDocument: Document;
  provider: TemplateProvider;
  insert(template: Template): void;
  onInserted?(): void;
}

const DISCARD_DRAFT = "Discard your unsaved template changes?";

function resultsShown(count: number): string {
  return `${count} template${count === 1 ? "" : "s"} shown`;
}

/**
 * A provider error carries copy written for the user. Anything else is a
 * transport or programming failure whose message must never reach a judge.
 */
function userMessage(error: unknown): string {
  if (!(error instanceof TemplateRequestError)) {
    return "Sorry, there is a problem with saved templates. Try again later.";
  }
  return error.status === 409
    ? "This template was changed elsewhere. Reload it before saving."
    : error.message;
}

export function createTemplateDialog(
  options: TemplateDialogOptions,
): TemplateDialog {
  const { ownerDocument: document, provider } = options;
  const view = createTemplateDialogView(document);
  const { dialog, search } = view;

  let templates: Template[] = [];
  let selected: Template | undefined;
  let draft: TemplateDraft | undefined;
  let pending: { request: number; selectedId?: string } | undefined;
  let searching = false;
  let stale = false;
  let busy = false;
  let version = 0;
  let destroyed = false;
  let returnFocus: HTMLElement | null = null;

  function render(): void {
    view.update({
      editing: draft !== undefined,
      busy,
      stale,
      searching,
      selectedIndex: selected ? templates.indexOf(selected) : -1,
    });
    draft?.setEditable(!busy);
  }

  function report(error: unknown): void {
    view.showStatus(userMessage(error), true);
  }

  function select(template?: Template): void {
    selected = undefined;
    try {
      view.showPreview(template);
      selected = template;
    } catch (error) {
      view.showPreview();
      report(error);
    }
    render();
    if (!selected) return;
    view.showStatus(`${resultsShown(templates.length)}. Selected: ${selected.title}`);
    view.scrollToResult(templates.indexOf(selected));
  }

  /** Abandons any in-flight or queued request owned by the previous state. */
  function supersede(): number {
    pending = undefined;
    return ++version;
  }

  function requestSearch(selectedId?: string): void {
    if (destroyed || draft || busy || !dialog.open) return;
    pending = { request: supersede(), selectedId };
    // The listed results now answer an older query. They stay on screen so the
    // list does not flicker, but they cannot be selected or acted on.
    selected = undefined;
    stale = true;
    render();
    if (!searching) void runSearch();
  }

  async function runSearch(): Promise<void> {
    searching = true;
    while (pending) {
      const { request, selectedId } = pending;
      pending = undefined;
      render();
      try {
        // Only one request is ever in flight. Keystrokes coalesce onto the
        // newest query, which throttles to the provider's own latency.
        const { items } = await provider.search(search.value);
        if (request !== version) continue;
        templates = items;
        stale = false;
        view.showResults(templates);
        view.showStatus(resultsShown(templates.length));
        select(selectedId === undefined
          ? templates[0]
          : templates.find((template) => template.id === selectedId));
      } catch (error) {
        if (request === version) report(error);
      }
    }
    searching = false;
    render();
  }

  function stopDraft(): void {
    supersede();
    busy = false;
    draft?.destroy();
    draft = undefined;
    view.showTitleError();
    render();
  }

  function edit(template?: Template): void {
    if (destroyed || draft || busy || !dialog.open) return;
    supersede();
    try {
      draft = createTemplateDraft(view.editorHost, view.title, template);
      view.showStatus("");
      view.showTitleError();
      render();
      view.title.focus();
    } catch (error) {
      render();
      report(error);
    }
  }

  function canDiscardDraft(): boolean {
    return !draft?.dirty || document.defaultView?.confirm(DISCARD_DRAFT) === true;
  }

  function close(restoreFocus = true): void {
    if (!canDiscardDraft()) return;
    stopDraft();
    dialog.close();
    if (restoreFocus) returnFocus?.focus();
  }

  function cancelDraft(): void {
    if (!draft || !canDiscardDraft()) return;
    stopDraft();
    search.focus();
    requestSearch();
  }

  async function save(): Promise<void> {
    if (!draft || busy) return;
    if (!view.title.value.trim()) {
      view.showTitleError("Enter a template title.");
      view.title.focus();
      return;
    }
    const current = draft;
    const request = supersede();
    busy = true;
    render();
    view.showStatus("Saving...");
    try {
      const input = current.read();
      const saved = current.template
        ? await provider.update(current.template.id, {
          ...input,
          expectedRevision: current.template.revision,
        })
        : await provider.create(input);
      if (request !== version) return;
      stopDraft();
      search.value = saved.title;
      search.focus();
      requestSearch(saved.id);
    } catch (error) {
      if (request !== version) return;
      busy = false;
      render();
      report(error);
    }
  }

  async function remove(template: Template): Promise<void> {
    if (draft || busy ||
      document.defaultView?.confirm(`Delete "${template.title}"?`) !== true) return;
    const request = supersede();
    busy = true;
    render();
    view.showStatus("Deleting...");
    try {
      await provider.delete(template.id, template.revision);
      if (request !== version) return;
      busy = false;
      requestSearch();
    } catch (error) {
      if (request !== version) return;
      busy = false;
      render();
      report(error);
    }
  }

  function insert(): void {
    if (destroyed || !dialog.open || draft || busy || stale || !selected) return;
    try {
      options.insert(selected);
      close(false);
      options.onInserted?.();
    } catch (error) {
      report(error);
    }
  }

  const actions: Record<string, (template?: Template) => void> = {
    close: () => close(),
    cancel: cancelDraft,
    create: () => edit(),
    select: (template) => select(template),
    edit: (template) => edit(template),
    delete: (template) => {
      if (template) void remove(template);
    },
    save: () => void save(),
    insert,
  };

  // One handler for the whole dialog, including rows rendered after each search.
  dialog.addEventListener("click", (event) => {
    if (destroyed || !dialog.open || !(event.target instanceof Element)) return;
    const button = event.target.closest<HTMLButtonElement>("button[data-action]");
    if (!button || button.disabled) return;
    const row = button.closest<HTMLLIElement>("li[data-index]");
    actions[button.dataset.action ?? ""]?.(
      row ? templates[Number(row.dataset.index)] : undefined,
    );
  });

  dialog.addEventListener("keydown", (event) => {
    if (destroyed || !dialog.open || draft || busy || stale || event.isComposing ||
      event.altKey || event.ctrlKey || event.metaKey || event.shiftKey ||
      !(event.target instanceof Element)) return;
    const row = event.target.matches('[data-action="select"]')
      ? event.target.closest<HTMLLIElement>("li[data-index]")
      : null;
    if (event.target !== search && !row) return;

    if (event.key === "Enter") {
      event.preventDefault();
      if (row) select(templates[Number(row.dataset.index)]);
      insert();
    } else if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      if (templates.length === 0) return;
      const from = row
        ? Number(row.dataset.index)
        : selected ? templates.indexOf(selected) : -1;
      const step = event.key === "ArrowDown" ? 1 : -1;
      const index = from < 0
        ? (step > 0 ? 0 : templates.length - 1)
        : (from + step + templates.length) % templates.length;
      select(templates[index]);
      if (row) view.focusResult(index);
    }
  });

  search.addEventListener("input", () => requestSearch());
  dialog.addEventListener("cancel", (event) => {
    event.preventDefault();
    close();
  });
  render();

  return {
    open(): void {
      if (destroyed || dialog.open) return;
      returnFocus = document.activeElement instanceof HTMLElement
        ? document.activeElement
        : null;
      supersede();
      selected = undefined;
      view.showPreview();
      dialog.showModal();
      search.focus();
      requestSearch();
    },
    destroy(): void {
      if (destroyed) return;
      destroyed = true;
      stopDraft();
      dialog.remove();
    },
  };
}
