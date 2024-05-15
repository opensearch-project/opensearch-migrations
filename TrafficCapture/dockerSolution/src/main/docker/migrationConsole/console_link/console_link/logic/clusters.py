from console_link.logic.instantiation import Environment


def cat_indices(env: Environment):
    cat_indices_path = '/_cat/indices?format=json'
    # source_indices = env.source_cluster.call_api(cat_indices_path)
    source_indices = {}
    target_indices = env.target_cluster.call_api(cat_indices_path)
    return source_indices, target_indices
