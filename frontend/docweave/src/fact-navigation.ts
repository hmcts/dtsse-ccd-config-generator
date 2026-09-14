import { type Node as ProseMirrorNode } from "prosemirror-model";
import {
  NodeSelection,
  Plugin,
  PluginKey,
  type Transaction,
} from "prosemirror-state";
import { Decoration, DecorationSet, type EditorView } from "prosemirror-view";

interface FactNavigationState {
  decorations: DecorationSet;
  sources: ReadonlyMap<string, string>;
}

export interface FactNavigationOptions {
  /** Told where the reader has landed on returning to the document. */
  announce?: (message: string) => void;
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
 * The control's own surroundings: a fieldset for a date input's three fields
 * or a group of radios, otherwise the element wrapping the control.
 */
function regionAround(source: HTMLElement): HTMLElement {
  return source.closest<HTMLElement>("fieldset") ?? source.parentElement ??
    source;
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
  announce?.(`Returned to generated field, ${fact.node.attrs.text as string}.`);
}

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
  sources: ReadonlyMap<string, string>,
  ownerDocument: Document,
): DecorationSet {
  const decorations: Decoration[] = [];

  document.descendants((node, position) => {
    if (node.type.name !== "generated_text") return true;

    const id = node.attrs.id;
    const sourceId = typeof id === "string" ? sources.get(id) : undefined;
    if (!sourceId || !ownerDocument.getElementById(sourceId)) return false;

    decorations.push(
      // A screen reader reads the value as ordinary text; the role description
      // marks where the field starts and ends, and aria-details lets it read
      // the source control's label without leaving the document.
      Decoration.node(position, position + node.nodeSize, {
        class: "docweave-editor__fact-link",
        role: "link",
        tabindex: "0",
        "aria-roledescription": "generated field",
        "aria-details": sourceId,
      }),
    );
    return false;
  });

  return DecorationSet.create(document, decorations);
}

function sourceForEvent(
  view: EditorView,
  event: Event,
): HTMLElement | undefined {
  const target = event.target as Element | null;
  if (!target || typeof target.closest !== "function") {
    return undefined;
  }

  const fact = target.closest<HTMLElement>(
    "[data-generated-text]",
  );
  if (!fact || !view.dom.contains(fact)) return undefined;

  const id = fact.dataset.generatedText;
  const sourceId = id === undefined
    ? undefined
    : factNavigationKey.getState(view.state)?.sources.get(id);
  return sourceId === undefined
    ? undefined
    : view.dom.ownerDocument.getElementById(sourceId) ?? undefined;
}

function navigateToSource(
  view: EditorView,
  event: Event,
): HTMLElement | undefined {
  const source = sourceForEvent(view, event);
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

function factIdForEvent(event: Event): string | undefined {
  const target = event.target as Element | null;
  return target?.closest<HTMLElement>("[data-generated-text]")?.dataset
    .generatedText;
}

export function setFactNavigationSources(
  transaction: Transaction,
  sources: ReadonlyMap<string, string>,
): Transaction {
  return transaction.setMeta(factNavigationKey, new Map(sources));
}

export function createFactNavigationPlugin(
  ownerDocument: Document,
  options: FactNavigationOptions = {},
): Plugin<FactNavigationState> {
  return new Plugin<FactNavigationState>({
    key: factNavigationKey,
    state: {
      init(_config, state) {
        const sources = new Map<string, string>();
        return {
          sources,
          decorations: createDecorations(state.doc, sources, ownerDocument),
        };
      },
      apply(transaction, pluginState) {
        const sources = transaction.getMeta(factNavigationKey) as
          ReadonlyMap<string, string> | undefined ?? pluginState.sources;
        return {
          sources,
          decorations: transaction.docChanged ||
              sources !== pluginState.sources
            ? createDecorations(transaction.doc, sources, ownerDocument)
            : pluginState.decorations,
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
        source.insertAdjacentElement("afterend", button);
        pending = { factId, button, region: regionAround(source) };
        ownerDocument.addEventListener("keydown", handleReturnShortcut, true);
        ownerDocument.addEventListener("focusin", handleFocusIn);
      };

      const leaveForSource = (event: Event): void => {
        const factId = factIdForEvent(event);
        const source = navigateToSource(editorView, event);
        if (factId && source) offerReturn(factId, source);
      };
      const handleClick = (event: MouseEvent): void => {
        leaveForSource(event);
      };
      const handleKeyDown = (event: KeyboardEvent): void => {
        if (event.key === "Enter") leaveForSource(event);
      };
      function handleReturnShortcut(event: KeyboardEvent): void {
        if (!pending || event.key.toLowerCase() !== "d" || !event.altKey ||
          !(event.ctrlKey || event.metaKey) || event.shiftKey) return;
        event.preventDefault();
        goBack();
      }
      function handleFocusIn(event: FocusEvent): void {
        const target = event.target;
        if (!pending || !(target instanceof Node)) return;
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
