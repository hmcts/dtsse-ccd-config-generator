import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { JSDOM } from "jsdom";

import { buildOrder, createOrderEditor, renderHtml } from "../src/index.js";

describe("renderHtml", () => {
  const dom = new JSDOM("<!doctype html>");
  const document = dom.window.document;

  it("renders the reader's document as plain HTML with facts as text", () => {
    const controller = createOrderEditor();
    controller.render(buildOrder((order) => {
      order.paragraph("heading", "IT IS ORDERED THAT:");
      order.orderedList("clauses", (list) => {
        list.item("possession", (content) => {
          content
            .text("Give up possession by ")
            .fact("deadline", "1 October 2026", { sourceId: "deadline" })
            .text(".");
        });
      });
    }));

    const html = renderHtml(controller.getSnapshot(), { document });

    assert.equal(
      html,
      "<p>IT IS ORDERED THAT:</p><ol><li><p>Give up possession by 1 October 2026.</p></li></ol>",
    );
    assert.doesNotMatch(html, /contenteditable|data-generated-text|id=/);
  });

  it("keeps marks, headings and list numbering from the editor document", () => {
    const controller = createOrderEditor();
    controller.render(buildOrder((order) => order.paragraph("p", "x")));
    const snapshot = controller.getSnapshot();
    const current = {
      type: "doc",
      content: [
        { type: "heading", attrs: { level: 2 }, content: [{ type: "text", text: "Costs" }] },
        {
          type: "ordered_list",
          attrs: { id: "ordered-list:user", order: 3 },
          content: [{
            type: "list_item",
            attrs: { id: null },
            content: [{
              type: "paragraph",
              attrs: { id: null },
              content: [{ type: "text", text: "bold", marks: [{ type: "strong" }] }],
            }],
          }],
        },
      ],
    };

    const html = renderHtml({ ...snapshot, current }, { document });

    assert.equal(
      html,
      '<h2>Costs</h2><ol start="3"><li><p><strong>bold</strong></p></li></ol>',
    );
  });

  it("renders the current document, not the generated one", () => {
    const controller = createOrderEditor();
    controller.render(buildOrder((order) => {
      order.paragraph("heading", "Generated wording.");
    }));
    const snapshot = controller.getSnapshot();
    const current = structuredClone(snapshot.current) as {
      content: Array<{ content: Array<{ text: string }> }>;
    };
    current.content[0]!.content[0]!.text = "Edited wording.";

    const html = renderHtml({ ...snapshot, current }, { document });

    assert.equal(html, "<p>Edited wording.</p>");
  });

  it("explains itself when there is no DOM", () => {
    const controller = createOrderEditor();
    controller.render(buildOrder((order) => order.paragraph("p", "x")));
    assert.throws(
      () => renderHtml(controller.getSnapshot()),
      /needs a DOM document/,
    );
  });
});
