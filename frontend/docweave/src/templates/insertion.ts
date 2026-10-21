import {
  Fragment,
  type Node as ProseMirrorNode,
  type NodeType,
  type ResolvedPos,
  Slice,
} from "prosemirror-model";
import {
  type Command,
  Selection,
  type Transaction,
} from "prosemirror-state";

import { editorSchema, isList } from "../schema.js";

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
  return position.parentOffset === 0 && position.index(clauseDepth) === 0;
}

/** Places the wording and leaves the caret after it. */
function insertAndSelect(
  transaction: Transaction,
  from: number,
  to: number,
  content: Fragment,
): void {
  const steps = transaction.steps.length;
  transaction.replaceWith(from, to, content);
  const end = transaction.mapping.slice(steps).map(to, 1);
  transaction.setSelection(Selection.near(transaction.doc.resolve(end), -1));
}

/**
 * The wording as items of a list of the type: each paragraph or heading an
 * item, with any lists after it as its subclauses or bullets, and the items of
 * a list of the same type items of their own. A list of the other type stays a
 * list, under the item before it, or under an empty one when nothing comes
 * before it.
 */
function listItems(document: Fragment, listType: NodeType): Fragment {
  const items: ProseMirrorNode[] = [];
  const listItem = editorSchema.nodes.list_item!;

  for (let index = 0; index < document.childCount; index++) {
    const node = document.child(index);
    if (node.type === listType) {
      items.push(...node.children);
      continue;
    }
    if (isList(node)) {
      const previous = items.pop();
      items.push(previous
        ? previous.copy(previous.content.addToEnd(node))
        : listItem.create(null, [editorSchema.nodes.paragraph!.create(), node]));
      continue;
    }
    const content = [node];
    while (document.maybeChild(index + 1) && isList(document.child(index + 1))) {
      content.push(document.child(++index));
    }
    items.push(listItem.create(null, content));
  }

  return Fragment.from(items);
}

/**
 * Inserts the wording after the clause that ends at the position, as items of
 * its list. The clause takes any list of the other kind the wording starts
 * with, in place rather than replaced, so undo leaves the clause as it is.
 */
function insertAfterClause(
  transaction: Transaction,
  clauseEnd: number,
  document: ProseMirrorNode,
  listType: NodeType,
): void {
  const { children } = document;
  const split = children.findIndex((node) => !isList(node) || node.type === listType);
  const leading = split === -1 ? children : children.slice(0, split);
  transaction.insert(clauseEnd - 1, leading);
  const end = transaction.mapping.map(clauseEnd);
  insertAndSelect(transaction, end, end, listItems(Fragment.from(children.slice(leading.length)), listType));
}

/**
 * Inserts the wording as the reader's own content straight after the managed
 * paragraph, clause or list with the id, as clauses of the same list when it is
 * a clause, and leaves the caret after it. After a clause's subclauses, a list
 * of their kind adds to them, and other wording goes on in that clause.
 */
export function insertAfterNode(
  document: ProseMirrorNode,
  id: string,
): Command {
  return (state, dispatch) => {
    let found: { node: ProseMirrorNode; position: number } | undefined;
    state.doc.descendants((node, position) => {
      if (found) return false;
      if (node.attrs.id === id) found = { node, position };
      return true;
    });
    if (!found) throw new Error(`No generated node with id ${id}`);
    if (found.node.isInline) {
      throw new Error(`Cannot insert after ${id}, which is within a clause`);
    }
    if (!dispatch) return true;

    const transaction = state.tr;
    const parent = transaction.doc.resolve(found.position).parent;
    const end = found.position + found.node.nodeSize;
    if (isList(found.node) && parent.type === editorSchema.nodes.list_item) {
      const listType = found.node.type;
      const addsToSubclauses = document.children.every((node) => node.type === listType);
      insertAndSelect(
        transaction,
        addsToSubclauses ? end - 1 : end,
        addsToSubclauses ? end - 1 : end,
        addsToSubclauses
          ? listItems(document.content, listType)
          : document.content,
      );
    } else if (isList(parent)) {
      insertAfterClause(transaction, end, document, parent.type);
    } else {
      insertAndSelect(transaction, end, end, document.content);
    }
    dispatch(transaction.scrollIntoView());
    return true;
  };
}

export interface InsertTemplateOptions {
  /**
   * Put the wording after the clause at the selection even from its start,
   * where it would otherwise go before: for adding to the end of a document
   * whose last clause is empty.
   */
  after?: boolean;
}

export function insertTemplate(
  document: ProseMirrorNode,
  selection?: Selection,
  { after: alwaysAfter = false }: InsertTemplateOptions = {},
): Command {
  return (state, dispatch) => {
    if (!dispatch) return true;

    const transaction = state.tr;
    if (selection) transaction.setSelection(selection);

    const { $to } = transaction.selection;
    const clause = clauseAt($to);

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
      if (containsManagedNode) {
        insertAndSelect(
          transaction,
          $to.pos,
          $to.pos,
          isList($to.parent)
            ? listItems(document.content, $to.parent.type)
            : document.content,
        );
      } else {
        transaction.replaceSelection(new Slice(document.content, 0, 0));
      }
    } else {
      const before = $to.before(clause.depth);
      const after = $to.after(clause.depth);
      const listType = clause.node.type === editorSchema.nodes.list_item
        ? $to.node(clause.depth - 1).type
        : undefined;
      const content = listType ? listItems(document.content, listType) : document.content;

      if (isEmptyPersonalClause(clause.node)) {
        // The template becomes the clause the reader has just started.
        insertAndSelect(transaction, before, after, content);
      } else if (!alwaysAfter && atClauseStart($to, clause.depth)) {
        insertAndSelect(transaction, before, before, content);
      } else if (listType) {
        insertAfterClause(transaction, after, document, listType);
      } else {
        insertAndSelect(transaction, after, after, content);
      }
    }

    if (!transaction.docChanged) {
      throw new Error("Template cannot be inserted at this position");
    }
    dispatch(transaction.scrollIntoView());
    return true;
  };
}
