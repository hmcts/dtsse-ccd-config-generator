import { Plugin } from "prosemirror-state";
import { Decoration, DecorationSet } from "prosemirror-view";

export function createDiffStylingPlugin(): Plugin {
  return new Plugin({
    props: {
      decorations(state) {
        const decorations: Decoration[] = [];

        state.doc.descendants((node, position, parent) => {
          const isClause = parent === state.doc ||
            parent?.type === state.schema.nodes.ordered_list;

          if (isClause && node.attrs.id === null) {
            decorations.push(
              Decoration.node(position, position + node.nodeSize, {
                class: "user-authored-paragraph",
              }),
            );
          }
        });

        return DecorationSet.create(state.doc, decorations);
      },
    },
  });
}
