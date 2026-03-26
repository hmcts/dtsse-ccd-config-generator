export type HeaderThemeKey = 'judicial' | 'myhmcts' | 'default';
export type HeaderLogo = 'judicial' | 'myhmcts' | 'none';
export type HeaderNavAlign = 'left' | 'right';
export type HeaderNavKind = 'link' | 'case-reference-search';
export type HeaderSearchMode = 'link' | 'case-reference';
export type HeaderEnvironment = 'local' | 'aat' | 'preview' | 'prod';

export type HeaderFlagDefinition =
  | string
  | {
      flagName: string;
      value: string;
    };

export type HeaderThemeConfig = {
  rolePattern: string;
  key: HeaderThemeKey;
  backgroundColor: string;
  logo: HeaderLogo;
  title: {
    text: string;
    href: string;
  };
};

export type HeaderAccountItem = {
  id: string;
  text: string;
  href?: string;
  action?: string;
};

export type HeaderNavItem = {
  id?: string;
  text: string;
  href?: string;
  active?: boolean;
  align?: HeaderNavAlign;
  kind?: HeaderNavKind;
  classes?: string[];
  action?: string;
  roles?: string[];
  notRoles?: string[];
  flags?: HeaderFlagDefinition[];
  notFlags?: HeaderFlagDefinition[];
};

export type HeaderMenuConfig = Record<string, HeaderNavItem[]>;

export type HeaderSearchModel = {
  mode: HeaderSearchMode;
  href?: string;
  label?: string;
  buttonText?: string;
  action?: string;
  name?: string;
  value?: string;
  invalid?: boolean;
  errorMessage?: string;
};

export type HeaderPhaseBannerModel = {
  visible: boolean;
  tag: string;
  preLinkText: string;
  linkText: string;
  linkHref: string;
  postLinkText: string;
  newWindowText: string;
  target?: string;
  rel?: string;
};

export type HeaderModel = {
  theme: {
    key: HeaderThemeKey;
    backgroundColor: string;
    logo: HeaderLogo;
  };
  title: {
    text: string;
    href: string;
  };
  skipLink: {
    text: string;
    href: string;
  };
  accountNav: {
    label: string;
    items: HeaderAccountItem[];
  };
  primaryNav: {
    visible: boolean;
    leftItems: HeaderNavItem[];
    rightItems: HeaderNavItem[];
  };
  search?: HeaderSearchModel;
  phaseBanner?: HeaderPhaseBannerModel;
};

export type HeaderContext = {
  xuiBaseUrl: string;
  user: {
    isAuthenticated: boolean;
    roles: string[];
    roleCategory?: string;
    displayName?: string;
    bookable?: boolean;
  };
  route: {
    path: string;
  };
  features: Record<string, boolean | string>;
  environment?: HeaderEnvironment;
  config?: {
    themes?: HeaderThemeConfig[];
    menuConfig?: HeaderMenuConfig;
    accountItems?: HeaderAccountItem[];
    phaseBanner?: HeaderPhaseBannerModel | null;
  };
};
