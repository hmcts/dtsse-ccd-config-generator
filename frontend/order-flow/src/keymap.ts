import {
  baseKeymap,
  chainCommands,
  toggleMark,
} from "prosemirror-commands";
import { redo, undo } from "prosemirror-history";
import { keymap } from "prosemirror-keymap";
import { splitListItem } from "prosemirror-schema-list";
import {
  type Command,
  type Plugin,
  TextSelection,
} from "prosemirror-state";

import { editorSchema } from "./schema.js";

const mac = typeof navigator !== "undefined" &&
  /Mac|iP(hone|[oa]d)/.test(navigator.platform);

const protectClausesFromSplittingOnEnter: Command = (state, dispatch) => {
  const { $cursor } = state.selection as TextSelection;
  if (!$cursor) return false;

  const paragraphType = editorSchema.nodes.paragraph!;
  const listItemType = editorSchema.nodes.list_item!;
  const parentNode = $cursor.node(-1);
  const isListItem = parentNode.type === listItemType;
  const clauseNode = isListItem ? parentNode : $cursor.parent;

  if (typeof clauseNode.attrs.clause_id !== "string") return false;

  if (!dispatch) return true;

  const clauseDepth = isListItem ? $cursor.depth - 1 : $cursor.depth;
  const insertPosition = $cursor.after(clauseDepth);
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
