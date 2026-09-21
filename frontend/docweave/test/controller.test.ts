import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { getDocumentNode } from "../src/builder.js";
import { createDocEditorController } from "../src/controller.js";
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
});
