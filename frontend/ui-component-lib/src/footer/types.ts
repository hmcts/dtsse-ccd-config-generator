export type FooterLanguage = 'en' | 'cy';

export type FooterNavigationItem = {
  text: string;
  href: string;
  target?: string;
};

export type FooterNavigation = {
  items: FooterNavigationItem[];
};

export type FooterLanguageToggle = {
  enabled: boolean;
  currentLanguage: FooterLanguage;
  action: string;
  text: string;
};

export type FooterModel = {
  navigation: FooterNavigation;
  languageToggle?: FooterLanguageToggle;
  openInNewWindowText: string;
  copyright: {
    text: string;
    href: string;
  };
};

export type FooterContext = {
  welshLanguageToggleEnabled?: boolean;
  currentLanguage?: FooterLanguage;
  languageToggleAction?: string;
};
