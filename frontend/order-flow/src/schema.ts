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
      id: { default: null },
    },
  },
  heading: basicNodes.heading,
  text: basicNodes.text,
  form_value: {
    inline: true,
    group: "inline",
    atom: true,
    attrs: {
      id: {},
      text: {},
    },
    toDOM(node) {
      return [
        "span",
        {
          "data-form-value": node.attrs.id,
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
      id: { default: null },
    },
    content: "list_item+",
    group: "block",
  },
  list_item: {
    ...listItem,
    attrs: {
      id: { default: null },
    },
    content: "paragraph ordered_list?",
  },
} satisfies Record<string, NodeSpec>;

const marks = {
  em: basicMarks.em,
  strong: basicMarks.strong,
} satisfies Record<string, MarkSpec>;

export const editorSchema = new Schema({ nodes, marks });
