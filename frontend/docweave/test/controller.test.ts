import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { getDocumentNode } from "../src/builder.js";
import { buildOrder, createOrderEditor } from "../src/index.js";

describe("headless order editor", () => {
  it("runs the controller headlessly and restores snapshots", () => {
    const first = buildOrder((order) => {
      order.paragraph("deadline", (content) => {
        content.text("Payment is due by ").fact("date", "1 September");
      });
    });
    const controller = createOrderEditor();
    controller.render(first);
    const restored = createOrderEditor({
      initialSnapshot: controller.getSnapshot(),
    });
    const second = buildOrder((order) => {
      order.paragraph("deadline", (content) => {
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
