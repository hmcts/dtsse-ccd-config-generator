import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { TextSelection } from "prosemirror-state";

import { getDocumentNode } from "../src/builder.js";
import { createDocEditorController } from "../src/controller.js";
import { editorSchema } from "../src/schema.js";
import { buildDoc, createDocEditor, renderText } from "../src/index.js";

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
    const text = (controller: ReturnType<typeof createDocEditor>) =>
      renderText(controller.getSnapshot());

    it("adds paragraphs after the last clause as the reader's own numbered clauses", () => {
      const changes: unknown[] = [];
      const controller = createDocEditor({ onChange: (s) => changes.push(s) });
      controller.render(order(false));
      const rendered = changes.length;

      const inserted = controller.insert(
        ["The hearing is adjourned.", "  ", "Costs reserved."],
        { at: "end" },
      );

      assert.equal(inserted, true);
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

    it("adds after the last clause even when the reader has emptied it", () => {
      for (const numbered of [false, true]) {
        const runtime = createDocEditorController({});
        runtime.controller.render(numbered ? order(false) : buildDoc((doc) => {
          doc.paragraph("heading", "IT IS ORDERED THAT:");
          doc.paragraph("decision", "The application is granted.");
        }));
        const { doc } = runtime.state;
        const last = doc.content.size - (numbered ? 3 : 1);
        runtime.dispatch(runtime.state.tr.delete(
          last - "The application is granted.".length,
          last,
        ));
        assert.equal(renderText(runtime.controller.getSnapshot()), "IT IS ORDERED THAT:\n");

        runtime.controller.insert(["Costs reserved."], { at: "end" });

        assert.equal(
          renderText(runtime.controller.getSnapshot()),
          "IT IS ORDERED THAT:\n\nCosts reserved.",
          numbered ? "numbered clause" : "paragraph",
        );
      }
    });

    it("inserts at the selection by default, after the clause the caret is in", () => {
      const runtime = createDocEditorController({});
      runtime.controller.render(order(true));
      const { doc } = runtime.state;
      let decision = -1;
      doc.descendants((node, pos) => {
        if (node.attrs.id === "item:decision") decision = pos;
      });
      runtime.dispatch(runtime.state.tr.setSelection(
        TextSelection.create(doc, decision + 2 + "The application".length),
      ));

      runtime.controller.insert(["The hearing is adjourned."]);

      assert.equal(
        renderText(runtime.controller.getSnapshot()),
        "IT IS ORDERED THAT:\nThe application is granted.\nThe hearing is adjourned.\nCosts in the case.",
      );
    });

    it("inserts nothing when every paragraph is blank", () => {
      const changes: unknown[] = [];
      const controller = createDocEditor({ onChange: (s) => changes.push(s) });
      controller.render(order(false));
      const rendered = changes.length;

      assert.equal(controller.insert(["", "  "]), false);
      assert.equal(controller.insert(["", "  "], { at: "end" }), false);

      assert.equal(changes.length, rendered);
      assert.equal(text(controller), "IT IS ORDERED THAT:\nThe application is granted.");
    });
  });
});
