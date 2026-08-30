import { type Node as ProseMirrorNode } from "prosemirror-model";
import {
  type EditorState,
  Plugin,
  PluginKey,
  type Transaction,
} from "prosemirror-state";
import {
  Decoration,
  DecorationSet,
} from "prosemirror-view";

interface DiffStylingState {
  decorations: DecorationSet;
  generatedDocument?: ProseMirrorNode;
}

type Revert = (state: EditorState) => Transaction | undefined;

const diffStylingKey = new PluginKey<DiffStylingState>("diff-styling");

function isClauseNode(
  node: ProseMirrorNode,
  parent: ProseMirrorNode | null,
  doc: ProseMirrorNode,
): boolean {
  return (parent === doc && node.type.name !== "ordered_list") ||
    parent?.type.name === "ordered_list";
}

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

  const $position = state.doc.resolve(position);
  const parent = $position.parent;
  const isOnlyItemInUserAuthoredList = node.type.name === "list_item" &&
    parent.type.name === "ordered_list" &&
    parent.attrs.id === null &&
    parent.childCount === 1;
  const deleteFrom = isOnlyItemInUserAuthoredList
    ? $position.before($position.depth)
    : position;
  const deleteTo = deleteFrom +
    (isOnlyItemInUserAuthoredList ? parent.nodeSize : node.nodeSize);

  return state.tr
    .delete(deleteFrom, deleteTo)
    .scrollIntoView();
}

export function restoreGeneratedNode(
  state: EditorState,
  position: number,
  generatedNode: ProseMirrorNode,
): Transaction | undefined {
  const node = state.doc.nodeAt(position);
  const nodeId = node?.attrs.id;

  if (
    !node ||
    typeof nodeId !== "string" ||
    nodeId !== generatedNode.attrs.id
  ) {
    return undefined;
  }

  return state.tr
    .replaceWith(position, position + node.nodeSize, generatedNode)
    .scrollIntoView();
}

export function setGeneratedDocument(
  transaction: Transaction,
  generatedDocument: ProseMirrorNode,
): Transaction {
  return transaction.setMeta(diffStylingKey, generatedDocument);
}

function createRevertButton(label: string): HTMLButtonElement {
  const button = document.createElement("button");

  button.type = "button";
  button.className = "revert-node-button";
  button.contentEditable = "false";
  button.setAttribute("aria-label", label);
  button.title = label;
  button.append(createUndoIcon());

  return button;
}

function clauseNodesById(doc: ProseMirrorNode): Map<string, ProseMirrorNode> {
  const clauses = new Map<string, ProseMirrorNode>();

  doc.descendants((node, _position, parent) => {
    const id = node.attrs.id;

    if (isClauseNode(node, parent, doc) && typeof id === "string") {
      clauses.set(id, node);
    }
  });

  return clauses;
}

function createDiffDecorations(
  doc: ProseMirrorNode,
  generatedDocument?: ProseMirrorNode,
): DecorationSet {
  const decorations: Decoration[] = [];
  const generatedClauses = generatedDocument
    ? clauseNodesById(generatedDocument)
    : new Map<string, ProseMirrorNode>();

  doc.descendants((node, position, parent) => {
    const isClause = isClauseNode(node, parent, doc);

    if (isClause && node.attrs.id === null) {
      decorations.push(
        Decoration.node(position, position + node.nodeSize, {
          class: "user-authored-paragraph",
        }, {
          diffKind: "inserted",
        }),
        Decoration.widget(
          position + 1,
          () => createRevertButton("Undo inserted paragraph"),
          {
            side: -1,
            revert: (state: EditorState) =>
              deleteUserAuthoredNode(state, position),
          },
        ),
      );
      return;
    }

    const id = node.attrs.id;
    const generatedNode = typeof id === "string"
      ? generatedClauses.get(id)
      : undefined;

    if (isClause && generatedNode && !node.eq(generatedNode)) {
      decorations.push(
        Decoration.node(position, position + node.nodeSize, {
          class: "modified-clause",
        }, {
          diffKind: "modified",
        }),
        Decoration.widget(
          position + 1,
          () => createRevertButton("Undo changes to clause"),
          {
            side: -1,
            revert: (state: EditorState) =>
              restoreGeneratedNode(state, position, generatedNode),
          },
        ),
      );
    }
  });

  return DecorationSet.create(doc, decorations);
}

export function createDiffStylingPlugin(): Plugin<DiffStylingState> {
  return new Plugin<DiffStylingState>({
    key: diffStylingKey,
    // Block any transaction that would delete a clause completely.
    filterTransaction(transaction, state) {
      if (transaction.getMeta(diffStylingKey)) return true;

      const clausesAfterTransaction = clauseNodesById(transaction.doc);

      return [...clauseNodesById(state.doc).keys()].every((id) =>
        clausesAfterTransaction.has(id)
      );
    },
    state: {
      init(_config, state) {
        return {
          decorations: createDiffDecorations(state.doc),
        };
      },
      apply(transaction, pluginState) {
        const generatedDocument = transaction.getMeta(diffStylingKey) as
          ProseMirrorNode | undefined ?? pluginState.generatedDocument;

        return {
          generatedDocument,
          decorations: transaction.docChanged ||
              generatedDocument !== pluginState.generatedDocument
            ? createDiffDecorations(transaction.doc, generatedDocument)
            : pluginState.decorations,
        };
      },
    },
    props: {
      decorations(state) {
        return diffStylingKey.getState(state)?.decorations;
      },
      handleClick(view, _position, event) {
        if (!(event.target instanceof Element)) return false;

        const button = event.target.closest<HTMLButtonElement>(
          ".revert-node-button",
        );
        if (!button || !view.dom.contains(button)) return false;

        const widgetPosition = view.posAtDOM(button, 0);
        const decoration = diffStylingKey.getState(view.state)?.decorations.find(
          widgetPosition,
          widgetPosition,
          (spec) => typeof spec.revert === "function",
        )[0];
        const revert = decoration?.spec.revert as Revert | undefined;
        const transaction = revert?.(view.state);

        if (!transaction) return false;

        view.dispatch(transaction);
        view.focus();
        return true;
      },
    },
  });
}
