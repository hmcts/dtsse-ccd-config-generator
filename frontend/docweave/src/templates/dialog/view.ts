import { DOMSerializer } from "prosemirror-model";

import { editorSchema } from "../../schema.js";
import {
  parseTemplateFragment,
  TEMPLATE_MAX_TITLE_LENGTH,
  type Template,
} from "../provider.js";

export interface DialogState {
  /** A draft is open, so the browse controls are replaced by the form. */
  editing: boolean;
  /** A save or delete is in flight and must not be interrupted. */
  busy: boolean;
  /** The listed results answer an older query, so they cannot be acted on. */
  stale: boolean;
  searching: boolean;
  selectedIndex: number;
}

let nextId = 0;

function find<E extends Element>(root: ParentNode, selector: string): E {
  const element = root.querySelector<E>(selector);
  if (!element) throw new Error(`Missing dialog element: ${selector}`);
  return element;
}

function snippet(template: Template): string {
  try {
    const { document } = parseTemplateFragment(template.content);
    return document.textBetween(0, document.content.size, " ")
      .replace(/\s+/gu, " ").trim();
  } catch {
    // A single broken template must not hide the rest of the results.
    return "Preview unavailable";
  }
}

export function createTemplateDialogView(document: Document) {
  const id = `docweave-templates-${++nextId}`;
  const dialog = document.createElement("dialog");
  dialog.className = "docweave-templates";
  dialog.setAttribute("aria-labelledby", `${id}-heading`);

  // Static chrome only. Everything that originates from a provider is written
  // through textContent or setAttribute below, so it is never parsed as markup.
  dialog.innerHTML = `
    <section class="docweave-templates__dialog">
      <div class="docweave-templates__header">
        <h2 id="${id}-heading" class="docweave-templates__visually-hidden">Insert template</h2>
        <button type="button" data-action="create" data-browse aria-label="Create template"
          class="docweave-templates__button docweave-templates__button--secondary docweave-templates__add">+</button>
        <button type="button" data-action="close" aria-label="Close saved templates"
          class="docweave-templates__close">&times;</button>
      </div>
      <p role="status" aria-live="polite" class="docweave-templates__status"></p>
      <div data-browse class="docweave-templates__search-row">
        <label for="${id}-search" class="docweave-templates__label">Search by keyword</label>
        <div id="${id}-hint" class="docweave-templates__hint">
          For example, witness statements, possession, transcript
        </div>
        <input type="search" id="${id}-search" class="docweave-templates__search"
          aria-describedby="${id}-hint" aria-controls="${id}-results">
      </div>
      <div data-browse class="docweave-templates__content">
        <div class="docweave-templates__results-panel">
          <ul id="${id}-results" class="docweave-templates__results" aria-label="Templates"></ul>
        </div>
        <div class="docweave-templates__preview" tabindex="0" role="region"
          aria-label="Template preview"></div>
      </div>
      <div data-browse class="docweave-templates__actions">
        <button type="button" data-action="insert" class="docweave-templates__button">Insert template</button>
        <button type="button" data-action="close"
          class="docweave-templates__button docweave-templates__button--secondary">Cancel</button>
      </div>
      <div class="docweave-templates__form" hidden>
        <div class="docweave-templates__field">
          <label for="${id}-title" class="docweave-templates__label">Template title</label>
          <span id="${id}-title-error" class="docweave-templates__error"></span>
          <input id="${id}-title" type="text" maxlength="${TEMPLATE_MAX_TITLE_LENGTH}"
            aria-describedby="${id}-title-error">
        </div>
        <div class="docweave-editor docweave-templates__editor"></div>
        <div class="docweave-templates__actions">
          <button type="button" data-action="save" class="docweave-templates__button">Save template</button>
          <button type="button" data-action="cancel"
            class="docweave-templates__button docweave-templates__button--secondary">Cancel</button>
        </div>
      </div>
      <template>
        <li class="docweave-templates__result-row">
          <button type="button" data-action="select" tabindex="-1"
            class="docweave-templates__result"><span data-title></span><span
            data-snippet class="docweave-templates__snippet"></span></button>
          <div class="docweave-templates__result-actions">
            <button type="button" data-action="edit" tabindex="-1"
              class="docweave-templates__result-action">Edit</button>
            <button type="button" data-action="delete" tabindex="-1"
              class="docweave-templates__result-action">Delete</button>
          </div>
        </li>
      </template>
    </section>
  `;

  const status = find<HTMLParagraphElement>(dialog, ".docweave-templates__status");
  const search = find<HTMLInputElement>(dialog, ".docweave-templates__search");
  const title = find<HTMLInputElement>(dialog, `#${id}-title`);
  const titleError = find<HTMLElement>(dialog, `#${id}-title-error`);
  const results = find<HTMLUListElement>(dialog, ".docweave-templates__results");
  const preview = find<HTMLElement>(dialog, ".docweave-templates__preview");
  const content = find<HTMLElement>(dialog, ".docweave-templates__content");
  const form = find<HTMLElement>(dialog, ".docweave-templates__form");
  const editorHost = find<HTMLElement>(dialog, ".docweave-templates__editor");
  const row = find<HTMLTemplateElement>(dialog, "template");
  document.body.append(dialog);

  function showStatus(message: string, error = false): void {
    status.textContent = message;
    status.classList.toggle("docweave-templates__status--error", error);
  }

  function showTitleError(message = ""): void {
    titleError.textContent = message;
    title.setAttribute("aria-invalid", String(message !== ""));
  }

  function showResults(templates: readonly Template[]): void {
    results.replaceChildren();
    if (templates.length === 0) {
      const empty = document.createElement("li");
      empty.className = "docweave-templates__empty";
      empty.textContent = "No templates found.";
      results.append(empty);
      return;
    }
    templates.forEach((template, index) => {
      const fragment = row.content.cloneNode(true) as DocumentFragment;
      find<HTMLLIElement>(fragment, "li").dataset.index = String(index);
      find(fragment, "[data-title]").textContent = template.title;
      find(fragment, "[data-snippet]").textContent = snippet(template);
      find(fragment, '[data-action="select"]')
        .setAttribute("aria-label", template.title);
      find(fragment, '[data-action="edit"]')
        .setAttribute("aria-label", `Edit ${template.title}`);
      find(fragment, '[data-action="delete"]')
        .setAttribute("aria-label", `Delete ${template.title}`);
      results.append(fragment);
    });
  }

  function showPreview(template?: Template): void {
    preview.replaceChildren();
    if (!template) return;
    const heading = document.createElement("h3");
    heading.textContent = template.title;
    const { document: parsed } = parseTemplateFragment(template.content);
    preview.append(heading, DOMSerializer.fromSchema(editorSchema)
      .serializeFragment(parsed.content, { document }));
    preview.scrollTop = 0;
  }

  function update(state: DialogState): void {
    for (const element of dialog.querySelectorAll<HTMLElement>("[data-browse]")) {
      element.hidden = state.editing;
    }
    form.hidden = !state.editing;
    search.disabled = state.busy;
    title.disabled = state.busy;
    editorHost.toggleAttribute("inert", state.busy);
    content.toggleAttribute("inert", state.busy);
    results.setAttribute("aria-busy", String(state.searching));

    for (const button of dialog.querySelectorAll<HTMLButtonElement>("button[data-action]")) {
      const action = button.dataset.action;
      // Close and cancel stay live so the dialog can never trap the user.
      button.disabled = action !== "close" && action !== "cancel" && (
        state.busy ||
        (state.stale && button.closest("li") !== null) ||
        (action === "insert" && state.selectedIndex < 0)
      );
    }

    // Roving tabindex: one tab stop for the whole list, arrows move within it.
    const active = state.selectedIndex < 0 ? 0 : state.selectedIndex;
    results.querySelectorAll<HTMLLIElement>("li[data-index]").forEach((item, index) => {
      const selected = index === state.selectedIndex;
      item.classList.toggle("docweave-templates__result--selected", selected);
      const select = find(item, '[data-action="select"]');
      if (selected) select.setAttribute("aria-current", "true");
      else select.removeAttribute("aria-current");
      for (const button of item.querySelectorAll("button")) {
        button.tabIndex = index === active ? 0 : -1;
      }
    });
  }

  function resultAt(index: number): HTMLLIElement | null {
    return results.querySelector<HTMLLIElement>(`li[data-index="${index}"]`);
  }

  function focusResult(index: number): void {
    resultAt(index)?.querySelector("button")?.focus({ preventScroll: true });
  }

  function scrollToResult(index: number): void {
    resultAt(index)?.scrollIntoView({ block: "nearest" });
  }

  return {
    dialog,
    search,
    title,
    editorHost,
    showStatus,
    showTitleError,
    showResults,
    showPreview,
    update,
    focusResult,
    scrollToResult,
  };
}
