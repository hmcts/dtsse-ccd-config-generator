import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { EditorState } from "prosemirror-state";

import { createOrderBuilder } from "../src/builder.js";
import { reconcileOrderDocument } from "../src/reconciliation.js";
import { editorSchema } from "../src/schema.js";

function buildOrder(text: string, formValue: string) {
  const builder = createOrderBuilder();

  builder.buildList("order-clauses")
    .listItem("give-possession")
    .text(text)
    .formValue("deadline", formValue)
    .text(".")
    .build();

  return builder.build();
}

describe("order document reconciliation", () => {
  it("updates a form value without replacing edited clause text", () => {
    const liveDocument = buildOrder(
      "The defendants must leave the property before ",
      "29 August 2026",
    );
    const targetDocument = buildOrder(
      "The defendants must give up possession on or before ",
      "12 September 2026",
    );
    const transaction = reconcileOrderDocument(
      EditorState.create({ schema: editorSchema, doc: liveDocument }).tr,
      targetDocument,
    );
    const paragraph = transaction.doc.firstChild!.firstChild!.firstChild!;

    assert.equal(
      paragraph.firstChild!.text,
      "The defendants must leave the property before ",
    );
    assert.equal(paragraph.child(1).attrs.text, "12 September 2026");
    assert.equal(paragraph.lastChild!.text, ".");
  });

  it("replaces an ordinary generated clause that changed", () => {
    const liveBuilder = createOrderBuilder();
    liveBuilder.setClause("heading", "Old heading");
    const targetBuilder = createOrderBuilder();
    targetBuilder.setClause("heading", "New heading");

    const transaction = reconcileOrderDocument(
      EditorState.create({
        schema: editorSchema,
        doc: liveBuilder.build(),
      }).tr,
      targetBuilder.build(),
    );

    assert.equal(transaction.doc.firstChild!.textContent, "New heading");
  });

  it("inserts and removes managed list items", () => {
    const liveBuilder = createOrderBuilder();
    liveBuilder.buildList("order-clauses")
      .listItem("keep", "Keep")
      .listItem("remove", "Remove")
      .build();
    const targetBuilder = createOrderBuilder();
    targetBuilder.buildList("order-clauses")
      .listItem("keep", "Keep")
      .listItem("insert", "Insert")
      .build();

    const transaction = reconcileOrderDocument(
      EditorState.create({
        schema: editorSchema,
        doc: liveBuilder.build(),
      }).tr,
      targetBuilder.build(),
    );
    const list = transaction.doc.firstChild!;

    assert.deepEqual(
      list.children.map((item) => item.attrs.id),
      ["clause:keep", "clause:insert"],
    );
  });
});
