import { type Node as ProseMirrorNode } from "prosemirror-model";

import { editorSchema } from "./schema.js";

export interface ListBuilder {
  listItem(id: string, text: string): ListBuilder;
  build(): OrderBuilder;
}

export interface OrderBuilder {
  setClause(id: string, text: string): void;
  buildList(): ListBuilder;
  build(): ProseMirrorNode;
}

export function createOrderBuilder(): OrderBuilder {
  const clauses: ProseMirrorNode[] = [];

  function setClause(id: string, text: string): void {
    clauses.push(
      editorSchema.node(
        "paragraph",
        { id },
        editorSchema.text(text),
      ),
    );
  }

  function buildList(): ListBuilder {
    const listItems: ProseMirrorNode[] = [];

    const listBuilder: ListBuilder = {
      listItem(itemId: string, text: string): ListBuilder {
        const paragraph = editorSchema.node(
          "paragraph",
          { id: itemId + "-content" },
          editorSchema.text(text),
        );

        listItems.push(
          editorSchema.node("list_item", { id: itemId }, paragraph),
        );
        return listBuilder;
      },
      build(): OrderBuilder {
        clauses.push(
          editorSchema.node(
            "ordered_list",
            null,
            listItems,
          ),
        );
        return orderBuilder;
      },
    };

    return listBuilder;
  }

  function build(): ProseMirrorNode {
    return editorSchema.node("doc", null, [...clauses]);
  }

  const orderBuilder: OrderBuilder = {
    setClause,
    buildList,
    build,
  };

  return orderBuilder;
}
