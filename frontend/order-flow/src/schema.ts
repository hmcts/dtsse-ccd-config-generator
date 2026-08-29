import { Schema } from "prosemirror-model";
import { schema } from "prosemirror-schema-basic";
import { addListNodes } from "prosemirror-schema-list";

const allowedNodeNames = ["doc", "paragraph", "heading", "text"];
const allowedMarkNames = ["em", "strong"];

const allowedSchema = new Schema({
  nodes: Object.fromEntries(
    allowedNodeNames.map((name) => [name, schema.spec.nodes.get(name)!]),
  ),
  marks: Object.fromEntries(
    allowedMarkNames.map((name) => [name, schema.spec.marks.get(name)!]),
  ),
});

const paragraphSpec = allowedSchema.spec.nodes.get("paragraph")!;
const nodesWithParagraphId = allowedSchema.spec.nodes.update("paragraph", {
  ...paragraphSpec,
  attrs: {
    ...paragraphSpec.attrs,
    id: { default: null },
  },
});

const nodesWithLists = addListNodes(
  nodesWithParagraphId,
  "paragraph block*",
  "block",
);

const listItemSpec = nodesWithLists.get("list_item")!;
const nodesWithListItemId = nodesWithLists.update("list_item", {
  ...listItemSpec,
  attrs: {
    ...listItemSpec.attrs,
    id: { default: null },
  },
});

export const editorSchema = new Schema({
  nodes: nodesWithListItemId,
  marks: allowedSchema.spec.marks,
});
