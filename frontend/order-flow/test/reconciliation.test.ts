import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { EditorState } from "prosemirror-state";

import { buildOrder } from "../src/builder.js";
import { reconcileOrderDocument } from "../src/reconciliation.js";
import { editorSchema } from "../src/schema.js";

describe("order document reconciliation", () => {
  it("is a no-op", () => {
    const live = buildOrder((order) => {
      order.paragraph("heading", "Edited heading");
    });
    const previousTarget = buildOrder((order) => {
      order.paragraph("heading", "Old heading");
    });
    const target = buildOrder((order) => {
      order.paragraph("heading", "New heading");
    });
    const transaction = EditorState.create({
      schema: editorSchema,
      doc: live,
    }).tr;

    const result = reconcileOrderDocument(
      transaction,
      previousTarget,
      target,
    );

    assert.equal(result, transaction);
    assert.equal(result.steps.length, 0);
    assert.equal(result.doc.firstChild!.textContent, "Edited heading");
  });

  it("updates generated text while preserving edited surrounding text", () => {
    const orderWithDate = (prefix: string, date: string) =>
      buildOrder((order) => {
        order.paragraph("deadline", (content) => {
          content.text(prefix).generatedText("date", date).text(".");
        });
      });
    const previousTarget = orderWithDate("Payment is due by ", "1 September");
    const live = orderWithDate("Please pay by ", "1 September");
    const target = orderWithDate("Payment is due by ", "8 September");
    const transaction = EditorState.create({
      schema: editorSchema,
      doc: live,
    }).tr;

    reconcileOrderDocument(transaction, previousTarget, target);

    const paragraph = transaction.doc.firstChild!;
    assert.equal(paragraph.firstChild!.text, "Please pay by ");
    assert.equal(paragraph.child(1).type.name, "generated_text");
    assert.equal(paragraph.child(1).attrs.text, "8 September");
    assert.equal(
      paragraph.child(1).attrs.id,
      "generated-text:paragraph:deadline:date",
    );
  });

  it("updates managed container markup without removing user content", () => {
    const generatedItem = editorSchema.node(
      "list_item",
      { id: "item:generated" },
      editorSchema.node("paragraph", null, editorSchema.text("Generated")),
    );
    const userItem = editorSchema.node(
      "list_item",
      null,
      editorSchema.node("paragraph", null, editorSchema.text("User")),
    );
    const previousList = editorSchema.node(
      "ordered_list",
      { id: "ordered-list:clauses", order: 1 },
      generatedItem,
    );
    const targetList = editorSchema.node(
      "ordered_list",
      { id: "ordered-list:clauses", order: 3 },
      generatedItem,
    );
    const liveList = editorSchema.node(
      "ordered_list",
      { id: "ordered-list:clauses", order: 1 },
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
