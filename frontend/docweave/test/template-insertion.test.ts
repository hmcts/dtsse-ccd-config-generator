import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  EditorState,
  NodeSelection,
  type Selection,
  TextSelection,
  type Transaction,
} from "prosemirror-state";

import { editorSchema } from "../src/schema.js";
import { insertTemplate } from "../src/templates/insertion.js";

function paragraph(id: string | null, text: string) {
  return editorSchema.node("paragraph", { id }, editorSchema.text(text));
}

function listItem(id: string | null, text: string, nested?: ReturnType<
  typeof editorSchema.node
>) {
  return editorSchema.node(
    "list_item",
    { id },
    nested ? [paragraph(null, text), nested] : paragraph(null, text),
  );
}

function run(
  doc: ReturnType<typeof editorSchema.node>,
  selection: Selection,
  template = editorSchema.node(
    "doc",
    null,
    paragraph(null, "Template wording"),
  ),
) {
  const state = EditorState.create({
    schema: editorSchema,
    doc,
    selection,
  });
  let transaction: Transaction | undefined;

  insertTemplate(template)(state, (dispatched) => {
    transaction = dispatched;
  });

  assert.ok(transaction);
  return transaction.doc;
}

describe("template insertion", () => {
  it("inserts after a managed paragraph without splitting it", () => {
    const doc = editorSchema.node(
      "doc",
      null,
      paragraph("paragraph:managed", "Generated wording"),
    );
    const inserted = run(doc, TextSelection.create(doc, 5));

    assert.deepEqual(
      inserted.children.map((node) => node.attrs.id),
      ["paragraph:managed", null],
    );
  });

  it("inserts beside the relevant managed item in a nested list", () => {
    const nested = editorSchema.node(
      "ordered_list",
      { id: "ordered-list:nested" },
      listItem("item:nested", "Nested wording"),
    );
    const doc = editorSchema.node(
      "doc",
      null,
      editorSchema.node(
        "ordered_list",
        { id: "ordered-list:outer" },
        listItem("item:outer", "Outer wording", nested),
      ),
    );
    let nestedPosition = 0;
    doc.descendants((node, position) => {
      if (node.attrs.id === "item:nested") nestedPosition = position + 4;
    });

    const inserted = run(
      doc,
      TextSelection.create(doc, nestedPosition),
    );
    const insertedNestedList = inserted.firstChild!.firstChild!.lastChild!;

    assert.deepEqual(
      insertedNestedList.children.map((node) => node.attrs.id),
      ["item:nested", null],
    );
  });

  it("preserves a managed paragraph when a range is selected", () => {
    const doc = editorSchema.node(
      "doc",
      null,
      paragraph("paragraph:managed", "Generated wording"),
    );
    const inserted = run(doc, TextSelection.create(doc, 2, 6));

    assert.equal(inserted.firstChild!.textContent, "Generated wording");
    assert.equal(inserted.lastChild!.textContent, "Template wording");
  });

  it("inserts before a clause touched only at its start boundary", () => {
    const first = paragraph("paragraph:first", "First");
    const second = paragraph("paragraph:second", "Second");
    const doc = editorSchema.node("doc", null, [first, second]);
    const inserted = run(
      doc,
      TextSelection.create(doc, 2, first.nodeSize + 1),
    );

    assert.deepEqual(
      inserted.children.map((node) => node.attrs.id),
      ["paragraph:first", null, "paragraph:second"],
    );
  });

  it("inserts after a selected managed node instead of replacing it", () => {
    const first = paragraph(null, "First");
    const managed = paragraph("paragraph:managed", "Managed");
    const doc = editorSchema.node("doc", null, [first, managed]);
    const inserted = run(
      doc,
      NodeSelection.create(doc, first.nodeSize),
    );

    assert.deepEqual(
      inserted.children.map((node) => node.attrs.id),
      [null, "paragraph:managed", null],
    );
  });

  it("preserves a template's nested list when inserting into a list", () => {
    const doc = editorSchema.node(
      "doc",
      null,
      editorSchema.node(
        "ordered_list",
        null,
        listItem("item:managed", "Managed"),
      ),
    );
    const template = editorSchema.node("doc", null, [
      paragraph(null, "Introduction"),
      editorSchema.node("ordered_list", null, [
        listItem(null, "Nested one"),
        listItem(null, "Nested two"),
      ]),
    ]);
    const inserted = run(doc, TextSelection.create(doc, 5), template);
    const insertedItem = inserted.firstChild!.lastChild!;

    assert.equal(insertedItem.firstChild!.textContent, "Introduction");
    assert.equal(insertedItem.lastChild!.type, editorSchema.nodes.ordered_list);
    assert.deepEqual(
      insertedItem.lastChild!.children.map((item) => item.textContent),
      ["Nested one", "Nested two"],
    );
  });
});
