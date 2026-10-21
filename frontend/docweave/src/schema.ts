import {
  type MarkSpec,
  type Node as ProseMirrorNode,
  type NodeSpec,
  Schema,
} from "prosemirror-model";
import {
  marks as basicMarks,
  nodes as basicNodes,
} from "prosemirror-schema-basic";
import {
  bulletList,
  listItem,
  orderedList,
} from "prosemirror-schema-list";

export function validHeadingLevel(value: unknown): void {
  if (typeof value !== "number" || !Number.isInteger(value) || value < 1 || value > 6) {
    throw new RangeError("Heading level must be an integer from 1 to 6");
  }
}

/**
 * What a list item holds, in the editor and in a final document: its wording,
 * then its subclauses or bullets. Rich text that holds more, such as wording
 * after a clause's subclauses, is fitted to it by ProseMirror's parser, which
 * makes that wording the next clause.
 */
export const LIST_ITEM_CONTENT = "paragraph+ (ordered_list | bullet_list)?";

/** Whether a node is a list: numbered, whose items are clauses, or bulleted. */
export function isList(node: ProseMirrorNode): boolean {
  return node.type.name === "ordered_list" || node.type.name === "bullet_list";
}

const nodes = {
  doc: basicNodes.doc,
  paragraph: {
    ...basicNodes.paragraph,
    attrs: {
      id: { default: null, validate: "string|null" },
    },
  },
  heading: {
    ...basicNodes.heading,
    attrs: { level: { default: 1, validate: validHeadingLevel } },
  },
  text: basicNodes.text,
  hard_break: { ...basicNodes.hard_break, leafText: () => "\n" },
  generated_text: {
    inline: true,
    group: "inline",
    atom: true,
    leafText(node) {
      return node.attrs.text as string;
    },
    attrs: {
      id: { validate: "string" },
      text: { validate: "string" },
    },
    toDOM(node) {
      return [
        "span",
        {
          "data-generated-text": node.attrs.id,
          contenteditable: "false",
        },
        node.attrs.text,
      ];
    },
  },
  ordered_list: {
    ...orderedList,
    attrs: {
      ...orderedList.attrs,
      id: { default: null, validate: "string|null" },
    },
    content: "list_item+",
    group: "block",
  },
  bullet_list: {
    ...bulletList,
    attrs: {
      id: { default: null, validate: "string|null" },
    },
    content: "list_item+",
    group: "block",
  },
  list_item: {
    ...listItem,
    attrs: {
      id: { default: null, validate: "string|null" },
    },
    content: LIST_ITEM_CONTENT,
  },
} satisfies Record<string, NodeSpec>;

const marks = {
  em: basicMarks.em,
  strong: basicMarks.strong,
} satisfies Record<string, MarkSpec>;

export const editorSchema = new Schema({ nodes, marks });
