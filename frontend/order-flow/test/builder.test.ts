import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { buildOrder } from "../src/builder.js";
import { editorSchema } from "../src/schema.js";

describe("order builder", () => {
  it("builds paragraphs and independently scoped ordered-list items", () => {
    const document = buildOrder((order) => {
      order.paragraph("heading", "IT IS ORDERED THAT:");
      order.paragraph("attendance", (content) => {
        content
          .text("The Court heard from ")
          .generatedText("register", "Alex Smith, counsel for the claimant")
          .text(".");
      });
      order.orderedList("clauses", (list) => {
        list.item("possession", (content) => {
          content
            .text("Give up possession by ")
            .generatedText("deadline", "1 September 2026")
            .text(".");
        });
        list.item("costs", "Pay the claimant's costs.");
      });
    });

    const json = JSON.parse(JSON.stringify(document.toJSON())) as unknown;
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

    assert.equal(document.firstChild!.childCount, 1);
    assert.equal(document.firstChild!.firstChild!.attrs.id, "item:first");
  });

  it("rejects an ordered list with no items", () => {
    assert.throws(
      () => buildOrder((order) => order.orderedList("empty", () => {})),
      { message: 'Ordered list "empty" must contain at least one item' },
    );
  });

  it("renders generated text with its managed DOM marker", () => {
    const document = buildOrder((order) => {
      order.paragraph("date", (content) => {
        content.generatedText("value", "1 September 2026");
      });
    });
    const generatedText = document.firstChild!.firstChild!;
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
          .generatedText("register", "Alex Smith")
          .text(".");
      });
    });

    assert.equal(
      document.textBetween(0, document.content.size),
      "The Court heard from Alex Smith.",
    );
  });
});
