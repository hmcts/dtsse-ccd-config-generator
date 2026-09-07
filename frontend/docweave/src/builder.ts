import { type Node as ProseMirrorNode } from "prosemirror-model";

import { assertValidGeneratedDocument } from "./invariants.js";
import { editorSchema } from "./schema.js";

export interface FactOptions {
  sourceId?: string;
}

export interface InlineBuilder {
  text(value: string): this;
  fact(id: string, value: string, options?: FactOptions): this;
}

type ClauseContent = string | ((content: InlineBuilder) => void);

export interface OrderedListBuilder {
  item(
    id: string,
    content: ClauseContent,
    define?: (item: ListItemBuilder) => void,
  ): void;
}

export interface ListItemBuilder {
  orderedList(
    id: string,
    define: (list: OrderedListBuilder) => void,
  ): void;
}

export interface OrderBuilder {
  paragraph(id: string, content: ClauseContent): void;
  orderedList(
    id: string,
    define: (list: OrderedListBuilder) => void,
  ): void;
}

export interface DocWeaveClause {
  readonly id: string;
  readonly textContent: string;
  readonly children: readonly DocWeaveClause[];
}

interface DocWeaveDocumentInternals {
  node: ProseMirrorNode;
  factSources: ReadonlyMap<string, string>;
}

interface BuiltOrderedList {
  node: ProseMirrorNode;
  clauses: readonly DocWeaveClause[];
}

const documentInternals = new WeakMap<
  DocWeaveDocument,
  DocWeaveDocumentInternals
>();
let createDocWeaveDocument: (
  node: ProseMirrorNode,
  factSources: ReadonlyMap<string, string>,
  children: readonly DocWeaveClause[],
  clausesById: ReadonlyMap<string, DocWeaveClause>,
) => DocWeaveDocument;

/** A generated document and its runtime-only DocWeave metadata. */
export class DocWeaveDocument {
  readonly textContent: string;
  readonly children: readonly DocWeaveClause[];
  readonly #clausesById: ReadonlyMap<string, DocWeaveClause>;

  private constructor(
    node: ProseMirrorNode,
    factSources: ReadonlyMap<string, string>,
    children: readonly DocWeaveClause[],
    clausesById: ReadonlyMap<string, DocWeaveClause>,
  ) {
    this.textContent = node.textContent;
    this.children = Object.freeze([...children]);
    this.#clausesById = new Map(clausesById);
    documentInternals.set(this, {
      node,
      factSources: new Map(factSources),
    });
    Object.freeze(this);
  }

  static {
    createDocWeaveDocument = (node, factSources, children, clausesById) =>
      new DocWeaveDocument(node, factSources, children, clausesById);
  }

  getClause(id: string): DocWeaveClause | undefined {
    return this.#clausesById.get(id);
  }
}

/** @internal */
export function getDocumentFactSources(
  document: DocWeaveDocument,
): ReadonlyMap<string, string> {
  const internals = documentInternals.get(document);
  if (!internals) throw new TypeError("Invalid DocWeaveDocument");
  return internals.factSources;
}

/** @internal */
export function getDocumentNode(
  document: DocWeaveDocument,
): ProseMirrorNode {
  const internals = documentInternals.get(document);
  if (!internals) throw new TypeError("Invalid DocWeaveDocument");
  return internals.node;
}

function assertValidSourceId(sourceId: string): void {
  if (sourceId.length === 0 || /[\t\n\f\r ]/.test(sourceId)) {
    throw new Error(`Invalid fact source ID: ${JSON.stringify(sourceId)}`);
  }
}

export function buildOrder(
  define: (order: OrderBuilder) => void,
): DocWeaveDocument {
  const nodes: ProseMirrorNode[] = [];
  const factSources = new Map<string, string>();
  const documentClauses: DocWeaveClause[] = [];
  const clausesById = new Map<string, DocWeaveClause>();
  const clauseIds = new Set<string>();

  function assertUniqueClauseId(id: string): void {
    if (clauseIds.has(id)) {
      throw new Error(`Duplicate clause ID: ${id}`);
    }
    clauseIds.add(id);
  }

  function createClause(
    id: string,
    textContent: string,
    children: readonly DocWeaveClause[] = [],
  ): DocWeaveClause {
    const clause = Object.freeze({
      id,
      textContent,
      children: Object.freeze([...children]),
    });
    clausesById.set(id, clause);
    return clause;
  }

  function buildContent(
    ownerId: string,
    content: ClauseContent,
  ): ProseMirrorNode[] {
    if (typeof content === "string") return [editorSchema.text(content)];

    const inlineNodes: ProseMirrorNode[] = [];
    const inlineBuilder: InlineBuilder = {
      text(value: string): InlineBuilder {
        if (value) inlineNodes.push(editorSchema.text(value));
        return inlineBuilder;
      },
      fact(
        id: string,
        value: string,
        options: FactOptions = {},
      ): InlineBuilder {
        const factId = `generated-text:${ownerId}:${id}`;
        inlineNodes.push(
          editorSchema.node("generated_text", {
            id: factId,
            text: value,
          }),
        );
        if (options.sourceId !== undefined) {
          assertValidSourceId(options.sourceId);
          factSources.set(factId, options.sourceId);
        }
        return inlineBuilder;
      },
    };

    content(inlineBuilder);
    return inlineNodes;
  }

  function buildOrderedList(
    id: string,
    defineList: (list: OrderedListBuilder) => void,
  ): BuiltOrderedList | undefined {
    const items: ProseMirrorNode[] = [];
    const clauses: DocWeaveClause[] = [];
    const listBuilder: OrderedListBuilder = {
      item(
        itemId: string,
        content: ClauseContent,
        defineItem?: (item: ListItemBuilder) => void,
      ): void {
        assertUniqueClauseId(itemId);
        const managedItemId = `item:${itemId}`;
        const ownContent = buildContent(managedItemId, content);
        const children = [
          editorSchema.node(
            "paragraph",
            null,
            ownContent,
          ),
        ];
        let childClauses: readonly DocWeaveClause[] = [];
        const itemBuilder: ListItemBuilder = {
          orderedList(
            nestedListId: string,
            defineNestedList: (list: OrderedListBuilder) => void,
          ): void {
            if (children.length > 1) {
              throw new Error(
                `List item "${itemId}" may contain only one nested ordered list`,
              );
            }
            const nestedList = buildOrderedList(
              nestedListId,
              defineNestedList,
            );
            if (nestedList) {
              children.push(nestedList.node);
              childClauses = nestedList.clauses;
            }
          },
        };

        defineItem?.(itemBuilder);
        items.push(
          editorSchema.node(
            "list_item",
            { id: managedItemId },
            children,
          ),
        );
        clauses.push(
          createClause(
            itemId,
            ownContent.map((node) => node.textContent).join(""),
            childClauses,
          ),
        );
      },
    };

    defineList(listBuilder);
    if (items.length === 0) return undefined;

    return {
      node: editorSchema.node(
        "ordered_list",
        { id: `ordered-list:${id}` },
        items,
      ),
      clauses: Object.freeze(clauses),
    };
  }

  const orderBuilder: OrderBuilder = {
    paragraph(id: string, content: ClauseContent): void {
      assertUniqueClauseId(id);
      const paragraphId = `paragraph:${id}`;
      const paragraphContent = buildContent(paragraphId, content);
      nodes.push(
        editorSchema.node(
          "paragraph",
          { id: paragraphId },
          paragraphContent,
        ),
      );
      documentClauses.push(
        createClause(
          id,
          paragraphContent.map((node) => node.textContent).join(""),
        ),
      );
    },
    orderedList(
      id: string,
      defineList: (list: OrderedListBuilder) => void,
    ): void {
      const list = buildOrderedList(id, defineList);
      if (list) {
        nodes.push(list.node);
        documentClauses.push(...list.clauses);
      }
    },
  };

  define(orderBuilder);
  const document = editorSchema.node("doc", null, nodes);
  assertValidGeneratedDocument(document);
  return createDocWeaveDocument(
    document,
    factSources,
    documentClauses,
    clausesById,
  );
}
