import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { createInMemoryTemplateProvider } from "../../examples/court-order/template-provider.js";

const content = {
  schema: "docweave-template" as const,
  version: 1 as const,
  content: {
    type: "doc",
    content: [{
      type: "paragraph",
      content: [{ type: "text", text: "Costs in the case." }],
    }],
  },
};

describe("in-memory template provider", () => {
  it("creates, searches, updates and deletes templates", async () => {
    const provider = createInMemoryTemplateProvider();
    const created = await provider.create({
      title: "Standard costs wording",
      content,
    });

    assert.equal(created.revision, 1);
    assert.deepEqual(created.content, content);
    assert.deepEqual(
      (await provider.search("COSTS")).items.map((item) => item.title),
      ["Standard costs wording"],
    );

    const updated = await provider.update(created.id, {
      title: "Updated costs wording",
      content,
      expectedRevision: created.revision,
    });
    assert.equal(updated.revision, 2);
    assert.equal((await provider.search("Updated")).items[0]?.title, "Updated costs wording");

    await assert.rejects(
      provider.update(created.id, {
        title: "Stale update",
        content,
        expectedRevision: 1,
      }),
      /changed elsewhere/,
    );

    await provider.delete(created.id, updated.revision);
    assert.deepEqual((await provider.search("")).items, []);
  });
});
