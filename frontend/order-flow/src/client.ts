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
import { menuBar, type MenuItem } from "prosemirror-menu";
import { type Node as ProseMirrorNode } from "prosemirror-model";
import {
  EditorState,
  Plugin,
  type Command,
} from "prosemirror-state";
import {Decoration, DecorationSet, EditorView} from "prosemirror-view";

import {
  insertUserParagraphAfterSystem,
  wrapInBulletList,
  wrapInOrderedList,
} from "./commands.js";
import { editorSchema } from "./schema.js";

import "prosemirror-view/style/prosemirror.css";
import "prosemirror-menu/style/menu.css";
import "prosemirror-example-setup/style/style.css";
import "prosemirror-gapcursor/style/gapcursor.css";

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

let debugPlugin = new Plugin({
  props: {
    decorations(state) {

      state.doc.descendants((node, pos) => {
        console.log(node.type.name, node.toString())
      });

      return DecorationSet.create(state.doc, []);
    }
  }
});

const menuItems = buildMenuItems(editorSchema);

function useCommand(item: MenuItem | undefined, command: Command): void {
  if (!item) return;

  item.spec.run = command;
  item.spec.enable = (state) => command(state);
}

useCommand(menuItems.wrapBulletList, wrapInBulletList);
useCommand(menuItems.wrapOrderedList, wrapInOrderedList);

const generatedKeymap = buildKeymap(editorSchema, {
  "Shift-Ctrl-8": false,
  "Shift-Ctrl-9": false,
});

function createEditorState(): EditorState {
  return EditorState.create({
    schema: editorSchema,
    plugins: [
    buildInputRules(editorSchema),
    keymap({ Enter: insertUserParagraphAfterSystem }),
    keymap({
      "Shift-Ctrl-8": wrapInBulletList,
      "Shift-Ctrl-9": wrapInOrderedList,
    }),
    keymap(generatedKeymap),
    keymap(baseKeymap),
    dropCursor(),
    gapCursor(),
    menuBar({
      floating: true,
      content: menuItems.fullMenu,
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
}

export interface OrderController {
  render(target: ProseMirrorNode): void;
}

interface TopLevelClause {
  node: ProseMirrorNode;
  position: number;
}

function findTopLevelClause(
  document: ProseMirrorNode,
  id: string,
): TopLevelClause | undefined {
  let result: TopLevelClause | undefined;

  document.descendants((node, pos) => {
    if (node.attrs.id === id) {
      result = { node, position: pos}
    }
  });

  return result;
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

  const controller: OrderController = {
    render(_target: ProseMirrorNode): void {
      // TODO: reconcile the target with view.state.doc.
    }
  };

  return controller;
}
