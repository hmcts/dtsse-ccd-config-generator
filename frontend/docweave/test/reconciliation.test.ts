import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { EditorState } from "prosemirror-state";

import { buildOrder } from "../src/builder.js";
import { reconcileOrderDocument } from "../src/reconciliation.js";
import { editorSchema } from "../src/schema.js";

describe("order document reconciliation", () => {
  it("replaces directly edited paragraph wording when reference wording changes", () => {
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
    assert.equal(result.steps.length, 1);
    assert.equal(result.doc.firstChild!.textContent, "New heading");
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

  it("updates generated text while preserving edited list-item wording", () => {
    const orderWithDate = (prefix: string, date: string) =>
      buildOrder((order) => {
        order.orderedList("clauses", (list) => {
          list.item("deadline", (content) => {
            content.text(prefix).generatedText("date", date).text(".");
          });
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

    const paragraph = transaction.doc.firstChild!.firstChild!.firstChild!;
    assert.equal(paragraph.firstChild!.text, "Please pay by ");
    assert.equal(paragraph.child(1).attrs.text, "8 September");
  });

  it("adds generated text when replacing edited list-item wording", () => {
    const orderWith = (
      wording: string,
      generatedText: string | undefined,
    ) =>
      buildOrder((order) => {
        order.orderedList("clauses", (list) => {
          list.item("deadline", (content) => {
            content.text(wording);
            if (generatedText) {
              content.generatedText("date", generatedText);
            }
          });
        });
      });
    const previousTarget = orderWith("Old wording", undefined);
    const live = orderWith("Judge-edited wording", undefined);
    const target = orderWith("New wording: ", "8 September");
    const transaction = EditorState.create({
      schema: editorSchema,
      doc: live,
    }).tr;

    reconcileOrderDocument(transaction, previousTarget, target);

    const item = transaction.doc.firstChild!.firstChild!;
    assert.equal(item.childCount, 1);
    assert.equal(item.firstChild!.childCount, 2);
    assert.equal(item.firstChild!.textContent, "New wording: 8 September");
  });

  it("removes generated text when replacing edited list-item wording", () => {
    const withGeneratedText = (wording: string) =>
      buildOrder((order) => {
        order.orderedList("clauses", (list) => {
          list.item("deadline", (content) => {
            content.text(wording).generatedText("date", "1 September");
          });
        });
      });
    const previousTarget = withGeneratedText("Old wording: ");
    const live = withGeneratedText("Judge-edited wording: ");
    const target = buildOrder((order) => {
      order.orderedList("clauses", (list) => {
        list.item("deadline", "New wording");
      });
    });
    const transaction = EditorState.create({
      schema: editorSchema,
      doc: live,
    }).tr;

    reconcileOrderDocument(transaction, previousTarget, target);

    const item = transaction.doc.firstChild!.firstChild!;
    assert.equal(item.childCount, 1);
    assert.equal(item.firstChild!.childCount, 1);
    assert.equal(item.textContent, "New wording");
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

  it("updates generated list-item wording when it contains a user-authored subclause", () => {
    const paragraph = (text: string) =>
      editorSchema.node("paragraph", null, editorSchema.text(text));
    const generatedItem = (text: string) =>
      editorSchema.node(
        "list_item",
        { id: "item:generated" },
        paragraph(text),
      );
    const userSubclause = editorSchema.node(
      "list_item",
      null,
      paragraph("User-authored subclause"),
    );
    const liveItem = editorSchema.node(
      "list_item",
      { id: "item:generated" },
      [
        paragraph("Old wording"),
        editorSchema.node("ordered_list", null, userSubclause),
      ],
    );
    const documentWith = (item: typeof liveItem) =>
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
      documentWith(generatedItem("Old wording")),
      documentWith(generatedItem("New wording")),
    );

    const reconciledItem = transaction.doc.firstChild!.firstChild!;
    assert.deepEqual(
      {
        wording: reconciledItem.firstChild!.textContent,
        nestedListId: reconciledItem.lastChild!.attrs.id,
        nestedText: reconciledItem.lastChild!.firstChild!.textContent,
      },
      {
        wording: "New wording",
        nestedListId: null,
        nestedText: "User-authored subclause",
      },
    );
  });

  it("replaces directly edited list-item wording when reference wording changes", () => {
    const paragraph = (text: string) =>
      editorSchema.node("paragraph", null, editorSchema.text(text));
    const generatedItem = (text: string) =>
      editorSchema.node(
        "list_item",
        { id: "item:generated" },
        paragraph(text),
      );
    const userSubclause = editorSchema.node(
      "list_item",
      null,
      paragraph("User-authored subclause"),
    );
    const liveItem = editorSchema.node(
      "list_item",
      { id: "item:generated" },
      [
        paragraph("Judge-edited wording"),
        editorSchema.node("ordered_list", null, userSubclause),
      ],
    );
    const documentWith = (item: typeof liveItem) =>
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
      documentWith(generatedItem("Old generated wording")),
      documentWith(generatedItem("New generated wording")),
    );

    const reconciledItem = transaction.doc.firstChild!.firstChild!;
    assert.equal(
      reconciledItem.firstChild!.textContent,
      "New generated wording",
    );
    assert.equal(
      reconciledItem.lastChild!.firstChild!.textContent,
      "User-authored subclause",
    );
  });

  it("removes user-authored descendants when their generated clause is removed", () => {
    const previousTarget = buildOrder((order) => {
      order.orderedList("clauses", (list) => {
        list.item("remove", "Generated parent");
        list.item("keep", "Keep");
      });
    });
    const target = buildOrder((order) => {
      order.orderedList("clauses", (list) => {
        list.item("keep", "Keep");
      });
    });
    const generatedParent = previousTarget.firstChild!.firstChild!;
    const userItem = editorSchema.node(
      "list_item",
      null,
      editorSchema.node(
        "paragraph",
        null,
        editorSchema.text("User-authored qualification"),
      ),
    );
    const userList = editorSchema.node("ordered_list", null, userItem);
    const live = EditorState.create({
      schema: editorSchema,
      doc: previousTarget,
    }).tr.insert(1 + generatedParent.nodeSize - 1, userList).doc;
    const transaction = EditorState.create({
      schema: editorSchema,
      doc: live,
    }).tr;

    reconcileOrderDocument(transaction, previousTarget, target);

    assert.equal(transaction.doc.textContent, "Keep");
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
