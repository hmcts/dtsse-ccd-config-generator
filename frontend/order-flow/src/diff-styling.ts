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

interface ClauseSnapshot {
  node: ProseMirrorNode;
  parent: string | symbol;
}

type Revert = (state: EditorState) => Transaction | undefined;

const diffStylingKey = new PluginKey<DiffStylingState>("diff-styling");
const documentParent = Symbol("document-parent");
const userAuthoredParent = Symbol("user-authored-parent");

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

  if (
    node.type.name === "list_item" &&
    generatedNode.type === node.type
  ) {
    const ownContentSize = node.children
      .filter((child) => child.type.name !== "ordered_list")
      .reduce((size, child) => size + child.nodeSize, 0);
    const generatedOwnContent = generatedNode.children.filter(
      (child) => child.type.name !== "ordered_list",
    );

    return state.tr
      .replaceWith(
        position + 1,
        position + 1 + ownContentSize,
        generatedOwnContent,
      )
      .scrollIntoView();
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

export function getGeneratedDocument(
  state: EditorState,
): ProseMirrorNode | undefined {
  return diffStylingKey.getState(state)?.generatedDocument;
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

function clauseSnapshotsById(
  doc: ProseMirrorNode,
): Map<string, ClauseSnapshot> {
  const clauses = new Map<string, ClauseSnapshot>();

  function visit(node: ProseMirrorNode, parent: string | symbol): void {
    node.forEach((child) => {
      const id = child.attrs.id;

      if (isClauseNode(child, node, doc) && typeof id === "string") {
        clauses.set(id, { node: child, parent });
      }

      let childParent = parent;
      if (child.type.name === "ordered_list" && typeof id === "string") {
        childParent = id;
      } else if (child.type.name === "list_item") {
        childParent = typeof id === "string" ? id : userAuthoredParent;
      }

      visit(child, childParent);
    });
  }

  visit(doc, documentParent);
  return clauses;
}

function clauseNodesById(doc: ProseMirrorNode): Map<string, ProseMirrorNode> {
  return new Map(
    [...clauseSnapshotsById(doc)].map(([id, clause]) => [id, clause.node]),
  );
}

function formValueIds(doc: ProseMirrorNode): Set<string> {
  const ids = new Set<string>();

  doc.descendants((node) => {
    const id = node.attrs.id;

    if (node.type.name === "form_value" && typeof id === "string") {
      ids.add(id);
    }
  });

  return ids;
}

function clauseMatchesGenerated(
  node: ProseMirrorNode,
  generatedNode: ProseMirrorNode,
): boolean {
  if (!node.sameMarkup(generatedNode)) return false;
  if (node.type.name !== "list_item") return node.eq(generatedNode);

  const ownContent = node.children.filter(
    (child) => child.type.name !== "ordered_list",
  );
  const generatedOwnContent = generatedNode.children.filter(
    (child) => child.type.name !== "ordered_list",
  );

  return ownContent.length === generatedOwnContent.length &&
    ownContent.every((child, index) =>
      child.eq(generatedOwnContent[index]!)
    );
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

    if (
      isClause &&
      generatedNode &&
      !clauseMatchesGenerated(node, generatedNode)
    ) {
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
    // Block ordinary transactions that remove managed content or reparent a clause.
    filterTransaction(transaction, state) {
      if (transaction.getMeta(diffStylingKey)) return true;

      const clausesBeforeTransaction = clauseSnapshotsById(state.doc);
      const clausesAfterTransaction = clauseSnapshotsById(transaction.doc);
      const formValuesAfterTransaction = formValueIds(transaction.doc);

      return [...clausesBeforeTransaction].every(([id, clause]) =>
        clausesAfterTransaction.get(id)?.parent === clause.parent
      ) && [...formValueIds(state.doc)].every((id) =>
        formValuesAfterTransaction.has(id)
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
