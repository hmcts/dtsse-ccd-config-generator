import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { EditorState } from "prosemirror-state";
import { DecorationSet } from "prosemirror-view";

import {
  createDiffStylingPlugin,
  deleteUserAuthoredNode,
} from "../src/diff-styling.js";
import { editorSchema } from "../src/schema.js";

describe("diff styling", () => {
  it("decorates user-authored clauses and not generated clauses", () => {
    const generatedItem = editorSchema.node(
      "list_item",
      { id: "clause:generated" },
      editorSchema.node("paragraph", null, editorSchema.text("Generated")),
    );
    const userAuthoredItem = editorSchema.node(
      "list_item",
      null,
      editorSchema.node("paragraph", null, editorSchema.text("User authored")),
    );
    const doc = editorSchema.node("doc", null, [
      editorSchema.node(
        "paragraph",
        { id: "clause:introduction" },
        editorSchema.text("Introduction"),
      ),
      editorSchema.node(
        "ordered_list",
        { id: "container:clauses" },
        [generatedItem, userAuthoredItem],
      ),
    ]);
    const plugin = createDiffStylingPlugin();
    const state = EditorState.create({
      schema: editorSchema,
      doc,
      plugins: [plugin],
    });
    const decorationSet = plugin.props.decorations?.call(plugin, state);

    assert.ok(decorationSet instanceof DecorationSet);
    const decorations = decorationSet.find();
    let userAuthoredPosition: number | undefined;
    doc.descendants((node, position) => {
      if (node === userAuthoredItem) userAuthoredPosition = position;
    });

    assert.notEqual(userAuthoredPosition, undefined);
    const nodeDecorations = decorations.filter(
      (decoration) => decoration.from < decoration.to,
    );
    const widgetDecorations = decorations.filter(
      (decoration) => decoration.from === decoration.to,
    );

    assert.equal(nodeDecorations.length, 1);
    assert.equal(widgetDecorations.length, 1);
    assert.equal(nodeDecorations[0]!.from, userAuthoredPosition);
    assert.equal(
      nodeDecorations[0]!.to,
      userAuthoredPosition! + userAuthoredItem.nodeSize,
    );
    assert.equal(widgetDecorations[0]!.from, userAuthoredPosition);
    assert.equal(
      widgetDecorations[0]!.spec.revertNodeFrom,
      userAuthoredPosition,
    );

    const transaction = deleteUserAuthoredNode(
      state,
      userAuthoredPosition!,
    );
    assert.ok(transaction);
    assert.deepEqual(
      transaction.doc.child(1).children.map((item) => item.attrs.id),
      ["clause:generated"],
    );
  });
});
