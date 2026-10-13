import { closeHistory } from "prosemirror-history";
import { type Node as ProseMirrorNode } from "prosemirror-model";
import {
  EditorState,
  type Plugin,
  Selection,
  type Transaction,
} from "prosemirror-state";

import {
  type DocWeaveDocument,
  getDocumentNode,
} from "./builder.js";
import {
  assertCurrentDocumentMatchesGenerated,
  assertValidGeneratedDocument,
} from "./invariants.js";
import { reconcileDocument } from "./reconciliation.js";
import { editorSchema } from "./schema.js";
import { type DocWeaveSnapshot, readSnapshot } from "./snapshot.js";
import {
  insertTemplate,
  type InsertTemplateOptions,
} from "./templates/insertion.js";

export type { DocWeaveSnapshot } from "./snapshot.js";

export interface DocEditorController {
  render(document: DocWeaveDocument): void;
  /**
   * Replaces the document being edited with a saved one, or with an empty
   * document when there is no snapshot. Undo history does not carry across, and
   * the next render is treated as the first. Use it to switch between documents
   * in one editor rather than destroying the editor and creating another.
   */
  load(snapshot?: DocWeaveSnapshot): void;
  /**
   * Adds wording to the document as the reader's own clauses, one per
   * paragraph, as if they had typed it: they can change, move or delete any of
   * it, and one undo takes it all out again. It goes in where a template would,
   * at the selection or, with at "end", after the document's last clause, as a
   * numbered clause when that is one. Blank paragraphs are left out; nothing
   * happens when none remain. Returns whether anything was inserted.
   */
  insert(paragraphs: readonly string[], options?: InsertOptions): boolean;
  getDocument(): DocWeaveDocument | undefined;
  getSnapshot(): DocWeaveSnapshot;
  destroy(): void;
}

export interface InsertOptions {
  /** Where the wording goes: the selection, or after the last clause. */
  at?: "selection" | "end";
}

/** What a render changed, for telling the reader. */
export interface RenderChange {
  docChanged: boolean;
  /** Facts whose value differs from the previous generated document. */
  changedFacts: ReadonlyArray<{ id: string; value: string }>;
}

function factValues(doc: ProseMirrorNode): Map<string, string> {
  const values = new Map<string, string>();
  doc.descendants((node) => {
    if (node.type.name === "generated_text") {
      values.set(node.attrs.id as string, node.attrs.text as string);
    }
    return node.type.name !== "generated_text";
  });
  return values;
}

function changedFacts(
  previous: ProseMirrorNode,
  next: ProseMirrorNode,
): Array<{ id: string; value: string }> {
  const before = factValues(previous);
  return [...factValues(next)]
    .filter(([id, value]) => before.has(id) && before.get(id) !== value)
    .map(([id, value]) => ({ id, value }));
}

interface CreateDocEditorControllerOptions {
  initialSnapshot?: DocWeaveSnapshot;
  /**
   * Called with the new snapshot whenever getSnapshot would return something
   * different, whatever caused it: the reader's edit, a render or a load. Not
   * called for a render that changes nothing, nor for the selection alone.
   */
  onChange?: (snapshot: DocWeaveSnapshot) => void;
  plugins?: readonly Plugin[];
  /** Called after each render that follows a previous generated document. */
  onRender?: (change: RenderChange) => void;
  prepareGeneratedTransaction?: (
    transaction: Transaction,
    generated: ProseMirrorNode,
    document?: DocWeaveDocument,
  ) => Transaction;
}

export interface DocEditorRuntime {
  readonly controller: DocEditorController;
  readonly state: EditorState;
  readonly generatedDocument: ProseMirrorNode;
  dispatch(transaction: Transaction): void;
  /**
   * Inserts a document's content as the reader's own clauses at the selection,
   * as one undo step that follow-up typing does not join.
   */
  insertDocument(
    wording: ProseMirrorNode,
    selection?: Selection,
    options?: InsertTemplateOptions,
  ): void;
  setStateListener(listener: (state: EditorState) => void): void;
}

export function createDocEditorController(
  options: CreateDocEditorControllerOptions,
): DocEditorRuntime {
  let state: EditorState;
  let generatedDocument: ProseMirrorNode;
  let hasGeneratedDocument: boolean;
  let document: DocWeaveDocument | undefined;
  let stateListener: ((state: EditorState) => void) | undefined;

  // A fresh state rather than a transaction, so that undo cannot cross from one
  // document into another. Validates before assigning: a bad snapshot leaves the
  // document being edited untouched.
  const load = (snapshot?: DocWeaveSnapshot): void => {
    const { current, generated } = snapshot
      ? readSnapshot(snapshot)
      : { current: undefined, generated: undefined };

    let loaded = EditorState.create({
      schema: editorSchema,
      doc: current,
      plugins: [...options.plugins ?? []],
    });
    if (generated) {
      loaded = loaded.apply(
        options.prepareGeneratedTransaction?.(loaded.tr, generated) ??
          loaded.tr,
      );
    }
    state = loaded;
    generatedDocument = generated ?? loaded.doc;
    hasGeneratedDocument = generated !== undefined;
    document = undefined;
  };
  load(options.initialSnapshot);

  const getSnapshot = (): DocWeaveSnapshot => ({
    schema: "docweave-document",
    version: 1,
    current: state.doc.toJSON() as Record<string, unknown>,
    generated: generatedDocument.toJSON() as Record<string, unknown>,
  });

  const notifyState = (): void => {
    stateListener?.(state);
  };

  const dispatch = (transaction: Transaction): void => {
    const before = state.doc;
    state = state.apply(transaction);
    notifyState();
    if (!state.doc.eq(before)) options.onChange?.(getSnapshot());
  };

  const insertDocument = (
    wording: ProseMirrorNode,
    selection?: Selection,
    insertOptions?: InsertTemplateOptions,
  ): void => {
    insertTemplate(wording, selection, insertOptions)(state, (transaction) => {
      assertCurrentDocumentMatchesGenerated(
        transaction.doc,
        generatedDocument,
      );
      dispatch(closeHistory(transaction));
      // Keep immediate follow-up typing in its own undo group too.
      dispatch(closeHistory(state.tr));
    });
  };

  const controller: DocEditorController = {
    render(nextDocument: DocWeaveDocument): void {
      const target = getDocumentNode(nextDocument);
      assertValidGeneratedDocument(target);
      let transaction = state.tr;

      if (hasGeneratedDocument) {
        transaction = reconcileDocument(
          transaction,
          generatedDocument,
          target,
        );
      } else {
        transaction.replaceWith(
          0,
          transaction.doc.content.size,
          target.content,
        );
      }

      transaction = options.prepareGeneratedTransaction?.(
        transaction,
        target,
        nextDocument,
      ) ?? transaction;
      const change: RenderChange | undefined = hasGeneratedDocument
        ? {
          docChanged: transaction.docChanged,
          changedFacts: changedFacts(generatedDocument, target),
        }
        : undefined;
      const snapshotChanged = transaction.docChanged ||
        !hasGeneratedDocument || !generatedDocument.eq(target);
      state = state.apply(transaction.setMeta("addToHistory", false));
      generatedDocument = target;
      hasGeneratedDocument = true;
      document = nextDocument;
      notifyState();
      if (snapshotChanged) options.onChange?.(getSnapshot());
      if (change) options.onRender?.(change);
    },
    load(snapshot?: DocWeaveSnapshot): void {
      const { doc: before } = state;
      const generatedBefore = generatedDocument;
      load(snapshot);
      // The editor state is always fresh, so views refresh, but a host that
      // persists every callback is only told when the snapshot differs.
      notifyState();
      if (!state.doc.eq(before) || !generatedDocument.eq(generatedBefore)) {
        options.onChange?.(getSnapshot());
      }
    },
    insert(paragraphs, { at = "selection" }: InsertOptions = {}): boolean {
      const wording = paragraphs.map((text) => text.trim()).filter(Boolean);
      if (wording.length === 0) return false;
      const document = editorSchema.node(
        "doc",
        null,
        wording.map((text) =>
          editorSchema.node("paragraph", null, editorSchema.text(text))
        ),
      );
      // The end of the document is also the start of its last clause when
      // that clause is empty, and the wording still belongs after it.
      if (at === "end") {
        insertDocument(document, Selection.atEnd(state.doc), { after: true });
      } else {
        insertDocument(document);
      }
      return true;
    },
    getDocument(): DocWeaveDocument | undefined {
      return document;
    },
    getSnapshot,
    destroy(): void {},
  };

  return {
    controller,
    get state(): EditorState {
      return state;
    },
    get generatedDocument(): ProseMirrorNode {
      return generatedDocument;
    },
    dispatch,
    insertDocument,
    setStateListener(listener: (state: EditorState) => void): void {
      stateListener = listener;
    },
  };
}
