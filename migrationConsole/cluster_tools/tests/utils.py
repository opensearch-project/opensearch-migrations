import time
from cluster_tools.base.utils import console_curl


def refresh_cluster(env, cluster):
    """Refresh a cluster to make sure all changes are visible."""
    console_curl(
        env=env,
        path="/_refresh",
        cluster=cluster,
        method='POST'
    )


def get_index_info(env, index_name, cluster):
    """Get information about an index in the specified cluster."""
    return console_curl(
        env=env,
        path=f"/{index_name}",
        cluster=cluster
    )


def wait_for_document(env, index_name, doc_id, cluster='target_cluster', max_retries=10, retry_interval=1):
    """Wait for a document to be available in the specified cluster."""
    for i in range(max_retries):
        try:
            response = console_curl(
                env=env,
                path=f"/{index_name}/_doc/{doc_id}",
                cluster=cluster
            )
            if response.get("found", False):
                return response
        except Exception:
            pass

        time.sleep(retry_interval)

    raise TimeoutError(f"Document {doc_id} not found in {index_name} after {max_retries * retry_interval} seconds")


# Legacy function aliases for backward compatibility
def source_cluster_refresh(env):
    """Legacy function for backward compatibility."""
    return refresh_cluster(env, 'source_cluster')


def target_cluster_refresh(env):
    """Legacy function for backward compatibility."""
    return refresh_cluster(env, 'target_cluster')


def get_source_index_info(env, index_name):
    """Legacy function for backward compatibility."""
    return get_index_info(env, index_name, 'source_cluster')


def get_target_index_info(env, index_name):
    """Legacy function for backward compatibility."""
    return get_index_info(env, index_name, 'target_cluster')
