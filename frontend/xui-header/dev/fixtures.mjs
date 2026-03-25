export function createPreviewScenarios(buildHeaderModel) {
  return [
    {
      id: 'default-staff',
      title: 'Default staff',
      model: buildHeaderModel({
        user: {
          isAuthenticated: true,
          roles: ['caseworker-civil']
        },
        route: {
          path: '/cases'
        },
        features: {},
        environment: 'local'
      })
    },
    {
      id: 'pui-case-manager',
      title: 'PUI case manager',
      model: buildHeaderModel({
        user: {
          isAuthenticated: true,
          roles: ['pui-case-manager']
        },
        route: {
          path: '/cases'
        },
        features: {},
        environment: 'local'
      })
    },
    {
      id: 'judicial',
      title: 'Judicial',
      model: buildHeaderModel({
        user: {
          isAuthenticated: true,
          roles: ['judge', 'caseworker-ia-iacjudge']
        },
        route: {
          path: '/search'
        },
        features: {},
        environment: 'local'
      })
    },
    {
      id: 'nav-hidden',
      title: 'Navigation hidden',
      model: buildHeaderModel({
        user: {
          isAuthenticated: true,
          roles: ['caseworker-civil']
        },
        route: {
          path: '/accept-terms-and-conditions'
        },
        features: {},
        environment: 'local'
      })
    },
    {
      id: 'case-reference-search',
      title: 'Case reference search mode',
      model: buildHeaderModel({
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
      })
    }
  ];
}
