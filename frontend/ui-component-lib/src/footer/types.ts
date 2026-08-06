export type FooterNavigationItem = {
  text: string;
  href: string;
  target?: string;
};

export type FooterNavigation = {
  items: FooterNavigationItem[];
};

export type FooterModel = {
  navigation: FooterNavigation;
  openInNewWindowText: string;
  copyright: {
    text: string;
    href: string;
  };
};

export type FooterContext = {
  xuiBaseUrl?: string;
};
