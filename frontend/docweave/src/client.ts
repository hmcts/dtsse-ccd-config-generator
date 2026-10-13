import { dropCursor } from "prosemirror-dropcursor";
import { gapCursor } from "prosemirror-gapcursor";
import { toggleMark } from "prosemirror-commands";
import { history, redo, undo } from "prosemirror-history";
import { keymap } from "prosemirror-keymap";
import { wrapInList } from "prosemirror-schema-list";
import { type Command } from "prosemirror-state";
import { EditorView } from "prosemirror-view";

import { createAnnouncer } from "./announcer.js";

import {
  type DocWeaveDocument,
  getDocumentFacts,
} from "./builder.js";
import { createClipboardPlugin } from "./clipboard.js";
import {
  createDocEditorController,
  type DocWeaveSnapshot,
  type DocEditorController,
  type RenderChange,
} from "./controller.js";
import {
  createDiffStylingPlugin,
  revertClauseAtSelection,
  setGeneratedDocument,
} from "./diff-styling.js";
import {
  createFactNavigationPlugin,
  getFactLabel,
  selectNextFact,
  selectPreviousFact,
  setFactNavigationFacts,
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
  createKeyboardHelpDialog,
  editorShortcuts,
  type KeyboardHelpDialog,
} from "./keyboard-help.js";
import {
  createKeymapPlugins,
  indentListItem,
  outdentListItem,
} from "./keymap.js";
import { createListNumberingPlugin } from "./list-numbering.js";
import { editorSchema } from "./schema.js";
import { InvalidSnapshotError, parseSnapshot } from "./snapshot.js";
import {
  createTemplateDialog,
  type TemplateDialog,
} from "./templates/dialog/index.js";
import {
  createHttpTemplateProvider,
  parseTemplateFragment,
  type TemplateProvider,
} from "./templates/index.js";

export interface CreateDocEditorOptions {
  mount?: HTMLElement | string;
  /**
   * The accessible name of the editing surface, read out when it receives
   * focus. Say what the document is: "Order", "Directions".
   */
  label?: string;
  initialSnapshot?: DocWeaveSnapshot;
  /**
   * A form field that holds the snapshot as JSON, so it is posted with the
   * form. The editor starts from the snapshot in it, unless given
   * initialSnapshot, and writes every change to it before calling onChange.
   * An empty field holds no document. A field holding something other than a
   * snapshot is reported to onError and left as it is, unwritten, until the
   * host empties it.
   */
  field?: HTMLTextAreaElement | HTMLInputElement;
  /** Called when field holds something other than a valid snapshot. */
  onError?: (error: InvalidSnapshotError) => void;
  /**
   * Called with the new snapshot whenever getSnapshot would return something
   * different: after the reader's edit, a render that changes it, or a load.
   */
  onChange?: (snapshot: DocWeaveSnapshot) => void;
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

const DEFAULT_LABEL = "Document";

/** One sentence on what a form change did to the document. */
export function describeRenderChange(
  name: string,
  change: RenderChange,
  labelOf: (factId: string) => string | undefined,
): string | undefined {
  if (!change.docChanged) return undefined;
  const [first] = change.changedFacts;
  if (!first) return `${name} updated.`;
  const label = labelOf(first.id);
  const detail = `${label ?? "A field"} is now ${first.value}.`;
  return change.changedFacts.length === 1
    ? `${name} updated: ${detail}`
    : `${name} updated: ${change.changedFacts.length} fields changed. ${detail}`;
}

function createTemplateButton(ownerDocument: Document): HTMLButtonElement {
  const button = ownerDocument.createElement("button");
  button.type = "button";
  button.className = "docweave-editor__toolbar-button";
  button.setAttribute("aria-label", "Insert template");
  button.textContent = "Insert template";
  return button;
}

function createHelpButton(ownerDocument: Document): HTMLButtonElement {
  const button = ownerDocument.createElement("button");
  button.type = "button";
  button.className = "docweave-editor__toolbar-button";
  button.setAttribute("aria-label", "Keyboard shortcuts");
  button.title = "Keyboard shortcuts";
  button.textContent = "?";
  return button;
}

/**
 * Reads the field's snapshot, if there is one, and returns the options with
 * an onChange that writes each new snapshot to the field first.
 */
function bindField(options: CreateDocEditorOptions): {
  initialSnapshot?: DocWeaveSnapshot;
  onChange?: (snapshot: DocWeaveSnapshot) => void;
} {
  const { field } = options;
  if (!field) return options;
  // Checked before it is written, so a bad one cannot replace the saved document.
  let initialSnapshot = options.initialSnapshot && parseSnapshot(options.initialSnapshot);
  let held = false;
  if (!initialSnapshot && field.value.trim()) {
    try {
      initialSnapshot = parseSnapshot(field.value);
    } catch (error) {
      if (!(error instanceof InvalidSnapshotError)) throw error;
      held = true;
      options.onError?.(error);
    }
  }
  const write = (snapshot: DocWeaveSnapshot): void => {
    held &&= field.value.trim() !== "";
    if (!held) field.value = JSON.stringify(snapshot);
  };
  if (initialSnapshot) write(initialSnapshot);
  return {
    initialSnapshot,
    onChange(snapshot) {
      write(snapshot);
      options.onChange?.(snapshot);
    },
  };
}

export function createDocEditor(
  options: CreateDocEditorOptions = {},
): DocEditorController {
  if (options.templates && options.mount === undefined) {
    throw new Error("Templates require an editor mount");
  }
  const { initialSnapshot, onChange } = bindField(options);
  if (options.mount === undefined) {
    return createDocEditorController({ initialSnapshot, onChange }).controller;
  }

  const ownerDocument = typeof options.mount === "string"
    ? globalThis.document
    : options.mount.ownerDocument;
  const editor = typeof options.mount === "string"
    ? ownerDocument?.querySelector<HTMLElement>(options.mount)
    : options.mount;
  if (!editor) {
    throw new Error(`Editor mount point not found: ${String(options.mount)}`);
  }
  // Mounting twice silently stacked a second toolbar and surface, which is almost
  // always a caller that forgot to destroy the previous editor (module reloads in
  // particular). Fail loudly rather than leaving two editors over one document.
  if (editor.querySelector(".docweave-editor__surface")) {
    throw new Error(
      `Editor mount point already has an editor, destroy it first: ${String(options.mount)}`,
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

  const announcer = createAnnouncer(editor.ownerDocument);
  let connectedToolbar: ConnectedEditorToolbar | undefined;
  let templateDialog: TemplateDialog | undefined;
  let helpDialog: KeyboardHelpDialog | undefined;

  const runtime = createDocEditorController({
    initialSnapshot,
    onChange,
    plugins: [
      createClipboardPlugin(),
      createListNumberingPlugin(),
      createDiffStylingPlugin({ announce: announcer.announce }),
      createFactNavigationPlugin(editor.ownerDocument, {
        announce: announcer.announce,
      }),
      createInputRulesPlugin(),
      keymap({
        "Alt-F10": () => {
          connectedToolbar?.focus();
          return true;
        },
        "Mod-Alt-z": revertClauseAtSelection,
        "Alt-Shift-ArrowDown": selectNextFact,
        "Alt-Shift-ArrowUp": selectPreviousFact,
      }),
      ...createKeymapPlugins(),
      dropCursor(),
      gapCursor(),
      history(),
    ],
    prepareGeneratedTransaction(transaction, generated, document) {
      setGeneratedDocument(transaction, generated);
      if (document) {
        setFactNavigationFacts(transaction, getDocumentFacts(document));
      }
      return transaction;
    },
    onRender(change) {
      const message = describeRenderChange(
        options.label ?? DEFAULT_LABEL,
        change,
        (factId) => getFactLabel(view.state, factId),
      );
      if (message) announcer.announce(message);
    },
  });

  const toolbar = createEditorToolbar(
    editor.ownerDocument,
    "Document formatting",
  );
  const templateButton = options.templates
    ? createTemplateButton(editor.ownerDocument)
    : undefined;
  if (templateButton) {
    toolbar.append(templateButton);
  }
  const helpButton = createHelpButton(editor.ownerDocument);
  toolbar.append(helpButton);
  const editorSurface = editor.ownerDocument.createElement("div");
  editorSurface.className = "docweave-editor__surface";
  const mountAlreadyStyled = editor.classList.contains("docweave-editor");
  editor.classList.add("docweave-editor");
  editor.append(toolbar, editorSurface, announcer.element);

  // Typing in the editor is not an answer to the host's form. Left to bubble,
  // these reach a form that re-renders on input, and re-rendering during a
  // keystroke makes ProseMirror discard it. Hosts use onChange instead.
  const stopAtMount = (event: Event): void => event.stopPropagation();
  editor.addEventListener("input", stopAtMount);
  editor.addEventListener("change", stopAtMount);

  function openTemplateDialog(): void {
    if (!templateDialog) return;
    templateDialog.open();
  }

  const view = new EditorView(editorSurface, {
    state: runtime.state,
    // The surface is a text box to assistive technology; without a role and a
    // name it is announced as nothing more than "editable".
    attributes: {
      role: "textbox",
      "aria-multiline": "true",
      "aria-label": options.label?.trim() || DEFAULT_LABEL,
    },
    handleKeyDown(editorView, event) {
      // Only the digit row: a Windows Alt code such as Alt+0233 starts with
      // Numpad0, which a keymap binding on "Alt-0" would swallow.
      if (event.code === "Digit0" && event.altKey && !event.ctrlKey &&
        !event.metaKey && !event.shiftKey) {
        event.preventDefault();
        helpDialog?.open();
        return true;
      }
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

  helpDialog = createKeyboardHelpDialog(
    editor.ownerDocument,
    editorShortcuts({ templates: options.templates !== undefined }),
    () => view.focus(),
  );
  editor.append(helpDialog.element);
  helpButton.addEventListener("click", () => helpDialog?.open());

  if (templateProvider && templateButton) {
    templateDialog = createTemplateDialog({
      ownerDocument: editor.ownerDocument,
      provider: templateProvider,
      insert(template) {
        const { document } = parseTemplateFragment(template.content, {
          dates: false,
        });
        runtime.insertDocument(document, view.state.selection);
      },
      onInserted: () => view.focus(),
    });
    templateButton.addEventListener("click", openTemplateDialog);
  }

  return {
    render: runtime.controller.render,
    load: runtime.controller.load,
    getDocument: runtime.controller.getDocument,
    getSnapshot: runtime.controller.getSnapshot,
    insert(paragraphs, options): boolean {
      const inserted = runtime.controller.insert(paragraphs, options);
      if (inserted) view.focus();
      return inserted;
    },
    destroy(): void {
      editor.removeEventListener("input", stopAtMount);
      editor.removeEventListener("change", stopAtMount);
      connectedToolbar?.destroy();
      templateDialog?.destroy();
      helpDialog?.destroy();
      announcer.destroy();
      view.destroy();
      toolbar.remove();
      editorSurface.remove();
      if (!mountAlreadyStyled) editor.classList.remove("docweave-editor");
    },
  };
}
