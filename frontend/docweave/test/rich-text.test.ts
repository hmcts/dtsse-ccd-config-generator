import assert from "node:assert/strict";
import { afterEach, beforeEach, describe, it } from "node:test";

import { JSDOM } from "jsdom";

import { listStarts } from "../src/list-numbering.js";
import { editorSchema } from "../src/schema.js";
import {
  buildDoc,
  createDocEditor,
  type DocWeaveSnapshot,
  renderHtml,
  renderText,
} from "../src/index.js";
import { installJsdomGlobals } from "./jsdom-globals.js";

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

  it("keeps bold and italic text", () => {
    const { current } = insertHtml(
      "<p>As in <strong>paragraph 1 above</strong>, <em>forthwith</em>.</p>",
    );

    const marks: Array<[string, string[]]> = [];
    current.child(2).forEach((text) => {
      marks.push([text.text!, text.marks.map((mark) => mark.type.name)]);
    });
    assert.deepEqual(marks, [
      ["As in ", []],
      ["paragraph 1 above", ["strong"]],
      [", ", []],
      ["forthwith", ["em"]],
      [".", []],
    ]);
  });

  it("keeps bullets as a bulleted list, which takes no clause number", () => {
    const { snapshot, current } = insertHtml(
      "<ol><li><p>The defendant must pay:</p><ul><li><p>the rent;</p></li></ul></li></ol>" +
        "<ul><li><p>the costs</p><ul><li><p>of the claim</p></li></ul></li></ul>",
    );

    assert.deepEqual(types(snapshot).slice(2), ["ordered_list", "bullet_list", "ordered_list"]);
    assert.deepEqual(listStarts(current).slice(2), [1, undefined, 2]);
    assert.equal(
      renderText(snapshot),
      "UPON the application of Jane Doe\nIT IS ORDERED THAT:\nThe defendant must pay:\n• the rent;\n" +
        "• the costs\n• of the claim\nCosts in the case.",
    );
    assert.equal(
      renderHtml(snapshot),
      "<p>UPON the application of Jane Doe</p><p>IT IS ORDERED THAT:</p>" +
        "<ol><li><p>The defendant must pay:</p><ul><li><p>the rent;</p></li></ul></li></ol>" +
        "<ul><li><p>the costs</p><ul><li><p>of the claim</p></li></ul></li></ul>" +
        '<ol start="2"><li><p>Costs in the case.</p></li></ol>',
    );
  });

  it("keeps a numbered list under a bullet as the numbers it has, not subclauses", () => {
    const { snapshot } = insertHtml(
      '<ul><li><p>Arrears of:</p><ol start="3"><li><p>rent</p></li><li><p>charges</p></li></ol></li></ul>',
    );

    assert.match(
      renderHtml(snapshot),
      /<ul><li><p>Arrears of:<\/p><ol start="3"><li><p>rent<\/p><\/li><li><p>charges<\/p><\/li><\/ol><\/li><\/ul>/,
    );
  });

  it("keeps what follows a clause's subclauses in that clause", () => {
    const { current, snapshot } = insertHtml(
      "<ol><li><p>The defendant must:</p><ol><li><p>pay the rent;</p></li></ol>" +
        "<p>and keep to the tenancy.</p></li><li><p>Costs reserved.</p></li></ol>",
    );

    const clauses = current.child(2);
    assert.equal(clauses.childCount, 2);
    assert.deepEqual(
      clauses.child(0).children.map((node) => [node.type.name, node.textContent]),
      [["paragraph", "The defendant must:"], ["ordered_list", "pay the rent;"], ["paragraph", "and keep to the tenancy."]],
    );
    assert.match(
      renderHtml(snapshot),
      /<ol><li><p>The defendant must:<\/p><ol type="i"><li><p>pay the rent;<\/p><\/li><\/ol><p>and keep to the tenancy\.<\/p><\/li>/,
    );
  });

  it("keeps headings as headings", () => {
    const { current } = insertHtml("<h2>Arrears</h2><p>The arrears are paid.</p>");

    assert.equal(current.child(2).type.name, "heading");
    assert.equal(current.child(2).attrs.level, 2);
    assert.equal(current.child(2).textContent, "Arrears");
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

  it("still loads a snapshot saved before bullets and paragraphs after subclauses", () => {
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

describe("pasting rich text", () => {
  let dom: JSDOM;
  let restoreGlobals: () => void;

  beforeEach(() => {
    dom = new JSDOM('<!doctype html><div id="editor"></div>', { pretendToBeVisual: true });
    restoreGlobals = installJsdomGlobals(dom);
  });

  afterEach(() => {
    restoreGlobals();
    dom.window.close();
  });

  function paste(html: string): DocWeaveSnapshot {
    const controller = createDocEditor({ mount: "#editor" });
    const event = new dom.window.Event("paste", { bubbles: true, cancelable: true });
    Object.defineProperty(event, "clipboardData", {
      value: { getData: (type: string) => type === "text/html" ? html : "" },
    });
    dom.window.document.querySelector(".ProseMirror")!.dispatchEvent(event);
    const snapshot = controller.getSnapshot();
    controller.destroy();
    return snapshot;
  }

  function inserted(html: string): DocWeaveSnapshot {
    const controller = createDocEditor({ mount: "#editor" });
    controller.insertHtml(html);
    const snapshot = controller.getSnapshot();
    controller.destroy();
    return snapshot;
  }

  for (const [description, html] of [
    ["numbered clauses with subclauses and wording after them", "<p>The claimant asks that:</p><ol><li><p>the defendant must:</p>" +
      "<ol><li><p>pay the rent;</p></li></ol><p>and keep to the tenancy.</p></li><li><p>costs are reserved.</p></li></ol>"],
    ["bullets with a numbered list under one", '<ul><li><p>Arrears of:</p><ol start="3"><li><p>rent</p></li></ol></li>' +
      "<li><p><strong>charges</strong></p></li></ul><ol><li><p>Costs reserved.</p></li></ol>"],
    ["a heading", "<h2>Arrears</h2><p>The arrears are paid.</p>"],
  ]) {
    it(`reads ${description} as inserting it does`, () => {
      const pasted = paste(html!);

      assert.deepEqual(pasted.current, inserted(html!).current);
      assert.notDeepEqual(pasted.current, pasted.generated);
    });
  }

  it("keeps a pasted clause's wording after its subclauses in that clause", () => {
    const { current } = paste(
      "<ol><li><p>The defendant must:</p><ol><li><p>pay the rent;</p></li></ol>" +
        "<p>and keep to the tenancy.</p></li><li><p>Costs reserved.</p></li></ol>",
    );

    const clauses = editorSchema.nodeFromJSON(current).child(0);
    assert.deepEqual(
      clauses.children.map((item) => item.lastChild!.textContent),
      ["and keep to the tenancy.", "Costs reserved."],
    );
  });
});
