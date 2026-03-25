import type { HeaderThemeConfig } from './types.js';

export const DEFAULT_SKIP_LINK = {
  text: 'Skip to main content',
  href: '#content'
} as const;

export const DEFAULT_ACCOUNT_NAV_LABEL = 'Account navigation';

export const DEFAULT_THEME_CONFIGS: HeaderThemeConfig[] = [
  {
    rolePattern: '(judge)|(judiciary)|(panelmember)',
    key: 'judicial',
    backgroundColor: '#8d0f0e',
    logo: 'judicial',
    title: {
      text: 'Judicial Case Manager',
      href: '/'
    }
  },
  {
    rolePattern: 'pui-case-manager',
    key: 'myhmcts',
    backgroundColor: '#202020',
    logo: 'myhmcts',
    title: {
      text: 'Manage Cases',
      href: '/'
    }
  },
  {
    rolePattern: '.+',
    key: 'default',
    backgroundColor: '#202020',
    logo: 'none',
    title: {
      text: 'Manage Cases',
      href: '/'
    }
  }
];

export const DEFAULT_CASE_MANAGER_ROLES = [
  'pui-case-manager',
  'caseworker-ia-legalrep-solicitor',
  'caseworker-ia-homeofficeapc',
  'caseworker-ia-respondentofficer',
  'caseworker-ia-homeofficelart',
  'caseworker-ia-homeofficepou'
] as const;

export const NAV_HIDDEN_ROUTE_FRAGMENTS = [
  'accept-terms-and-conditions',
  'terms-and-conditions'
] as const;
