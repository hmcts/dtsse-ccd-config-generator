import { dropCursor } from "prosemirror-dropcursor";
import { gapCursor } from "prosemirror-gapcursor";
import { toggleMark } from "prosemirror-commands";
import { history, redo, undo } from "prosemirror-history";
import { type Node as ProseMirrorNode } from "prosemirror-model";
import { wrapInList } from "prosemirror-schema-list";
import { type Command, EditorState } from "prosemirror-state";
import { EditorView } from "prosemirror-view";

import {
  createDiffStylingPlugin,
  getGeneratedDocument,
  setGeneratedDocument,
} from "./diff-styling.js";
import {
  createKeymapPlugins,
  indentListItem,
  outdentListItem,
} from "./keymap.js";
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
      createDiffStylingPlugin(),
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

const wrapInOrderedList = wrapInList(editorSchema.nodes.ordered_list!);

const createNumberedClause: Command = (state, dispatch, view) => {
  const selectionTouchesManagedContent = [
    state.selection.$from,
    state.selection.$to,
  ].some(($position) =>
    $position.depth > 0 &&
    typeof $position.node(1).attrs.id === "string"
  );

  return !selectionTouchesManagedContent &&
    wrapInOrderedList(state, dispatch, view);
};

const editorCommands = {
  undo,
  redo,
  bold: toggleMark(editorSchema.marks.strong!),
  italic: toggleMark(editorSchema.marks.em!),
  numbered: createNumberedClause,
  outdent: outdentListItem,
  indent: indentListItem,
} satisfies Record<string, Command>;

type EditorCommandName = keyof typeof editorCommands;

function isEditorCommandName(value: string): value is EditorCommandName {
  return Object.hasOwn(editorCommands, value);
}

function connectToolbar(
  toolbar: HTMLElement,
  view: EditorView,
): () => void {
  const buttons = toolbar.querySelectorAll<HTMLButtonElement>(
    "[data-editor-command]",
  );

  function update(): void {
    for (const button of buttons) {
      const commandName = button.dataset.editorCommand;
      const enabled = commandName !== undefined &&
        isEditorCommandName(commandName) &&
        editorCommands[commandName](view.state);

      button.disabled = !enabled;
      button.setAttribute("aria-disabled", String(!enabled));
    }
  }

  toolbar.addEventListener("mousedown", (event) => {
    if (event.target instanceof Element &&
      event.target.closest("[data-editor-command]")) {
      event.preventDefault();
    }
  });

  toolbar.addEventListener("click", (event) => {
    if (!(event.target instanceof Element)) return;

    const button = event.target.closest<HTMLButtonElement>(
      "[data-editor-command]",
    );
    const commandName = button?.dataset.editorCommand;
    if (!button || button.disabled || commandName === undefined ||
      !isEditorCommandName(commandName)) return;

    view.focus();
    editorCommands[commandName](view.state, view.dispatch, view);
  });

  update();
  return update;
}

export function createOrderEditor(
  selector: string
): OrderController {
  const editor = document.querySelector<HTMLElement>(selector);
  const toolbar = document.querySelector<HTMLElement>(
    "#order-editor-toolbar",
  );
  if (!editor || !toolbar) {
    throw new Error(`Editor or toolbar element not found: ${selector}`);
  }

  let updateToolbar = (): void => {};

  const view = new EditorView(editor, {
    state: createEditorState(),
    dispatchTransaction(transaction) {
      const nextState = view.state.apply(transaction);
      view.updateState(nextState);
      updateToolbar();
      documentDebugElement.textContent = JSON.stringify(
        nextState.doc.toJSON(),
        null,
        2,
      );
      htmlDebugElement.textContent = formatHtml(view.dom.outerHTML);
    },
  });

  updateToolbar = connectToolbar(toolbar, view);

  documentDebugElement.textContent = JSON.stringify(
    view.state.doc.toJSON(),
    null,
    2,
  );
  htmlDebugElement.textContent = formatHtml(view.dom.outerHTML);

  const controller: OrderController = {
    render(target: ProseMirrorNode): void {
      let transaction = view.state.tr;
      const previousTarget = getGeneratedDocument(view.state);

      if (previousTarget) {
        transaction = reconcileOrderDocument(
          transaction,
          previousTarget,
          target,
        );
      } else {
        transaction.replaceWith(
          0,
          transaction.doc.content.size,
          target.content,
        );
      }

      setGeneratedDocument(transaction, target);

      view.dispatch(transaction.setMeta("addToHistory", false));
    }
  };

  return controller;
}
