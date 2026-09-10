import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { EditorState, TextSelection } from "prosemirror-state";

import { getDocumentNode } from "../src/builder.js";
import { createInputRulesPlugin } from "../src/input-rules.js";
import { buildDoc } from "../src/index.js";
import { editorSchema } from "../src/schema.js";

/** Types text at the cursor the way the editor view hands it to input rules. */
function type(state: EditorState, text: string): EditorState {
  const plugin = createInputRulesPlugin();
  let current = state;
  const view = {
    get state() {
      return current;
    },
    dispatch(transaction: ReturnType<EditorState["apply"]> extends never ? never : Parameters<EditorState["apply"]>[0]) {
      current = current.apply(transaction);
    },
    composing: false,
  };
  const handled = plugin.props.handleTextInput!.call(
    plugin,
    view as never,
    state.selection.from,
    state.selection.to,
    text,
    () => state.tr.insertText(text),
  );
  return handled ? current : current.apply(current.tr.insertText(text));
}

function stateWithCursorAtEndOfLastParagraph(doc: EditorState["doc"]): EditorState {
  const state = EditorState.create({
    doc,
    plugins: [createInputRulesPlugin()],
  });
  const end = state.doc.content.size - 1;
  return state.apply(state.tr.setSelection(TextSelection.create(state.doc, end)));
}

describe("numbered clause input rule", () => {
  it('turns "1. " in a paragraph the reader wrote into a numbered clause', () => {
    const doc = editorSchema.node("doc", null, [
      editorSchema.node("paragraph", null, [editorSchema.text("1.")]),
    ]);
    const state = type(stateWithCursorAtEndOfLastParagraph(doc), " ");

    assert.equal(state.doc.firstChild!.type.name, "ordered_list");
    assert.equal(state.doc.firstChild!.attrs.order, 1);
    assert.equal(state.doc.firstChild!.attrs.id, null);
    assert.equal(state.doc.textContent, "");
  });

  it("starts the list at the number typed", () => {
    const doc = editorSchema.node("doc", null, [
      editorSchema.node("paragraph", null, [editorSchema.text("3.")]),
    ]);
    const state = type(stateWithCursorAtEndOfLastParagraph(doc), " ");

    assert.equal(state.doc.firstChild!.attrs.order, 3);
  });

  it("leaves a generated clause alone", () => {
    const generated = buildDoc((order) => {
      order.paragraph("heading", "1.");
    });
    const state = type(
      stateWithCursorAtEndOfLastParagraph(getDocumentNode(generated)),
      " ",
    );

    assert.equal(state.doc.firstChild!.type.name, "paragraph");
    assert.equal(state.doc.textContent, "1. ");
  });
});
