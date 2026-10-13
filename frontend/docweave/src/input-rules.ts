import { InputRule, inputRules } from "prosemirror-inputrules";
import { type EditorState, type Plugin } from "prosemirror-state";
import { canJoin, findWrapping } from "prosemirror-transform";

import { editorSchema } from "./schema.js";

/** Whether the selection starts or ends inside a generated clause. */
export function selectionTouchesManagedContent(state: EditorState): boolean {
  return [state.selection.$from, state.selection.$to].some(($position) =>
    $position.depth > 0 && typeof $position.node(1).attrs.id === "string"
  );
}

/**
 * Typing "1. " at the start of a paragraph the reader wrote turns it into a
 * numbered clause, as the toolbar button does; the number typed starts the
 * list, and a list directly above continues instead if the numbers run on.
 * Generated clauses are left alone.
 */
export const numberedClauseRule = new InputRule(
  /^(\d+)\.\s$/u,
  (state, match, start, end) => {
    if (selectionTouchesManagedContent(state)) return null;
    const listType = editorSchema.nodes.ordered_list!;
    const order = Number(match[1]);
    const transaction = state.tr.delete(start, end);
    const range = transaction.doc.resolve(start).blockRange();
    const wrapping = range && findWrapping(range, listType, { order });
    if (!range || !wrapping) return null;
    transaction.wrap(range, wrapping);
    const before = transaction.doc.resolve(start - 1).nodeBefore;
    if (
      before?.type === listType &&
      before.attrs.id === null &&
      before.childCount + (before.attrs.order as number) === order &&
      canJoin(transaction.doc, start - 1)
    ) {
      transaction.join(start - 1);
    }
    return transaction;
  },
);

export function createInputRulesPlugin(): Plugin {
  return inputRules({ rules: [numberedClauseRule] });
}
