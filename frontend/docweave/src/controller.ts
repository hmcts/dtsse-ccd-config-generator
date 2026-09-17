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

  let state = EditorState.create({
    schema: editorSchema,
    doc: initialCurrent,
    plugins: [...options.plugins ?? []],
  });
  let generatedDocument = initialGenerated ?? state.doc;
  let hasGeneratedDocument = initialGenerated !== undefined;
  if (initialGenerated) {
    state = state.apply(
      options.prepareGeneratedTransaction?.(
        state.tr,
        initialGenerated,
      ) ?? state.tr,
    );
  }

  let document: DocWeaveDocument | undefined;
  let stateListener: ((state: EditorState) => void) | undefined;

  const getSnapshot = (): DocWeaveSnapshot => ({
    schema: "docweave-document",
    version: 1,
    current: state.doc.toJSON() as Record<string, unknown>,
    generated: generatedDocument.toJSON() as Record<string, unknown>,
  });

  const notifyChange = (): void => {
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
      state = state.apply(transaction.setMeta("addToHistory", false));
      generatedDocument = target;
      hasGeneratedDocument = true;
      document = nextDocument;
      notifyChange();
      if (change) options.onRender?.(change);
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
      notifyChange();
    },
    setStateListener(listener: (state: EditorState) => void): void {
      stateListener = listener;
    },
  };
}
