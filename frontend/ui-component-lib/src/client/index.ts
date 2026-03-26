export type HeaderEnhancementOptions = {
  root?: ParentNode;
};

export function initUiComponentLibEnhancements(
  options: HeaderEnhancementOptions = {}
): void {
  const root = options.root ?? document;
  const hosts =
    root instanceof Element || root instanceof Document || root instanceof DocumentFragment
      ? Array.from(root.querySelectorAll<HTMLElement>('hmcts-xui-header'))
      : [];

  hosts.forEach((host) => {
    const shadowRoot = host.shadowRoot;
    if (!shadowRoot) {
      return;
    }

    shadowRoot.querySelectorAll<HTMLElement>('[data-header-action]').forEach((actionElement) => {
      if (actionElement.dataset.uiComponentLibBound === 'true') {
        return;
      }

      actionElement.dataset.uiComponentLibBound = 'true';
      actionElement.addEventListener('click', (event) => {
        event.preventDefault();
        const action = actionElement.dataset.headerAction;
        if (!action) {
          return;
        }

        host.dispatchEvent(
          new CustomEvent('hmcts-header-action', {
            bubbles: true,
            composed: true,
            detail: {
              action,
              text: actionElement.dataset.headerText ?? actionElement.textContent?.trim() ?? ''
            }
          })
        );
      });
    });
  });
}

export const initXuiHeaderEnhancements = initUiComponentLibEnhancements;
