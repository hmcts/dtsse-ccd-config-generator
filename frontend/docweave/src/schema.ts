import { type MarkSpec, type NodeSpec, Schema } from "prosemirror-model";
import {
  marks as basicMarks,
  nodes as basicNodes,
} from "prosemirror-schema-basic";
import {
  listItem,
  orderedList,
} from "prosemirror-schema-list";

export function validHeadingLevel(value: unknown): void {
  if (typeof value !== "number" || !Number.isInteger(value) || value < 1 || value > 6) {
    throw new RangeError("Heading level must be an integer from 1 to 6");
  }
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
  list_item: {
    ...listItem,
    attrs: {
      id: { default: null, validate: "string|null" },
    },
    content: "paragraph+ ordered_list?",
  },
} satisfies Record<string, NodeSpec>;

const marks = {
  em: basicMarks.em,
  strong: basicMarks.strong,
} satisfies Record<string, MarkSpec>;

export const editorSchema = new Schema({ nodes, marks });
