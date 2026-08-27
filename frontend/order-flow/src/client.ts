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
import { EditorView } from "prosemirror-view";
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

if (!editor || !content) {
  throw new Error("The editor page is missing its ProseMirror mount points");
}

const editorSchema = new Schema({
  nodes: addListNodes(
    schema.spec.nodes,
    "paragraph block*",
    "block",
  ),
  marks: schema.spec.marks,
});

function schemaSection(title: string, names: string[]): HTMLElement {
  const section = document.createElement("section");
  const heading = document.createElement("h2");
  const contents = document.createElement("pre");

  heading.textContent = title;
  contents.textContent = names.join("\n");
  section.append(heading, contents);

  return section;
}

const nodesDebug = schemaSection(
  "Schema nodes",
  Object.keys(editorSchema.nodes),
);
const marksDebug = schemaSection(
  "Schema marks",
  Object.keys(editorSchema.marks),
);
const documentSection = document.createElement("section");
const documentHeading = document.createElement("h2");
const documentDebug = document.createElement("pre");
documentHeading.textContent = "Document structure";
documentSection.append(documentHeading, documentDebug);
editor.after(documentSection, nodesDebug, marksDebug);

const state = EditorState.create({
  doc: DOMParser.fromSchema(editorSchema).parse(content),
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
  },
});

documentDebug.textContent = JSON.stringify(view.state.doc.toJSON(), null, 2);
