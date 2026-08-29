import { wrapInList } from "prosemirror-schema-list";
import {
  type Command,
  type EditorState,
  TextSelection,
} from "prosemirror-state";

import { editorSchema } from "./schema.js";

function selectionTouchesSystemParagraph(state: EditorState): boolean {
  if (typeof state.selection.$from.parent.attrs.clause_id === "string") {
    return true;
  }

  let touchesSystemParagraph = false;

  state.doc.nodesBetween(
    state.selection.from,
    state.selection.to,
    (node) => {
      if (node.isTextblock && typeof node.attrs.clause_id === "string") {
        touchesSystemParagraph = true;
        return false;
      }
    },
  );

  return touchesSystemParagraph;
}

function outsideSystemParagraph(command: Command): Command {
  return (state, dispatch, view) =>
    !selectionTouchesSystemParagraph(state) &&
    command(state, dispatch, view);
}

export const insertUserParagraphAfterSystem: Command = (state, dispatch) => {
  if (!state.selection.empty) {
    return selectionTouchesSystemParagraph(state);
  }

  const { $from } = state.selection;

  if (
    $from.depth !== 1 ||
    typeof $from.parent.attrs.clause_id !== "string"
  ) {
    return false;
  }

  if (dispatch) {
    const insertPosition = $from.after();
    const paragraph = editorSchema.node("paragraph");
    const transaction = state.tr.insert(insertPosition, paragraph);

    transaction.setSelection(
      TextSelection.create(transaction.doc, insertPosition + 1),
    );
    dispatch(transaction.scrollIntoView());
  }

  return true;
};

export const wrapInOrderedList = outsideSystemParagraph(
  wrapInList(editorSchema.nodes.ordered_list!),
);
