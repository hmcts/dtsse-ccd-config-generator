import { initAll } from "govuk-frontend";
import { DOMParser, Schema } from "prosemirror-model";
import { EditorState } from "prosemirror-state";
import { EditorView } from "prosemirror-view";
import { schema } from "prosemirror-schema-basic";
import { addListNodes } from "prosemirror-schema-list";
import { exampleSetup } from "prosemirror-example-setup";

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

const state = EditorState.create({
  doc: DOMParser.fromSchema(editorSchema).parse(content),
  plugins: exampleSetup({ schema: editorSchema }),
});

new EditorView(editor, { state });
