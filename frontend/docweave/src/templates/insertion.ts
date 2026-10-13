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
  return position.parentOffset === 0 && position.index(clauseDepth) === 0;
}

/** Places the wording and leaves the caret after it. */
function insertAndSelect(
  transaction: Transaction,
  from: number,
  to: number,
  content: Fragment,
): void {
  transaction.replaceWith(from, to, content);
  const end = transaction.mapping.map(to, 1);
  transaction.setSelection(Selection.near(transaction.doc.resolve(end), -1));
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

/**
 * Inserts the wording as the reader's own content straight after the managed
 * paragraph, clause or list with the id, as clauses of the same list when it is
 * a clause, and leaves the caret after it. A clause's subclauses can only end
 * its clause, so wording after them becomes its last subclauses.
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
    const subclauses = found.node.type === editorSchema.nodes.ordered_list &&
      parent.type === editorSchema.nodes.list_item;
    const end = found.position + found.node.nodeSize - (subclauses ? 1 : 0);
    insertAndSelect(
      transaction,
      end,
      end,
      subclauses || parent.type === editorSchema.nodes.ordered_list
        ? listItems(document)
        : document.content,
    );
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
          $to.parent.type === editorSchema.nodes.ordered_list
            ? listItems(document)
            : document.content,
        );
      } else {
        transaction.replaceSelection(new Slice(document.content, 0, 0));
      }
    } else {
      const before = $to.before(clause.depth);
      const after = $to.after(clause.depth);
      const content = clause.node.type === editorSchema.nodes.list_item
        ? listItems(document)
        : document.content;

      if (isEmptyPersonalClause(clause.node)) {
        // The template becomes the clause the reader has just started.
        insertAndSelect(transaction, before, after, content);
      } else {
        const position = !alwaysAfter && atClauseStart($to, clause.depth)
          ? before
          : after;
        insertAndSelect(transaction, position, position, content);
      }
    }

    if (!transaction.docChanged) {
      throw new Error("Template cannot be inserted at this position");
    }
    dispatch(transaction.scrollIntoView());
    return true;
  };
}
