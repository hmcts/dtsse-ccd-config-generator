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
const nodesWithParagraphClauseId = allowedSchema.spec.nodes.update("paragraph", {
  ...paragraphSpec,
  attrs: {
    ...paragraphSpec.attrs,
    clause_id: { default: null },
  },
});

const nodesWithLists = addListNodes(
  nodesWithParagraphClauseId,
  "paragraph block*",
  "block",
);

const listItemSpec = nodesWithLists.get("list_item")!;
const nodesWithListItemClauseId = nodesWithLists.update("list_item", {
  ...listItemSpec,
  attrs: {
    ...listItemSpec.attrs,
    clause_id: { default: null },
  },
});

export const editorSchema = new Schema({
  nodes: nodesWithListItemClauseId,
  marks: allowedSchema.spec.marks,
});
