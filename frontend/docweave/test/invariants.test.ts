import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { EditorState } from "prosemirror-state";

import { buildOrder as buildDocWeaveDocument } from "../src/builder.js";
import {
  assertCurrentDocumentMatchesGenerated,
  assertValidGeneratedDocument,
  assertValidGeneratedDocumentTransition,
} from "../src/invariants.js";
import { reconcileOrderDocument } from "../src/reconciliation.js";
import { editorSchema } from "../src/schema.js";

const buildOrder = (
  define: Parameters<typeof buildDocWeaveDocument>[0],
) => buildDocWeaveDocument(define).node;

describe("generated document invariants", () => {
  it("uses the ProseMirror schema to reject invalid document structure", () => {
    const invalidList = editorSchema.nodes.ordered_list!.create(
      { id: "ordered-list:invalid" },
      editorSchema.node("paragraph", null, editorSchema.text("Not an item")),
    );
    const invalidDocument = editorSchema.nodes.doc!.create(null, invalidList);

    assert.throws(
      () => assertValidGeneratedDocument(invalidDocument),
      /Invalid content for node ordered_list/,
    );
  });

  it("rejects duplicate managed IDs anywhere in a generated document", () => {
    assert.throws(
      () =>
        buildOrder((order) => {
          order.orderedList("clauses", (list) => {
            list.item("duplicate", "First occurrence");
            list.item("duplicate", "Second occurrence");
          });
        }),
      { message: "Duplicate managed node ID: item:duplicate" },
    );
  });

  it("rejects duplicate empty-string managed IDs", () => {
    const duplicateIdDocument = editorSchema.node("doc", null, [
      editorSchema.node("paragraph", { id: "" }, editorSchema.text("First")),
      editorSchema.node("paragraph", { id: "" }, editorSchema.text("Second")),
    ]);

    assert.throws(
      () => assertValidGeneratedDocument(duplicateIdDocument),
      { message: "Duplicate managed node ID: " },
    );
  });

  it("rejects changing the node type for an existing managed ID", () => {
    const previous = editorSchema.node(
      "doc",
      null,
      editorSchema.node(
        "paragraph",
        { id: "managed" },
        editorSchema.text("Paragraph"),
      ),
    );
    const target = editorSchema.node(
      "doc",
      null,
      editorSchema.node(
        "ordered_list",
        null,
        editorSchema.node(
          "list_item",
          { id: "managed" },
          editorSchema.node("paragraph", null, editorSchema.text("Item")),
        ),
      ),
    );

    assert.throws(
      () => assertValidGeneratedDocumentTransition(previous, target),
      { message: "Managed node changed type: managed" },
    );
  });

  it("rejects moving an existing managed node to another managed parent", () => {
    const previous = buildOrder((order) => {
      order.orderedList("first", (list) => {
        list.item("moved", "Moved");
        list.item("first-retained", "First retained");
      });
      order.orderedList("second", (list) => {
        list.item("second-retained", "Second retained");
      });
    });
    const target = buildOrder((order) => {
      order.orderedList("first", (list) => {
        list.item("first-retained", "First retained");
      });
      order.orderedList("second", (list) => {
        list.item("second-retained", "Second retained");
        list.item("moved", "Moved");
      });
    });

    assert.throws(
      () => assertValidGeneratedDocumentTransition(previous, target),
      { message: "Managed node changed parent: item:moved" },
    );
  });

  it("rejects reordering existing managed siblings", () => {
    const orderWithItems = (ids: readonly string[]) =>
      buildOrder((order) => {
        order.orderedList("clauses", (list) => {
          for (const id of ids) list.item(id, id);
        });
      });

    const previous = orderWithItems(["first", "second"]);
    const target = orderWithItems(["second", "first"]);
    const transaction = EditorState.create({
      schema: editorSchema,
      doc: previous,
    }).tr;

    assert.throws(
      () => reconcileOrderDocument(transaction, previous, target),
      {
        message:
          "Managed children changed relative order under: ordered-list:clauses",
      },
    );
    assert.equal(transaction.steps.length, 0);
  });

  it("rejects adding or removing managed children below an existing non-container", () => {
    const previous = buildOrder((order) => {
      order.paragraph("deadline", "Payment is due soon");
    });
    const target = buildOrder((order) => {
      order.paragraph("deadline", (content) => {
        content.text("Payment is due by ").fact("date", "1 October");
      });
    });

    assert.throws(
      () => assertValidGeneratedDocumentTransition(previous, target),
      {
        message:
          "Managed children added or removed under non-container: paragraph:deadline",
      },
    );
    assert.throws(
      () => assertValidGeneratedDocumentTransition(target, previous),
      {
        message:
          "Managed children added or removed under non-container: paragraph:deadline",
      },
    );
  });

  it("allows managed children to change at the root and in managed containers", () => {
    const previous = buildOrder((order) => {
      order.orderedList("clauses", (list) => {
        list.item("parent", "Payment is due soon");
      });
    });
    const target = buildOrder((order) => {
      order.paragraph("heading", "IT IS ORDERED THAT:");
      order.orderedList("clauses", (list) => {
        list.item("parent", (content) => {
          content.text("Payment is due by ").fact("date", "1 October");
        });
        list.item("costs", "Pay the claimant's costs");
      });
    });

    assert.doesNotThrow(() =>
      assertValidGeneratedDocumentTransition(previous, target)
    );
    assert.doesNotThrow(() =>
      assertValidGeneratedDocumentTransition(target, previous)
    );
  });

  it("allows an existing managed node's value to change", () => {
    const orderWithDate = (date: string) =>
      buildOrder((order) => {
        order.paragraph("deadline", (content) => {
          content.fact("date", date);
        });
      });

    assert.doesNotThrow(() =>
      assertValidGeneratedDocumentTransition(
        orderWithDate("1 October"),
        orderWithDate("2 October"),
      )
    );
  });

  it("rejects a restored current document with different managed structure", () => {
    const generated = buildOrder((order) => {
      order.paragraph("heading", "Generated");
    });
    const current = editorSchema.node(
      "doc",
      null,
      editorSchema.node(
        "paragraph",
        null,
        editorSchema.text("User-authored replacement"),
      ),
    );

    assert.throws(
      () => assertCurrentDocumentMatchesGenerated(current, generated),
      {
        message:
          "Current document does not preserve the generated managed structure",
      },
    );
  });

  it("allows restored current documents to contain ordinary user edits", () => {
    const generated = buildOrder((order) => {
      order.orderedList("clauses", (list) => {
        list.item("generated", "Generated wording");
      });
    });
    const editedGeneratedItem = editorSchema.node(
      "list_item",
      { id: "item:generated" },
      editorSchema.node(
        "paragraph",
        null,
        editorSchema.text("Judge-edited wording"),
      ),
    );
    const userAuthoredItem = editorSchema.node(
      "list_item",
      null,
      editorSchema.node(
        "paragraph",
        null,
        editorSchema.text("User-authored clause"),
      ),
    );
    const current = editorSchema.node(
      "doc",
      null,
      editorSchema.node(
        "ordered_list",
        { id: "ordered-list:clauses" },
        [editedGeneratedItem, userAuthoredItem],
      ),
    );

    assert.doesNotThrow(() =>
      assertCurrentDocumentMatchesGenerated(current, generated)
    );
  });
});
