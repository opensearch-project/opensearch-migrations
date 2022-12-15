import upgrade_testing_framework.cluster_management.docker_framework_client as dfc
from upgrade_testing_framework.cluster_management.container_configuration import ContainerConfiguration

def test_WHEN_create_ContainerConfiguration_THEN_extracts_rest_port():
    # Set up our test
    test_port = 9201
    test_container_config = ContainerConfiguration(
        "",
        None,
        [dfc.PortMapping(1, 1), dfc.PortMapping(9200, test_port)],
        []
    )

    # Run our test
    actual_value = test_container_config.rest_port

    # Check the results
    assert test_port == actual_value
