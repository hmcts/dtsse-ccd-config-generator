import { type Node as ProseMirrorNode } from "prosemirror-model";
import { closeHistory } from "prosemirror-history";
import {
  type Command,
  type EditorState,
  Plugin,
  PluginKey,
  type Transaction,
} from "prosemirror-state";
import {
  Decoration,
  DecorationSet,
  type EditorView,
} from "prosemirror-view";

import {
  CHANGE_DESCRIPTIONS,
  clauseMatchesGenerated,
  clauseNodesById,
  isClauseNode,
} from "./changes.js";
import { isElement } from "./dom.js";
import { createUndoIcon } from "./icons.js";
import { hasSameManagedStructure } from "./invariants.js";

type Announce = (message: string) => void;

interface DiffStylingState {
  decorations: DecorationSet;
  generatedDocument?: ProseMirrorNode;
  announce?: Announce;
}

export interface DiffStylingOptions {
  /** Told about edits the invariants refuse and about reverted clauses. */
  announce?: Announce;
}

export const BLOCKED_EDIT_MESSAGE =
  "That edit was not made. Generated clauses and facts cannot be deleted or moved, but their wording can be edited.";

type Revert = (state: EditorState) => Transaction | undefined;

const diffStylingKey = new PluginKey<DiffStylingState>("diff-styling");
const revertKey = new PluginKey("clause-revert");

export function deleteUserAuthoredNode(
  state: EditorState,
  position: number,
): Transaction | undefined {
  const node = state.doc.nodeAt(position);
  // Docweave generates clauses with an ID; the reader wrote any without one, such as a heading.
  if (!node || typeof node.attrs.id === "string") return undefined;

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

interface ClauseMarker {
  /** Spoken before the clause, since its colour says nothing to a screen reader. */
  description: string;
  /** The gutter button's name. */
  revertLabel: string;
  /** Announced once the revert has happened. */
  revertedMessage: string;
}

const MARKERS: Record<"inserted" | "modified", ClauseMarker> = {
  inserted: {
    description: CHANGE_DESCRIPTIONS.inserted,
    revertLabel: "Undo inserted clause",
    revertedMessage: "Inserted clause removed.",
  },
  modified: {
    description: CHANGE_DESCRIPTIONS.modified,
    revertLabel: "Undo changes to clause",
    revertedMessage: "Clause restored to its generated wording.",
  },
};

function createClauseMarker(
  ownerDocument: Document,
  marker: ClauseMarker,
): HTMLElement {
  const container = ownerDocument.createElement("span");
  container.className = "docweave-editor__clause-marker";
  container.contentEditable = "false";

  const description = ownerDocument.createElement("span");
  description.className = "docweave-editor__visually-hidden";
  description.textContent = `${marker.description} `;

  const button = ownerDocument.createElement("button");
  button.type = "button";
  button.className = "docweave-editor__revert";
  button.setAttribute("aria-label", marker.revertLabel);
  button.title = marker.revertLabel;
  button.append(
    createUndoIcon(ownerDocument, "docweave-editor__revert-icon"),
  );

  container.append(description, button);
  return container;
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

    if (isClause && typeof node.attrs.id !== "string") {
      decorations.push(
        Decoration.node(position, position + node.nodeSize, {
          class:
            "docweave-editor__clause docweave-editor__clause--inserted",
        }, {
          diffKind: "inserted",
        }),
        Decoration.widget(
          position + 1,
          (view) => createClauseMarker(view.dom.ownerDocument, MARKERS.inserted),
          {
            side: -1,
            revert: (state: EditorState) =>
              deleteUserAuthoredNode(state, position),
            revertedMessage: MARKERS.inserted.revertedMessage,
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
          class:
            "docweave-editor__clause docweave-editor__clause--modified",
        }, {
          diffKind: "modified",
        }),
        Decoration.widget(
          position + 1,
          (view) => createClauseMarker(view.dom.ownerDocument, MARKERS.modified),
          {
            side: -1,
            revert: (state: EditorState) =>
              restoreGeneratedNode(state, position, generatedNode),
            revertedMessage: MARKERS.modified.revertedMessage,
          },
        ),
      );
    }
  });

  return DecorationSet.create(doc, decorations);
}

/** Reverts the clause whose marker sits at the given position, if any. */
function revertAt(
  state: EditorState,
  position: number,
  dispatch?: (transaction: Transaction) => void,
): boolean {
  const pluginState = diffStylingKey.getState(state);
  const decoration = pluginState?.decorations.find(
    position,
    position,
    (spec) => typeof spec.revert === "function",
  )[0];
  const revert = decoration?.spec.revert as Revert | undefined;
  const transaction = revert?.(state);
  if (!transaction) return false;

  if (dispatch) {
    dispatch(closeHistory(transaction).setMeta(revertKey, true));
    pluginState?.announce?.(decoration!.spec.revertedMessage as string);
  }
  return true;
}

/**
 * Reverts the innermost inserted or modified clause around the selection,
 * without first tabbing to its gutter button.
 */
export const revertClauseAtSelection: Command = (state, dispatch) => {
  const { $from } = state.selection;
  for (let depth = $from.depth; depth >= 1; depth -= 1) {
    const node = $from.node(depth);
    if (!isClauseNode(node, $from.node(depth - 1), state.doc)) continue;
    if (revertAt(state, $from.before(depth) + 1, dispatch)) return true;
  }
  return false;
};

function revertForButton(view: EditorView, target: EventTarget | null): boolean {
  if (!isElement(target)) return false;
  const button = target.closest<HTMLButtonElement>(".docweave-editor__revert");
  if (!button || !view.dom.contains(button)) return false;

  const reverted = revertAt(view.state, view.posAtDOM(button, 0), view.dispatch);
  if (reverted) view.focus();
  return reverted;
}

export function createDiffStylingPlugin(
  options: DiffStylingOptions = {},
): Plugin<DiffStylingState> {
  return new Plugin<DiffStylingState>({
    key: diffStylingKey,
    appendTransaction(transactions, _oldState, state) {
      // The revert is one undo step, separate from immediate follow-up typing.
      if (transactions.some((transaction) => transaction.getMeta(revertKey))) {
        return closeHistory(state.tr);
      }
      return null;
    },
    // Ordinary edits must preserve the complete managed structure.
    filterTransaction(transaction, state) {
      if (transaction.getMeta(diffStylingKey)) return true;

      const allowed = hasSameManagedStructure(state.doc, transaction.doc);
      // A refused keystroke is otherwise silent: the document simply does not
      // change, which a screen reader user cannot see.
      if (!allowed) options.announce?.(BLOCKED_EDIT_MESSAGE);
      return allowed;
    },
    state: {
      init(_config, state) {
        return {
          decorations: createDiffDecorations(state.doc),
          announce: options.announce,
        };
      },
      apply(transaction, pluginState) {
        const generatedDocument = transaction.getMeta(diffStylingKey) as
          ProseMirrorNode | undefined ?? pluginState.generatedDocument;

        return {
          generatedDocument,
          announce: pluginState.announce,
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
    },
    view(editorView) {
      // ProseMirror's own click handling only sees real mouse clicks, so a
      // button activated from the keyboard needs ordinary DOM listeners.
      const handleClick = (event: MouseEvent): void => {
        if (revertForButton(editorView, event.target)) event.preventDefault();
      };
      const handleKeyDown = (event: KeyboardEvent): void => {
        if (event.key !== "Enter" && event.key !== " ") return;
        if (revertForButton(editorView, event.target)) {
          event.preventDefault();
          event.stopPropagation();
        }
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
