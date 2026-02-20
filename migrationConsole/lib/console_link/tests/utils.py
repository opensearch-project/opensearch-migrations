from typing import Dict, Optional

from console_link.models.cluster import AuthMethod, Cluster
from console_link.models.client_options import ClientOptions


def create_valid_cluster(endpoint: str = "https://opensearchtarget:9200",
                         allow_insecure: bool = True,
                         auth_type: AuthMethod = AuthMethod.BASIC_AUTH,
                         version: Optional[str] = None,
                         details: Optional[Dict] = None,
                         client_options: Optional[ClientOptions] = None):

    if details is None and auth_type == AuthMethod.BASIC_AUTH:
        details = {"username": "admin", "password": "myStrongPassword123!"}

    custom_cluster_config = {
        "endpoint": endpoint,
        "allow_insecure": allow_insecure,
        auth_type.name.lower(): details if details else {}
    }
    if version:
        custom_cluster_config["version"] = version
    return Cluster(config=custom_cluster_config, client_options=client_options)
