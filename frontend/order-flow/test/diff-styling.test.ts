import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  type Command,
  EditorState,
  TextSelection,
  type Transaction,
} from "prosemirror-state";
import { DecorationSet } from "prosemirror-view";

import {
  createDiffStylingPlugin,
  deleteUserAuthoredNode,
  getGeneratedDocument,
  restoreGeneratedNode,
  setGeneratedDocument,
} from "../src/diff-styling.js";
import { indentListItem, outdentListItem } from "../src/keymap.js";
import { editorSchema } from "../src/schema.js";

function listItem(id: string | null, text: string) {
  return editorSchema.node(
    "list_item",
    { id },
    editorSchema.node("paragraph", null, editorSchema.text(text)),
  );
}

function positionInsideClause(
  doc: ReturnType<typeof editorSchema.node>,
  text: string,
): number {
  let position: number | undefined;

  doc.descendants((node, nodePosition) => {
    if (node.type.name === "list_item" && node.textContent === text) {
      position = nodePosition + 2;
      return false;
    }
    return true;
  });

  if (position === undefined) throw new Error(`Clause not found: ${text}`);
  return position;
}

function listCommandTransaction(
  state: EditorState,
  command: Command,
  clauseText: string,
): Transaction {
  const stateWithSelection = state.apply(
    state.tr.setSelection(
      TextSelection.create(
        state.doc,
        positionInsideClause(state.doc, clauseText),
      ),
    ),
  );
  let transaction: Transaction | undefined;

  assert.equal(
    command(stateWithSelection, (dispatched) => {
      transaction = dispatched;
    }),
    true,
  );
  assert.ok(transaction);
  return transaction;
}

describe("diff styling", () => {
  it("retains the latest generated document as reconciliation baseline", () => {
    const generatedDocument = editorSchema.node(
      "doc",
      null,
      editorSchema.node(
        "paragraph",
        { id: "clause:generated" },
        editorSchema.text("Generated"),
      ),
    );
    const state = EditorState.create({
      schema: editorSchema,
      plugins: [createDiffStylingPlugin()],
    });
    const stateWithBaseline = state.apply(
      setGeneratedDocument(state.tr, generatedDocument),
    );
    const stateAfterEdit = stateWithBaseline.apply(
      stateWithBaseline.tr.insertText("Edited", 1),
    );

    assert.ok(getGeneratedDocument(stateWithBaseline)?.eq(generatedDocument));
    assert.ok(getGeneratedDocument(stateAfterEdit)?.eq(generatedDocument));
  });

  it("rejects deletion of a generated clause", () => {
    const generatedClause = editorSchema.node(
      "paragraph",
      { id: "clause:generated" },
      editorSchema.text("Generated"),
    );
    const plugin = createDiffStylingPlugin();
    const state = EditorState.create({
      schema: editorSchema,
      doc: editorSchema.node("doc", null, generatedClause),
      plugins: [plugin],
    });

    const result = state.applyTransaction(
      state.tr.delete(0, generatedClause.nodeSize),
    );

    assert.equal(result.transactions.length, 0);
    assert.ok(result.state.doc.firstChild!.eq(generatedClause));
  });

  it("rejects indenting a generated clause", () => {
    const doc = editorSchema.node(
      "doc",
      null,
      editorSchema.node("ordered_list", { id: "container:clauses" }, [
        listItem("clause:first", "First"),
        listItem("clause:second", "Second"),
      ]),
    );
    const state = EditorState.create({
      schema: editorSchema,
      doc,
      plugins: [createDiffStylingPlugin()],
    });
    const transaction = listCommandTransaction(
      state,
      indentListItem,
      "Second",
    );

    const result = state.applyTransaction(transaction);

    assert.equal(result.transactions.length, 0);
    assert.ok(result.state.doc.eq(doc));
  });

  it("rejects outdenting a generated clause", () => {
    const nested = listItem("clause:second", "Second");
    const parent = editorSchema.node(
      "list_item",
      { id: "clause:first" },
      [
        editorSchema.node("paragraph", null, editorSchema.text("First")),
        editorSchema.node("ordered_list", null, nested),
      ],
    );
    const doc = editorSchema.node(
      "doc",
      null,
      editorSchema.node(
        "ordered_list",
        { id: "container:clauses" },
        parent,
      ),
    );
    const state = EditorState.create({
      schema: editorSchema,
      doc,
      plugins: [createDiffStylingPlugin()],
    });
    const transaction = listCommandTransaction(
      state,
      outdentListItem,
      "Second",
    );

    const result = state.applyTransaction(transaction);

    assert.equal(result.transactions.length, 0);
    assert.ok(result.state.doc.eq(doc));
  });

  it("rejects moving a generated clause between generated parents", () => {
    const second = listItem("clause:second", "Second");
    const first = editorSchema.node(
      "list_item",
      { id: "clause:first" },
      [
        editorSchema.node("paragraph", null, editorSchema.text("First")),
        editorSchema.node("ordered_list", null, second),
      ],
    );
    const third = listItem("clause:third", "Third");
    const doc = editorSchema.node(
      "doc",
      null,
      editorSchema.node(
        "ordered_list",
        { id: "container:clauses" },
        [first, third],
      ),
    );
    const movedFirst = listItem("clause:first", "First");
    const movedThird = editorSchema.node(
      "list_item",
      { id: "clause:third" },
      [
        editorSchema.node("paragraph", null, editorSchema.text("Third")),
        editorSchema.node("ordered_list", null, second),
      ],
    );
    const target = editorSchema.node(
      "doc",
      null,
      editorSchema.node(
        "ordered_list",
        { id: "container:clauses" },
        [movedFirst, movedThird],
      ),
    );
    const state = EditorState.create({
      schema: editorSchema,
      doc,
      plugins: [createDiffStylingPlugin()],
    });

    const result = state.applyTransaction(
      state.tr.replaceWith(0, state.doc.content.size, target.content),
    );

    assert.equal(result.transactions.length, 0);
    assert.ok(result.state.doc.eq(doc));
  });

  it("rejects reordering generated clauses within their parent", () => {
    const first = listItem("clause:first", "First");
    const second = listItem("clause:second", "Second");
    const doc = editorSchema.node(
      "doc",
      null,
      editorSchema.node(
        "ordered_list",
        { id: "container:clauses" },
        [first, second],
      ),
    );
    const reordered = editorSchema.node(
      "doc",
      null,
      editorSchema.node(
        "ordered_list",
        { id: "container:clauses" },
        [second, first],
      ),
    );
    const state = EditorState.create({
      schema: editorSchema,
      doc,
      plugins: [createDiffStylingPlugin()],
    });

    const result = state.applyTransaction(
      state.tr.replaceWith(0, state.doc.content.size, reordered.content),
    );

    assert.equal(result.transactions.length, 0);
    assert.ok(result.state.doc.eq(doc));
  });

  it("allows indenting a user-authored clause", () => {
    const doc = editorSchema.node(
      "doc",
      null,
      editorSchema.node("ordered_list", { id: "container:clauses" }, [
        listItem("clause:generated", "Generated"),
        listItem(null, "User authored"),
      ]),
    );
    const state = EditorState.create({
      schema: editorSchema,
      doc,
      plugins: [createDiffStylingPlugin()],
    });
    const transaction = listCommandTransaction(
      state,
      indentListItem,
      "User authored",
    );

    const result = state.applyTransaction(transaction);

    assert.equal(result.transactions.length, 1);
    assert.equal(result.state.doc.firstChild!.childCount, 1);
  });

  it("allows outdenting a user-authored clause", () => {
    const userAuthored = listItem(null, "User authored");
    const generated = editorSchema.node(
      "list_item",
      { id: "clause:generated" },
      [
        editorSchema.node("paragraph", null, editorSchema.text("Generated")),
        editorSchema.node("ordered_list", null, userAuthored),
      ],
    );
    const doc = editorSchema.node(
      "doc",
      null,
      editorSchema.node(
        "ordered_list",
        { id: "container:clauses" },
        generated,
      ),
    );
    const state = EditorState.create({
      schema: editorSchema,
      doc,
      plugins: [createDiffStylingPlugin()],
    });
    const transaction = listCommandTransaction(
      state,
      outdentListItem,
      "User authored",
    );

    const result = state.applyTransaction(transaction);

    assert.equal(result.transactions.length, 1);
    assert.equal(result.state.doc.firstChild!.childCount, 2);
  });

  it("rejects deletion of a form value", () => {
    const formValue = editorSchema.node("form_value", {
      id: "fact:generated:date",
      text: "30 August 2026",
    });
    const generatedClause = editorSchema.node(
      "paragraph",
      { id: "clause:generated" },
      [editorSchema.text("By "), formValue],
    );
    const plugin = createDiffStylingPlugin();
    const state = EditorState.create({
      schema: editorSchema,
      doc: editorSchema.node("doc", null, generatedClause),
      plugins: [plugin],
    });

    const result = state.applyTransaction(state.tr.delete(4, 5));

    assert.equal(result.transactions.length, 0);
    assert.ok(result.state.doc.firstChild!.lastChild!.eq(formValue));
  });

  it("allows a generated clause to be edited blank", () => {
    const generatedClause = editorSchema.node(
      "paragraph",
      { id: "clause:generated" },
      editorSchema.text("Generated"),
    );
    const plugin = createDiffStylingPlugin();
    const state = EditorState.create({
      schema: editorSchema,
      doc: editorSchema.node("doc", null, generatedClause),
      plugins: [plugin],
    });

    const result = state.applyTransaction(
      state.tr.delete(1, generatedClause.content.size + 1),
    );

    assert.equal(result.transactions.length, 1);
    assert.equal(result.state.doc.firstChild!.attrs.id, "clause:generated");
    assert.equal(result.state.doc.firstChild!.textContent, "");
  });

  it("allows reconciliation to remove a generated clause", () => {
    const keptClause = editorSchema.node(
      "paragraph",
      { id: "clause:keep" },
      editorSchema.text("Keep"),
    );
    const removedClause = editorSchema.node(
      "paragraph",
      { id: "clause:remove" },
      editorSchema.text("Remove"),
    );
    const target = editorSchema.node("doc", null, keptClause);
    const plugin = createDiffStylingPlugin();
    const state = EditorState.create({
      schema: editorSchema,
      doc: editorSchema.node("doc", null, [keptClause, removedClause]),
      plugins: [plugin],
    });
    const transaction = state.tr.delete(
      keptClause.nodeSize,
      keptClause.nodeSize + removedClause.nodeSize,
    );

    const result = state.applyTransaction(
      setGeneratedDocument(transaction, target),
    );

    assert.equal(result.transactions.length, 1);
    assert.ok(result.state.doc.eq(target));
  });

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
    assert.equal(typeof widgetDecorations[0]!.spec.revert, "function");

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
      (spec) => typeof spec.revert === "function",
    );

    assert.equal(modifiedDecorations.length, 1);
    assert.equal(revertDecorations.length, 1);
    assert.equal(revertDecorations[0]!.from, 1);

    const transaction = restoreGeneratedNode(state, 0, generatedClause);
    assert.ok(transaction);
    assert.ok(transaction.doc.firstChild!.eq(generatedClause));
  });

  it("restores a list item's own content without removing its children", () => {
    const generatedItem = editorSchema.node(
      "list_item",
      { id: "clause:possession" },
      editorSchema.node(
        "paragraph",
        null,
        editorSchema.text("Give up possession"),
      ),
    );
    const firstChild = editorSchema.node(
      "list_item",
      null,
      editorSchema.node("paragraph", null, editorSchema.text("Unless x")),
    );
    const secondChild = editorSchema.node(
      "list_item",
      null,
      editorSchema.node("paragraph", null, editorSchema.text("And y")),
    );
    const nestedList = editorSchema.node(
      "ordered_list",
      null,
      [firstChild, secondChild],
    );
    const editedItem = editorSchema.node(
      "list_item",
      { id: "clause:possession" },
      [
        editorSchema.node(
          "paragraph",
          null,
          editorSchema.text("Give up possession caveat x"),
        ),
        nestedList,
      ],
    );
    const doc = editorSchema.node(
      "doc",
      null,
      editorSchema.node(
        "ordered_list",
        { id: "container:clauses" },
        editedItem,
      ),
    );
    const state = EditorState.create({ schema: editorSchema, doc });

    const transaction = restoreGeneratedNode(state, 1, generatedItem);

    assert.ok(transaction);
    const restoredItem = transaction.doc.firstChild!.firstChild!;
    assert.ok(restoredItem.firstChild!.eq(generatedItem.firstChild!));
    assert.ok(restoredItem.lastChild!.eq(nestedList));
    assert.equal(restoredItem.childCount, 2);
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

  it("does not mark a generated item changed when it gains a user-authored child", () => {
    const generatedItem = editorSchema.node(
      "list_item",
      { id: "clause:possession" },
      editorSchema.node(
        "paragraph",
        null,
        editorSchema.text("Give up possession"),
      ),
    );
    const userAuthoredItem = editorSchema.node(
      "list_item",
      null,
      editorSchema.node(
        "paragraph",
        null,
        editorSchema.text("And another thing"),
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
    const liveItem = editorSchema.node(
      "list_item",
      { id: "clause:possession" },
      [
        generatedItem.firstChild!,
        editorSchema.node("ordered_list", null, userAuthoredItem),
      ],
    );
    const liveDocument = editorSchema.node(
      "doc",
      null,
      editorSchema.node(
        "ordered_list",
        { id: "container:clauses" },
        liveItem,
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
    assert.equal(
      decorationSet.find(
        undefined,
        undefined,
        (spec) => spec.diffKind === "modified",
      ).length,
      0,
    );
    assert.equal(
      decorationSet.find(
        undefined,
        undefined,
        (spec) => spec.diffKind === "inserted",
      ).length,
      1,
    );
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
