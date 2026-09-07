import {
  Fragment,
  type Node as ProseMirrorNode,
  type ResolvedPos,
  Slice,
} from "prosemirror-model";
import {
  type Command,
  type Selection,
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
  }
  return undefined;
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
        ? transaction.insert(
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

    const insertBefore = insertionPosition.parentOffset === 0;
    const position = insertBefore
      ? insertionPosition.before(clause.depth)
      : insertionPosition.after(clause.depth);
    const content = clause.node.type === editorSchema.nodes.list_item
      ? listItems(document)
      : document.content;

    transaction = transaction.insert(position, content);
    if (!transaction.docChanged) {
      throw new Error("Template cannot be inserted at this position");
    }
    dispatch(transaction.scrollIntoView());
    return true;
  };
}
