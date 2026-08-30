import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  EditorState,
  TextSelection,
  type Transaction,
} from "prosemirror-state";

import {
  indentListItem,
  outdentListItem,
  protectClausesFromSplittingOnEnter,
} from "../src/keymap.js";
import { editorSchema } from "../src/schema.js";

function pressEnter(doc: ReturnType<typeof editorSchema.node>, position: number) {
  const state = EditorState.create({
    schema: editorSchema,
    doc,
    selection: TextSelection.create(doc, position),
  });
  let transaction: Transaction | undefined;

  const handled = protectClausesFromSplittingOnEnter(
    state,
    (dispatched) => {
      transaction = dispatched;
    },
  );

  assert.equal(handled, true);
  assert.ok(transaction);
  return transaction;
}

describe("protected clause Enter handling", () => {
  it("inserts before a generated paragraph when the cursor is at its start", () => {
    const generated = editorSchema.node(
      "paragraph",
      { id: "clause:generated" },
      editorSchema.text("Generated"),
    );
    const doc = editorSchema.node("doc", null, generated);
    const transaction = pressEnter(doc, 1);

    assert.deepEqual(
      transaction.doc.children.map((node) => node.attrs.id),
      [null, "clause:generated"],
    );
  });

  it("inserts after a generated paragraph when the cursor is within it", () => {
    const generated = editorSchema.node(
      "paragraph",
      { id: "clause:generated" },
      editorSchema.text("Generated"),
    );
    const doc = editorSchema.node("doc", null, generated);
    const transaction = pressEnter(doc, 4);

    assert.deepEqual(
      transaction.doc.children.map((node) => node.attrs.id),
      ["clause:generated", null],
    );
  });

  it("inserts before a generated list item at the start of its clause", () => {
    const generated = editorSchema.node(
      "list_item",
      { id: "clause:generated" },
      editorSchema.node("paragraph", null, editorSchema.text("Generated")),
    );
    const list = editorSchema.node(
      "ordered_list",
      { id: "container:clauses" },
      generated,
    );
    const doc = editorSchema.node("doc", null, list);
    const transaction = pressEnter(doc, 3);

    assert.deepEqual(
      transaction.doc.firstChild!.children.map((node) => node.attrs.id),
      [null, "clause:generated"],
    );
  });

  it("inserts after a generated list item at the end of its clause", () => {
    const generated = editorSchema.node(
      "list_item",
      { id: "clause:generated" },
      editorSchema.node("paragraph", null, editorSchema.text("Generated")),
    );
    const list = editorSchema.node(
      "ordered_list",
      { id: "container:clauses" },
      generated,
    );
    const doc = editorSchema.node("doc", null, list);
    const transaction = pressEnter(doc, 12);

    assert.deepEqual(
      transaction.doc.firstChild!.children.map((node) => node.attrs.id),
      ["clause:generated", null],
    );
  });
});

function listItem(id: string, text: string) {
  return editorSchema.node(
    "list_item",
    { id },
    editorSchema.node("paragraph", null, editorSchema.text(text)),
  );
}

function positionInsideClause(
  doc: ReturnType<typeof editorSchema.node>,
  id: string,
): number {
  let position: number | undefined;

  doc.descendants((node, nodePosition) => {
    if (node.attrs.id === id) {
      position = nodePosition + 2;
      return false;
    }
    return true;
  });

  if (position === undefined) {
    throw new Error(`Clause not found: ${id}`);
  }
  return position;
}

function runListCommand(
  command: typeof indentListItem,
  doc: ReturnType<typeof editorSchema.node>,
  clauseId: string,
) {
  const state = EditorState.create({
    schema: editorSchema,
    doc,
    selection: TextSelection.create(
      doc,
      positionInsideClause(doc, clauseId),
    ),
  });
  let transaction: Transaction | undefined;

  const handled = command(state, (dispatched) => {
    transaction = dispatched;
  });

  assert.equal(handled, true);
  assert.ok(transaction);
  return transaction.doc;
}

describe("list indentation", () => {
  it("indents the current item beneath the previous item", () => {
    const doc = editorSchema.node(
      "doc",
      null,
      editorSchema.node("ordered_list", { id: "container:clauses" }, [
        listItem("clause:first", "One"),
        listItem("clause:second", "Two"),
      ]),
    );

    const indented = runListCommand(
      indentListItem,
      doc,
      "clause:second",
    );
    const topLevelList = indented.firstChild!;
    const firstItem = topLevelList.firstChild!;
    const nestedList = firstItem.lastChild!;

    assert.equal(topLevelList.childCount, 1);
    assert.equal(nestedList.type.name, "ordered_list");
    assert.equal(nestedList.firstChild!.attrs.id, "clause:second");
  });

  it("outdents a nested item to its parent's level", () => {
    const secondItem = listItem("clause:second", "Two");
    const firstItem = editorSchema.node(
      "list_item",
      { id: "clause:first" },
      [
        editorSchema.node("paragraph", null, editorSchema.text("One")),
        editorSchema.node("ordered_list", null, secondItem),
      ],
    );
    const doc = editorSchema.node(
      "doc",
      null,
      editorSchema.node(
        "ordered_list",
        { id: "container:clauses" },
        firstItem,
      ),
    );

    const outdented = runListCommand(
      outdentListItem,
      doc,
      "clause:second",
    );
    const topLevelList = outdented.firstChild!;

    assert.equal(topLevelList.childCount, 2);
    assert.equal(topLevelList.lastChild!.attrs.id, "clause:second");
    assert.equal(topLevelList.firstChild!.childCount, 1);
  });
});
