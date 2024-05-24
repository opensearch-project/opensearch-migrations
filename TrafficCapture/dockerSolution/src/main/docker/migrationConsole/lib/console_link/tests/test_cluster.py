import pytest  # type: ignore
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


def test_valid_cluster_config():
    cluster = Cluster(valid_cluster_config)
    assert isinstance(cluster, Cluster)


def test_invalid_auth_type_refused():
    invalid_auth_type = {
        "endpoint": "https://opensearchtarget:9200",
        "authorization": {
            "type": "invalid_type",
        },
    }
    with pytest.raises(ValueError) as excinfo:
        Cluster(invalid_auth_type)
    assert "Invalid config file for cluster" in excinfo.value.args[0]
    assert excinfo.value.args[1]["authorization"] == [
        {"type": ["unallowed value invalid_type"]}
    ]


def test_missing_endpoint_refused():
    missing_endpoint = {
        "allow_insecure": True,
        "authorization": {
            "type": "basic_auth",
            "details": {"username": "XXXXX", "password": "XXXXXXXXXXXXXXXXXXX!"},
        },
    }
    with pytest.raises(ValueError) as excinfo:
        Cluster(missing_endpoint)
    assert "Invalid config file for cluster" in excinfo.value.args[0]
    assert excinfo.value.args[1]["endpoint"] == ["required field"]
