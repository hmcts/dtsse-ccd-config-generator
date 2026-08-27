import { initAll } from "govuk-frontend";
import { baseKeymap } from "prosemirror-commands";
import { dropCursor } from "prosemirror-dropcursor";
import {
  buildInputRules,
  buildKeymap,
  buildMenuItems,
} from "prosemirror-example-setup";
import { gapCursor } from "prosemirror-gapcursor";
import { history } from "prosemirror-history";
import { keymap } from "prosemirror-keymap";
import { menuBar } from "prosemirror-menu";
import { DOMParser, Schema } from "prosemirror-model";
import { EditorState, Plugin } from "prosemirror-state";
import {Decoration, DecorationSet, EditorView} from "prosemirror-view";
import { schema } from "prosemirror-schema-basic";
import { addListNodes } from "prosemirror-schema-list";

import "prosemirror-view/style/prosemirror.css";
import "prosemirror-menu/style/menu.css";
import "prosemirror-example-setup/style/style.css";
import "prosemirror-gapcursor/style/gapcursor.css";
import "./application.scss";

initAll();

const editor = document.querySelector<HTMLElement>("#editor");
const content = document.querySelector<HTMLElement>("#content");
const documentDebug = document.querySelector<HTMLElement>("#document-debug");
const htmlDebug = document.querySelector<HTMLElement>("#html-debug");
const nodesDebug = document.querySelector<HTMLElement>("#nodes-debug");
const marksDebug = document.querySelector<HTMLElement>("#marks-debug");
const schemaDebug = document.querySelector<HTMLElement>("#schema-debug");

if (!editor || !content || !documentDebug || !htmlDebug || !nodesDebug || !marksDebug || !schemaDebug) {
  throw new Error("The editor page is missing its ProseMirror mount points");
}

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

const editorSchema = new Schema({
  nodes: addListNodes(
    allowedSchema.spec.nodes,
    "paragraph block*",
    "block",
  ),
  marks: allowedSchema.spec.marks,
});

nodesDebug.textContent = Object.keys(editorSchema.nodes).join("\n");
marksDebug.textContent = Object.keys(editorSchema.marks).join("\n");
schemaDebug.textContent = JSON.stringify(
  {
    nodes: editorSchema.spec.nodes.toObject(),
    marks: editorSchema.spec.marks.toObject(),
  },
  (_key, value) => typeof value === "function" ? "[Function]" : value,
  2,
);

function formatHtml(html: string): string {
  let depth = 0;

  return html.replace(/></g, ">\n<").split("\n").map((line) => {
    if (line.startsWith("</")) depth--;
    const indentedLine = `${"  ".repeat(depth)}${line}`;
    if (/^<[^/!][^>]*>$/.test(line)) depth++;
    return indentedLine;
  }).join("\n");
}

let doc = editorSchema.node("doc", null, [
  editorSchema.node("paragraph", {"foo": 1}, [editorSchema.text("IT IS ORDERED THAT:")])
]);
let otherdoc = DOMParser.fromSchema(editorSchema).parse(content);

const state = EditorState.create({
  doc: doc,
  plugins: [
    buildInputRules(editorSchema),
    keymap(buildKeymap(editorSchema)),
    keymap(baseKeymap),
    dropCursor(),
    gapCursor(),
    menuBar({
      floating: true,
      content: buildMenuItems(editorSchema).fullMenu,
    }),
    history(),
    new Plugin({
      props: {
        attributes: {
          class: "ProseMirror-example-setup-style",
        },
      },
    }),
  ],
});

const view = new EditorView(editor, {
  state,
  dispatchTransaction(transaction) {
    const nextState = view.state.apply(transaction);
    view.updateState(nextState);
    documentDebug.textContent = JSON.stringify(
      nextState.doc.toJSON(),
      null,
      2,
    );
    htmlDebug.textContent = formatHtml(view.dom.outerHTML);
  },
});

documentDebug.textContent = JSON.stringify(view.state.doc.toJSON(), null, 2);
htmlDebug.textContent = formatHtml(view.dom.outerHTML);
