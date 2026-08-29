import { type MarkSpec, type NodeSpec, Schema } from "prosemirror-model";
import {
  marks as basicMarks,
  nodes as basicNodes,
} from "prosemirror-schema-basic";
import {
  listItem,
  orderedList,
} from "prosemirror-schema-list";

const nodes = {
  doc: basicNodes.doc,
  paragraph: {
    ...basicNodes.paragraph,
    attrs: {
      clause_id: { default: null },
    },
  },
  heading: basicNodes.heading,
  text: basicNodes.text,
  ordered_list: {
    ...orderedList,
    content: "list_item+",
    group: "block",
  },
  list_item: {
    ...listItem,
    attrs: {
      clause_id: { default: null },
    },
    content: "paragraph ordered_list?",
  },
} satisfies Record<string, NodeSpec>;

const marks = {
  em: basicMarks.em,
  strong: basicMarks.strong,
} satisfies Record<string, MarkSpec>;

export const editorSchema = new Schema({ nodes, marks });
