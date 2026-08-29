import { type Node as ProseMirrorNode } from "prosemirror-model";

import { editorSchema } from "./schema.js";

export interface ClauseBuilder {
  text(text: string): ClauseBuilder;
  formValue(slotId: string, text: string): ClauseBuilder;
}

type ClauseContent = string | ((clause: ClauseBuilder) => void);

export interface ListBuilder {
  listItem(clauseId: string, text?: string): ListItemBuilder;
}

export interface ListItemBuilder {
  text(text: string): ListItemBuilder;
  formValue(slotId: string, text: string): ListItemBuilder;
  listItem(clauseId: string, text?: string): ListItemBuilder;
  build(): OrderBuilder;
}

export interface OrderBuilder {
  setClause(clauseId: string, content: ClauseContent): void;
  buildList(containerId: string): ListBuilder;
  build(): ProseMirrorNode;
}

export function createOrderBuilder(): OrderBuilder {
  const clauses: ProseMirrorNode[] = [];

  function buildClauseContent(
    clauseId: string,
    content: ClauseContent,
  ): ProseMirrorNode[] {
    if (typeof content === "string") return [editorSchema.text(content)];

    const nodes: ProseMirrorNode[] = [];
    const clauseBuilder: ClauseBuilder = {
      text(text: string): ClauseBuilder {
        if (text) nodes.push(editorSchema.text(text));
        return clauseBuilder;
      },
      formValue(slotId: string, text: string): ClauseBuilder {
        nodes.push(
          editorSchema.node("form_value", {
            id: `fact:${clauseId}:${slotId}`,
            text,
          }),
        );
        return clauseBuilder;
      },
    };

    content(clauseBuilder);
    return nodes;
  }

  function setClause(clauseId: string, content: ClauseContent): void {
    clauses.push(
      editorSchema.node(
        "paragraph",
        { id: `clause:${clauseId}` },
        buildClauseContent(clauseId, content),
      ),
    );
  }

  function buildList(containerId: string): ListBuilder {
    const listItems: ProseMirrorNode[] = [];
    let currentClauseId: string | undefined;
    let currentContent: ProseMirrorNode[] = [];

    function finishListItem(): void {
      if (!currentClauseId) return;

      const paragraph = editorSchema.node(
        "paragraph",
        null,
        currentContent,
      );

      listItems.push(
        editorSchema.node(
          "list_item",
          { id: `clause:${currentClauseId}` },
          paragraph,
        ),
      );
      currentClauseId = undefined;
      currentContent = [];
    }

    function startListItem(
      clauseId: string,
      text?: string,
    ): ListItemBuilder {
      finishListItem();
      currentClauseId = clauseId;
      currentContent = text ? [editorSchema.text(text)] : [];
      return listItemBuilder;
    }

    const listItemBuilder: ListItemBuilder = {
      text(text: string): ListItemBuilder {
        if (text) currentContent.push(editorSchema.text(text));
        return listItemBuilder;
      },
      formValue(slotId: string, text: string): ListItemBuilder {
        currentContent.push(
          editorSchema.node(
            "form_value",
            {
              id: `fact:${currentClauseId}:${slotId}`,
              text,
            },
          ),
        );
        return listItemBuilder;
      },
      listItem: startListItem,
      build(): OrderBuilder {
        finishListItem();
        clauses.push(
          editorSchema.node(
            "ordered_list",
            { id: `container:${containerId}` },
            listItems,
          ),
        );
        return orderBuilder;
      },
    };

    const listBuilder: ListBuilder = {
      listItem: startListItem,
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
