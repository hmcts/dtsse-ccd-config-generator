import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { JSDOM } from "jsdom";

import { type Node as ProseMirrorNode } from "prosemirror-model";

import { listStarts } from "../src/list-numbering.js";
import { editorSchema } from "../src/schema.js";
import {
  buildDoc,
  createDocEditor,
  type DocWeaveSnapshot,
  renderHtml,
  renderText,
} from "../src/index.js";

const { document: ownerDocument } = new JSDOM().window;

const order = () =>
  buildDoc((doc) => {
    doc.paragraph("upon", "UPON the application of Jane Doe");
    doc.paragraph("ordered-that", "IT IS ORDERED THAT:");
    doc.orderedList("costs", (list) => list.item("costs", "Costs in the case."));
  });

function insertHtml(html: string) {
  const controller = createDocEditor();
  controller.render(order());
  const inserted = controller.insertHtml(html, {
    at: { after: "paragraph:ordered-that" },
    ownerDocument,
  });
  const snapshot = controller.getSnapshot();
  return { inserted, snapshot, current: editorSchema.nodeFromJSON(snapshot.current) };
}

const types = (snapshot: DocWeaveSnapshot) =>
  editorSchema.nodeFromJSON(snapshot.current).children.map((node) => node.type.name);

describe("inserting rich text", () => {
  it("adds numbered lists as clauses numbered on from the document's own", () => {
    const { inserted, snapshot, current } = insertHtml(
      "<p>The claimant asks that:</p><ol><li><p>the hearing is adjourned;</p></li>" +
        "<li><p>costs are reserved.</p></li></ol>",
    );

    assert.equal(inserted, true);
    assert.deepEqual(types(snapshot), ["paragraph", "paragraph", "paragraph", "ordered_list", "ordered_list"]);
    assert.deepEqual(listStarts(current), [undefined, undefined, undefined, 1, 3]);
  });

  it("keeps a list as a list when it goes into a list of the other kind, under the clause before it", () => {
    const shape = (node: ProseMirrorNode): unknown =>
      node.isTextblock ? node.textContent : [node.type.name, ...node.children.map(shape)];
    const bullets = "<ul><li><p>the rent;</p></li><li><p>the costs</p></li></ul>";
    const listOfBullets = ["bullet_list", ["list_item", "the rent;"], ["list_item", "the costs"]];

    for (const at of [{ after: "item:costs" }, "end"] as const) {
      const controller = createDocEditor();
      controller.render(order());
      controller.insertHtml(bullets, { at, ownerDocument });
      const clauses = editorSchema.nodeFromJSON(controller.getSnapshot().current).child(2);
      assert.deepEqual(shape(clauses), [
        "ordered_list",
        ["list_item", "Costs in the case.", listOfBullets],
      ], JSON.stringify(at));
    }

    const withSubclauses = createDocEditor();
    withSubclauses.render(buildDoc((doc) => {
      doc.orderedList("clauses", (list) => {
        list.item("pay", "The defendant must pay:", (item) => {
          item.orderedList("sums", (sums) => sums.item("arrears", "the arrears;"));
        });
      });
    }));
    withSubclauses.insertHtml(bullets, { at: { after: "ordered-list:sums" }, ownerDocument });
    assert.deepEqual(shape(editorSchema.nodeFromJSON(withSubclauses.getSnapshot().current).child(0).child(0)), [
      "list_item",
      "The defendant must pay:",
      ["ordered_list", ["list_item", "the arrears;", listOfBullets]],
    ]);

    const note = { type: "doc", content: [{ type: "bullet_list", content: [{ type: "list_item", content: [
      { type: "paragraph", content: [{ type: "text", text: "A note." }] },
    ] }] }] };
    const afterBullet = createDocEditor({ initialSnapshot: {
      schema: "docweave-document",
      version: 1,
      current: note,
      generated: { type: "doc", content: [{ type: "paragraph" }] },
    } });
    afterBullet.insertHtml("<ol><li><p>First</p></li></ol>", { at: "end", ownerDocument });
    assert.deepEqual(shape(editorSchema.nodeFromJSON(afterBullet.getSnapshot().current).child(0)), [
      "bullet_list",
      ["list_item", "A note.", ["ordered_list", ["list_item", "First"]]],
    ]);
  });

  it("inserts nothing when the rich text holds no text", () => {
    for (const html of ["", "<p></p>", "<p> </p><p><br></p>"]) {
      const controller = createDocEditor();
      controller.render(order());
      const generated = controller.getSnapshot();

      assert.equal(controller.insertHtml(html, { at: "end", ownerDocument }), false, html);
      assert.deepEqual(controller.getSnapshot(), generated);
    }
  });

  it("still loads a snapshot saved before bullet support", () => {
    const saved: DocWeaveSnapshot = {
      schema: "docweave-document",
      version: 1,
      current: {
        type: "doc",
        content: [{
          type: "ordered_list",
          attrs: { id: null, order: 1 },
          content: [{
            type: "list_item",
            attrs: { id: null },
            content: [
              { type: "paragraph", attrs: { id: null }, content: [{ type: "text", text: "Clause." }] },
              {
                type: "ordered_list",
                attrs: { id: null, order: 1 },
                content: [{
                  type: "list_item",
                  attrs: { id: null },
                  content: [{ type: "paragraph", attrs: { id: null }, content: [{ type: "text", text: "Subclause." }] }],
                }],
              },
            ],
          }],
        }],
      },
      generated: { type: "doc", content: [{ type: "paragraph", attrs: { id: null } }] },
    };

    const controller = createDocEditor({ initialSnapshot: saved });

    assert.equal(renderText(controller.getSnapshot()), "Clause.\nSubclause.");
    assert.equal(
      renderHtml(controller.getSnapshot()),
      '<ol><li><p>Clause.</p><ol type="i"><li><p>Subclause.</p></li></ol></li></ol>',
    );
  });
});
