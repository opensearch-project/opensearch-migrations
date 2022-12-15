from upgrade_testing_framework.cluster_management.node_configuration import NodeConfiguration
import upgrade_testing_framework.core.versions_engine as ev

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
        "cluster.name": test_cluster_name,
        "cluster.initial_master_nodes": ",".join(test_master_nodes),
        "discovery.seed_hosts": ",".join(test_seed_hosts),
        "node.name": test_node_name,
        "bootstrap.memory_lock": "true",
        "k1": "v1"
    }
    assert expected_value == actual_value.config

    assert "/usr/share/elasticsearch/data" == actual_value.data_dir

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
        "cluster.name": test_cluster_name,
        "cluster.initial_master_nodes": ",".join(test_master_nodes),
        "discovery.seed_hosts": ",".join(test_seed_hosts),
        "node.name": test_node_name,
        "bootstrap.memory_lock": "true",
        "k1": "v1"
    }
    assert expected_value == actual_value.config

    assert "/usr/share/opensearch/data" == actual_value.data_dir
