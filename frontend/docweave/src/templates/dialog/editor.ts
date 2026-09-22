import { toggleMark } from "prosemirror-commands";
import { history, redo, undo } from "prosemirror-history";
import { keymap } from "prosemirror-keymap";
import { wrapInList } from "prosemirror-schema-list";
import { EditorState, TextSelection } from "prosemirror-state";
import { EditorView } from "prosemirror-view";

import {
  connectEditorToolbar,
  createEditorToolbar,
  type ConnectedEditorToolbar,
} from "../../editor-toolbar.js";
import { createKeymapPlugins, indentListItem, outdentListItem } from "../../keymap.js";
import { editorSchema } from "../../schema.js";
import {
  readTemplateDates,
  templateDateProblem,
  writeTemplateDates,
} from "../dates.js";
import {
  createTemplateFragment,
  parseTemplateFragment,
  type SaveTemplateInput,
  type Template,
} from "../provider.js";

const DATE_EXAMPLE = "Hearing date";

/**
 * A template being written. Owns the editor, its toolbar and dirty tracking;
 * the dialog only reads, locks or destroys it.
 */
export function createTemplateDraft(
  host: HTMLElement,
  title: HTMLInputElement,
  template?: Template,
  /** Start from the template's wording but save as a new template. */
  copy = false,
) {
  const document = host.ownerDocument;
  const doc = template
    // An author edits a date as the text they would write for it.
    ? parseTemplateFragment({
      ...template.content,
      content: writeTemplateDates(template.content.content),
    }, { dates: false }).document
    : editorSchema.nodes.doc!.create(null, editorSchema.nodes.paragraph!.create());
  const toolbar = createEditorToolbar(document, "Template editor formatting");
  const dateButton = document.createElement("button");
  dateButton.type = "button";
  dateButton.className = "docweave-editor__toolbar-button";
  dateButton.textContent = "Insert date";
  toolbar.append(dateButton);
  const mount = document.createElement("div");
  mount.className = "docweave-editor__surface";
  host.replaceChildren(toolbar, mount);
  title.value = template?.title ?? "";

  let dirty = false;
  let editable = true;
  let connected: ConnectedEditorToolbar | undefined;

  const view = new EditorView(mount, {
    attributes: {
      role: "textbox",
      "aria-multiline": "true",
      "aria-label": "Template wording",
    },
    state: EditorState.create({
      schema: editorSchema,
      doc,
      plugins: [
        keymap({
          "Alt-F10": () => {
            connected?.focus();
            return true;
          },
        }),
        ...createKeymapPlugins(),
        history(),
      ],
    }),
    editable: () => editable,
    dispatchTransaction(transaction) {
      // A locked draft is being saved, so nothing may change under the request.
      if (!editable) return;
      view.updateState(view.state.apply(transaction));
      connected?.update();
      if (transaction.docChanged) dirty = true;
    },
  });

  const markDirty = (): void => {
    dirty = true;
  };

  try {
    connected = connectEditorToolbar(toolbar, view, {
      undo,
      redo,
      bold: toggleMark(editorSchema.marks.strong!),
      italic: toggleMark(editorSchema.marks.em!),
      numbered: wrapInList(editorSchema.nodes.ordered_list!),
      outdent: outdentListItem,
      indent: indentListItem,
    });
  } catch (error) {
    view.destroy();
    host.replaceChildren();
    throw error;
  }
  // Writes an example date, with its inside selected ready to be typed over.
  const insertDate = (): void => {
    if (!editable) return;
    const { from } = view.state.selection;
    const transaction = view.state.tr.insertText(`[date: ${DATE_EXAMPLE}]`);
    const start = transaction.mapping.map(from, -1) + "[date: ".length;
    view.dispatch(transaction.setSelection(TextSelection.create(
      transaction.doc,
      start,
      start + DATE_EXAMPLE.length,
    )));
    view.focus();
  };
  dateButton.addEventListener("click", insertDate);
  title.addEventListener("input", markDirty);

  return {
    template: copy ? undefined : template,
    get dirty(): boolean {
      return dirty;
    },
    /** Why the dates the author wrote cannot be saved as they stand, if they cannot. */
    get dateProblem(): string | undefined {
      return templateDateProblem(
        readTemplateDates(createTemplateFragment(view.state.doc).content),
      );
    },
    focus(): void {
      view.focus();
    },
    read(): SaveTemplateInput {
      const fragment = createTemplateFragment(view.state.doc);
      return {
        title: title.value.trim(),
        content: parseTemplateFragment({
          ...fragment,
          content: readTemplateDates(fragment.content),
        }).fragment,
      };
    },
    setEditable(value: boolean): void {
      if (editable === value) return;
      editable = value;
      // Re-runs the editable prop so contenteditable follows the lock.
      view.setProps({ editable: () => editable });
    },
    destroy(): void {
      dateButton.removeEventListener("click", insertDate);
      title.removeEventListener("input", markDirty);
      connected?.destroy();
      view.destroy();
      host.replaceChildren();
    },
  };
}

export type TemplateDraft = ReturnType<typeof createTemplateDraft>;
