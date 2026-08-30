import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { type Node as ProseMirrorNode } from "prosemirror-model";
import { EditorState } from "prosemirror-state";

import {
  applyClauseDiffAction,
  createDiffStylingPlugin,
  setGeneratedDocument,
} from "../src/diff-styling.js";
import { editorSchema } from "../src/schema.js";

function listItem(id: string | null, text: string): ProseMirrorNode {
  return editorSchema.node(
    "list_item",
    { id },
    editorSchema.node("paragraph", null, editorSchema.text(text)),
  );
}

function orderDocument(items: ProseMirrorNode[]): ProseMirrorNode {
  return editorSchema.node(
    "doc",
    null,
    editorSchema.node(
      "ordered_list",
      { id: "container:clauses" },
      items,
    ),
  );
}

function generatedParagraph(): ProseMirrorNode {
  return editorSchema.node(
    "paragraph",
    { id: "clause:generated" },
    editorSchema.text("Generated"),
  );
}

function stateWithGeneratedDocument(
  doc: ProseMirrorNode,
  generatedDocument: ProseMirrorNode,
) {
  const plugin = createDiffStylingPlugin();
  let state = EditorState.create({
    schema: editorSchema,
    doc,
    plugins: [plugin],
  });

  state = state.apply(setGeneratedDocument(state.tr, generatedDocument));
  return { plugin, state };
}

function widgetActions(
  plugin: ReturnType<typeof createDiffStylingPlugin>,
  state: EditorState,
) {
  return plugin.getState(state)!.decorations.find()
    .filter((decoration) => decoration.from === decoration.to)
    .map((decoration) => decoration.spec.clauseDiffAction);
}

describe("diff styling", () => {
  it("decorates an inserted clause with an action that deletes it", () => {
    const generatedItem = listItem("clause:generated", "Generated");
    const insertedItem = listItem(null, "User authored");
    const generatedDocument = orderDocument([generatedItem]);
    const { plugin, state } = stateWithGeneratedDocument(
      orderDocument([generatedItem, insertedItem]),
      generatedDocument,
    );
    const actions = widgetActions(plugin, state);

    assert.equal(actions.length, 1);
    assert.equal(actions[0].kind, "delete");

    const transaction = applyClauseDiffAction(state, actions[0]);
    assert.ok(transaction);
    assert.ok(transaction.doc.eq(generatedDocument));
  });

  it("decorates a modified clause with an action that restores it", () => {
    const generatedDocument = orderDocument([
      listItem("clause:generated", "Generated wording"),
    ]);
    const { plugin, state } = stateWithGeneratedDocument(
      orderDocument([listItem("clause:generated", "Edited wording")]),
      generatedDocument,
    );
    const actions = widgetActions(plugin, state);

    assert.equal(actions.length, 1);
    assert.equal(actions[0].kind, "restore");

    const transaction = applyClauseDiffAction(state, actions[0]);
    assert.ok(transaction);
    assert.ok(transaction.doc.eq(generatedDocument));
  });

  it("removes a user-authored list when undoing its only item", () => {
    const generated = generatedParagraph();
    const generatedDocument = editorSchema.node("doc", null, generated);
    const insertedList = editorSchema.node(
      "ordered_list",
      null,
      listItem(null, "User authored"),
    );
    const { plugin, state } = stateWithGeneratedDocument(
      editorSchema.node("doc", null, [generated, insertedList]),
      generatedDocument,
    );
    const actions = widgetActions(plugin, state);

    assert.equal(actions.length, 1);
    const transaction = applyClauseDiffAction(state, actions[0]);
    assert.ok(transaction);
    assert.ok(transaction.doc.eq(generatedDocument));
  });

  it("removes items individually before removing their list", () => {
    const generated = generatedParagraph();
    const generatedDocument = editorSchema.node("doc", null, generated);
    const insertedList = editorSchema.node(
      "ordered_list",
      null,
      [listItem(null, "First"), listItem(null, "Second")],
    );
    const initial = stateWithGeneratedDocument(
      editorSchema.node("doc", null, [generated, insertedList]),
      generatedDocument,
    );
    const initialActions = widgetActions(initial.plugin, initial.state);

    assert.equal(initialActions.length, 2);
    const firstTransaction = applyClauseDiffAction(
      initial.state,
      initialActions[0],
    );
    assert.ok(firstTransaction);

    const stateWithOneItem = initial.state.apply(firstTransaction);
    const finalActions = widgetActions(initial.plugin, stateWithOneItem);
    assert.equal(finalActions.length, 1);

    const finalTransaction = applyClauseDiffAction(
      stateWithOneItem,
      finalActions[0],
    );
    assert.ok(finalTransaction);
    assert.ok(finalTransaction.doc.eq(generatedDocument));
  });

  it("does not decorate an unchanged generated clause", () => {
    const generatedDocument = orderDocument([
      listItem("clause:generated", "Generated wording"),
    ]);
    const { plugin, state } = stateWithGeneratedDocument(
      generatedDocument,
      generatedDocument,
    );

    assert.deepEqual(widgetActions(plugin, state), []);
  });
});
