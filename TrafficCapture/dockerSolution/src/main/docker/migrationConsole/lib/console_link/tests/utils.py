from typing import Dict, Optional

from console_link.models.cluster import AuthMethod, Cluster


def create_valid_cluster(endpoint: str = "https://opensearchtarget:9200",
                         allow_insecure: bool = True,
                         auth_type: AuthMethod = AuthMethod.BASIC_AUTH,
                         details: Optional[Dict] = None):
    if details is None:
        details = {"username": "admin", "password": "myStrongPassword123!"}

    auth_block = {
        AuthMethod.NO_AUTH: {},
        AuthMethod.BASIC_AUTH: details,
        AuthMethod.SIGV4: {}
    }

    custom_cluster_config = {
        "endpoint": endpoint,
        "allow_insecure": allow_insecure,
        auth_type.name.lower(): auth_block[auth_type]
    }
    return Cluster(custom_cluster_config)
