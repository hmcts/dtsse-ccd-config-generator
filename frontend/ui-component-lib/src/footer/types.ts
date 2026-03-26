export type FooterLanguage = 'en' | 'cy';

export type FooterTextValue = {
  text: string;
};

export type FooterEmailValue = {
  address: string;
  text: string;
};

export type FooterHelp = {
  heading: string;
  email: FooterEmailValue;
  phone: FooterTextValue;
  opening: FooterTextValue;
  probate: FooterTextValue;
  otherContact: FooterTextValue;
};

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
  help?: FooterHelp;
  navigation: FooterNavigation;
  languageToggle?: FooterLanguageToggle;
  openInNewWindowText: string;
  copyright: {
    text: string;
    href: string;
  };
};

export type FooterContext = {
  help?: FooterHelp | null;
  navigation?: FooterNavigation;
  welshLanguageToggleEnabled?: boolean;
  currentLanguage?: FooterLanguage;
  languageToggleAction?: string;
  termsAndConditionsFeatureEnabled?: boolean;
};
