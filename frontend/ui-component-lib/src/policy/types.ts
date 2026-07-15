export type HeaderThemeKey = 'judicial' | 'myhmcts' | 'default';
export type HeaderLogo = 'judicial' | 'myhmcts' | 'none';
export type HeaderNavAlign = 'left' | 'right';
export type HeaderEnvironment = 'prod' | 'aat';

export type HeaderThemeConfig = {
  rolePattern: string;
  key: HeaderThemeKey;
  logo: HeaderLogo;
  title: {
    text: string;
    href: string;
  };
};

export type HeaderAccountItem = {
  text: string;
  href: string;
};

export type HeaderNavItem = {
  text: string;
  href: string;
};

export type HeaderMenuItem = {
  text: string;
  href?: string;
  align?: HeaderNavAlign;
  roles?: string[];
  notRoles?: string[];
};

export type HeaderMenuConfig = Record<string, HeaderMenuItem[]>;

export type HeaderModel = {
  /** @deprecated The package always serves assets from /assets/ui-component-lib. */
  assetsPath: string;
  theme: {
    key: HeaderThemeKey;
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
    leftItems: HeaderNavItem[];
    rightItems: HeaderNavItem[];
  };
};

export type HeaderContext = {
  xuiBaseUrl: string;
  environment?: HeaderEnvironment;
  user: {
    roles: string[];
  };
};
