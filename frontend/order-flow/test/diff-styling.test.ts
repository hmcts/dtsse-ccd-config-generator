import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { EditorState } from "prosemirror-state";
import { DecorationSet } from "prosemirror-view";

import {
  createDiffStylingPlugin,
  deleteUserAuthoredNode,
  restoreGeneratedNode,
  setGeneratedDocument,
} from "../src/diff-styling.js";
import { editorSchema } from "../src/schema.js";

describe("diff styling", () => {
  it("decorates user-authored clauses and not generated clauses", () => {
    const generatedItem = editorSchema.node(
      "list_item",
      { id: "clause:generated" },
      editorSchema.node("paragraph", null, editorSchema.text("Generated")),
    );
    const userAuthoredItem = editorSchema.node(
      "list_item",
      null,
      editorSchema.node("paragraph", null, editorSchema.text("User authored")),
    );
    const doc = editorSchema.node("doc", null, [
      editorSchema.node(
        "paragraph",
        { id: "clause:introduction" },
        editorSchema.text("Introduction"),
      ),
      editorSchema.node(
        "ordered_list",
        { id: "container:clauses" },
        [generatedItem, userAuthoredItem],
      ),
    ]);
    const plugin = createDiffStylingPlugin();
    const state = EditorState.create({
      schema: editorSchema,
      doc,
      plugins: [plugin],
    });
    const decorationSet = plugin.props.decorations?.call(plugin, state);

    assert.ok(decorationSet instanceof DecorationSet);
    const decorations = decorationSet.find();
    let userAuthoredPosition: number | undefined;
    doc.descendants((node, position) => {
      if (node === userAuthoredItem) userAuthoredPosition = position;
    });

    assert.notEqual(userAuthoredPosition, undefined);
    const nodeDecorations = decorations.filter(
      (decoration) => decoration.from < decoration.to,
    );
    const widgetDecorations = decorations.filter(
      (decoration) => decoration.from === decoration.to,
    );

    assert.equal(nodeDecorations.length, 1);
    assert.equal(widgetDecorations.length, 1);
    assert.equal(nodeDecorations[0]!.from, userAuthoredPosition);
    assert.equal(
      nodeDecorations[0]!.to,
      userAuthoredPosition! + userAuthoredItem.nodeSize,
    );
    assert.equal(widgetDecorations[0]!.from, userAuthoredPosition! + 1);
    assert.equal(
      widgetDecorations[0]!.spec.revertNodeFrom,
      userAuthoredPosition,
    );

    const transaction = deleteUserAuthoredNode(
      state,
      userAuthoredPosition!,
    );
    assert.ok(transaction);
    assert.deepEqual(
      transaction.doc.child(1).children.map((item) => item.attrs.id),
      ["clause:generated"],
    );
  });

  it("decorates a generated clause changed by the user and restores it", () => {
    const generatedClause = editorSchema.node(
      "paragraph",
      { id: "clause:possession" },
      editorSchema.text("Give up possession"),
    );
    const editedClause = editorSchema.node(
      "paragraph",
      { id: "clause:possession" },
      editorSchema.text("Leave the property"),
    );
    const generatedDocument = editorSchema.node(
      "doc",
      null,
      generatedClause,
    );
    const plugin = createDiffStylingPlugin();
    let state = EditorState.create({
      schema: editorSchema,
      doc: editorSchema.node("doc", null, editedClause),
      plugins: [plugin],
    });

    state = state.apply(setGeneratedDocument(state.tr, generatedDocument));

    const decorationSet = plugin.props.decorations?.call(plugin, state);
    assert.ok(decorationSet instanceof DecorationSet);
    const modifiedDecorations = decorationSet.find(
      undefined,
      undefined,
      (spec) => spec.diffKind === "modified",
    );
    const revertDecorations = decorationSet.find(
      undefined,
      undefined,
      (spec) => spec.revertKind === "restore",
    );

    assert.equal(modifiedDecorations.length, 1);
    assert.equal(revertDecorations.length, 1);
    assert.equal(revertDecorations[0]!.from, 1);

    const transaction = restoreGeneratedNode(state, 0, generatedClause);
    assert.ok(transaction);
    assert.ok(transaction.doc.firstChild!.eq(generatedClause));
  });

  it("does not decorate an unchanged generated clause", () => {
    const generatedClause = editorSchema.node(
      "paragraph",
      { id: "clause:possession" },
      editorSchema.text("Give up possession"),
    );
    const generatedDocument = editorSchema.node(
      "doc",
      null,
      generatedClause,
    );
    const plugin = createDiffStylingPlugin();
    let state = EditorState.create({
      schema: editorSchema,
      doc: generatedDocument,
      plugins: [plugin],
    });

    state = state.apply(setGeneratedDocument(state.tr, generatedDocument));

    const decorationSet = plugin.props.decorations?.call(plugin, state);
    assert.ok(decorationSet instanceof DecorationSet);
    assert.equal(decorationSet.find().length, 0);
  });

  it("decorates a changed list item without decorating its list container", () => {
    const generatedItem = editorSchema.node(
      "list_item",
      { id: "clause:possession" },
      editorSchema.node(
        "paragraph",
        null,
        editorSchema.text("Give up possession"),
      ),
    );
    const editedItem = editorSchema.node(
      "list_item",
      { id: "clause:possession" },
      editorSchema.node(
        "paragraph",
        null,
        editorSchema.text("Leave the property"),
      ),
    );
    const generatedDocument = editorSchema.node(
      "doc",
      null,
      editorSchema.node(
        "ordered_list",
        { id: "container:clauses" },
        generatedItem,
      ),
    );
    const liveDocument = editorSchema.node(
      "doc",
      null,
      editorSchema.node(
        "ordered_list",
        { id: "container:clauses" },
        editedItem,
      ),
    );
    const plugin = createDiffStylingPlugin();
    let state = EditorState.create({
      schema: editorSchema,
      doc: liveDocument,
      plugins: [plugin],
    });

    state = state.apply(setGeneratedDocument(state.tr, generatedDocument));

    const decorationSet = plugin.props.decorations?.call(plugin, state);
    assert.ok(decorationSet instanceof DecorationSet);
    const modifiedDecorations = decorationSet.find(
      undefined,
      undefined,
      (spec) => spec.diffKind === "modified",
    );

    assert.equal(modifiedDecorations.length, 1);
    assert.equal(modifiedDecorations[0]!.from, 1);
    assert.equal(modifiedDecorations[0]!.to, 1 + editedItem.nodeSize);
  });

  it("deletes an ID-less list when undoing its only item", () => {
    const generatedClause = editorSchema.node(
      "paragraph",
      { id: "clause:introduction" },
      editorSchema.text("Introduction"),
    );
    const userAuthoredItem = editorSchema.node(
      "list_item",
      null,
      editorSchema.node(
        "paragraph",
        null,
        editorSchema.text("User authored"),
      ),
    );
    const userAuthoredList = editorSchema.node(
      "ordered_list",
      null,
      userAuthoredItem,
    );
    const doc = editorSchema.node("doc", null, [
      generatedClause,
      userAuthoredList,
    ]);
    const state = EditorState.create({ schema: editorSchema, doc });
    let itemPosition: number | undefined;

    doc.descendants((node, position) => {
      if (node === userAuthoredItem) itemPosition = position;
    });

    assert.notEqual(itemPosition, undefined);
    const transaction = deleteUserAuthoredNode(state, itemPosition!);

    assert.ok(transaction);
    assert.equal(transaction.doc.childCount, 1);
    assert.ok(transaction.doc.firstChild!.eq(generatedClause));
  });

  it("keeps an ID-less list when undoing one of several items", () => {
    const firstItem = editorSchema.node(
      "list_item",
      null,
      editorSchema.node("paragraph", null, editorSchema.text("First")),
    );
    const secondItem = editorSchema.node(
      "list_item",
      null,
      editorSchema.node("paragraph", null, editorSchema.text("Second")),
    );
    const doc = editorSchema.node(
      "doc",
      null,
      editorSchema.node("ordered_list", null, [firstItem, secondItem]),
    );
    const state = EditorState.create({ schema: editorSchema, doc });
    const transaction = deleteUserAuthoredNode(state, 1);

    assert.ok(transaction);
    assert.equal(transaction.doc.firstChild!.type.name, "ordered_list");
    assert.equal(transaction.doc.firstChild!.childCount, 1);
    assert.equal(transaction.doc.firstChild!.textContent, "Second");
  });
});
