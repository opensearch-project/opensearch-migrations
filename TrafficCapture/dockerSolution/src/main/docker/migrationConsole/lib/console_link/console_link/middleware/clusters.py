from console_link.models.cluster import Cluster


def cat_indices(cluster: Cluster, as_json=False):
    as_json_suffix = "?format=json" if as_json else ""
    cat_indices_path = f"/_cat/indices{as_json_suffix}"
    r = cluster.call_api(cat_indices_path)
    return r.json() if as_json else r.content
