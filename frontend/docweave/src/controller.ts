import { type Node as ProseMirrorNode } from "prosemirror-model";
import {
  EditorState,
  type Plugin,
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

export interface DocWeaveSnapshot {
  schema: "docweave-document";
  version: 1;
  current: Record<string, unknown>;
  generated: Record<string, unknown>;
}

export interface DocEditorController {
  render(document: DocWeaveDocument): void;
  /**
   * Replaces the document being edited with a saved one, or with an empty
   * document when there is no snapshot. Undo history does not carry across, and
   * the next render is treated as the first. Use it to switch between documents
   * in one editor rather than destroying the editor and creating another.
   */
  load(snapshot?: DocWeaveSnapshot): void;
  getDocument(): DocWeaveDocument | undefined;
  getSnapshot(): DocWeaveSnapshot;
  destroy(): void;
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
    if (snapshot &&
      (snapshot.schema !== "docweave-document" || snapshot.version !== 1)) {
      throw new Error("Unsupported Docweave snapshot version");
    }

    const current = snapshot
      ? editorSchema.nodeFromJSON(snapshot.current)
      : undefined;
    const generated = snapshot
      ? editorSchema.nodeFromJSON(snapshot.generated)
      : undefined;
    if (generated && current) {
      assertValidGeneratedDocument(generated);
      assertCurrentDocumentMatchesGenerated(current, generated);
    }

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
    dispatch(transaction: Transaction): void {
      state = state.apply(transaction);
      notifyState();
      if (transaction.docChanged) options.onChange?.(getSnapshot());
    },
    setStateListener(listener: (state: EditorState) => void): void {
      stateListener = listener;
    },
  };
}
