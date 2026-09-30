import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { buildDoc, createDocEditor, describeChanges } from "../src/index.js";
import { clauses, edited, generatedOrder, userClause } from "./fixtures/order.js";

describe("describeChanges", () => {
  it("finds no changes in a document as generated", () => {
    assert.deepEqual(describeChanges(generatedOrder()), { inserted: 0, modified: 0, removed: 0 });
  });

  it("counts the clauses the reader wrote", () => {
    const snapshot = edited(generatedOrder(), (current) => {
      clauses(current).push(userClause("The defendant may apply to vary this order."));
      current.content!.push({ type: "paragraph", attrs: { id: null }, content: [{ type: "text", text: "Dated today." }] });
    });

    assert.deepEqual(describeChanges(snapshot), { inserted: 2, modified: 0, removed: 0 });
  });

  it("counts the generated clauses the reader reworded, not the paragraphs inside them", () => {
    const snapshot = edited(generatedOrder(), (current) => {
      clauses(current)[1]!.content![0]!.content = [{ type: "text", text: "There is no order for costs." }];
    });

    assert.deepEqual(describeChanges(snapshot), { inserted: 0, modified: 1, removed: 0 });
  });

  it("counts the generated clauses that are no longer in the reader's document", () => {
    const snapshot = edited(generatedOrder(), (current) => {
      clauses(current).pop();
    });

    assert.deepEqual(describeChanges(snapshot), { inserted: 0, modified: 0, removed: 1 });
  });

  it("does not count a clause whose nested clause alone changed", () => {
    const controller = createDocEditor();
    controller.render(buildDoc((doc) => {
      doc.orderedList("clauses", (list) => {
        list.item("parent", "Parent clause.", (item) => {
          item.orderedList("children", (children) => children.item("child", "Child clause."));
        });
      });
    }));
    const snapshot = edited(controller.getSnapshot(), (current) => {
      const child = current.content![0]!.content![0]!.content![1]!.content![0]!;
      child.content![0]!.content = [{ type: "text", text: "Reworded child." }];
    });

    assert.deepEqual(describeChanges(snapshot), { inserted: 0, modified: 1, removed: 0 });
  });
});
