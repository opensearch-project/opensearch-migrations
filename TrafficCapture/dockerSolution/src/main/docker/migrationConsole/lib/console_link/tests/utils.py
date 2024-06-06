from console_link.models.cluster import Cluster, AuthMethod


def create_valid_cluster(endpoint="https://opensearchtarget:9200",
                         allow_insecure=True,
                         auth_type=AuthMethod.BASIC_AUTH.name.lower(),
                         details=None):
    if details is None:
        details = {"username": "admin", "password": "myStrongPassword123!"}

    custom_cluster_config = {
        "endpoint": endpoint,
        "allow_insecure": allow_insecure,
        "authorization": {
            "type": auth_type,
            "details": details,
        },
    }
    return Cluster(custom_cluster_config)
