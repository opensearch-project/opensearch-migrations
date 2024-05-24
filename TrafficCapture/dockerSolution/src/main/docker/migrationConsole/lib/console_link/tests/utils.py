from console_link.models.cluster import Cluster

# Define a valid cluster configuration
valid_cluster_config = {
    "endpoint": "https://opensearchtarget:9200",
    "allow_insecure": True,
    "authorization": {
        "type": "basic_auth",
        "details": {"username": "admin", "password": "myStrongPassword123!"},
    },
}


def create_valid_cluster():
    return Cluster(valid_cluster_config)
