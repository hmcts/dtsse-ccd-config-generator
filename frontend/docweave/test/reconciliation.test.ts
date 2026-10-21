import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { Fragment, Slice } from "prosemirror-model";
import { EditorState } from "prosemirror-state";

import {
  buildDoc as buildDocWeaveDocument,
  getDocumentNode,
} from "../src/builder.js";
import { createDocEditorController } from "../src/controller.js";
import { restoreGeneratedNode } from "../src/diff-styling.js";
import { describeChanges, renderHtml } from "../src/index.js";
import { reconcileDocument } from "../src/reconciliation.js";
import { editorSchema } from "../src/schema.js";

const buildDoc = (
  define: Parameters<typeof buildDocWeaveDocument>[0],
) => getDocumentNode(buildDocWeaveDocument(define));

describe("document reconciliation", () => {
  it("replaces directly edited paragraph wording when reference wording changes", () => {
    const live = buildDoc((doc) => {
      doc.paragraph("heading", "Edited heading");
    });
    const previousTarget = buildDoc((doc) => {
      doc.paragraph("heading", "Old heading");
    });
    const target = buildDoc((doc) => {
      doc.paragraph("heading", "New heading");
    });
    const transaction = EditorState.create({
      schema: editorSchema,
      doc: live,
    }).tr;

    const result = reconcileDocument(
      transaction,
      previousTarget,
      target,
    );

    assert.equal(result, transaction);
    assert.equal(result.steps.length, 1);
    assert.equal(result.doc.firstChild!.textContent, "New heading");
  });

  it("adds a generated node after the reader's content that follows its generated predecessor", () => {
    const paragraph = (id: string | null, text: string) =>
      editorSchema.node("paragraph", { id }, editorSchema.text(text));
    const previousTarget = editorSchema.node("doc", null, [
      paragraph("paragraph:upon", "UPON the application"),
      paragraph("paragraph:signed", "Signed"),
    ]);
    const target = editorSchema.node("doc", null, [
      paragraph("paragraph:upon", "UPON the application"),
      paragraph("paragraph:ordered-that", "IT IS ORDERED THAT:"),
      paragraph("paragraph:signed", "Signed"),
    ]);
    const transaction = EditorState.create({
      schema: editorSchema,
      doc: editorSchema.node("doc", null, [
        paragraph("paragraph:upon", "UPON the application"),
        paragraph(null, "The reader's note on the application"),
        paragraph("paragraph:signed", "Signed"),
      ]),
    }).tr;

    reconcileDocument(transaction, previousTarget, target);

    assert.deepEqual(
      transaction.doc.children.map((node) => node.textContent),
      [
        "UPON the application",
        "The reader's note on the application",
        "IT IS ORDERED THAT:",
        "Signed",
      ],
    );
  });

  it("updates generated text while preserving edited surrounding text", () => {
    const orderWithDate = (prefix: string, date: string) =>
      buildDoc((doc) => {
        doc.paragraph("deadline", (content) => {
          content.text(prefix).fact("date", date).text(".");
        });
      });
    const previousTarget = orderWithDate("Payment is due by ", "1 September");
    const live = orderWithDate("Please pay by ", "1 September");
    const target = orderWithDate("Payment is due by ", "8 September");
    const transaction = EditorState.create({
      schema: editorSchema,
      doc: live,
    }).tr;

    reconcileDocument(transaction, previousTarget, target);

    const paragraph = transaction.doc.firstChild!;
    assert.equal(paragraph.firstChild!.text, "Please pay by ");
    assert.equal(paragraph.child(1).type.name, "generated_text");
    assert.equal(paragraph.child(1).attrs.text, "8 September");
    assert.equal(
      paragraph.child(1).attrs.id,
      "generated-text:paragraph:deadline:date",
    );
  });

  it("keeps the reader's formatting on generated text whose value changes", () => {
    const orderWithDate = (date: string) =>
      buildDoc((doc) => {
        doc.paragraph("deadline", (content) => {
          content.text("By ").fact("date", date).text(".");
        });
      });
    const previousTarget = orderWithDate("1 October");
    const target = orderWithDate("2 October");
    const marks = [
      editorSchema.marks.strong!.create(),
      editorSchema.marks.em!.create(),
    ];
    const state = EditorState.create({
      schema: editorSchema,
      doc: previousTarget,
    });
    const live = state.apply(
      state.tr.addMark(0, previousTarget.content.size, marks[0]!)
        .addMark(0, previousTarget.content.size, marks[1]!),
    ).doc;
    const transaction = EditorState.create({
      schema: editorSchema,
      doc: live,
    }).tr;

    reconcileDocument(transaction, previousTarget, target);

    const paragraph = transaction.doc.firstChild!;
    assert.equal(paragraph.child(1).attrs.text, "2 October");
    assert.deepEqual(
      paragraph.children.map((node) => node.marks.map((mark) => mark.type.name)),
      [["em", "strong"], ["em", "strong"], ["em", "strong"]],
    );
  });

  it("updates generated text while preserving edited list-item wording", () => {
    const orderWithDate = (prefix: string, date: string) =>
      buildDoc((doc) => {
        doc.orderedList("clauses", (list) => {
          list.item("deadline", (content) => {
            content.text(prefix).fact("date", date).text(".");
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

    reconcileDocument(transaction, previousTarget, target);

    const paragraph = transaction.doc.firstChild!.firstChild!.firstChild!;
    assert.equal(paragraph.firstChild!.text, "Please pay by ");
    assert.equal(paragraph.child(1).attrs.text, "8 September");
  });

  it("adds generated text when replacing edited list-item wording", () => {
    const orderWith = (
      wording: string,
      generatedText: string | undefined,
    ) =>
      buildDoc((doc) => {
        doc.orderedList("clauses", (list) => {
          list.item("deadline", (content) => {
            content.text(wording);
            if (generatedText) {
              content.fact("date", generatedText);
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

    reconcileDocument(transaction, previousTarget, target);

    const item = transaction.doc.firstChild!.firstChild!;
    assert.equal(item.childCount, 1);
    assert.equal(item.firstChild!.childCount, 2);
    assert.equal(item.firstChild!.textContent, "New wording: 8 September");
  });

  it("removes generated text when replacing edited list-item wording", () => {
    const withGeneratedText = (wording: string) =>
      buildDoc((doc) => {
        doc.orderedList("clauses", (list) => {
          list.item("deadline", (content) => {
            content.text(wording).fact("date", "1 September");
          });
        });
      });
    const previousTarget = withGeneratedText("Old wording: ");
    const live = withGeneratedText("Judge-edited wording: ");
    const target = buildDoc((doc) => {
      doc.orderedList("clauses", (list) => {
        list.item("deadline", "New wording");
      });
    });
    const transaction = EditorState.create({
      schema: editorSchema,
      doc: live,
    }).tr;

    reconcileDocument(transaction, previousTarget, target);

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

    reconcileDocument(transaction, previous, target);

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

    reconcileDocument(transaction, previous, target);

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

    reconcileDocument(
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

    reconcileDocument(
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
    const previousTarget = buildDoc((doc) => {
      doc.orderedList("clauses", (list) => {
        list.item("remove", "Generated parent");
        list.item("keep", "Keep");
      });
    });
    const target = buildDoc((doc) => {
      doc.orderedList("clauses", (list) => {
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

    reconcileDocument(transaction, previousTarget, target);

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
    const secondGeneratedSubclause = editorSchema.node(
      "list_item",
      { id: "item:subclause-b" },
      paragraph("Second generated subclause"),
    );
    const userSubclause = editorSchema.node(
      "list_item",
      null,
      paragraph("User subclause"),
    );
    const secondUserSubclause = editorSchema.node(
      "list_item",
      null,
      paragraph("Second user subclause"),
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
          [generatedSubclause, secondGeneratedSubclause],
        ),
      ],
    );
    const liveItem = editorSchema.node(
      "list_item",
      { id: "item:parent" },
      [
        paragraph("Edited parent clause"),
        editorSchema.node("ordered_list", null, [userSubclause, secondUserSubclause]),
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

    reconcileDocument(
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
      ["item:subclause-a", "item:subclause-b", null, null],
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

    reconcileDocument(
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
      buildDoc((doc) => {
        doc.orderedList("clauses", (list) => {
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

    reconcileDocument(transaction, previous, target);

    const reconciledItem = transaction.doc.firstChild!.firstChild!;
    assert.equal(reconciledItem.childCount, 1);
    assert.equal(reconciledItem.firstChild!.textContent, "Parent clause");
  });

  it("moves the reader's bullets into a clause of their own when a generated subclause list arrives", () => {
    const clause = (withSums: boolean) => buildDoc((doc) => {
      doc.orderedList("clauses", (list) => {
        list.item("pay", "The defendant must pay:", (item) => {
          if (withSums) item.orderedList("sums", (sums) => sums.item("rent", "the rent;"));
        });
      });
    });
    const previousTarget = clause(false);
    const item = previousTarget.firstChild!.firstChild!;
    const live = previousTarget.replace(
      2 + item.nodeSize - 2,
      2 + item.nodeSize - 2,
      new Slice(Fragment.from(editorSchema.node("bullet_list", null, [
        editorSchema.node("list_item", null, editorSchema.node("paragraph", null, editorSchema.text("reader bullet"))),
      ])), 0, 0),
    );
    const transaction = EditorState.create({ schema: editorSchema, doc: live }).tr;

    reconcileDocument(transaction, previousTarget, clause(true));

    transaction.doc.check();
    assert.deepEqual(
      transaction.doc.firstChild!.children.map((item) =>
        [item.attrs.id, item.children.map((child) => [child.type.name, child.attrs.id, child.textContent])]
      ),
      [
        ["item:pay", [["paragraph", null, "The defendant must pay:"], ["ordered_list", "ordered-list:sums", "the rent;"]]],
        [null, [["paragraph", null, ""], ["bullet_list", null, "reader bullet"]]],
      ],
    );
  });

  describe("the reader's wording in a generated clause", () => {
    const clauses = (wording: string, withSums: boolean) => buildDocWeaveDocument((doc) => {
      doc.orderedList("clauses", (list) => {
        list.item("pay", wording, (item) => {
          if (withSums) item.orderedList("sums", (sums) => sums.item("rent", "pay the rent;"));
        });
      });
    });
    const items = (runtime: ReturnType<typeof createDocEditorController>) =>
      runtime.state.doc.child(0).children.map((item) => [item.attrs.id, item.textContent]);
    const revert = (runtime: ReturnType<typeof createDocEditorController>) =>
      runtime.dispatch(restoreGeneratedNode(runtime.state, 1, runtime.generatedDocument.child(0).child(0))!);

    it("is all reverted, leaving the clause as generated", () => {
      const runtime = createDocEditorController({});
      runtime.controller.render(clauses("Costs in the case.", false));
      const paragraph = (text: string) => editorSchema.node("paragraph", null, editorSchema.text(text));
      const item = runtime.state.doc.child(0).child(0);
      runtime.dispatch(runtime.state.tr.replaceWith(2, 2 + item.content.size, [
        paragraph("Costs edited."),
        paragraph("extra"),
      ]));
      assert.deepEqual(describeChanges(runtime.controller.getSnapshot()), { inserted: 0, modified: 1 });

      revert(runtime);

      assert.deepEqual(describeChanges(runtime.controller.getSnapshot()), { inserted: 0, modified: 0 });
    });

    it("keeps one copy of a fact the reader moved into a paragraph of their own", () => {
      const pay = (amount: string) => buildDocWeaveDocument((doc) => {
        doc.orderedList("clauses", (list) => {
          list.item("pay", (content) => content.text("Pay ").fact("amount", amount));
        });
      });
      const facts = (runtime: ReturnType<typeof createDocEditorController>) => {
        const found: string[] = [];
        runtime.state.doc.descendants((node) => {
          if (node.type.name === "generated_text") found.push(node.attrs.text as string);
        });
        return found;
      };
      const split = (runtime: ReturnType<typeof createDocEditorController>) => {
        const item = runtime.state.doc.child(0).child(0);
        const fact = item.firstChild!.lastChild!;
        runtime.dispatch(runtime.state.tr.replaceWith(2, 2 + item.content.size, [
          editorSchema.node("paragraph", null, editorSchema.text("Pay now")),
          editorSchema.node("paragraph", null, [editorSchema.text("by Friday "), fact]),
        ]));
      };

      const reverted = createDocEditorController({});
      reverted.controller.render(pay("£100"));
      split(reverted);
      revert(reverted);
      assert.deepEqual(facts(reverted), ["£100"]);
      assert.doesNotThrow(() => renderHtml(reverted.controller.getSnapshot()));

      const rendered = createDocEditorController({});
      rendered.controller.render(pay("£100"));
      split(rendered);
      rendered.controller.render(pay("£200"));
      assert.deepEqual(facts(rendered), ["£200"]);
      assert.doesNotThrow(() => renderHtml(rendered.controller.getSnapshot()));
    });
  });
});
