from typing import Dict, Optional

from console_link.models.cluster import AuthMethod, Cluster


def create_valid_cluster(endpoint: str = "https://opensearchtarget:9200",
                         allow_insecure: bool = True,
                         auth_type: AuthMethod = AuthMethod.BASIC_AUTH,
                         details: Optional[Dict] = None):

    if details is None and auth_type == AuthMethod.BASIC_AUTH:
        details = {"username": "admin", "password": "myStrongPassword123!"}

    custom_cluster_config = {
        "endpoint": endpoint,
        "allow_insecure": allow_insecure,
        auth_type.name.lower(): details if details else {}
    }
    return Cluster(custom_cluster_config)
