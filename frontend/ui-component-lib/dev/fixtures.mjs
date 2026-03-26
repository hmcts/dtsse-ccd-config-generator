function createFooterContext(overrides = {}) {
  return {
    welshLanguageToggleEnabled: false,
    currentLanguage: 'en',
    termsAndConditionsFeatureEnabled: false,
    ...overrides
  };
}

export function createPreviewScenarios(buildHeaderModel, buildFooterModel) {
  return [
    {
      id: 'live-reference-case-list',
      title: 'Live reference case list',
      headerModel: buildHeaderModel({
        user: {
          isAuthenticated: true,
          roles: ['caseworker', 'caseworker-befta_master'],
          roleCategory: 'legal-operations'
        },
        route: {
          path: '/cases'
        },
        features: {},
        environment: 'local'
      }),
      footerModel: buildFooterModel(createFooterContext()),
      mainContent: {
        title: 'Case list'
      }
    },
    {
      id: 'default-staff',
      title: 'Default staff',
      headerModel: buildHeaderModel({
        user: {
          isAuthenticated: true,
          roles: ['caseworker-civil']
        },
        route: {
          path: '/cases'
        },
        features: {},
        environment: 'local'
      }),
      footerModel: buildFooterModel(createFooterContext()),
      mainContent: {
        title: 'Case list'
      }
    },
    {
      id: 'pui-case-manager',
      title: 'PUI case manager',
      headerModel: buildHeaderModel({
        user: {
          isAuthenticated: true,
          roles: ['pui-case-manager']
        },
        route: {
          path: '/cases'
        },
        features: {},
        environment: 'local'
      }),
      footerModel: buildFooterModel(createFooterContext()),
      mainContent: {
        title: 'Case list'
      }
    },
    {
      id: 'judicial',
      title: 'Judicial',
      headerModel: buildHeaderModel({
        user: {
          isAuthenticated: true,
          roles: ['judge', 'caseworker-ia-iacjudge']
        },
        route: {
          path: '/search'
        },
        features: {},
        environment: 'local'
      }),
      footerModel: buildFooterModel(createFooterContext()),
      mainContent: {
        title: 'Search'
      }
    },
    {
      id: 'nav-hidden',
      title: 'Navigation hidden',
      headerModel: buildHeaderModel({
        user: {
          isAuthenticated: true,
          roles: ['caseworker-civil']
        },
        route: {
          path: '/accept-terms-and-conditions'
        },
        features: {},
        environment: 'local'
      }),
      footerModel: buildFooterModel(createFooterContext()),
      mainContent: {
        title: 'Terms and conditions'
      }
    },
    {
      id: 'case-reference-search',
      title: 'Case reference search mode',
      headerModel: buildHeaderModel({
        user: {
          isAuthenticated: true,
          roles: ['caseworker-civil']
        },
        route: {
          path: '/cases'
        },
        features: {
          'feature-global-search': true
        },
        environment: 'local'
      }),
      footerModel: buildFooterModel(createFooterContext({
        welshLanguageToggleEnabled: true
      })),
      mainContent: {
        title: 'Search'
      }
    },
    {
      id: 'footer-with-help',
      title: 'Footer with help content',
      headerModel: buildHeaderModel({
        user: {
          isAuthenticated: true,
          roles: ['caseworker-civil']
        },
        route: {
          path: '/cases'
        },
        features: {},
        environment: 'local'
      }),
      footerModel: buildFooterModel(createFooterContext({
        help: {
          heading: 'Get help with Probate',
          probate: { text: 'Support is available if you need help using this service.' },
          email: {
            address: 'contactprobate@justice.gov.uk',
            text: 'contactprobate@justice.gov.uk'
          },
          phone: { text: '0300 303 0648' },
          opening: { text: 'Monday to Friday, 9am to 1pm (Closed on bank holidays)' },
          otherContact: { text: 'For other services, use the Get help link below.' }
        }
      })),
      mainContent: {
        title: 'Footer preview'
      }
    }
  ];
}
