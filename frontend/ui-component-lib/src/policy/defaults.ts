import type {
  HeaderMenuConfig,
  HeaderThemeConfig
} from './types.js';

export const DEFAULT_SKIP_LINK = {
  text: 'Skip to main content',
  href: '#content'
} as const;

export const DEFAULT_ACCOUNT_NAV_LABEL = 'Account navigation';
export const DEFAULT_ASSETS_PATH = '/assets/ui-component-lib';
export const DEFAULT_ACCOUNT_ITEMS = [
  {
    text: 'Sign out',
    href: '/logout'
  }
] as const;

export const DEFAULT_THEME_CONFIGS: HeaderThemeConfig[] = [
  {
    rolePattern: '(judge)|(judiciary)|(panelmember)',
    key: 'judicial',
    logo: 'judicial',
    title: {
      text: 'Judicial Case Manager',
      href: '/'
    }
  },
  {
    rolePattern: 'pui-case-manager',
    key: 'myhmcts',
    logo: 'myhmcts',
    title: {
      text: 'Manage Cases',
      href: '/'
    }
  },
  {
    rolePattern: '.+',
    key: 'default',
    logo: 'none',
    title: {
      text: 'Manage Cases',
      href: '/'
    }
  }
];

export const DEFAULT_MENU_CONFIG: HeaderMenuConfig = {
  '(judge)|(judiciary)|(panelmember)': [
    {
      text: 'My work',
      href: '/work/my-work/list',
      roles: [
        'caseworker-civil',
        'caseworker-ia-iacjudge',
        'caseworker-privatelaw',
        'caseworker-publiclaw',
        'caseworker-employment-etjudge',
        'caseworker-st_cic'
      ]
    },
    {
      text: 'All work',
      href: '/work/all-work/tasks',
      roles: ['task-supervisor']
    },
    {
      text: 'Case list',
      href: '/cases',
      roles: [
        'caseworker-sscs-judge',
        'caseworker-sscs-panelmember',
        'caseworker-cmc-judge',
        'caseworker-divorce-judge',
        'caseworker-divorce-financialremedy-judiciary',
        'caseworker-probate-judge',
        'caseworker-ia-iacjudge',
        'caseworker-civil',
        'caseworker-privatelaw',
        'caseworker-publiclaw-judiciary',
        'caseworker-employment-etjudge',
        'caseworker-st_cic',
        'caseworker-pcs'
      ]
    },
    {
      text: 'Find case',
      href: '/cases/case-search',
      roles: [
        'caseworker-sscs-judge',
        'caseworker-sscs-panelmember',
        'caseworker-cmc-judge',
        'caseworker-divorce-judge',
        'caseworker-divorce-financialremedy-judiciary',
        'caseworker-probate-judge',
        'caseworker-publiclaw-judiciary',
        'caseworker-employment-etjudge',
        'caseworker-st_cic',
        'caseworker-pcs'
      ]
    },
    {
      text: 'Search',
      href: '/search',
      roles: [
        'caseworker-civil',
        'caseworker-ia-iacjudge',
        'caseworker-privatelaw',
        'caseworker-publiclaw',
        'caseworker-st_cic-judge',
        'caseworker-st_cic-senior-judge',
        'caseworker-employment-etjudge',
        'caseworker-st_cic',
        'caseworker-pcs'
      ]
    },
    {
      text: 'Work access',
      href: '/booking',
      roles: ['fee-paid-judge']
    }
  ],
  '(pui-case-manager)': [
    {
      text: 'Case list',
      href: '/cases'
    },
    {
      text: 'Create case',
      href: '/cases/case-filter'
    },
    {
      text: 'Notice of change',
      href: '/noc',
      roles: [
        'caseworker-divorce-solicitor',
        'caseworker-probate-solicitor',
        'caseworker-privatelaw-solicitor',
        'caseworker-employment-legalrep-solicitor',
        'caseworker-pcs-solicitor'
      ]
    },
    {
      text: 'Find case',
      href: '/cases/case-search',
      align: 'right'
    }
  ],
  '.+': [
    {
      text: 'My work',
      href: '/work/my-work/list',
      roles: [
        'caseworker-civil',
        'caseworker-civil-staff',
        'caseworker-ia-caseofficer',
        'caseworker-ia-admofficer',
        'caseworker-privatelaw',
        'caseworker-publiclaw',
        'caseworker-employment',
        'caseworker-st_cic'
      ]
    },
    {
      text: 'All work',
      href: '/work/all-work/tasks',
      roles: ['task-supervisor']
    },
    {
      text: 'Case list',
      href: '/cases',
      roles: [
        'caseworker-caa',
        'caseworker-divorce',
        'caseworker-sscs',
        'caseworker-adoption',
        'caseworker-civil',
        'caseworker-cmc',
        'caseworker-employment',
        'caseworker-privatelaw',
        'caseworker-hrs',
        'caseworker-probate',
        'caseworker-ia',
        'caseworker-publiclaw',
        'caseworker-st_cic',
        'caseworker-pcs'
      ]
    },
    {
      text: 'Create case',
      href: '/cases/case-filter'
    },
    {
      text: 'Find case',
      href: '/cases/case-search',
      roles: [
        'caseworker-caa',
        'caseworker-divorce',
        'caseworker-sscs',
        'caseworker-adoption',
        'caseworker-civil',
        'caseworker-cmc',
        'caseworker-employment',
        'caseworker-privatelaw',
        'caseworker-hrs',
        'caseworker-probate',
        'caseworker-publiclaw',
        'caseworker-publiclaw-courtadmin',
        'caseworker-st_cic',
        'caseworker-pcs'
      ]
    },
    {
      text: 'Search',
      href: '/search',
      roles: [
        'caseworker-civil',
        'caseworker-ia-caseofficer',
        'senior-tribunal-caseworker',
        'tribunal-caseworker',
        'caseworker-ia-admofficer',
        'caseworker-befta_master',
        'caseworker-privatelaw',
        'caseworker-publiclaw',
        'caseworker-st_cic',
        'caseworker-st_cic-senior-caseworker',
        'caseworker-sscs',
        'caseworker-employment',
        'caseworker-pcs'
      ]
    },
    {
      text: 'Refunds',
      href: '/refunds',
      roles: ['payments-refund-approver', 'payments-refund']
    },
    {
      text: 'Staff',
      href: '/staff',
      roles: ['staff-admin']
    }
  ]
};

export const AAT_MENU_DIFFERENCES: HeaderMenuConfig = {
  '(judge)|(judiciary)|(panelmember)': [
    {
      text: 'My work',
      roles: ['caseworker-sscs-judge', 'caseworker-sscs-panelmember']
    },
    {
      text: 'Search',
      roles: ['caseworker-sscs-judge', 'caseworker-sscs-panelmember']
    }
  ],
  '(pui-case-manager)': [
    {
      text: 'Notice of change',
      roles: [
        'caseworker-civil',
        'caseworker-civil-solictor',
        'caseworker-befta_master-solicitor'
      ]
    }
  ],
  '.+': [
    {
      text: 'My work',
      roles: ['caseworker-sscs-clerk', 'caseworker-sscs-registrar']
    },
    {
      text: 'Search',
      roles: [
        'caseworker-sscs-clerk',
        'caseworker-sscs-registrar',
        'caseworker-probate'
      ]
    }
  ]
};
