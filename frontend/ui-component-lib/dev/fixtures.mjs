const DEFAULT_XUI_BASE_URL = 'http://localhost:3000';

export function createPreviewScenarios(buildHeaderModel, buildFooterModel) {
  const footerModel = buildFooterModel();

  return [
    {
      id: 'live-reference-case-list',
      title: 'Live reference case list',
      headerModel: buildHeaderModel({
        xuiBaseUrl: DEFAULT_XUI_BASE_URL,
        user: {
            roles: ['caseworker', 'caseworker-befta_master'],
        },
      }),
      footerModel,
      mainContent: {
        title: 'Case list'
      }
    },
    {
      id: 'default-staff',
      title: 'Default staff',
      headerModel: buildHeaderModel({
        xuiBaseUrl: DEFAULT_XUI_BASE_URL,
        user: {
            roles: ['caseworker-civil']
        },
      }),
      footerModel,
      mainContent: {
        title: 'Case list'
      }
    },
    {
      id: 'pui-case-manager',
      title: 'PUI case manager',
      headerModel: buildHeaderModel({
        xuiBaseUrl: DEFAULT_XUI_BASE_URL,
        user: {
            roles: ['pui-case-manager']
        },
      }),
      footerModel,
      mainContent: {
        title: 'Case list'
      }
    },
    {
      id: 'judicial',
      title: 'Judicial',
      headerModel: buildHeaderModel({
        xuiBaseUrl: DEFAULT_XUI_BASE_URL,
        user: {
            roles: ['caseworker', 'caseworker-divorce', 'caseworker-divorce-judge']
        },
      }),
      footerModel,
      mainContent: {
        title: 'Case list'
      }
    },
    {
      id: 'nav-hidden',
      title: 'Navigation hidden',
      headerModel: buildHeaderModel({
        xuiBaseUrl: DEFAULT_XUI_BASE_URL,
        user: {
            roles: ['caseworker-civil']
        },
      }),
      footerModel,
      mainContent: {
        title: 'Terms and conditions'
      }
    },
    {
      id: 'case-reference-search',
      title: 'Case reference search mode',
      headerModel: buildHeaderModel({
        xuiBaseUrl: DEFAULT_XUI_BASE_URL,
        user: {
            roles: ['caseworker-civil']
        },
      }),
      footerModel,
      mainContent: {
        title: 'Search'
      }
    }
  ];
}
