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

function managedClause(
  position: ResolvedPos,
): { depth: number; node: ProseMirrorNode } | undefined {
  for (let depth = position.depth; depth > 0; depth--) {
    const node = position.node(depth);
    if ((node.type === editorSchema.nodes.paragraph ||
      node.type === editorSchema.nodes.list_item) &&
      typeof node.attrs.id === "string") {
      return { depth, node };
    }
    // A personal clause is the clause being edited, even inside a managed one.
    if (node.type === editorSchema.nodes.list_item) return undefined;
  }
  return undefined;
}

/** Whether the position is where the clause's wording begins. */
function atClauseStart(position: ResolvedPos, clauseDepth: number): boolean {
  if (position.parentOffset !== 0) return false;
  for (let depth = clauseDepth; depth < position.depth; depth++) {
    if (position.index(depth) !== 0) return false;
  }
  return true;
}

/** Inserts beside the selection and leaves the caret after the new wording. */
function insertAndSelect(
  transaction: Transaction,
  position: number,
  content: Fragment,
): Transaction {
  transaction.insert(position, content);
  const end = transaction.mapping.map(position, 1);
  return transaction.setSelection(
    Selection.near(transaction.doc.resolve(end), -1),
  );
}

/** Replaces the selection; an empty paragraph is filled, not followed. */
function fillSelection(
  transaction: Transaction,
  content: Fragment,
): Transaction {
  const { $from, empty } = transaction.selection;
  if (!empty || $from.parent.type !== editorSchema.nodes.paragraph ||
    $from.parent.content.size !== 0) {
    return transaction.replaceSelection(new Slice(content, 0, 0));
  }

  const end = $from.after();
  transaction.replaceRange($from.before(), end, new Slice(content, 0, 0));
  return transaction.setSelection(
    Selection.near(transaction.doc.resolve(transaction.mapping.map(end)), -1),
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
    const clause = managedClause(insertionPosition);

    if (!clause) {
      const { from, to } = transaction.selection;
      let containsManagedNode = false;
      transaction.doc.nodesBetween(
        from,
        to,
        (node, position) => {
          // A managed list around a personal clause is not in the selection.
          const enclosesSelection = position < from &&
            position + node.nodeSize > to;
          if (typeof node.attrs.id === "string" && !enclosesSelection) {
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
          transaction.selection.$to.parent.type ===
              editorSchema.nodes.ordered_list
            ? listItems(document)
            : document.content,
        )
        : fillSelection(transaction, document.content);
      if (!transaction.docChanged) {
        throw new Error("Template cannot be inserted at this position");
      }
      dispatch(transaction.scrollIntoView());
      return true;
    }

    const insertBefore = atClauseStart(insertionPosition, clause.depth);
    const position = insertBefore
      ? insertionPosition.before(clause.depth)
      : insertionPosition.after(clause.depth);
    const content = clause.node.type === editorSchema.nodes.list_item
      ? listItems(document)
      : document.content;

    transaction = insertAndSelect(transaction, position, content);
    if (!transaction.docChanged) {
      throw new Error("Template cannot be inserted at this position");
    }
    dispatch(transaction.scrollIntoView());
    return true;
  };
}
