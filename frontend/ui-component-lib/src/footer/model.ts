import {
  DEFAULT_COPYRIGHT,
  DEFAULT_FOOTER_NAVIGATION,
  DEFAULT_LANGUAGE_TOGGLE_ACTION,
  DEFAULT_OPEN_IN_NEW_WINDOW_TEXT
} from './defaults.js';
import type {
  FooterContext,
  FooterLanguage,
  FooterLanguageToggle,
  FooterModel
} from './types.js';

export function buildFooterModel(context: FooterContext = {}): FooterModel {
  const languageToggle = resolveLanguageToggle(context);

  return {
    navigation: {
      items: DEFAULT_FOOTER_NAVIGATION.items.map((item) => ({ ...item }))
    },
    ...(languageToggle ? { languageToggle } : {}),
    openInNewWindowText: DEFAULT_OPEN_IN_NEW_WINDOW_TEXT,
    copyright: {
      ...DEFAULT_COPYRIGHT
    }
  };
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
