import type {
  HeaderMenuConfig,
  HeaderPhaseBannerModel,
  HeaderThemeConfig
} from './types.js';

export const DEFAULT_SKIP_LINK = {
  text: 'Skip to main content',
  href: '#content'
} as const;

export const DEFAULT_ACCOUNT_NAV_LABEL = 'Account navigation';
export const DEFAULT_ACCOUNT_ITEMS = [
  {
    id: 'sign-out',
    text: 'Sign out',
    action: 'sign-out'
  }
] as const;

export const DEFAULT_PHASE_BANNER: HeaderPhaseBannerModel = {
  visible: true,
  tag: 'beta',
  preLinkText: 'This is a new service - your',
  linkText: 'feedback',
  linkHref: 'https://www.smartsurvey.co.uk/s/CCDSurvey/',
  postLinkText: 'will help us to improve it.',
  newWindowText: '(Opens in a new window)',
  target: '_blank',
  rel: 'noopener noreferrer'
};

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

export const DEFAULT_MENU_FLAGS: Record<string, boolean | string> = {
  MC_Work_Allocation: true,
  MC_Notice_of_Change: true,
  'feature-global-search': true,
  'mc-work-allocation-active-feature': 'WorkAllocationRelease2',
  'feature-refunds': true
};

export const DEFAULT_MENU_CONFIG: HeaderMenuConfig = {
  '(judge)|(judiciary)|(panelmember)': [
    {
      text: 'My work',
      href: '/work/my-work/list',
      active: true,
      flags: [
        'MC_Work_Allocation',
        {
          flagName: 'mc-work-allocation-active-feature',
          value: 'WorkAllocationRelease2'
        }
      ],
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
      active: false,
      flags: [
        'MC_Work_Allocation',
        {
          flagName: 'mc-work-allocation-active-feature',
          value: 'WorkAllocationRelease2'
        }
      ],
      roles: ['task-supervisor']
    },
    {
      text: 'Case list',
      href: '/cases',
      active: false,
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
      active: false,
      classes: ['hmcts-search-toggle__button'],
      flags: ['feature-global-search'],
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
      active: false,
      flags: ['feature-global-search'],
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
      text: 'Find case',
      href: '/cases/case-search',
      active: false,
      align: 'right',
      classes: ['hmcts-search-toggle__button'],
      notFlags: ['feature-global-search']
    },
    {
      text: 'Work access',
      href: '/booking',
      active: false,
      roles: ['fee-paid-judge']
    },
    {
      text: '16-digit-ref-search',
      href: '/cases/case-search',
      active: true,
      align: 'right',
      classes: ['hmcts-search-toggle__button'],
      flags: ['feature-global-search']
    }
  ],
  '(pui-case-manager)': [
    {
      text: 'Case list',
      href: '/cases',
      active: false
    },
    {
      text: 'Create case',
      href: '/cases/case-filter',
      active: false
    },
    {
      text: 'Notice of change',
      href: '/noc',
      active: false,
      flags: ['MC_Notice_of_Change'],
      roles: [
        'caseworker-divorce-solicitor',
        'caseworker-probate-solicitor',
        'caseworker-privatelaw-solicitor'
      ]
    },
    {
      text: 'Find case',
      href: '/cases/case-search',
      active: false,
      align: 'right',
      classes: ['hmcts-search-toggle__button']
    }
  ],
  '.+': [
    {
      text: 'My work',
      href: '/work/my-work/list',
      active: true,
      flags: [
        'MC_Work_Allocation',
        {
          flagName: 'mc-work-allocation-active-feature',
          value: 'WorkAllocationRelease2'
        }
      ],
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
      active: false,
      flags: [
        'MC_Work_Allocation',
        {
          flagName: 'mc-work-allocation-active-feature',
          value: 'WorkAllocationRelease2'
        }
      ],
      roles: ['task-supervisor']
    },
    {
      text: 'Task list',
      href: '/tasks',
      active: false,
      flags: [
        'MC_Work_Allocation',
        {
          flagName: 'mc-work-allocation-active-feature',
          value: 'WorkAllocationRelease1'
        }
      ],
      roles: ['caseworker-ia-caseofficer']
    },
    {
      text: 'Task manager',
      href: '/tasks/task-manager',
      active: false,
      flags: [
        'MC_Work_Allocation',
        {
          flagName: 'mc-work-allocation-active-feature',
          value: 'WorkAllocationRelease1'
        }
      ],
      roles: ['caseworker-ia-caseofficer', 'task-supervisor']
    },
    {
      text: 'Case list',
      href: '/cases',
      active: false,
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
      href: '/cases/case-filter',
      active: false
    },
    {
      text: 'Find case',
      href: '/cases/case-search',
      active: false,
      classes: ['hmcts-search-toggle__button'],
      flags: ['feature-global-search'],
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
      active: false,
      flags: ['feature-global-search'],
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
      active: false,
      flags: ['feature-refunds'],
      roles: ['payments-refund-approver', 'payments-refund']
    },
    {
      text: 'Find case',
      href: '/cases/case-search',
      active: false,
      align: 'right',
      classes: ['hmcts-search-toggle__button'],
      notFlags: ['feature-global-search']
    },
    {
      text: 'Find case',
      href: '',
      active: false,
      align: 'right',
      flags: ['feature-global-search']
    },
    {
      text: 'Staff',
      href: '/staff',
      active: false,
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
      roles: ['caseworker-sscs-clerk', 'caseworker-sscs-registrar']
    }
  ]
};
