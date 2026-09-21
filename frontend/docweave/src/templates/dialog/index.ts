import {
  TemplateRequestError,
  type Template,
  type TemplateProvider,
} from "../provider.js";

import { isElement, isFocusable } from "../../dom.js";
import {
  addToDate,
  calendarDay,
  parseTypedDate,
  resolveTemplateDates,
  templateDateLabels,
  templateDateVariables,
} from "../dates.js";
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
  /** The current time, for working out "today". Tests pass a fixed one. */
  now?(): Date;
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
  /** The template whose dates the reader is being asked for, and their labels. */
  let dating: { template: Template; variables: string[] } | undefined;
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
      dating: dating !== undefined,
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
    if (destroyed || draft || dating || busy || !dialog.open) return;
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

  function edit(template?: Template, copy = false): void {
    if (destroyed || draft || busy || !dialog.open) return;
    supersede();
    try {
      draft = createTemplateDraft(view.editorHost, view.title, template, copy);
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
    dating = undefined;
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
    let dateProblem: string | undefined;
    try {
      // Reading the dates validates the draft, which can fail: it may be too large.
      dateProblem = draft.dateProblem;
    } catch (error) {
      report(error);
      return;
    }
    if (dateProblem) {
      view.showStatus(dateProblem, true);
      draft.focus();
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

  /** What the reader is asked for. Saving insists on a label, so the name is a last resort. */
  function dateLabel(content: unknown, name: string): string {
    return templateDateLabels(content).get(name) ?? name;
  }

  function today(): Date {
    const now = options.now?.() ?? new Date();
    return calendarDay(now.getFullYear(), now.getMonth() + 1, now.getDate());
  }

  /**
   * Quick dates, counted from today: a pill, or shorthand such as 2w or 3m
   * typed into a day field.
   */
  function quickDate(target: EventTarget | null, typed: boolean): void {
    if (!dating || !isElement(target)) return;
    const group = target.closest(".docweave-templates__date");
    const index = group ? [...view.dateFields.children].indexOf(group) : -1;
    if (index < 0) return;
    if (!typed) {
      const days = target.closest<HTMLElement>("[data-date-pill-days]")?.dataset.datePillDays;
      if (days === undefined) return;
      view.setDate(index, addToDate(today(), Number(days), "days"));
      view.focusDate(index);
      return;
    }
    const shorthand = (target as HTMLElement).dataset.datePart === "day"
      ? /^(\d{1,4})\s*([dwm])$/iu.exec((target as HTMLInputElement).value.trim())
      : null;
    if (!shorthand) return;
    const unit = ({ d: "days", w: "weeks", m: "months" } as const)[
      shorthand[2]!.toLowerCase() as "d" | "w" | "m"
    ];
    view.setDate(index, addToDate(today(), Number(shorthand[1]), unit));
  }

  function finishInsert(template: Template, values: ReadonlyMap<string, Date>): void {
    options.insert({
      ...template,
      content: {
        ...template.content,
        content: resolveTemplateDates(template.content.content, values, today()),
      },
    });
    close(false);
    options.onInserted?.();
  }

  function insert(): void {
    if (destroyed || !dialog.open || draft || dating || busy || stale || !selected) return;
    try {
      const { content } = selected.content;
      const variables = templateDateVariables(content);
      if (variables.length === 0) {
        finishInsert(selected, new Map());
        return;
      }
      dating = { template: selected, variables };
      view.showDates(variables.map((name) => dateLabel(content, name)));
      view.showStatus("");
      render();
      view.focusDate(0);
    } catch (error) {
      report(error);
    }
  }

  function insertDates(): void {
    if (destroyed || !dialog.open || !dating) return;
    const { content } = dating.template.content;
    const values = new Map<string, Date>();
    let firstError = -1;
    view.readDates().forEach((parts, index) => {
      const name = dating!.variables[index]!;
      const date = parseTypedDate(...parts);
      if (date) values.set(name, date);
      else if (firstError < 0) firstError = index;
      view.showDateError(
        index,
        date
          ? ""
          : parts.every((part) => part === "")
          ? `Enter a date for ${dateLabel(content, name)}`
          : `${dateLabel(content, name)} must be a real date`,
      );
    });
    if (firstError >= 0) {
      view.focusDate(firstError);
      return;
    }
    try {
      finishInsert(dating.template, values);
    } catch (error) {
      report(error);
    }
  }

  function leaveDates(): void {
    if (!dating) return;
    dating = undefined;
    render();
    search.focus();
  }

  const actions: Record<string, (template?: Template) => void> = {
    close: () => close(),
    cancel: cancelDraft,
    create: () => edit(),
    select: (template) => select(template),
    edit: (template) => edit(template),
    copy: (template) => edit(template, true),
    delete: (template) => {
      if (template) void remove(template);
    },
    save: () => void save(),
    insert,
    "insert-dates": insertDates,
    back: leaveDates,
  };

  // One handler for the whole dialog, including rows rendered after each search.
  dialog.addEventListener("click", (event) => {
    if (destroyed || !dialog.open || !isElement(event.target)) return;
    const button = event.target.closest<HTMLButtonElement>("button[data-action]");
    if (!button || button.disabled) return;
    const row = button.closest<HTMLLIElement>("li[data-index]");
    actions[button.dataset.action ?? ""]?.(
      row ? templates[Number(row.dataset.index)] : undefined,
    );
  });

  view.dateFields.addEventListener("input", (event) => quickDate(event.target, true));
  view.dateFields.addEventListener("click", (event) => quickDate(event.target, false));
  view.dateFields.addEventListener("keydown", (event) => {
    // Only from a date's own fields: Enter on a pill is that pill being pressed.
    if (event.key !== "Enter" || event.isComposing || !isElement(event.target) ||
      !event.target.matches("input[data-date-part]")) return;
    event.preventDefault();
    insertDates();
  });

  dialog.addEventListener("keydown", (event) => {
    if (destroyed || !dialog.open || draft || dating || busy || stale ||
      event.isComposing ||
      event.altKey || event.ctrlKey || event.metaKey || event.shiftKey ||
      !isElement(event.target)) return;
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
      const active = document.activeElement;
      returnFocus = isFocusable(active) ? active : null;
      supersede();
      selected = undefined;
      dating = undefined;
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
