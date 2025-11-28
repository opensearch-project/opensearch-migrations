from console_link.models.cluster import Cluster, HttpMethod

from .common_utils import execute_api_call
from .default_operations import DefaultOperationsLibrary


class OpensearchV1_XOperationsLibrary(DefaultOperationsLibrary):
    pass


class OpensearchV2_XOperationsLibrary(DefaultOperationsLibrary):

    def clear_index_templates(self, cluster: Cluster, **kwargs):
        # Remove legacy templates
        execute_api_call(cluster=cluster, method=HttpMethod.DELETE, path="/_template/*", **kwargs)
        # Remove composable index templates
        composable_template_names = self.get_all_composable_index_template_names(cluster=cluster, **kwargs)
        for name in composable_template_names:
            execute_api_call(cluster=cluster, method=HttpMethod.DELETE, path=f"/_index_template/{name}", **kwargs)


class OpensearchV3_XOperationsLibrary(DefaultOperationsLibrary):

    def clear_index_templates(self, cluster: Cluster, **kwargs):
        # Remove legacy templates
        execute_api_call(cluster=cluster, method=HttpMethod.DELETE, path="/_template/*", **kwargs)
        # Remove composable index templates
        composable_template_names = self.get_all_composable_index_template_names(cluster=cluster, **kwargs)
        for name in composable_template_names:
            execute_api_call(cluster=cluster, method=HttpMethod.DELETE, path=f"/_index_template/{name}", **kwargs)
