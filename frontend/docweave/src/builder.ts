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

const documentFactSources = new WeakMap<
  DocWeaveDocument,
  ReadonlyMap<string, string>
>();
let createDocWeaveDocument: (
  node: ProseMirrorNode,
  factSources: ReadonlyMap<string, string>,
) => DocWeaveDocument;

/** A generated document and its runtime-only DocWeave metadata. */
export class DocWeaveDocument {
  readonly node: ProseMirrorNode;

  private constructor(
    node: ProseMirrorNode,
    factSources: ReadonlyMap<string, string>,
  ) {
    this.node = node;
    documentFactSources.set(this, new Map(factSources));
  }

  static {
    createDocWeaveDocument = (node, factSources) =>
      new DocWeaveDocument(node, factSources);
  }
}

/** @internal */
export function getDocumentFactSources(
  document: DocWeaveDocument,
): ReadonlyMap<string, string> {
  const factSources = documentFactSources.get(document);
  if (!factSources) throw new TypeError("Invalid DocWeaveDocument");
  return factSources;
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
  ): ProseMirrorNode | undefined {
    const items: ProseMirrorNode[] = [];
    const listBuilder: OrderedListBuilder = {
      item(
        itemId: string,
        content: ClauseContent,
        defineItem?: (item: ListItemBuilder) => void,
      ): void {
        const managedItemId = `item:${itemId}`;
        const children = [
          editorSchema.node(
            "paragraph",
            null,
            buildContent(managedItemId, content),
          ),
        ];
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
            if (nestedList) children.push(nestedList);
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
      },
    };

    defineList(listBuilder);
    if (items.length === 0) return undefined;

    return editorSchema.node(
      "ordered_list",
      { id: `ordered-list:${id}` },
      items,
    );
  }

  const orderBuilder: OrderBuilder = {
    paragraph(id: string, content: ClauseContent): void {
      const paragraphId = `paragraph:${id}`;
      nodes.push(
        editorSchema.node(
          "paragraph",
          { id: paragraphId },
          buildContent(paragraphId, content),
        ),
      );
    },
    orderedList(
      id: string,
      defineList: (list: OrderedListBuilder) => void,
    ): void {
      const list = buildOrderedList(id, defineList);
      if (list) nodes.push(list);
    },
  };

  define(orderBuilder);
  const document = editorSchema.node("doc", null, nodes);
  assertValidGeneratedDocument(document);
  return createDocWeaveDocument(document, factSources);
}
