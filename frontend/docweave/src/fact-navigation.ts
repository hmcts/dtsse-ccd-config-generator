import { type Node as ProseMirrorNode } from "prosemirror-model";
import {
  type Command,
  type EditorState,
  NodeSelection,
  Plugin,
  PluginKey,
  type Transaction,
} from "prosemirror-state";
import { Decoration, DecorationSet, type EditorView } from "prosemirror-view";

import { type FactMetadata } from "./builder.js";
import { isElement, isNode } from "./dom.js";

type Announce = (message: string) => void;

interface FactNavigationState {
  decorations: DecorationSet;
  facts: ReadonlyMap<string, FactMetadata>;
  /** What each fact is called, from its options or the page. */
  labels: ReadonlyMap<string, string>;
  announce?: Announce;
}

export interface FactNavigationOptions {
  /** Told where the reader has landed: on a field, or back in the document. */
  announce?: Announce;
}

/** The label a screen reader would give the control, if the page has one. */
function labelForSource(source: HTMLElement): string | undefined {
  const text = (element: Element | null | undefined): string | undefined =>
    element?.textContent?.replace(/\s+/gu, " ").trim() || undefined;
  const legend = text(source.closest("fieldset")?.querySelector("legend"));
  const type = source.getAttribute("type");
  // The element's own label list, rather than a selector built from its ID,
  // which need not be selector-safe.
  const labels = (source as Partial<HTMLInputElement>).labels;
  const own = source.tagName === "INPUT" &&
      (type === "radio" || type === "checkbox")
    // An option's own label names the choice, not the question.
    ? undefined
    : text(labels?.[0]) ?? source.getAttribute("aria-label") ?? undefined;
  return own ?? legend;
}

/** "Possession deadline, 1 October 2026", or just the value without a label. */
export function describeFact(state: EditorState, node: ProseMirrorNode): string {
  const label = factNavigationKey.getState(state)?.labels.get(node.attrs.id as string);
  const value = node.attrs.text as string;
  return label ? `${label}, ${value}` : value;
}

/** @internal */
export function getFactLabel(state: EditorState, factId: string): string | undefined {
  return factNavigationKey.getState(state)?.labels.get(factId);
}

export const RETURN_TO_DOCUMENT_LABEL = "Return to document";

/** A source control the reader went to from a fact, and the way back. */
interface PendingReturn {
  factId: string;
  button: HTMLButtonElement;
  /** Focus may move within this without withdrawing the offer. */
  region: HTMLElement;
}

/**
 * The control itself, or the group it belongs to: a fieldset for a date
 * input's three fields or a set of radios.
 */
/**
 * The whole control as the page lays it out: a fieldset or group for a date
 * input or radios, else the GOV.UK form group, else the control itself. The
 * return button goes after it, so it never lands between a radio and its
 * label, whose adjacent-sibling styling would break, nor inside an input's
 * prefix wrapper.
 */
function controlAround(source: HTMLElement): HTMLElement {
  return source.closest<HTMLElement>(
    "fieldset, [role=group], .govuk-form-group",
  ) ?? source;
}

function factPosition(
  view: EditorView,
  factId: string,
): { position: number; node: ProseMirrorNode } | undefined {
  let found: { position: number; node: ProseMirrorNode } | undefined;
  view.state.doc.descendants((node, position) => {
    if (found) return false;
    if (node.type.name === "generated_text" && node.attrs.id === factId) {
      found = { position, node };
      return false;
    }
    return true;
  });
  return found;
}

/**
 * Puts the reader back on the fact they left, which reconciliation has kept
 * under the same ID even if its value changed while they were away.
 */
function returnToFact(
  view: EditorView,
  factId: string,
  announce?: (message: string) => void,
): void {
  const fact = factPosition(view, factId);
  if (!fact) {
    view.focus();
    announce?.("Returned to the document. The field you left is no longer in it.");
    return;
  }
  view.dispatch(
    view.state.tr
      .setSelection(NodeSelection.create(view.state.doc, fact.position))
      .scrollIntoView(),
  );
  // Focus goes to the editor, not the fact's span: ProseMirror only tracks the
  // selection while its own element is focused, and the node selection and
  // announcement together say where the reader has landed.
  view.focus();
  const label = getFactLabel(view.state, factId);
  announce?.(
    `Returned to ${label ? describeFact(view.state, fact.node) : `generated field, ${fact.node.attrs.text as string}`}.`,
  );
}

function allFacts(
  doc: ProseMirrorNode,
): Array<{ position: number; node: ProseMirrorNode }> {
  const facts: Array<{ position: number; node: ProseMirrorNode }> = [];
  doc.descendants((node, position) => {
    if (node.type.name === "generated_text") facts.push({ position, node });
    return node.type.name !== "generated_text";
  });
  return facts;
}

/**
 * Moves the selection to the next or previous fact, so the document can be
 * walked field by field the way a form is walked control by control. Past
 * the last field it wraps to the first, as Word's next-field does; the
 * announced position keeps the reader oriented.
 */
function selectFact(direction: 1 | -1): Command {
  return (state, dispatch) => {
    const facts = allFacts(state.doc);
    const { from, to } = state.selection;
    const announce = factNavigationKey.getState(state)?.announce;
    if (facts.length === 0) {
      announce?.("This document has no fields.");
      return true;
    }
    const found = direction === 1
      ? facts.findIndex((fact) => fact.position >= to)
      : facts.findLastIndex((fact) => fact.position < from);
    const index = found !== -1 ? found : direction === 1 ? 0 : facts.length - 1;
    if (!dispatch) return true;
    const fact = facts[index]!;
    dispatch(
      state.tr
        .setSelection(NodeSelection.create(state.doc, fact.position))
        .scrollIntoView(),
    );
    announce?.(
      `${describeFact(state, fact.node)}. Field ${index + 1} of ${facts.length}.`,
    );
    return true;
  };
}

export const selectNextFact: Command = selectFact(1);
export const selectPreviousFact: Command = selectFact(-1);

const factNavigationKey = new PluginKey<FactNavigationState>(
  "fact-navigation",
);

const focusableSelector = [
  'input:not([type="hidden"]):not(:disabled)',
  "select:not(:disabled)",
  "textarea:not(:disabled)",
  "button:not(:disabled)",
  "a[href]",
  '[tabindex]:not([tabindex="-1"])',
].join(", ");

function createDecorations(
  document: ProseMirrorNode,
  facts: ReadonlyMap<string, FactMetadata>,
  ownerDocument: Document,
): Pick<FactNavigationState, "decorations" | "labels"> {
  const decorations: Decoration[] = [];
  const labels = new Map<string, string>();

  document.descendants((node, position) => {
    if (node.type.name !== "generated_text") return true;

    const id = node.attrs.id as string;
    const fact = facts.get(id);
    const source = fact?.sourceId === undefined
      ? null
      : ownerDocument.getElementById(fact.sourceId);
    const label = fact?.label ?? (source ? labelForSource(source) : undefined);
    if (label) labels.set(id, label);
    if (!source) return false;

    decorations.push(
      // A screen reader reads the value as ordinary text; the role description
      // marks where the field starts and ends, the description names it, and
      // aria-details lets it read the source control without leaving.
      Decoration.node(position, position + node.nodeSize, {
        class: "docweave-editor__fact-link",
        role: "link",
        tabindex: "0",
        "aria-roledescription": "generated field",
        "aria-details": source.id,
        ...(label ? { "aria-description": label } : {}),
      }),
    );
    return false;
  });

  return { decorations: DecorationSet.create(document, decorations), labels };
}

/** The fact under the event's target, if any. */
function factIdAtTarget(view: EditorView, event: Event): string | undefined {
  const target = event.target;
  if (!isElement(target)) return undefined;
  const fact = target.closest<HTMLElement>("[data-generated-text]");
  return fact && view.dom.contains(fact) ? fact.dataset.generatedText : undefined;
}

/** The fact the selection sits on, as after keyboard field navigation. */
function selectedFactId(view: EditorView): string | undefined {
  const { selection } = view.state;
  return selection instanceof NodeSelection &&
      selection.node.type.name === "generated_text"
    ? selection.node.attrs.id as string
    : undefined;
}

function sourceForFact(view: EditorView, factId: string): HTMLElement | undefined {
  const sourceId = factNavigationKey.getState(view.state)?.facts.get(factId)
    ?.sourceId;
  return sourceId === undefined
    ? undefined
    : view.dom.ownerDocument.getElementById(sourceId) ?? undefined;
}

function navigateToSource(
  view: EditorView,
  event: Event,
  factId: string,
): HTMLElement | undefined {
  const source = sourceForFact(view, factId);
  if (!source) return undefined;

  const focusTarget = source.matches(focusableSelector)
    ? source
    : source.querySelector<HTMLElement>(focusableSelector);
  const reduceMotion = view.dom.ownerDocument.defaultView?.matchMedia?.(
    "(prefers-reduced-motion: reduce)",
  ).matches ?? false;

  event.preventDefault();
  focusTarget?.focus({ preventScroll: true });
  source.scrollIntoView({
    behavior: reduceMotion ? "auto" : "smooth",
    block: "center",
  });
  // Without a control to focus the reader has not left the document, so there
  // is nothing to return from.
  return focusTarget ? source : undefined;
}

export function setFactNavigationFacts(
  transaction: Transaction,
  facts: ReadonlyMap<string, FactMetadata>,
): Transaction {
  return transaction.setMeta(factNavigationKey, new Map(facts));
}

export function createFactNavigationPlugin(
  ownerDocument: Document,
  options: FactNavigationOptions = {},
): Plugin<FactNavigationState> {
  return new Plugin<FactNavigationState>({
    key: factNavigationKey,
    state: {
      init(_config, state) {
        const facts = new Map<string, FactMetadata>();
        return {
          facts,
          announce: options.announce,
          ...createDecorations(state.doc, facts, ownerDocument),
        };
      },
      apply(transaction, pluginState) {
        const facts = transaction.getMeta(factNavigationKey) as
          ReadonlyMap<string, FactMetadata> | undefined ?? pluginState.facts;
        const unchanged = !transaction.docChanged && facts === pluginState.facts;
        return {
          facts,
          announce: pluginState.announce,
          ...(unchanged
            ? { decorations: pluginState.decorations, labels: pluginState.labels }
            : createDecorations(transaction.doc, facts, ownerDocument)),
        };
      },
    },
    props: {
      decorations(state) {
        return factNavigationKey.getState(state)?.decorations;
      },
    },
    view(editorView) {
      let pending: PendingReturn | undefined;

      const clearPending = (): void => {
        if (!pending) return;
        pending.button.remove();
        pending = undefined;
        ownerDocument.removeEventListener("keydown", handleReturnShortcut, true);
        ownerDocument.removeEventListener("focusin", handleFocusIn);
      };

      const goBack = (): void => {
        if (!pending) return;
        const { factId } = pending;
        clearPending();
        returnToFact(editorView, factId, options.announce);
      };

      // The way back is a button beside the control the reader landed on, and
      // a shortcut that works wherever they are on the page.
      // Both last only while the reader stays with that control: moving on
      // to anything else on the page, this editor included, withdraws them.
      const offerReturn = (factId: string, source: HTMLElement): void => {
        clearPending();
        const button = ownerDocument.createElement("button");
        button.type = "button";
        button.className = "docweave-editor__return";
        button.textContent = RETURN_TO_DOCUMENT_LABEL;
        button.addEventListener("click", goBack);
        const control = controlAround(source);
        control.insertAdjacentElement("afterend", button);
        pending = { factId, button, region: control };
        ownerDocument.addEventListener("keydown", handleReturnShortcut, true);
        ownerDocument.addEventListener("focusin", handleFocusIn);
      };

      const leaveForSource = (event: Event, factId: string | undefined): void => {
        if (factId === undefined) return;
        const source = navigateToSource(editorView, event, factId);
        if (source) offerReturn(factId, source);
      };
      const handleClick = (event: MouseEvent): void => {
        leaveForSource(event, factIdAtTarget(editorView, event));
      };
      // Enter works on a focused fact span and on a fact reached by keyboard
      // field navigation, which selects the node while the editor keeps focus.
      const handleKeyDown = (event: KeyboardEvent): void => {
        if (event.key !== "Enter") return;
        leaveForSource(
          event,
          factIdAtTarget(editorView, event) ?? selectedFactId(editorView),
        );
      };
      function handleReturnShortcut(event: KeyboardEvent): void {
        if (!pending || event.key.toLowerCase() !== "d" || !event.altKey ||
          !(event.ctrlKey || event.metaKey) || event.shiftKey) return;
        event.preventDefault();
        goBack();
      }
      function handleFocusIn(event: FocusEvent): void {
        const target = event.target;
        // A realm-free check: the target may belong to another document.
        if (!pending || !isNode(target)) return;
        if (pending.region.contains(target) || target === pending.button) return;
        clearPending();
      }

      editorView.dom.addEventListener("click", handleClick, true);
      editorView.dom.addEventListener("keydown", handleKeyDown, true);
      return {
        destroy(): void {
          clearPending();
          editorView.dom.removeEventListener("click", handleClick, true);
          editorView.dom.removeEventListener("keydown", handleKeyDown, true);
        },
      };
    },
  });
}
