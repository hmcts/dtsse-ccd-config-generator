import { Plugin } from "prosemirror-state";
import { Decoration, DecorationSet } from "prosemirror-view";

export function createListNumberingPlugin(): Plugin {
  return new Plugin({
    props: {
      decorations(state) {
        const decorations: Decoration[] = [];
        let nextNumber = 1;

        state.doc.forEach((node, position) => {
          if (node.type.name !== "ordered_list") return;

          decorations.push(
            Decoration.node(
              position,
              position + node.nodeSize,
              { start: String(nextNumber) },
            ),
          );

          nextNumber += node.childCount;
        });

        return DecorationSet.create(state.doc, decorations);
      },
    },
  });
}
