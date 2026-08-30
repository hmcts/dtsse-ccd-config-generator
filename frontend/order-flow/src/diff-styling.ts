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

type ClauseDiffAction = {
  kind: "delete";
  nodePosition: number;
} | {
  generatedNode: ProseMirrorNode;
  kind: "restore";
  nodePosition: number;
};

interface DiffStylingState {
  decorations: DecorationSet;
  generatedDocument?: ProseMirrorNode;
}

const diffStylingPluginKey = new PluginKey<DiffStylingState>(
  "diff-styling",
);

function createUndoIcon(): HTMLImageElement {
  const icon = document.createElement("img");

  icon.alt = "";
  icon.className = "revert-node-button__icon";
  icon.src = new URL("./assets/revert.svg", import.meta.url).href;
  return icon;
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

function isClauseNode(
  node: ProseMirrorNode,
  parent: ProseMirrorNode | null,
  doc: ProseMirrorNode,
): boolean {
  return (parent === doc && node.type.name === "paragraph") ||
    (parent?.type.name === "ordered_list" && node.type.name === "list_item");
}

function insertedDeletePosition(
  doc: ProseMirrorNode,
  node: ProseMirrorNode,
  position: number,
  parent: ProseMirrorNode | null,
): number {
  const isOnlyItemInUserAuthoredList = node.type.name === "list_item" &&
    parent?.type.name === "ordered_list" &&
    parent.attrs.id === null &&
    parent.childCount === 1;

  if (!isOnlyItemInUserAuthoredList) return position;

  const $position = doc.resolve(position);
  return $position.before($position.depth);
}

function managedClauseId(node: ProseMirrorNode): string | undefined {
  const id = node.attrs.id;
  return typeof id === "string" && id.startsWith("clause:")
    ? id
    : undefined;
}

function generatedClausesById(
  generatedDocument: ProseMirrorNode,
): Map<string, ProseMirrorNode> {
  const clauses = new Map<string, ProseMirrorNode>();

  generatedDocument.descendants((node) => {
    const id = managedClauseId(node);
    if (!id) return true;
    if (clauses.has(id)) throw new Error(`Duplicate generated clause ID: ${id}`);

    clauses.set(id, node);
    return true;
  });

  return clauses;
}

function addClauseDiff(
  decorations: Decoration[],
  node: ProseMirrorNode,
  position: number,
  className: string,
  label: string,
  action: ClauseDiffAction,
): void {
  decorations.push(
    Decoration.node(position, position + node.nodeSize, {
      class: `diff-clause ${className}`,
    }),
    Decoration.widget(
      position + 1,
      () => createRevertButton(label),
      { clauseDiffAction: action, side: -1 },
    ),
  );
}

function createDiffDecorations(
  doc: ProseMirrorNode,
  generatedDocument?: ProseMirrorNode,
): DecorationSet {
  if (!generatedDocument) return DecorationSet.empty;

  const generatedClauses = generatedClausesById(generatedDocument);
  const decorations: Decoration[] = [];

  doc.descendants((node, position, parent) => {
    if (!isClauseNode(node, parent, doc)) return true;

    if (node.attrs.id === null) {
      addClauseDiff(
        decorations,
        node,
        position,
        "diff-clause--inserted",
        "Undo inserted paragraph",
        {
          kind: "delete",
          nodePosition: insertedDeletePosition(
            doc,
            node,
            position,
            parent,
          ),
        },
      );
      return false;
    }

    const id = managedClauseId(node);
    const generatedNode = id ? generatedClauses.get(id) : undefined;
    if (generatedNode && !node.eq(generatedNode)) {
      addClauseDiff(
        decorations,
        node,
        position,
        "diff-clause--modified",
        "Revert paragraph to generated wording",
        { kind: "restore", nodePosition: position, generatedNode },
      );
    }

    return false;
  });

  return DecorationSet.create(doc, decorations);
}

export function applyClauseDiffAction(
  state: EditorState,
  action: ClauseDiffAction,
): Transaction | undefined {
  const node = state.doc.nodeAt(action.nodePosition);
  if (!node) return undefined;

  if (action.kind === "delete") {
    if (node.attrs.id !== null) return undefined;

    return state.tr
      .delete(action.nodePosition, action.nodePosition + node.nodeSize)
      .scrollIntoView();
  }

  if (managedClauseId(node) !== managedClauseId(action.generatedNode)) {
    return undefined;
  }

  return state.tr
    .replaceWith(
      action.nodePosition,
      action.nodePosition + node.nodeSize,
      action.generatedNode,
    )
    .scrollIntoView();
}

export function setGeneratedDocument(
  transaction: Transaction,
  generatedDocument: ProseMirrorNode,
): Transaction {
  return transaction.setMeta(diffStylingPluginKey, { generatedDocument });
}

export function createDiffStylingPlugin(): Plugin<DiffStylingState> {
  return new Plugin<DiffStylingState>({
    key: diffStylingPluginKey,
    state: {
      init() {
        return { decorations: DecorationSet.empty };
      },
      apply(transaction, pluginState) {
        const metadata = transaction.getMeta(diffStylingPluginKey) as
          | { generatedDocument: ProseMirrorNode }
          | undefined;
        const generatedDocument = metadata?.generatedDocument ??
          pluginState.generatedDocument;

        if (!transaction.docChanged && !metadata) return pluginState;

        return {
          generatedDocument,
          decorations: createDiffDecorations(
            transaction.doc,
            generatedDocument,
          ),
        };
      },
    },
    props: {
      decorations(state) {
        return this.getState(state)?.decorations;
      },
      handleClick(view, _position, event) {
        if (!(event.target instanceof Element)) return false;

        const button = event.target.closest<HTMLButtonElement>(
          ".revert-node-button",
        );
        if (!button || !view.dom.contains(button)) return false;

        const widgetPosition = view.posAtDOM(button, 0);
        const decoration = this.getState(view.state)?.decorations.find(
          widgetPosition,
          widgetPosition,
          (spec) => spec.clauseDiffAction !== undefined,
        )[0];
        const action = decoration?.spec.clauseDiffAction as
          | ClauseDiffAction
          | undefined;
        if (!action) return false;

        const transaction = applyClauseDiffAction(view.state, action);
        if (!transaction) return false;

        view.dispatch(transaction);
        view.focus();
        return true;
      },
    },
  });
}
