define(["angular", "feed-mgr/datasources/module-name", "kylo-utils/LazyLoadUtil", "angular-ui-router"], function(angular, moduleName, lazyLoadUtil) {
    var module = angular.module(moduleName, []);

    module.config(["$stateProvider", "$compileProvider", function($stateProvider, $compileProvider) {
        //pre-assign modules until directives are rewritten to use the $onInit method.
        //https://docs.angularjs.org/guide/migration#migrating-from-1-5-to-1-6
        $compileProvider.preAssignBindingsEnabled(true);

        $stateProvider.state("datasources", {
            url: "/datasources",
            params: {},
            views: {
                "content": {
                    templateUrl: "js/feed-mgr/datasources/list.html",
                    controller: "DatasourcesListController",
                    controllerAs: "vm"
                }
            },
            resolve: {
                loadMyCtrl: lazyLoadController("feed-mgr/datasources/DatasourcesListController")
            },
            data: {
                breadcrumbRoot: true,
                displayName: "Data Sources",
                module: moduleName
            }
        });
        $stateProvider.state("datasource-details", {
            url: "/datasource-details/{datasourceId}",
            params: {
                datasourceId: null
            },
            views: {
                "content": {
                    templateUrl: "js/feed-mgr/datasources/details.html",
                    controller: "DatasourcesDetailsController",
                    controllerAs: "vm"
                }
            },
            resolve: {
                loadMyCtrl: lazyLoadController(["feed-mgr/datasources/DatasourcesDetailsController"])
            },
            data: {
                breadcrumbRoot: false,
                displayName: "Data Source Details",
                module: moduleName
            }
        });

        function lazyLoadController(path) {
            return lazyLoadUtil.lazyLoadController(path, ['feed-mgr/datasources/module-require']);
        }
    }]);
});
