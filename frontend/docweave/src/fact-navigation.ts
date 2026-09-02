import { type Node as ProseMirrorNode } from "prosemirror-model";
import {
  Plugin,
  PluginKey,
  type Transaction,
} from "prosemirror-state";
import { Decoration, DecorationSet, type EditorView } from "prosemirror-view";

interface FactNavigationState {
  decorations: DecorationSet;
  sources: ReadonlyMap<string, string>;
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
      Decoration.node(position, position + node.nodeSize, {
        class: "docweave-editor__fact-link",
        role: "link",
        tabindex: "0",
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

function navigateToSource(view: EditorView, event: Event): boolean {
  const source = sourceForEvent(view, event);
  if (!source) return false;

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
  return true;
}

export function setFactNavigationSources(
  transaction: Transaction,
  sources: ReadonlyMap<string, string>,
): Transaction {
  return transaction.setMeta(factNavigationKey, new Map(sources));
}

export function createFactNavigationPlugin(
  ownerDocument: Document,
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
      const handleClick = (event: MouseEvent): void => {
        navigateToSource(editorView, event);
      };
      const handleKeyDown = (event: KeyboardEvent): void => {
        if (event.key === "Enter") navigateToSource(editorView, event);
      };

      editorView.dom.addEventListener("click", handleClick, true);
      editorView.dom.addEventListener("keydown", handleKeyDown, true);
      return {
        destroy(): void {
          editorView.dom.removeEventListener("click", handleClick, true);
          editorView.dom.removeEventListener("keydown", handleKeyDown, true);
        },
      };
    },
  });
}
