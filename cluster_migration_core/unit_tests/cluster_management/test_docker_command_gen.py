from docker.types import Ulimit

import cluster_migration_core.cluster_management.docker_command_gen as dcg


def test_WHEN_gen_run_command_THEN_as_expected():
    # Set up our test
    test_args = {
        "image": "image",
        "name": "container_name",
        "network": "network_name",
        "ports": {9200: 80, 6160: 42},
        "volumes": {
            "/mydir1": {"bind": "/path", "mode": "ro"},
            "volume1": {"bind": "/path2", "mode": "rw"}
        },
        "ulimits": [
            Ulimit(name='limit', soft=1, hard=2)
        ],
        "detach": True,
        "environment": [
            "a=b",
            "c"
        ],
        "extra_hosts": {
            "name": "host"
        },
        "entrypoint": [
            "cmd 1",
            "cmd 2"
        ]
    }

    # Run our test
    generated_command = dcg.gen_docker_run(**test_args)

    # Check our results
    expected_command = ("docker run --name container_name --network network_name --publish 80:9200 --publish 42:6160"
                        " --volume /mydir1:/path:ro --volume volume1:/path2:rw --ulimit limit=1:2 --env a=b"
                        " --env c --add-host name:host --entrypoint cmd 1 --entrypoint cmd 2 --detach image")
    assert expected_command == generated_command


def test_WHEN_gen_run_command_2_THEN_as_expected():
    # Set up our test
    test_args = {
        "image": "image",
        "name": "container_name",
        "network": "network_name",
        "ports": {9200: 80, 6160: 42},
        "volumes": {},
        "ulimits": [
            Ulimit(name='limit', soft=1, hard=2)
        ],
        "detach": False,
        "environment": [
            "a=b",
            "c"
        ],
        "extra_hosts": {},
        "entrypoint": []
    }

    # Run our test
    generated_command = dcg.gen_docker_run(**test_args)

    # Check our results
    expected_command = ("docker run --name container_name --network network_name --publish 80:9200 --publish 42:6160"
                        " --ulimit limit=1:2 --env a=b --env c image")
    assert expected_command == generated_command
