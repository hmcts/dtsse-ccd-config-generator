import { dropCursor } from "prosemirror-dropcursor";
import { gapCursor } from "prosemirror-gapcursor";
import { history } from "prosemirror-history";
import { type Node as ProseMirrorNode } from "prosemirror-model";
import { EditorState } from "prosemirror-state";
import { EditorView } from "prosemirror-view";

import { createKeymapPlugins } from "./keymap.js";
import { reconcileOrderDocument } from "./reconciliation.js";
import { editorSchema } from "./schema.js";

import "prosemirror-gapcursor/style/gapcursor.css";
import "prosemirror-view/style/prosemirror.css";

const documentDebug = document.querySelector<HTMLElement>("#document-debug");
const htmlDebug = document.querySelector<HTMLElement>("#html-debug");
const nodesDebug = document.querySelector<HTMLElement>("#nodes-debug");
const marksDebug = document.querySelector<HTMLElement>("#marks-debug");
const schemaDebug = document.querySelector<HTMLElement>("#schema-debug");

if (!documentDebug || !htmlDebug || !nodesDebug || !marksDebug || !schemaDebug) {
  throw new Error("The editor page is missing its ProseMirror mount points");
}

const documentDebugElement = documentDebug;
const htmlDebugElement = htmlDebug;

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

function createEditorState(): EditorState {
  return EditorState.create({
    schema: editorSchema,
    plugins: [
      ...createKeymapPlugins(),
      dropCursor(),
      gapCursor(),
      history(),
    ],
  });
}

export interface OrderController {
  render(target: ProseMirrorNode): void;
}

export function createOrderEditor(
  selector: string
): OrderController {
  const editor = document.querySelector<HTMLElement>(selector);
  if (!editor) {
    throw new Error(`Editor element not found: ${selector}`);
  }

  const view = new EditorView(editor, {
    state: createEditorState(),
    dispatchTransaction(transaction) {
      const nextState = view.state.apply(transaction);
      view.updateState(nextState);
      documentDebugElement.textContent = JSON.stringify(
        nextState.doc.toJSON(),
        null,
        2,
      );
      htmlDebugElement.textContent = formatHtml(view.dom.outerHTML);
    },
  });

  documentDebugElement.textContent = JSON.stringify(
    view.state.doc.toJSON(),
    null,
    2,
  );
  htmlDebugElement.textContent = formatHtml(view.dom.outerHTML);

  let hasRendered = false;

  const controller: OrderController = {
    render(target: ProseMirrorNode): void {
      let transaction = view.state.tr;

      if (hasRendered) {
        transaction = reconcileOrderDocument(transaction, target);
      } else {
        transaction.replaceWith(
          0,
          transaction.doc.content.size,
          target.content,
        );
      }
      hasRendered = true;

      if (transaction.docChanged) {
        view.dispatch(transaction.setMeta("addToHistory", false));
      }
    }
  };

  return controller;
}
