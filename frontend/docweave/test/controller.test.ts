import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { getDocumentNode } from "../src/builder.js";
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
});
