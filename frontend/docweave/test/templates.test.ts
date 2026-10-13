import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { editorSchema } from "../src/schema.js";
import {
  createTemplateFragment,
  parseTemplateFragment,
} from "../src/templates/provider.js";

describe("template fragments", () => {
  it("round trips supported rich text", () => {
    const document = editorSchema.node(
      "doc",
      null,
      editorSchema.node("ordered_list", null, [
        editorSchema.node("list_item", null, [
          editorSchema.node(
            "paragraph",
            null,
            editorSchema.text("Pay the claimant", [
              editorSchema.mark("strong"),
            ]),
          ),
        ]),
      ]),
    );

    const fragment = createTemplateFragment(document);

    assert.deepEqual(
      parseTemplateFragment(fragment).document.toJSON(),
      document.toJSON(),
    );
  });

  it("rejects generated facts and managed IDs", () => {
    assert.throws(
      () => parseTemplateFragment({
        schema: "docweave-template",
        version: 1,
        content: {
          type: "doc",
          content: [{
            type: "paragraph",
            attrs: { id: "managed" },
            content: [{ type: "text", text: "Protected" }],
          }],
        },
      }),
      /Managed node IDs/,
    );

    assert.throws(
      () => parseTemplateFragment({
        schema: "docweave-template",
        version: 1,
        content: {
          type: "doc",
          content: [{
            type: "paragraph",
            content: [{
              type: "generated_text",
              attrs: { id: "fact", text: "Protected" },
            }],
          }],
        },
      }),
      /Unsupported template node/,
    );
  });
});
