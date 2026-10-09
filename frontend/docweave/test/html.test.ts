import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { JSDOM } from "jsdom";

import { buildDoc, createDocEditor, renderHtml } from "../src/index.js";
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

  it("numbers the lists as the editor shows them, whatever their own numbering says", () => {
    const controller = createDocEditor();
    controller.render(buildDoc((doc) => {
      doc.orderedList("first", (list) => {
        list.item("one", (content) => content.text("One"));
        list.item("two", (content) => content.text("Two"));
      });
      doc.paragraph("recital", "Further directions:");
      doc.orderedList("second", (list) => {
        list.item("three", (content) => content.text("Three"));
      });
    }));
    const current = structuredClone(controller.getSnapshot().current) as {
      content: Array<{ type: string; attrs?: Record<string, unknown> }>;
    };
    current.content[0]!.attrs = { ...current.content[0]!.attrs, order: 5 };

    const html = renderHtml({ ...controller.getSnapshot(), current }, { document });

    assert.equal(
      html,
      "<ol><li><p>One</p></li><li><p>Two</p></li></ol>"
        + "<p>Further directions:</p>"
        + '<ol start="3"><li><p>Three</p></li></ol>',
    );
  });

  it("numbers a list within a clause as the editor shows it", () => {
    const controller = createDocEditor();
    controller.render(buildDoc((doc) => {
      doc.orderedList("clauses", (list) => {
        list.item("parent", "Parent clause.", (item) => {
          item.orderedList("children", (children) => children.item("child", "Child clause."));
        });
      });
    }));

    assert.equal(
      renderHtml(controller.getSnapshot(), { document }),
      '<ol><li><p>Parent clause.</p><ol type="i"><li><p>Child clause.</p></li></ol></li></ol>',
    );
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

  it("keeps marks and headings from the editor document, numbered as the editor shows", () => {
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
      "<h2>Costs</h2><ol><li><p><strong>bold</strong></p></li></ol>",
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
  const marked = (kind: string, description: string, wording: string) =>
    `<li class="docweave-editor__clause docweave-editor__clause--${kind}">` +
    `<p><span class="docweave-editor__visually-hidden">${description} </span>${wording}</p>`;

  it("renders a document as generated without marks", () => {
    const snapshot = generatedOrder();

    assert.equal(
      renderHtml(snapshot, { document, changes: true }),
      renderHtml(snapshot, { document }),
    );
  });

  it("marks the clauses the reader wrote and changed as the editor does, and no others", () => {
    const snapshot = edited(generatedOrder(), (current) => {
      clauses(current)[1]!.content![0]!.content = [
        { type: "text", text: "The defendant must pay the claimant's fixed costs." },
      ];
      clauses(current).push(userClause("The defendant may apply to vary this order."));
    });

    assert.equal(
      renderHtml(snapshot, { document, changes: true }),
      "<p>IT IS ORDERED THAT:</p><ol>" +
        "<li><p>The defendant must give up possession by 1 October 2026.</p></li>" +
        marked("modified", "Modified clause.", "The defendant must pay the claimant's fixed costs.") + "</li>" +
        marked("inserted", "Inserted clause.", "The defendant may apply to vary this order.") + "</li>" +
        "</ol>",
    );
  });

  it("marks a changed paragraph outside the numbered clauses", () => {
    const snapshot = edited(generatedOrder(), (current) => {
      current.content![0]!.content = [{ type: "text", text: "IT IS ORDERED BY CONSENT THAT:" }];
    });

    assert.match(
      renderHtml(snapshot, { document, changes: true }),
      /^<p class="docweave-editor__clause docweave-editor__clause--modified"><span class="docweave-editor__visually-hidden">Modified clause\. <\/span>IT IS ORDERED BY CONSENT THAT:<\/p>/,
    );
  });

  it("gives an emptied or empty clause a line to show its mark on", () => {
    const snapshot = edited(generatedOrder(), (current) => {
      delete clauses(current)[1]!.content![0]!.content;
      current.content!.push({ type: "paragraph", attrs: { id: null } });
    });

    const html = renderHtml(snapshot, { document, changes: true });

    assert.match(html, new RegExp(`${marked("modified", "Modified clause.", "<br>")}</li></ol>`));
    assert.match(
      html,
      /<p class="docweave-editor__clause docweave-editor__clause--inserted"><span class="docweave-editor__visually-hidden">Inserted clause\. <\/span><br><\/p>$/,
    );
  });

  it("marks a changed nested clause on its own item, not on its parent", () => {
    const controller = createDocEditor();
    controller.render(buildDoc((doc) => {
      doc.orderedList("clauses", (list) => {
        list.item("parent", "Parent clause.", (item) => {
          item.orderedList("children", (children) => children.item("child", "Child clause."));
        });
      });
    }));
    const snapshot = edited(controller.getSnapshot(), (current) => {
      current.content![0]!.content![0]!.content![1]!.content![0]!.content![0]!.content = [
        { type: "text", text: "Reworded child." },
      ];
    });

    assert.equal(
      renderHtml(snapshot, { document, changes: true }),
      '<ol><li><p>Parent clause.</p><ol type="i">' + marked("modified", "Modified clause.", "Reworded child.") +
        "</li></ol></li></ol>",
    );
  });
});
