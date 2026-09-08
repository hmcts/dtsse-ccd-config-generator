import { toggleMark } from "prosemirror-commands";
import { history, redo, undo } from "prosemirror-history";
import { wrapInList } from "prosemirror-schema-list";
import { EditorState } from "prosemirror-state";
import { EditorView } from "prosemirror-view";

import {
  connectEditorToolbar,
  createEditorToolbar,
  type ConnectedEditorToolbar,
} from "../../editor-toolbar.js";
import { createKeymapPlugins, indentListItem, outdentListItem } from "../../keymap.js";
import { editorSchema } from "../../schema.js";
import {
  createTemplateFragment,
  parseTemplateFragment,
  type SaveTemplateInput,
  type Template,
} from "../provider.js";

/**
 * A template being written. Owns the editor, its toolbar and dirty tracking;
 * the dialog only reads, locks or destroys it.
 */
export function createTemplateDraft(
  host: HTMLElement,
  title: HTMLInputElement,
  template?: Template,
) {
  const document = host.ownerDocument;
  const doc = template
    ? parseTemplateFragment(template.content).document
    : editorSchema.nodes.doc!.create(null, editorSchema.nodes.paragraph!.create());
  const toolbar = createEditorToolbar(document, "Template editor formatting");
  const mount = document.createElement("div");
  mount.className = "docweave-editor__surface";
  host.replaceChildren(toolbar, mount);
  title.value = template?.title ?? "";

  let dirty = false;
  let editable = true;
  let connected: ConnectedEditorToolbar | undefined;

  const view = new EditorView(mount, {
    state: EditorState.create({
      schema: editorSchema,
      doc,
      plugins: [...createKeymapPlugins(), history()],
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
  title.addEventListener("input", markDirty);

  return {
    template,
    get dirty(): boolean {
      return dirty;
    },
    read(): SaveTemplateInput {
      return {
        title: title.value.trim(),
        content: createTemplateFragment(view.state.doc),
      };
    },
    setEditable(value: boolean): void {
      if (editable === value) return;
      editable = value;
      // Re-runs the editable prop so contenteditable follows the lock.
      view.setProps({ editable: () => editable });
    },
    destroy(): void {
      title.removeEventListener("input", markDirty);
      connected?.destroy();
      view.destroy();
      host.replaceChildren();
    },
  };
}

export type TemplateDraft = ReturnType<typeof createTemplateDraft>;
