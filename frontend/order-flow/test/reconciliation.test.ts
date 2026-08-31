import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { EditorState } from "prosemirror-state";

import { createOrderBuilder } from "../src/builder.js";
import { reconcileOrderDocument } from "../src/reconciliation.js";
import { editorSchema } from "../src/schema.js";

describe("order document reconciliation", () => {
  it("is a no-op", () => {
    const liveBuilder = createOrderBuilder();
    liveBuilder.setClause("heading", "Edited heading");
    const previousTargetBuilder = createOrderBuilder();
    previousTargetBuilder.setClause("heading", "Old heading");
    const targetBuilder = createOrderBuilder();
    targetBuilder.setClause("heading", "New heading");
    const transaction = EditorState.create({
      schema: editorSchema,
      doc: liveBuilder.build(),
    }).tr;

    const result = reconcileOrderDocument(
      transaction,
      previousTargetBuilder.build(),
      targetBuilder.build(),
    );

    assert.equal(result, transaction);
    assert.equal(result.steps.length, 0);
    assert.equal(result.doc.firstChild!.textContent, "Edited heading");
  });

  it("updates managed container markup without removing user content", () => {
    const generatedItem = editorSchema.node(
      "list_item",
      { id: "clause:generated" },
      editorSchema.node("paragraph", null, editorSchema.text("Generated")),
    );
    const userItem = editorSchema.node(
      "list_item",
      null,
      editorSchema.node("paragraph", null, editorSchema.text("User")),
    );
    const previousList = editorSchema.node(
      "ordered_list",
      { id: "container:clauses", order: 1 },
      generatedItem,
    );
    const targetList = editorSchema.node(
      "ordered_list",
      { id: "container:clauses", order: 3 },
      generatedItem,
    );
    const liveList = editorSchema.node(
      "ordered_list",
      { id: "container:clauses", order: 1 },
      [generatedItem, userItem],
    );
    const previous = editorSchema.node("doc", null, previousList);
    const target = editorSchema.node("doc", null, targetList);
    const transaction = EditorState.create({
      schema: editorSchema,
      doc: editorSchema.node("doc", null, liveList),
    }).tr;

    reconcileOrderDocument(transaction, previous, target);

    assert.equal(transaction.doc.firstChild!.attrs.order, 3);
    assert.equal(transaction.doc.firstChild!.childCount, 2);
    assert.equal(transaction.doc.firstChild!.lastChild!.textContent, "User");
  });
});
