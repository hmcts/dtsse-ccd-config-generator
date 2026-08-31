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

  it("updates untouched generated list-item wording", () => {
    const generatedItem = (text: string) =>
      editorSchema.node(
        "list_item",
        { id: "item:generated" },
        editorSchema.node("paragraph", null, editorSchema.text(text)),
      );
    const userItem = editorSchema.node(
      "list_item",
      null,
      editorSchema.node("paragraph", null, editorSchema.text("User clause")),
    );
    const list = (children: ReturnType<typeof generatedItem>[]) =>
      editorSchema.node(
        "ordered_list",
        { id: "ordered-list:clauses" },
        children,
      );
    const previousItem = generatedItem("Old wording");
    const targetItem = generatedItem("New wording");
    const previous = editorSchema.node("doc", null, list([previousItem]));
    const target = editorSchema.node("doc", null, list([targetItem]));
    const transaction = EditorState.create({
      schema: editorSchema,
      doc: editorSchema.node("doc", null, list([previousItem, userItem])),
    }).tr;

    reconcileOrderDocument(transaction, previous, target);

    assert.equal(
      transaction.doc.firstChild!.firstChild!.textContent,
      "New wording",
    );
    assert.equal(
      transaction.doc.firstChild!.lastChild!.textContent,
      "User clause",
    );
  });

  it("adds a nested generated list without replacing edited parent content", () => {
    const paragraph = (text: string) =>
      editorSchema.node("paragraph", null, editorSchema.text(text));
    const generatedSubclause = editorSchema.node(
      "list_item",
      { id: "item:subclause-a" },
      paragraph("Generated subclause"),
    );
    const userSubclause = editorSchema.node(
      "list_item",
      null,
      paragraph("User subclause"),
    );
    const previousItem = editorSchema.node(
      "list_item",
      { id: "item:parent" },
      paragraph("Parent clause"),
    );
    const targetItem = editorSchema.node(
      "list_item",
      { id: "item:parent" },
      [
        paragraph("Parent clause"),
        editorSchema.node(
          "ordered_list",
          { id: "ordered-list:subclauses" },
          generatedSubclause,
        ),
      ],
    );
    const liveItem = editorSchema.node(
      "list_item",
      { id: "item:parent" },
      [
        paragraph("Edited parent clause"),
        editorSchema.node("ordered_list", null, userSubclause),
      ],
    );
    const documentWith = (item: typeof previousItem) =>
      editorSchema.node(
        "doc",
        null,
        editorSchema.node(
          "ordered_list",
          { id: "ordered-list:clauses" },
          item,
        ),
      );
    const transaction = EditorState.create({
      schema: editorSchema,
      doc: documentWith(liveItem),
    }).tr;

    reconcileOrderDocument(
      transaction,
      documentWith(previousItem),
      documentWith(targetItem),
    );

    const reconciledItem = transaction.doc.firstChild!.firstChild!;
    const reconciledList = reconciledItem.lastChild!;
    assert.equal(reconciledItem.firstChild!.textContent, "Edited parent clause");
    assert.equal(reconciledList.attrs.id, "ordered-list:subclauses");
    assert.deepEqual(
      reconciledList.children.map((item) => item.attrs.id),
      ["item:subclause-a", null],
    );
  });

  it("removes a nested generated list without removing user subclauses", () => {
    const paragraph = (text: string) =>
      editorSchema.node("paragraph", null, editorSchema.text(text));
    const generatedSubclause = editorSchema.node(
      "list_item",
      { id: "item:subclause-a" },
      paragraph("Generated subclause"),
    );
    const userSubclause = editorSchema.node(
      "list_item",
      null,
      paragraph("User subclause"),
    );
    const nestedList = (children: typeof generatedSubclause[]) =>
      editorSchema.node(
        "ordered_list",
        { id: "ordered-list:subclauses" },
        children,
      );
    const previousItem = editorSchema.node(
      "list_item",
      { id: "item:parent" },
      [paragraph("Parent clause"), nestedList([generatedSubclause])],
    );
    const targetItem = editorSchema.node(
      "list_item",
      { id: "item:parent" },
      paragraph("Parent clause"),
    );
    const liveItem = editorSchema.node(
      "list_item",
      { id: "item:parent" },
      [
        paragraph("Edited parent clause"),
        nestedList([generatedSubclause, userSubclause]),
      ],
    );
    const documentWith = (item: typeof previousItem) =>
      editorSchema.node(
        "doc",
        null,
        editorSchema.node(
          "ordered_list",
          { id: "ordered-list:clauses" },
          item,
        ),
      );
    const transaction = EditorState.create({
      schema: editorSchema,
      doc: documentWith(liveItem),
    }).tr;

    reconcileOrderDocument(
      transaction,
      documentWith(previousItem),
      documentWith(targetItem),
    );

    const reconciledItem = transaction.doc.firstChild!.firstChild!;
    const reconciledList = reconciledItem.lastChild!;
    assert.equal(reconciledItem.firstChild!.textContent, "Edited parent clause");
    assert.equal(reconciledList.type.name, "ordered_list");
    assert.equal(reconciledList.attrs.id, null);
    assert.equal(reconciledList.childCount, 1);
    assert.equal(reconciledList.firstChild!.textContent, "User subclause");
  });

  it("removes the nested list when its final generated subclause is removed", () => {
    const withSubclause = (includeSubclause: boolean) =>
      buildOrder((order) => {
        order.orderedList("clauses", (list) => {
          list.item("parent", "Parent clause", (item) => {
            if (includeSubclause) {
              item.orderedList("subclauses", (subclauses) => {
                subclauses.item("subclause-a", "Generated subclause");
              });
            }
          });
        });
      });
    const previous = withSubclause(true);
    const target = withSubclause(false);
    const transaction = EditorState.create({
      schema: editorSchema,
      doc: previous,
    }).tr;

    reconcileOrderDocument(transaction, previous, target);

    const reconciledItem = transaction.doc.firstChild!.firstChild!;
    assert.equal(reconciledItem.childCount, 1);
    assert.equal(reconciledItem.firstChild!.textContent, "Parent clause");
  });
});
