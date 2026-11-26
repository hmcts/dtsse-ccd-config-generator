import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  acceptChanges,
  buildDoc,
  createDocEditor,
  describeChanges,
  parseSnapshot,
  renderText,
} from "../src/index.js";
import { clauses, edited, generatedOrder, userClause } from "./fixtures/order.js";

describe("describeChanges", () => {
  it("finds no changes in a document as generated", () => {
    assert.deepEqual(describeChanges(generatedOrder()), { inserted: 0, modified: 0 });
  });

  it("counts the clauses the reader wrote", () => {
    const snapshot = edited(generatedOrder(), (current) => {
      clauses(current).push(userClause("The defendant may apply to vary this order."));
      current.content!.push({ type: "paragraph", attrs: { id: null }, content: [{ type: "text", text: "Dated today." }] });
    });

    assert.deepEqual(describeChanges(snapshot), { inserted: 2, modified: 0 });
  });

  it("counts the generated clauses the reader reworded, not the paragraphs inside them", () => {
    const snapshot = edited(generatedOrder(), (current) => {
      clauses(current)[1]!.content![0]!.content = [{ type: "text", text: "There is no order for costs." }];
    });

    assert.deepEqual(describeChanges(snapshot), { inserted: 0, modified: 1 });
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

    assert.deepEqual(describeChanges(snapshot), { inserted: 0, modified: 1 });
  });

  it("counts a heading the reader added, since Docweave generates none", () => {
    const snapshot = edited(generatedOrder(), (current) => {
      current.content!.unshift({ type: "heading", attrs: { level: 2 }, content: [{ type: "text", text: "Order" }] });
    });

    assert.deepEqual(describeChanges(snapshot), { inserted: 1, modified: 0 });
  });
});

describe("acceptChanges", () => {
  /** A judge's order: a clause and a heading they wrote, and a generated clause they reworded. */
  const judges = () =>
    edited(generatedOrder(), (current) => {
      clauses(current).push(userClause("The claim is adjourned."));
      clauses(current)[1]!.content![0]!.content = [{ type: "text", text: "There is no order for costs." }];
      current.content!.push({ type: "heading", attrs: { level: 2 }, content: [{ type: "text", text: "Next steps" }] });
    });

  it("takes the reader's document as generated, so none of their changes count", () => {
    const accepted = acceptChanges(judges());

    assert.deepEqual(describeChanges(accepted), { inserted: 0, modified: 0 });
    assert.equal(renderText(accepted), renderText(judges()));
  });

  it("keeps the clauses the reader wrote, as it keeps generated ones", () => {
    const accepted = acceptChanges(judges());
    const without = (edit: (current: Parameters<Parameters<typeof edited>[1]>[0]) => void) =>
      () => parseSnapshot(edited(accepted, edit));

    assert.throws(without((current) => clauses(current).pop()), { code: "invalid-document" });
    assert.throws(without((current) => current.content!.pop()), { code: "invalid-document" });
  });

  it("counts the next reader's changes against the accepted wording", () => {
    const next = edited(acceptChanges(judges()), (current) => {
      clauses(current)[2]!.content![0]!.content = [{ type: "text", text: "The claim is adjourned generally." }];
      clauses(current).push(userClause("The claimant may apply to restore the claim."));
    });

    assert.deepEqual(describeChanges(next), { inserted: 1, modified: 1 });
  });

  it("gives each accepted clause its own ID when a document is accepted again", () => {
    const again = edited(acceptChanges(judges()), (current) => {
      clauses(current).push(userClause("The claimant may apply to restore the claim."));
    });

    assert.deepEqual(describeChanges(acceptChanges(again)), { inserted: 0, modified: 0 });
  });
});
