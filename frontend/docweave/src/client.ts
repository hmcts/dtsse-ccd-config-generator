import { dropCursor } from "prosemirror-dropcursor";
import { gapCursor } from "prosemirror-gapcursor";
import { toggleMark } from "prosemirror-commands";
import { history, redo, undo } from "prosemirror-history";
import { type Node as ProseMirrorNode } from "prosemirror-model";
import { wrapInList } from "prosemirror-schema-list";
import {
  type Command,
  EditorState,
  type SelectionBookmark,
} from "prosemirror-state";
import { EditorView } from "prosemirror-view";

import {
  type DocWeaveDocument,
  getDocumentFactSources,
  getDocumentNode,
} from "./builder.js";
import { createClipboardPlugin } from "./clipboard.js";
import {
  createDiffStylingPlugin,
  getGeneratedDocument,
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
  createKeymapPlugins,
  indentListItem,
  outdentListItem,
} from "./keymap.js";
import { createListNumberingPlugin } from "./list-numbering.js";
import {
  assertCurrentDocumentMatchesGenerated,
  assertValidGeneratedDocument,
} from "./invariants.js";
import { reconcileOrderDocument } from "./reconciliation.js";
import { editorSchema } from "./schema.js";
import {
  createTemplateDialog,
  type TemplateDialog,
} from "./templates/dialog.js";
import {
  createHttpTemplateProvider,
  parseTemplateFragment,
  type TemplateProvider,
} from "./templates/index.js";
import { insertTemplate } from "./templates/insertion.js";

function createEditorState(
  ownerDocument: Document,
  document?: ProseMirrorNode,
): EditorState {
  return EditorState.create({
    schema: editorSchema,
    doc: document,
    plugins: [
      createClipboardPlugin(),
      createListNumberingPlugin(),
      createDiffStylingPlugin(),
      createFactNavigationPlugin(ownerDocument),
      ...createKeymapPlugins(),
      dropCursor(),
      gapCursor(),
      history(),
    ],
  });
}

export interface DocWeaveSnapshot {
  schema: "docweave-document";
  version: 1;
  current: Record<string, unknown>;
  generated: Record<string, unknown>;
}

export interface CreateOrderEditorOptions {
  mount: HTMLElement | string;
  initialSnapshot?: DocWeaveSnapshot;
  onChange?: (snapshot: DocWeaveSnapshot) => void;
  templates?: {
    url?: string;
    csrfToken?: string | (() => string | undefined);
    provider?: TemplateProvider;
  };
}

export interface OrderEditorController {
  render(document: DocWeaveDocument): void;
  getSnapshot(): DocWeaveSnapshot;
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
  options: CreateOrderEditorOptions,
): OrderEditorController {
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

  if (options.initialSnapshot &&
    (options.initialSnapshot.schema !== "docweave-document" ||
      options.initialSnapshot.version !== 1)) {
    throw new Error("Unsupported Docweave snapshot version");
  }

  const initialCurrent = options.initialSnapshot
    ? editorSchema.nodeFromJSON(options.initialSnapshot.current)
    : undefined;
  const initialGenerated = options.initialSnapshot
    ? editorSchema.nodeFromJSON(options.initialSnapshot.generated)
    : undefined;
  if (initialGenerated && initialCurrent) {
    assertValidGeneratedDocument(initialGenerated);
    assertCurrentDocumentMatchesGenerated(initialCurrent, initialGenerated);
  }
  let initialState = createEditorState(editor.ownerDocument, initialCurrent);
  if (initialGenerated) {
    initialState = initialState.apply(
      setGeneratedDocument(initialState.tr, initialGenerated),
    );
  }

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
  let templateBookmark: SelectionBookmark | undefined;

  const getSnapshot = (): DocWeaveSnapshot => {
    const generated = getGeneratedDocument(view.state) ?? view.state.doc;
    return {
      schema: "docweave-document",
      version: 1,
      current: view.state.doc.toJSON() as Record<string, unknown>,
      generated: generated.toJSON() as Record<string, unknown>,
    };
  };

  const view = new EditorView(editorSurface, {
    state: initialState,
    dispatchTransaction(transaction) {
      if (templateBookmark) {
        templateBookmark = templateBookmark.map(transaction.mapping);
      }
      const nextState = view.state.apply(transaction);
      view.updateState(nextState);
      connectedToolbar?.update();
      options.onChange?.(getSnapshot());
    },
  });

  connectedToolbar = connectEditorToolbar(toolbar, view, editorCommands);

  if (templateProvider && templateButton) {
    templateDialog = createTemplateDialog({
      ownerDocument: editor.ownerDocument,
      provider: templateProvider,
      insert(template) {
        const { document } = parseTemplateFragment(template.content);
        const bookmark = templateBookmark ?? view.state.selection.getBookmark();
        const command = insertTemplate(
          document,
          bookmark.resolve(view.state.doc),
        );
        command(view.state, (transaction) => {
          const generated = getGeneratedDocument(view.state);
          if (generated) {
            assertCurrentDocumentMatchesGenerated(transaction.doc, generated);
          }
          view.dispatch(transaction);
        }, view);
        templateBookmark = undefined;
      },
      onInserted: () => view.focus(),
    });
    templateButton.addEventListener("click", () => {
      templateBookmark = view.state.selection.getBookmark();
      templateDialog?.open();
    });
  }

  const controller: OrderEditorController = {
    render(document: DocWeaveDocument): void {
      const target = getDocumentNode(document);
      assertValidGeneratedDocument(target);
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
      setFactNavigationSources(
        transaction,
        getDocumentFactSources(document),
      );

      view.dispatch(transaction.setMeta("addToHistory", false));
    },
    getSnapshot,
    destroy(): void {
      connectedToolbar?.destroy();
      templateDialog?.destroy();
      view.destroy();
      toolbar.remove();
      editorSurface.remove();
      if (!mountAlreadyStyled) editor.classList.remove("docweave-editor");
    },
  };

  return controller;
}
