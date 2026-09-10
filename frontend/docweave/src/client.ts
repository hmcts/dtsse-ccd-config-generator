import { dropCursor } from "prosemirror-dropcursor";
import { gapCursor } from "prosemirror-gapcursor";
import { toggleMark } from "prosemirror-commands";
import { closeHistory, history, redo, undo } from "prosemirror-history";
import { wrapInList } from "prosemirror-schema-list";
import { type Command } from "prosemirror-state";
import { EditorView } from "prosemirror-view";

import {
  type DocWeaveDocument,
  getDocumentFactSources,
} from "./builder.js";
import { createClipboardPlugin } from "./clipboard.js";
import {
  createOrderEditorController,
  type DocWeaveSnapshot,
  type OrderEditorController,
} from "./controller.js";
import {
  createDiffStylingPlugin,
  setGeneratedDocument,
} from "./diff-styling.js";
import {
  createFactNavigationPlugin,
  setFactNavigationSources,
} from "./fact-navigation.js";
import {
  connectEditorToolbar,
  createEditorToolbar,
  type ConnectedEditorToolbar,
  type EditorToolbarCommands,
} from "./editor-toolbar.js";
import {
  createInputRulesPlugin,
  selectionTouchesManagedContent,
} from "./input-rules.js";
import {
  createKeymapPlugins,
  indentListItem,
  outdentListItem,
} from "./keymap.js";
import { createListNumberingPlugin } from "./list-numbering.js";
import { assertCurrentDocumentMatchesGenerated } from "./invariants.js";
import { editorSchema } from "./schema.js";
import {
  createTemplateDialog,
  type TemplateDialog,
} from "./templates/dialog/index.js";
import {
  createHttpTemplateProvider,
  parseTemplateFragment,
  type TemplateProvider,
} from "./templates/index.js";
import { insertTemplate } from "./templates/insertion.js";

export interface CreateOrderEditorOptions {
  mount?: HTMLElement | string;
  initialSnapshot?: DocWeaveSnapshot;
  templates?: {
    url?: string;
    csrfToken?: string | (() => string | undefined);
    provider?: TemplateProvider;
  };
}

const wrapInOrderedList = wrapInList(editorSchema.nodes.ordered_list!);

const createNumberedClause: Command = (state, dispatch, view) =>
  !selectionTouchesManagedContent(state) &&
  wrapInOrderedList(state, dispatch, view);

const editorCommands = {
  undo,
  redo,
  bold: toggleMark(editorSchema.marks.strong!),
  italic: toggleMark(editorSchema.marks.em!),
  numbered: createNumberedClause,
  outdent: outdentListItem,
  indent: indentListItem,
} satisfies EditorToolbarCommands;

function createTemplateButton(ownerDocument: Document): HTMLButtonElement {
  const button = ownerDocument.createElement("button");
  button.type = "button";
  button.className = "docweave-editor__toolbar-button";
  button.setAttribute("aria-label", "Insert template");
  button.textContent = "Insert template";
  return button;
}

export function createOrderEditor(
  options: CreateOrderEditorOptions = {},
): OrderEditorController {
  if (options.templates && options.mount === undefined) {
    throw new Error("Templates require an editor mount");
  }
  if (options.mount === undefined) {
    return createOrderEditorController(options).controller;
  }

  const ownerDocument = typeof options.mount === "string"
    ? globalThis.document
    : options.mount.ownerDocument;
  const editor = typeof options.mount === "string"
    ? ownerDocument?.querySelector<HTMLElement>(options.mount)
    : options.mount;
  if (!editor) {
    throw new Error(`Order editor mount point not found: ${String(options.mount)}`);
  }
  // Mounting twice silently stacked a second toolbar and surface, which is almost
  // always a caller that forgot to destroy the previous editor (module reloads in
  // particular). Fail loudly rather than leaving two editors over one document.
  if (editor.querySelector(".docweave-editor__surface")) {
    throw new Error(
      `Order editor mount point already has an editor, destroy it first: ${String(options.mount)}`,
    );
  }

  const templateProvider = options.templates
    ? options.templates.provider ??
      (options.templates.url
        ? createHttpTemplateProvider({
          url: options.templates.url,
          csrfToken: options.templates.csrfToken,
        })
        : undefined)
    : undefined;
  if (options.templates && !templateProvider) {
    throw new Error("Templates require either a provider or URL");
  }

  const runtime = createOrderEditorController({
    initialSnapshot: options.initialSnapshot,
    plugins: [
      createClipboardPlugin(),
      createListNumberingPlugin(),
      createDiffStylingPlugin(),
      createFactNavigationPlugin(editor.ownerDocument),
      createInputRulesPlugin(),
      ...createKeymapPlugins(),
      dropCursor(),
      gapCursor(),
      history(),
    ],
    prepareGeneratedTransaction(transaction, generated, document) {
      setGeneratedDocument(transaction, generated);
      if (document) {
        setFactNavigationSources(
          transaction,
          getDocumentFactSources(document),
        );
      }
      return transaction;
    },
  });

  const toolbar = createEditorToolbar(
    editor.ownerDocument,
    "Order editor formatting",
  );
  const templateButton = options.templates
    ? createTemplateButton(editor.ownerDocument)
    : undefined;
  if (templateButton) {
    toolbar.append(templateButton);
  }
  const editorSurface = editor.ownerDocument.createElement("div");
  editorSurface.className = "docweave-editor__surface";
  const mountAlreadyStyled = editor.classList.contains("docweave-editor");
  editor.classList.add("docweave-editor");
  editor.append(toolbar, editorSurface);

  let connectedToolbar: ConnectedEditorToolbar | undefined;
  let templateDialog: TemplateDialog | undefined;

  function openTemplateDialog(): void {
    if (!templateDialog) return;
    templateDialog.open();
  }

  const view = new EditorView(editorSurface, {
    state: runtime.state,
    handleKeyDown(editorView, event) {
      const { empty, $from } = editorView.state.selection;
      if (!templateDialog || !editorView.editable || event.defaultPrevented ||
        event.key !== "/" || event.altKey || event.ctrlKey || event.metaKey ||
        event.shiftKey || event.isComposing || editorView.composing || !empty ||
        $from.parent.type !== editorSchema.nodes.paragraph ||
        $from.parent.content.size !== 0) return false;
      event.preventDefault();
      openTemplateDialog();
      return true;
    },
    dispatchTransaction(transaction) {
      runtime.dispatch(transaction);
    },
  });
  runtime.setStateListener((state) => {
    view.updateState(state);
    connectedToolbar?.update();
  });

  connectedToolbar = connectEditorToolbar(toolbar, view, editorCommands);

  if (templateProvider && templateButton) {
    templateDialog = createTemplateDialog({
      ownerDocument: editor.ownerDocument,
      provider: templateProvider,
      insert(template) {
        const { document } = parseTemplateFragment(template.content);
        const command = insertTemplate(
          document,
          view.state.selection,
        );
        command(view.state, (transaction) => {
          assertCurrentDocumentMatchesGenerated(
            transaction.doc,
            runtime.generatedDocument,
          );
          view.dispatch(closeHistory(transaction));
          // Keep immediate follow-up typing in its own undo group too.
          view.dispatch(closeHistory(view.state.tr));
        }, view);
      },
      onInserted: () => view.focus(),
    });
    templateButton.addEventListener("click", openTemplateDialog);
  }

  return {
    render: runtime.controller.render,
    getDocument: runtime.controller.getDocument,
    getSnapshot: runtime.controller.getSnapshot,
    destroy(): void {
      connectedToolbar?.destroy();
      templateDialog?.destroy();
      view.destroy();
      toolbar.remove();
      editorSurface.remove();
      if (!mountAlreadyStyled) editor.classList.remove("docweave-editor");
    },
  };
}
