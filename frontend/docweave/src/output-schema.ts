import { type Node as ProseMirrorNode, Schema } from "prosemirror-model";
import {
  marks as basicMarks,
  nodes as basicNodes,
} from "prosemirror-schema-basic";
import { listItem, orderedList } from "prosemirror-schema-list";

/**
 * What a final document may contain: the editor schema without its editing
 * furniture. There are no managed IDs and no facts; a fact is ordinary text
 * once the document leaves the editor. The snapshot keeps that structure.
 */
export const outputSchema = new Schema({
  nodes: {
    doc: basicNodes.doc,
    paragraph: basicNodes.paragraph,
    heading: basicNodes.heading,
    text: basicNodes.text,
    ordered_list: { ...orderedList, content: "list_item+", group: "block" },
    list_item: { ...listItem, content: "paragraph+ ordered_list?" },
  },
  marks: {
    em: basicMarks.em,
    strong: basicMarks.strong,
  },
});

type NodeJSON = Record<string, unknown> & {
  type?: unknown;
  attrs?: Record<string, unknown>;
  content?: unknown;
};

/**
 * Converts editor-schema JSON: facts become text, and IDs are left behind. A
 * fact with no value becomes nothing, since a text node may not be empty.
 */
function toOutputJSON(node: NodeJSON): NodeJSON | undefined {
  if (node.type === "generated_text") {
    const text = node.attrs?.text;
    return text ? { type: "text", text } : undefined;
  }
  const { id: _id, ...attrs } = node.attrs ?? {};
  const output: NodeJSON = { ...node, attrs };
  if (Array.isArray(node.content)) {
    output.content = node.content
      .map((child) => toOutputJSON(child as NodeJSON))
      .filter((child) => child !== undefined);
  }
  return output;
}

/** Parses a snapshot's editor-schema document into the output schema. */
export function toOutputDocument(
  editorDocument: Record<string, unknown>,
): ProseMirrorNode {
  const node = outputSchema.nodeFromJSON(toOutputJSON(editorDocument)!);
  node.check();
  return node;
}
