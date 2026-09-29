import {
  baseKeymap,
  chainCommands,
  toggleMark,
} from "prosemirror-commands";
import { redo, undo } from "prosemirror-history";
import { keymap } from "prosemirror-keymap";
import {
  liftListItem,
  sinkListItem,
  splitListItem,
} from "prosemirror-schema-list";
import {
  type Command,
  type EditorState,
  type Plugin,
  TextSelection,
  type Transaction,
} from "prosemirror-state";

import { hasSameManagedStructure } from "./invariants.js";
import { editorSchema } from "./schema.js";

export const mac = typeof navigator !== "undefined" &&
  /Mac|iP(hone|[oa]d)/.test(navigator.platform);

export const indentListItem = sinkListItem(
  editorSchema.nodes.list_item!,
);

export const outdentListItem = liftListItem(
  editorSchema.nodes.list_item!,
);

/** Whether the cursor is in a clause the reader added, with no managed ID. */
function inAddedClause(state: EditorState): boolean {
  const { $from } = state.selection;
  for (let depth = $from.depth; depth > 0; depth -= 1) {
    const node = $from.node(depth);
    if (node.type === editorSchema.nodes.list_item) {
      return typeof node.attrs.id !== "string";
    }
  }
  return false;
}

/**
 * Runs a list command where the invariants would let its change through, and
 * otherwise declines the key, so Tab and Shift+Tab move focus on as they do in
 * any other control. Outdenting a clause the reader added keeps Shift+Tab even
 * when the move is refused, so the refusal is announced rather than focus
 * leaving the document unexplained; a refused Tab always moves on, so the
 * document is never a keyboard trap.
 */
function whereAllowed(
  command: Command,
  announceRefusalInAddedClause: boolean,
): Command {
  return (state, dispatch, view) => {
    let transaction: Transaction | undefined;
    const handled = command(state, (dispatched) => {
      transaction = dispatched;
    }, view);
    if (!handled || !transaction) return false;
    if (
      !hasSameManagedStructure(state.doc, transaction.doc) &&
      !(announceRefusalInAddedClause && inAddedClause(state))
    ) {
      return false;
    }
    dispatch?.(transaction);
    return true;
  };
}

export const indentClauseOnTab = whereAllowed(indentListItem, false);

export const outdentClauseOnTab = whereAllowed(outdentListItem, true);

export const protectClausesFromSplittingOnEnter: Command = (
  state,
  dispatch,
) => {
  const { $cursor } = state.selection as TextSelection;
  if (!$cursor) return false;

  const paragraphType = editorSchema.nodes.paragraph!;
  const listItemType = editorSchema.nodes.list_item!;
  const parentNode = $cursor.node(-1);
  const isListItem = parentNode.type === listItemType;
  const clauseNode = isListItem ? parentNode : $cursor.parent;

  if (typeof clauseNode.attrs.id !== "string") return false;

  if (!dispatch) return true;

  const clauseDepth = isListItem ? $cursor.depth - 1 : $cursor.depth;
  // Only the start of the clause's first paragraph is the start of the clause.
  const atClauseStart = $cursor.parentOffset === 0 &&
    $cursor.index(clauseDepth) === 0;
  const insertPosition = atClauseStart
    ? $cursor.before(clauseDepth)
    : $cursor.after(clauseDepth);
  const node = isListItem
    ? listItemType.create(null, paragraphType.create())
    : paragraphType.create();
  const transaction = state.tr.insert(insertPosition, node);

  transaction.setSelection(
    TextSelection.create(
      transaction.doc,
      insertPosition + (isListItem ? 2 : 1),
    ),
  );

  dispatch(transaction.scrollIntoView());
  return true;
};

function buildKeymap(): Record<string, Command> {
  const bindings: Record<string, Command> = {};

  function bind(key: string, command: Command): void {
    bindings[key] = command;
  }

  bind("Mod-z", undo);
  bind("Shift-Mod-z", redo);
  if (!mac) bind("Mod-y", redo);

  bind("Mod-b", toggleMark(editorSchema.marks.strong!));
  bind("Mod-B", toggleMark(editorSchema.marks.strong!));
  bind("Mod-i", toggleMark(editorSchema.marks.em!));
  bind("Mod-I", toggleMark(editorSchema.marks.em!));

  bind("Tab", indentClauseOnTab);
  bind("Shift-Tab", outdentClauseOnTab);

  const splitListItemOnEnter = splitListItem(
    editorSchema.nodes.list_item!,
  );

  bind(
    "Enter",
    chainCommands(
      protectClausesFromSplittingOnEnter,
      splitListItemOnEnter,
    ),
  );

  return bindings;
}

export function createKeymapPlugins(): Plugin[] {
  return [
    keymap(buildKeymap()),
    keymap(baseKeymap),
  ];
}
