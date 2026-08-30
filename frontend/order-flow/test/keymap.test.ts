import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  EditorState,
  TextSelection,
  type Transaction,
} from "prosemirror-state";

import { protectClausesFromSplittingOnEnter } from "../src/keymap.js";
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
