import {
  DEFAULT_COPYRIGHT,
  DEFAULT_FOOTER_CONTEXT,
  DEFAULT_LANGUAGE_TOGGLE_ACTION,
  DEFAULT_OPEN_IN_NEW_WINDOW_TEXT
} from './defaults.js';
import type {
  FooterContext,
  FooterLanguage,
  FooterLanguageToggle,
  FooterModel,
  FooterNavigationItem
} from './types.js';

export function buildFooterModel(context: FooterContext = {}): FooterModel {
  const mergedContext: FooterContext = {
    ...DEFAULT_FOOTER_CONTEXT,
    ...context
  };
  const languageToggle = resolveLanguageToggle(mergedContext);

  return {
    ...(mergedContext.help ? { help: cloneHelp(mergedContext.help) } : {}),
    navigation: {
      items: resolveNavigationItems(mergedContext)
    },
    ...(languageToggle ? { languageToggle } : {}),
    openInNewWindowText: DEFAULT_OPEN_IN_NEW_WINDOW_TEXT,
    copyright: {
      ...DEFAULT_COPYRIGHT
    }
  };
}

function cloneHelp(help: NonNullable<FooterContext['help']>): NonNullable<FooterModel['help']> {
  return {
    heading: help.heading,
    email: { ...help.email },
    phone: { ...help.phone },
    opening: { ...help.opening },
    probate: { ...help.probate },
    otherContact: { ...help.otherContact }
  };
}

function resolveNavigationItems(context: FooterContext): FooterNavigationItem[] {
  const items = (context.navigation ?? DEFAULT_FOOTER_CONTEXT.navigation ?? { items: [] }).items
    .map((item) => ({ ...item }));

  return items.map((item) => {
    if (item.text !== 'Terms and conditions') {
      return item;
    }

    return {
      ...item,
      href: context.termsAndConditionsFeatureEnabled === false
        ? '/legacy-terms-and-conditions'
        : '/terms-and-conditions'
    };
  });
}

function resolveLanguageToggle(context: FooterContext): FooterLanguageToggle | undefined {
  if (!context.welshLanguageToggleEnabled) {
    return undefined;
  }

  const currentLanguage: FooterLanguage = context.currentLanguage ?? 'en';

  return {
    enabled: true,
    currentLanguage,
    action: context.languageToggleAction ?? DEFAULT_LANGUAGE_TOGGLE_ACTION,
    text: currentLanguage === 'cy' ? 'English' : 'Cymraeg'
  };
}
