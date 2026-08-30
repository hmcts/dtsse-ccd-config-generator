import { type Node as ProseMirrorNode } from "prosemirror-model";
import {
  type EditorState,
  Plugin,
  type Transaction,
} from "prosemirror-state";
import {
  Decoration,
  DecorationSet,
} from "prosemirror-view";

function createUndoIcon(): HTMLImageElement {
  const icon = document.createElement("img");

  icon.alt = "";
  icon.className = "revert-node-button__icon";
  icon.src = new URL("./assets/revert.svg", import.meta.url).href;
  return icon;
}

export function deleteUserAuthoredNode(
  state: EditorState,
  position: number,
): Transaction | undefined {
  const node = state.doc.nodeAt(position);
  if (!node || node.attrs.id !== null) return undefined;

  return state.tr
    .delete(position, position + node.nodeSize)
    .scrollIntoView();
}

function createRevertButton(): HTMLButtonElement {
  const button = document.createElement("button");

  button.type = "button";
  button.className = "revert-node-button";
  button.contentEditable = "false";
  button.setAttribute("aria-label", "Undo inserted paragraph");
  button.title = "Undo inserted paragraph";
  button.append(createUndoIcon());

  return button;
}

function createDiffDecorations(doc: ProseMirrorNode): DecorationSet {
  const decorations: Decoration[] = [];

  doc.descendants((node, position, parent) => {
    const isClause = parent === doc ||
      parent?.type.name === "ordered_list";

    if (isClause && node.attrs.id === null) {
      decorations.push(
        Decoration.node(position, position + node.nodeSize, {
          class: "user-authored-paragraph",
        }),
        Decoration.widget(position + 1, createRevertButton, {
          revertNodeFrom: position,
          side: -1,
        }),
      );
    }
  });

  return DecorationSet.create(doc, decorations);
}

export function createDiffStylingPlugin(): Plugin<DecorationSet> {
  return new Plugin<DecorationSet>({
    state: {
      init(_config, state) {
        return createDiffDecorations(state.doc);
      },
      apply(transaction, decorations) {
        return transaction.docChanged
          ? createDiffDecorations(transaction.doc)
          : decorations;
      },
    },
    props: {
      decorations(state) {
        return this.getState(state);
      },
      handleClick(view, _position, event) {
        if (!(event.target instanceof Element)) return false;

        const button = event.target.closest<HTMLButtonElement>(
          ".revert-node-button",
        );
        if (!button || !view.dom.contains(button)) return false;

        const widgetPosition = view.posAtDOM(button, 0);
        const decoration = this.getState(view.state)?.find(
          widgetPosition,
          widgetPosition,
          (spec) => typeof spec.revertNodeFrom === "number",
        )[0];
        const nodePosition = decoration?.spec.revertNodeFrom;
        if (typeof nodePosition !== "number") return false;

        const transaction = deleteUserAuthoredNode(
          view.state,
          nodePosition,
        );
        if (!transaction) return false;

        view.dispatch(transaction);
        view.focus();
        return true;
      },
    },
  });
}
