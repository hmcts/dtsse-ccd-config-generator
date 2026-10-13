import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { getDocumentNode } from "../src/builder.js";
import { createDocEditorController } from "../src/controller.js";
import { editorSchema } from "../src/schema.js";
import { buildDoc, createDocEditor } from "../src/index.js";

describe("headless editor", () => {
  it("runs the controller headlessly and restores snapshots", () => {
    const first = buildDoc((doc) => {
      doc.paragraph("deadline", (content) => {
        content.text("Payment is due by ").fact("date", "1 September");
      });
    });
    const controller = createDocEditor();
    controller.render(first);
    const restored = createDocEditor({
      initialSnapshot: controller.getSnapshot(),
    });
    const second = buildDoc((doc) => {
      doc.paragraph("deadline", (content) => {
        content.text("Payment is due by ").fact("date", "8 September");
      });
    });

    restored.render(second);

    assert.equal(restored.getDocument(), second);
    assert.deepEqual(
      restored.getSnapshot().current,
      getDocumentNode(second).toJSON(),
    );
  });

  it("loads another document in place, treating the next render as the first", () => {
    const order = (date: string) =>
      buildDoc((doc) => {
        doc.paragraph("deadline", (content) => {
          content.text("Payment is due by ").fact("date", date);
        });
      });
    const directions = buildDoc((doc) => {
      doc.paragraph("directions", "The parties must file evidence.");
    });
    const controller = createDocEditor();
    controller.render(order("1 September"));
    const saved = controller.getSnapshot();

    controller.load();
    assert.equal(controller.getDocument(), undefined);
    controller.render(directions);
    assert.deepEqual(
      controller.getSnapshot().current,
      getDocumentNode(directions).toJSON(),
    );

    controller.load(saved);
    assert.deepEqual(controller.getSnapshot(), saved);
    const later = order("8 September");
    controller.render(later);
    assert.deepEqual(
      controller.getSnapshot().current,
      getDocumentNode(later).toJSON(),
    );
  });

  it("keeps the current document when asked to load an unsupported snapshot", () => {
    const controller = createDocEditor();
    controller.render(buildDoc((doc) => doc.paragraph("kept", "Kept.")));
    const before = controller.getSnapshot();

    assert.throws(
      () => controller.load({ ...before, version: 2 as 1 }),
      /Unsupported Docweave snapshot version/,
    );
    assert.deepEqual(controller.getSnapshot(), before);
  });

  it("reports the snapshot whenever it changes, and only then", () => {
    const changes: unknown[] = [];
    const controller = createDocEditor({
      onChange: (snapshot) => changes.push(snapshot),
    });
    const first = () => buildDoc((doc) => doc.paragraph("first", "First."));

    controller.render(first());
    assert.deepEqual(changes, [controller.getSnapshot()]);

    controller.render(first());
    assert.equal(changes.length, 1);

    const saved = controller.getSnapshot();
    controller.load();
    assert.deepEqual(changes[1], controller.getSnapshot());
    controller.load(saved);
    assert.deepEqual(changes[2], saved);
    assert.equal(changes.length, 3);

    // Loading what is already there is not a change, however it is loaded.
    controller.load(controller.getSnapshot());
    controller.load(saved);
    controller.load(JSON.parse(JSON.stringify(saved)));
    assert.equal(changes.length, 3);
    controller.load();
    controller.load();
    assert.equal(changes.length, 4);
  });

  it("still hands the view a fresh state when a load leaves the snapshot as it was", () => {
    const changes: unknown[] = [];
    const runtime = createDocEditorController({
      onChange: (snapshot) => changes.push(snapshot),
    });
    const states: unknown[] = [];
    runtime.setStateListener((state) => states.push(state));
    runtime.controller.render(buildDoc((doc) => doc.paragraph("first", "First.")));
    const saved = runtime.controller.getSnapshot();
    const [seenChanges, seenStates] = [changes.length, states.length];

    runtime.controller.load(saved);

    assert.equal(changes.length, seenChanges);
    assert.equal(states.length, seenStates + 1);
    assert.deepEqual(runtime.controller.getSnapshot(), saved);
  });

  describe("inserting wording for the reader", () => {
    const order = (withCosts: boolean) =>
      buildDoc((doc) => {
        doc.paragraph("heading", "IT IS ORDERED THAT:");
        doc.orderedList("clauses", (list) => {
          list.item("decision", "The application is granted.");
          if (withCosts) list.item("costs", "Costs in the case.");
        });
      });
    const text = (controller: ReturnType<typeof createDocEditor>) => {
      const doc = editorSchema.nodeFromJSON(controller.getSnapshot().current);
      return doc.textBetween(0, doc.content.size, "\n");
    };

    it("adds paragraphs after the last clause as the reader's own numbered clauses", () => {
      const changes: unknown[] = [];
      const controller = createDocEditor({ onChange: (s) => changes.push(s) });
      controller.render(order(false));
      const rendered = changes.length;

      controller.insert(
        ["The hearing is adjourned.", "  ", "Costs reserved."],
        { at: "end" },
      );

      assert.equal(
        text(controller),
        "IT IS ORDERED THAT:\nThe application is granted.\nThe hearing is adjourned.\nCosts reserved.",
      );
      assert.equal(changes.length, rendered + 1);
      const list = editorSchema.nodeFromJSON(controller.getSnapshot().current)
        .child(1);
      assert.equal(list.childCount, 3);
      assert.equal(list.child(1).attrs.id, null);
      assert.equal(list.child(2).attrs.id, null);
    });

    it("keeps the inserted wording through later renders of the same clauses", () => {
      const controller = createDocEditor();
      controller.render(order(false));
      controller.insert(["The hearing is adjourned."], { at: "end" });

      controller.render(order(true));

      // The new generated clause follows its generated sibling; the reader's stays.
      assert.equal(
        text(controller),
        "IT IS ORDERED THAT:\nThe application is granted.\nCosts in the case.\nThe hearing is adjourned.",
      );
    });

    it("inserts nothing when every paragraph is blank", () => {
      const changes: unknown[] = [];
      const controller = createDocEditor({ onChange: (s) => changes.push(s) });
      controller.render(order(false));
      const rendered = changes.length;

      controller.insert(["", "  "]);
      controller.insert(["", "  "], { at: "end" });

      assert.equal(changes.length, rendered);
      assert.equal(text(controller), "IT IS ORDERED THAT:\nThe application is granted.");
    });
  });
});
