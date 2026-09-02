import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  buildOrder,
  getDocumentFactSources,
} from "../src/builder.js";
import { editorSchema } from "../src/schema.js";

describe("order builder", () => {
  it("builds paragraphs and independently scoped ordered-list items", () => {
    const document = buildOrder((order) => {
      order.paragraph("heading", "IT IS ORDERED THAT:");
      order.paragraph("attendance", (content) => {
        content
          .text("The Court heard from ")
          .fact("register", "Alex Smith, counsel for the claimant")
          .text(".");
      });
      order.orderedList("clauses", (list) => {
        list.item("possession", (content) => {
          content
            .text("Give up possession by ")
            .fact("deadline", "1 September 2026")
            .text(".");
        });
        list.item("costs", "Pay the claimant's costs.");
      });
    });

    const json = JSON.parse(JSON.stringify(document.node.toJSON())) as unknown;
    assert.deepEqual(json, {
      type: "doc",
      content: [
        {
          type: "paragraph",
          attrs: { id: "paragraph:heading" },
          content: [{ type: "text", text: "IT IS ORDERED THAT:" }],
        },
        {
          type: "paragraph",
          attrs: { id: "paragraph:attendance" },
          content: [
            { type: "text", text: "The Court heard from " },
            {
              type: "generated_text",
              attrs: {
                id: "generated-text:paragraph:attendance:register",
                text: "Alex Smith, counsel for the claimant",
              },
            },
            { type: "text", text: "." },
          ],
        },
        {
          type: "ordered_list",
          attrs: { order: 1, id: "ordered-list:clauses" },
          content: [
            {
              type: "list_item",
              attrs: { id: "item:possession" },
              content: [
                {
                  type: "paragraph",
                  attrs: { id: null },
                  content: [
                    { type: "text", text: "Give up possession by " },
                    {
                      type: "generated_text",
                      attrs: {
                        id: "generated-text:item:possession:deadline",
                        text: "1 September 2026",
                      },
                    },
                    { type: "text", text: "." },
                  ],
                },
              ],
            },
            {
              type: "list_item",
              attrs: { id: "item:costs" },
              content: [
                {
                  type: "paragraph",
                  attrs: { id: null },
                  content: [
                    { type: "text", text: "Pay the claimant's costs." },
                  ],
                },
              ],
            },
          ],
        },
      ],
    });
  });

  it("only includes list items added by the callback", () => {
    const includeSecondItem = false;
    const document = buildOrder((order) => {
      order.orderedList("clauses", (list) => {
        list.item("first", "First");
        if (includeSecondItem) list.item("second", "Second");
      });
    });

    assert.equal(document.node.firstChild!.childCount, 1);
    assert.equal(document.node.firstChild!.firstChild!.attrs.id, "item:first");
  });

  it("builds managed nested ordered lists", () => {
    const document = buildOrder((order) => {
      order.orderedList("clauses", (list) => {
        list.item("parent", "Parent clause", (item) => {
          item.orderedList("subclauses", (subclauses) => {
            subclauses.item("subclause-a", "First subclause");
            subclauses.item("subclause-b", "Second subclause");
          });
        });
      });
    });

    const parent = document.node.firstChild!.firstChild!;
    const nestedList = parent.lastChild!;

    assert.equal(parent.attrs.id, "item:parent");
    assert.equal(nestedList.type.name, "ordered_list");
    assert.equal(nestedList.attrs.id, "ordered-list:subclauses");
    assert.deepEqual(
      nestedList.children.map((item) => item.attrs.id),
      ["item:subclause-a", "item:subclause-b"],
    );
  });

  it("rejects an ordered list with no items", () => {
    assert.throws(
      () => buildOrder((order) => order.orderedList("empty", () => {})),
      { message: 'Ordered list "empty" must contain at least one item' },
    );
  });

  it("rejects an empty nested ordered list", () => {
    assert.throws(
      () =>
        buildOrder((order) => {
          order.orderedList("clauses", (list) => {
            list.item("parent", "Parent", (item) => {
              item.orderedList("empty", () => {});
            });
          });
        }),
      { message: 'Ordered list "empty" must contain at least one item' },
    );
  });

  it("renders generated text with its managed DOM marker", () => {
    const document = buildOrder((order) => {
      order.paragraph("date", (content) => {
        content.fact("value", "1 September 2026");
      });
    });
    const generatedText = document.node.firstChild!.firstChild!;
    const toDOM = editorSchema.nodes.generated_text!.spec.toDOM;

    assert.ok(toDOM);
    assert.deepEqual(toDOM(generatedText), [
      "span",
      {
        "data-generated-text": "generated-text:paragraph:date:value",
        contenteditable: "false",
      },
      "1 September 2026",
    ]);
  });

  it("includes generated text in plain-text serialization", () => {
    const document = buildOrder((order) => {
      order.paragraph("attendance", (content) => {
        content
          .text("The Court heard from ")
          .fact("register", "Alex Smith")
          .text(".");
      });
    });

    assert.equal(
      document.node.textBetween(0, document.node.content.size),
      "The Court heard from Alex Smith.",
    );
  });

  it("keeps fact source IDs outside the ProseMirror document", () => {
    const document = buildOrder((order) => {
      order.paragraph("payment", (content) => {
        content.fact("amount", "£2342.00", {
          sourceId: "arrears-amount",
        });
      });
    });

    assert.equal(document.node.type.name, "doc");
    assert.equal(
      getDocumentFactSources(document).get(
        "generated-text:paragraph:payment:amount",
      ),
      "arrears-amount",
    );
    assert.doesNotMatch(
      JSON.stringify(document.node.toJSON()),
      /arrears-amount/,
    );
  });

  it("rejects source IDs that cannot be HTML IDs", () => {
    for (const sourceId of ["", "two words", "line\nbreak"]) {
      assert.throws(
        () =>
          buildOrder((order) => {
            order.paragraph("payment", (content) => {
              content.fact("amount", "£1", { sourceId });
            });
          }),
        /Invalid fact source ID/,
      );
    }
  });
});
