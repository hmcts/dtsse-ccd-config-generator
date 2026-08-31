import { type Node as ProseMirrorNode } from "prosemirror-model";

import { editorSchema } from "./schema.js";

export interface InlineBuilder {
  text(value: string): this;
  generatedText(id: string, value: string): this;
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

export function buildOrder(
  define: (order: OrderBuilder) => void,
): ProseMirrorNode {
  const nodes: ProseMirrorNode[] = [];

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
      generatedText(id: string, value: string): InlineBuilder {
        inlineNodes.push(
          editorSchema.node("generated_text", {
            id: `generated-text:${ownerId}:${id}`,
            text: value,
          }),
        );
        return inlineBuilder;
      },
    };

    content(inlineBuilder);
    return inlineNodes;
  }

  function buildOrderedList(
    id: string,
    defineList: (list: OrderedListBuilder) => void,
  ): ProseMirrorNode {
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
            children.push(buildOrderedList(nestedListId, defineNestedList));
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
    if (items.length === 0) {
      throw new Error(`Ordered list "${id}" must contain at least one item`);
    }

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
      nodes.push(buildOrderedList(id, defineList));
    },
  };

  define(orderBuilder);
  return editorSchema.node("doc", null, nodes);
}
