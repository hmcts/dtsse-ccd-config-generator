import { type JSDOM } from "jsdom";

import { polyfillBrowserApis } from "./jsdom-polyfills.js";

const globalNames = [
  "window",
  "document",
  "navigator",
  "Node",
  "Text",
  "Element",
  "HTMLElement",
  "MutationObserver",
  "DOMParser",
  "KeyboardEvent",
  "MouseEvent",
  "requestAnimationFrame",
  "cancelAnimationFrame",
  "getComputedStyle",
] as const;

/**
 * Makes a jsdom window the global browser environment ProseMirror expects,
 * returning the function that puts the globals back.
 */
export function installJsdomGlobals(dom: JSDOM): () => void {
  const window = dom.window;
  polyfillBrowserApis(window);
  Object.defineProperty(window.Range.prototype, "getClientRects", {
    configurable: true,
    value: () => [new window.DOMRect()],
  });
  for (const prototype of [window.Range.prototype, window.Text.prototype]) {
    Object.defineProperty(prototype, "getBoundingClientRect", {
      configurable: true,
      value: () => new window.DOMRect(),
    });
  }
  const originals = new Map(
    globalNames.map((name) => [
      name,
      Object.getOwnPropertyDescriptor(globalThis, name),
    ]),
  );
  const values: Record<(typeof globalNames)[number], unknown> = {
    window,
    document: window.document,
    navigator: window.navigator,
    Node: window.Node,
    Text: window.Text,
    Element: window.Element,
    HTMLElement: window.HTMLElement,
    MutationObserver: window.MutationObserver,
    DOMParser: window.DOMParser,
    KeyboardEvent: window.KeyboardEvent,
    MouseEvent: window.MouseEvent,
    requestAnimationFrame: window.requestAnimationFrame.bind(window),
    cancelAnimationFrame: window.cancelAnimationFrame.bind(window),
    getComputedStyle: window.getComputedStyle.bind(window),
  };
  for (const name of globalNames) {
    Object.defineProperty(globalThis, name, {
      configurable: true,
      writable: true,
      value: values[name],
    });
  }
  return () => {
    dom.window.close();
    for (const name of globalNames) {
      const descriptor = originals.get(name);
      if (descriptor) Object.defineProperty(globalThis, name, descriptor);
      else Reflect.deleteProperty(globalThis, name);
    }
  };
}
