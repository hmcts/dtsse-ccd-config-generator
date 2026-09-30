import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { JSDOM } from "jsdom";

import { buildDoc, createDocEditor, type DocWeaveSnapshot, renderHtml } from "../src/index.js";
import { clauses, edited, generatedOrder, userClause } from "./fixtures/order.js";

describe("renderHtml", () => {
  const dom = new JSDOM("<!doctype html>");
  const document = dom.window.document;

  it("renders the reader's document as plain HTML with facts as text", () => {
    const controller = createDocEditor();
    controller.render(buildDoc((doc) => {
      doc.paragraph("heading", "IT IS ORDERED THAT:");
      doc.orderedList("clauses", (list) => {
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

  it("keeps the formatting the reader gave a fact", () => {
    const controller = createDocEditor();
    controller.render(buildDoc((doc) => {
      doc.paragraph("deadline", (content) => {
        content.text("By ").fact("date", "1 October").text(".");
      });
    }));
    const current = structuredClone(controller.getSnapshot().current) as {
      content: Array<{ content: Array<{ marks?: unknown[] }> }>;
    };
    for (const node of current.content[0]!.content) {
      node.marks = [{ type: "em" }, { type: "strong" }];
    }

    const html = renderHtml(
      { ...controller.getSnapshot(), current },
      { document },
    );

    assert.equal(
      html,
      "<p><em><strong>By 1 October.</strong></em></p>",
    );
  });

  it("leaves out a fact with no value rather than emitting an empty text node", () => {
    const controller = createDocEditor();
    controller.render(buildDoc((doc) => {
      doc.paragraph("possession", (content) => {
        content.text("Costs: ").fact("amount", "").text("to be assessed.");
      });
    }));

    assert.equal(
      renderHtml(controller.getSnapshot(), { document }),
      "<p>Costs: to be assessed.</p>",
    );
  });

  it("keeps marks, headings and list numbering from the editor document", () => {
    const controller = createDocEditor();
    controller.render(buildDoc((doc) => doc.paragraph("p", "x")));
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
    const controller = createDocEditor();
    controller.render(buildDoc((doc) => {
      doc.paragraph("heading", "Generated wording.");
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
    const controller = createDocEditor();
    controller.render(buildDoc((doc) => doc.paragraph("p", "x")));
    assert.throws(
      () => renderHtml(controller.getSnapshot()),
      /needs a DOM document/,
    );
  });
});

describe("renderHtml with changes", () => {
  const dom = new JSDOM("<!doctype html>");
  const document = dom.window.document;

  it("renders a document as generated without marks", () => {
    const snapshot = generatedOrder();

    assert.equal(
      renderHtml(snapshot, { document, changes: true }),
      renderHtml(snapshot, { document }),
    );
  });

  it("marks a clause the reader wrote as inserted", () => {
    const snapshot = edited(generatedOrder(), (current) => {
      clauses(current).push(userClause("The defendant may apply to vary this order."));
    });

    const html = renderHtml(snapshot, { document, changes: true });

    assert.match(
      html,
      /<li data-docweave-change="inserted"><p><ins>The defendant may apply to vary this order\.<\/ins><\/p><\/li><\/ol>$/,
    );
  });

  it("shows a reworded clause's generated wording deleted and the reader's inserted, word by word", () => {
    const snapshot = edited(generatedOrder(), (current) => {
      clauses(current)[1]!.content![0]!.content = [
        { type: "text", text: "The defendant must pay the claimant's fixed costs." },
      ];
    });

    const html = renderHtml(snapshot, { document, changes: true });

    assert.match(
      html,
      /<li data-docweave-change="modified"><p>The defendant must pay the claimant's <ins>fixed <\/ins>costs\.<\/p><\/li>/,
    );
  });

  describe("a reworded paragraph outside the numbered clauses", () => {
    function preamble(): DocWeaveSnapshot {
      const controller = createDocEditor();
      controller.render(buildDoc((doc) => {
        doc.paragraph("before", (content) => {
          content.text("Before District Judge ").fact("judge", "Smith").text(" sitting at Bristol.");
        });
      }));
      return controller.getSnapshot();
    }

    const paragraph = (current: { content?: unknown[] }) =>
      current.content![0] as { content: Array<{ type: string; text?: string; attrs?: { text?: string } }> };

    it("keeps the rest of the paragraph around the reworded wording", () => {
      const snapshot = edited(preamble(), (current) => {
        paragraph(current).content[0]!.text = "Before Deputy District Judge ";
      });

      assert.equal(
        renderHtml(snapshot, { document, changes: true }),
        '<p data-docweave-change="modified">Before <ins>Deputy </ins>District Judge Smith sitting at Bristol.</p>',
      );
    });

    it("shows a fact the reader emptied as deleted", () => {
      const snapshot = edited(preamble(), (current) => {
        paragraph(current).content[1]!.attrs!.text = "";
      });

      assert.match(renderHtml(snapshot, { document, changes: true }), /Judge <del>Smith <\/del>sitting at Bristol/);
    });
  });

  it("marks a paragraph the reader added before a generated one, leaving the generated one as it was", () => {
    const snapshot = edited(generatedOrder(), (current) => {
      clauses(current)[1]!.content!.unshift({
        type: "paragraph",
        attrs: { id: null },
        content: [{ type: "text", text: "On the claimant's application:" }],
      });
    });

    assert.match(
      renderHtml(snapshot, { document, changes: true }),
      /<li data-docweave-change="modified"><p><ins>On the claimant's application:<\/ins><\/p><p>The defendant must pay the claimant's costs\.<\/p><\/li>/,
    );
  });

});
