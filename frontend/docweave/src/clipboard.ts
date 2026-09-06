import { Plugin } from "prosemirror-state";

function trimTrailingNonBreakingSpaces(container: HTMLElement): void {
  for (const paragraph of container.querySelectorAll("p")) {
    const walker = paragraph.ownerDocument.createTreeWalker(
      paragraph,
      paragraph.ownerDocument.defaultView?.NodeFilter.SHOW_TEXT ?? 4,
    );
    const textNodes: Text[] = [];
    let node = walker.nextNode();

    while (node) {
      textNodes.push(node as Text);
      node = walker.nextNode();
    }

    for (let index = textNodes.length - 1; index >= 0; index--) {
      const textNode = textNodes[index]!;
      textNode.data = textNode.data.replace(/\u00a0+$/u, "");
      if (textNode.data) break;
    }
  }
}

function normalizeWordLists(container: HTMLElement): void {
  let listId: string | undefined;
  let rootList: HTMLOListElement | HTMLUListElement | undefined;
  const parentByLevel = new Map<number, HTMLLIElement>();

  for (const wrapper of [...container.children]) {
    const list = wrapper.matches("ol, ul")
      ? wrapper as HTMLOListElement | HTMLUListElement
      : wrapper.querySelector<HTMLOListElement | HTMLUListElement>(
        ":scope > ol, :scope > ul",
      );
    const item = list?.querySelector<HTMLLIElement>(":scope > li");
    const itemListId = item?.dataset.listid;
    const level = Number(item?.dataset.ariaLevel);

    if (!list || !item || !itemListId || !Number.isInteger(level) || level < 1) {
      listId = undefined;
      rootList = undefined;
      parentByLevel.clear();
      continue;
    }

    if (level === 1) {
      if (itemListId === listId && rootList) {
        rootList.append(item);
        wrapper.remove();
      } else {
        listId = itemListId;
        rootList = list;
      }
    } else {
      const parent = itemListId === listId
        ? parentByLevel.get(level - 1)
        : undefined;
      if (!parent) {
        listId = undefined;
        rootList = undefined;
        parentByLevel.clear();
        continue;
      }

      let nestedList = parent.querySelector<
        HTMLOListElement | HTMLUListElement
      >(":scope > ol, :scope > ul");
      if (!nestedList) {
        nestedList = list.cloneNode(false) as
          | HTMLOListElement
          | HTMLUListElement;
        nestedList.removeAttribute("start");
        parent.append(nestedList);
      }
      nestedList.append(item);
      wrapper.remove();
    }

    for (const existingLevel of parentByLevel.keys()) {
      if (existingLevel >= level) parentByLevel.delete(existingLevel);
    }
    parentByLevel.set(level, item);
  }
}

export function createClipboardPlugin(): Plugin {
  return new Plugin({
    props: {
      transformPastedHTML(html, view) {
        const container = view.dom.ownerDocument.createElement("div");
        container.innerHTML = html;

        if (container.querySelector("li[data-listid][data-aria-level]")) {
          trimTrailingNonBreakingSpaces(container);
          normalizeWordLists(container);
        }

        return container.innerHTML;
      },
    },
  });
}
