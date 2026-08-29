import { type Node as ProseMirrorNode } from "prosemirror-model";

import { editorSchema } from "./schema.js";

export interface ListBuilder {
  listItem(clauseId: string, text: string): ListBuilder;
  build(): OrderBuilder;
}

export interface OrderBuilder {
  setClause(clauseId: string, text: string): void;
  buildList(): ListBuilder;
  build(): ProseMirrorNode;
}

export function createOrderBuilder(): OrderBuilder {
  const clauses: ProseMirrorNode[] = [];

  function setClause(clauseId: string, text: string): void {
    clauses.push(
      editorSchema.node(
        "paragraph",
        { clause_id: clauseId },
        editorSchema.text(text),
      ),
    );
  }

  function buildList(): ListBuilder {
    const listItems: ProseMirrorNode[] = [];

    const listBuilder: ListBuilder = {
      listItem(clauseId: string, text: string): ListBuilder {
        const paragraph = editorSchema.node(
          "paragraph",
          null,
          editorSchema.text(text),
        );

        listItems.push(
          editorSchema.node("list_item", { clause_id: clauseId }, paragraph),
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
