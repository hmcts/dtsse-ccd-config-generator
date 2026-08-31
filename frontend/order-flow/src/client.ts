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

function createEditorState(document?: ProseMirrorNode): EditorState {
  return EditorState.create({
    schema: editorSchema,
    doc: document,
    plugins: [
      createDiffStylingPlugin(),
      ...createKeymapPlugins(),
      dropCursor(),
      gapCursor(),
      history(),
    ],
  });
}

export interface OrderEditorDocument {
  schema: "order-flow-document";
  version: 1;
  current: Record<string, unknown>;
  generated: Record<string, unknown>;
}

export interface CreateOrderEditorOptions {
  mount: HTMLElement | string;
  toolbar?: HTMLElement | string;
  initialDocument?: OrderEditorDocument;
  onChange?: (document: OrderEditorDocument) => void;
}

export interface OrderEditorController {
  render(target: ProseMirrorNode): void;
  getDocument(): OrderEditorDocument;
  destroy(): void;
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

interface ConnectedToolbar {
  update(): void;
  destroy(): void;
}

function connectToolbar(
  toolbar: HTMLElement,
  view: EditorView,
): ConnectedToolbar {
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

  const handleMouseDown = (event: MouseEvent): void => {
    if (event.target instanceof Element &&
      event.target.closest("[data-editor-command]")) {
      event.preventDefault();
    }
  };

  const handleClick = (event: MouseEvent): void => {
    if (!(event.target instanceof Element)) return;

    const button = event.target.closest<HTMLButtonElement>(
      "[data-editor-command]",
    );
    const commandName = button?.dataset.editorCommand;
    if (!button || button.disabled || commandName === undefined ||
      !isEditorCommandName(commandName)) return;

    view.focus();
    editorCommands[commandName](view.state, view.dispatch, view);
  };

  toolbar.addEventListener("mousedown", handleMouseDown);
  toolbar.addEventListener("click", handleClick);

  update();
  return {
    update,
    destroy(): void {
      toolbar.removeEventListener("mousedown", handleMouseDown);
      toolbar.removeEventListener("click", handleClick);
    },
  };
}

export function createOrderEditor(
  options: CreateOrderEditorOptions,
): OrderEditorController {
  const ownerDocument = typeof options.mount === "string"
    ? globalThis.document
    : options.mount.ownerDocument;
  const editor = typeof options.mount === "string"
    ? ownerDocument?.querySelector<HTMLElement>(options.mount)
    : options.mount;
  const toolbar = typeof options.toolbar === "string"
    ? ownerDocument?.querySelector<HTMLElement>(options.toolbar)
    : options.toolbar;
  if (!editor) {
    throw new Error(`Order editor mount point not found: ${String(options.mount)}`);
  }

  const initialCurrent = options.initialDocument
    ? editorSchema.nodeFromJSON(options.initialDocument.current)
    : undefined;
  const initialGenerated = options.initialDocument
    ? editorSchema.nodeFromJSON(options.initialDocument.generated)
    : undefined;
  let initialState = createEditorState(initialCurrent);
  if (initialGenerated) {
    initialState = initialState.apply(
      setGeneratedDocument(initialState.tr, initialGenerated),
    );
  }

  let connectedToolbar: ConnectedToolbar | undefined;

  const getDocument = (): OrderEditorDocument => {
    const generated = getGeneratedDocument(view.state) ?? view.state.doc;
    return {
      schema: "order-flow-document",
      version: 1,
      current: view.state.doc.toJSON() as Record<string, unknown>,
      generated: generated.toJSON() as Record<string, unknown>,
    };
  };

  const view = new EditorView(editor, {
    state: initialState,
    dispatchTransaction(transaction) {
      const nextState = view.state.apply(transaction);
      view.updateState(nextState);
      connectedToolbar?.update();
      options.onChange?.(getDocument());
    },
  });

  editor.classList.add("order-flow-editor");
  if (toolbar) connectedToolbar = connectToolbar(toolbar, view);

  const controller: OrderEditorController = {
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
    },
    getDocument,
    destroy(): void {
      connectedToolbar?.destroy();
      view.destroy();
    },
  };

  return controller;
}
