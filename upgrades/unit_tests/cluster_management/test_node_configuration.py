from upgrade_testing_framework.cluster_management.node_configuration import NodeConfiguration
import upgrade_testing_clients.versions_engine as ev

def test_WHEN_create_NodeConfiguration_AND_elasticsearch_THEN_has_expected_values():
    # Set up our test
    test_engine_version = ev.EngineVersion(ev.ENGINE_ELASTICSEARCH, 7, 10, 2)
    test_node_name = "node-name"
    test_cluster_name = "cluster-name"
    test_master_nodes = ["m1", "m2"]
    test_seed_hosts = ["s1", "s2"]
    test_additional_config = {"k1": "v1"}

    # Run our test
    actual_value = NodeConfiguration(test_engine_version, test_node_name, test_cluster_name, test_master_nodes, test_seed_hosts, test_additional_config)

    # Check the results
    expected_value = {
        "config": {
            "cluster.name": test_cluster_name,
            "cluster.initial_master_nodes": ",".join(test_master_nodes),
            "discovery.seed_hosts": ",".join(test_seed_hosts),
            "node.name": test_node_name,
            "bootstrap.memory_lock": "true",
            "k1": "v1"
        },
        "data_dir": "/usr/share/elasticsearch/data",
        "engine_version": str(test_engine_version),
        "user": "elasticsearch"
    }
    assert expected_value == actual_value.to_dict()

def test_WHEN_create_NodeConfiguration_AND_opensearch_THEN_has_expected_values():
    # Set up our test
    test_engine_version = ev.EngineVersion(ev.ENGINE_OPENSEARCH, 1, 3, 6)
    test_node_name = "node-name"
    test_cluster_name = "cluster-name"
    test_master_nodes = ["m1", "m2"]
    test_seed_hosts = ["s1", "s2"]
    test_additional_config = {"k1": "v1"}

    # Run our test
    actual_value = NodeConfiguration(test_engine_version, test_node_name, test_cluster_name, test_master_nodes, test_seed_hosts, test_additional_config)

    # Check the results
    expected_value = {
        "config": {
            "cluster.name": test_cluster_name,
            "cluster.initial_master_nodes": ",".join(test_master_nodes),
            "discovery.seed_hosts": ",".join(test_seed_hosts),
            "node.name": test_node_name,
            "bootstrap.memory_lock": "true",
            "k1": "v1"
        },
        "data_dir": "/usr/share/opensearch/data",
        "engine_version": str(test_engine_version),
        "user": "elasticsearch"
    }
    assert expected_value == actual_value.to_dict()
