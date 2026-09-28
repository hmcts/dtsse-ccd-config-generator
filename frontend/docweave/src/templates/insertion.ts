import {
  Fragment,
  type Node as ProseMirrorNode,
  type ResolvedPos,
  Slice,
} from "prosemirror-model";
import {
  type Command,
  Selection,
  type Transaction,
} from "prosemirror-state";

import { editorSchema } from "../schema.js";

/** The nearest numbered clause, or generated paragraph, around a position. */
function clauseAt(
  position: ResolvedPos,
): { depth: number; node: ProseMirrorNode } | undefined {
  for (let depth = position.depth; depth > 0; depth--) {
    const node = position.node(depth);
    if (node.type === editorSchema.nodes.list_item ||
      (node.type === editorSchema.nodes.paragraph &&
        typeof node.attrs.id === "string")) {
      return { depth, node };
    }
  }
  return undefined;
}

function isEmptyPersonalClause(node: ProseMirrorNode): boolean {
  return typeof node.attrs.id !== "string" && node.childCount === 1 &&
    node.firstChild!.content.size === 0;
}

/** Whether the position is where the clause's wording begins. */
function atClauseStart(position: ResolvedPos, clauseDepth: number): boolean {
  if (position.parentOffset !== 0) return false;
  for (let depth = clauseDepth; depth < position.depth; depth++) {
    if (position.index(depth) !== 0) return false;
  }
  return true;
}

/** Places the wording and leaves the caret after it. */
function insertAndSelect(
  transaction: Transaction,
  from: number,
  to: number,
  content: Fragment,
): Transaction {
  transaction.replaceWith(from, to, content);
  const end = transaction.mapping.map(to, 1);
  return transaction.setSelection(
    Selection.near(transaction.doc.resolve(end), -1),
  );
}

function listItems(document: ProseMirrorNode): Fragment {
  const items: ProseMirrorNode[] = [];

  for (let index = 0; index < document.childCount; index++) {
    const node = document.child(index);
    if (node.type === editorSchema.nodes.ordered_list) {
      items.push(...node.children);
      continue;
    }
    const paragraph = node.type === editorSchema.nodes.paragraph
      ? node
      : editorSchema.nodes.paragraph!.create(null, node.content);
    const next = document.maybeChild(index + 1);
    if (next?.type === editorSchema.nodes.ordered_list) {
      items.push(
        editorSchema.nodes.list_item!.create(null, [paragraph, next]),
      );
      index++;
    } else {
      items.push(editorSchema.nodes.list_item!.create(null, paragraph));
    }
  }

  return Fragment.from(items);
}

export function insertTemplate(
  document: ProseMirrorNode,
  selection?: Selection,
): Command {
  return (state, dispatch) => {
    if (!dispatch) return true;

    let transaction = state.tr;
    if (selection) transaction = transaction.setSelection(selection);

    const insertionPosition = transaction.selection.empty
      ? transaction.selection.$from
      : transaction.selection.$to;
    const clause = clauseAt(insertionPosition);

    if (!clause) {
      let containsManagedNode = false;
      transaction.doc.nodesBetween(
        transaction.selection.from,
        transaction.selection.to,
        (node) => {
          if (typeof node.attrs.id === "string") {
            containsManagedNode = true;
            return false;
          }
          return true;
        },
      );
      transaction = containsManagedNode
        ? insertAndSelect(
          transaction,
          transaction.selection.to,
          transaction.selection.to,
          transaction.selection.$to.parent.type ===
              editorSchema.nodes.ordered_list
            ? listItems(document)
            : document.content,
        )
        : transaction.replaceSelection(new Slice(document.content, 0, 0));
      if (!transaction.docChanged) {
        throw new Error("Template cannot be inserted at this position");
      }
      dispatch(transaction.scrollIntoView());
      return true;
    }

    const before = insertionPosition.before(clause.depth);
    const after = insertionPosition.after(clause.depth);
    const content = clause.node.type === editorSchema.nodes.list_item
      ? listItems(document)
      : document.content;

    if (isEmptyPersonalClause(clause.node)) {
      // The template becomes the clause the reader has just started.
      transaction = insertAndSelect(transaction, before, after, content);
    } else {
      const position = atClauseStart(insertionPosition, clause.depth)
        ? before
        : after;
      transaction = insertAndSelect(transaction, position, position, content);
    }
    if (!transaction.docChanged) {
      throw new Error("Template cannot be inserted at this position");
    }
    dispatch(transaction.scrollIntoView());
    return true;
  };
}
