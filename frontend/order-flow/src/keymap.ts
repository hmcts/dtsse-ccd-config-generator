import {
  baseKeymap,
  toggleMark,
} from "prosemirror-commands";
import { redo, undo } from "prosemirror-history";
import { keymap } from "prosemirror-keymap";
import { splitListItem } from "prosemirror-schema-list";
import { type Command, type Plugin } from "prosemirror-state";

import { editorSchema } from "./schema.js";

const mac = typeof navigator !== "undefined" &&
  /Mac|iP(hone|[oa]d)/.test(navigator.platform);

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

  // Clause id must be unique and must not be inherited on split.
  const splitListItemOnEnter = splitListItem(
    editorSchema.nodes.list_item!,
    { clause_id: null },
  );

  bind("Enter", splitListItemOnEnter);

  return bindings;
}

export function createKeymapPlugins(): Plugin[] {
  return [
    keymap(buildKeymap()),
    keymap(baseKeymap),
  ];
}
