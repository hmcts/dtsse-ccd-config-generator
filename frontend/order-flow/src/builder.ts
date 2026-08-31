import { type Node as ProseMirrorNode } from "prosemirror-model";

import { editorSchema } from "./schema.js";

export interface InlineBuilder {
  text(value: string): this;
  generatedText(id: string, value: string): this;
}

type ClauseContent = string | ((content: InlineBuilder) => void);

export interface OrderedListBuilder {
  item(id: string, content: ClauseContent): void;
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
      const items: ProseMirrorNode[] = [];
      const listBuilder: OrderedListBuilder = {
        item(itemId: string, content: ClauseContent): void {
          const managedItemId = `item:${itemId}`;
          const paragraph = editorSchema.node(
            "paragraph",
            null,
            buildContent(managedItemId, content),
          );
          items.push(
            editorSchema.node(
              "list_item",
              { id: managedItemId },
              paragraph,
            ),
          );
        },
      };

      defineList(listBuilder);
      if (items.length === 0) {
        throw new Error(`Ordered list "${id}" must contain at least one item`);
      }

      nodes.push(
        editorSchema.node(
          "ordered_list",
          { id: `ordered-list:${id}` },
          items,
        ),
      );
    },
  };

  define(orderBuilder);
  return editorSchema.node("doc", null, nodes);
}
