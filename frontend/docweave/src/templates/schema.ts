import { Schema } from "prosemirror-model";

import { editorSchema } from "../schema.js";
import {
  TEMPLATE_DATE_NODE,
  writeTemplateDate,
  type TemplateDate,
} from "./dates.js";

/**
 * What a stored template may hold: a document's rich text, without generated
 * facts, plus the dates it works out when it is inserted. Dates exist only
 * here. An author edits them as text and inserting writes them out as text,
 * so both of those use the editor's schema and no date can reach a document.
 */
export const templateSchema = new Schema({
  nodes: editorSchema.spec.nodes.remove("generated_text").addToEnd(TEMPLATE_DATE_NODE, {
    inline: true,
    group: "inline",
    atom: true,
    leafText(node) {
      return writeTemplateDate(node.attrs as TemplateDate);
    },
    attrs: {
      name: {},
      label: { default: null },
      offset: { default: 0 },
      unit: { default: "days" },
    },
    toDOM(node) {
      return [
        "span",
        { class: "docweave-template-date" },
        writeTemplateDate(node.attrs as TemplateDate).slice(1, -1),
      ];
    },
  }),
  marks: editorSchema.spec.marks,
});
