from pathlib import Path

import yaml


CLUSTER_WORKFLOWS = (
    Path(__file__).resolve().parents[1]
    / "testWorkflows/clusterWorkflows.yaml"
)


def test_starved_opensearch_2_19_reuses_single_node_template():
    document = yaml.safe_load(CLUSTER_WORKFLOWS.read_text())
    templates = {
        template["name"]: template
        for template in document["spec"]["templates"]
    }

    base = templates["opensearch-2-19-single-node"]
    defaults = {
        parameter["name"]: parameter.get("default")
        for parameter in base["inputs"]["parameters"]
    }
    assert defaults["opensearch-config-extra"] == ""
    assert defaults["cpu"] == "2"
    assert defaults["memory"] == "4Gi"
    assert defaults["heap"] == "2g"
    script = base["container"]["args"][0]
    assert "{{inputs.parameters.opensearch-config-extra}}" in script
    assert script.count("{{inputs.parameters.cpu}}") == 2
    assert script.count("{{inputs.parameters.memory}}") == 2
    assert "-Xms{{inputs.parameters.heap}} -Xmx{{inputs.parameters.heap}}" in script

    starved = templates["opensearch-2-19-starved-single-node"]
    assert "container" not in starved
    deploy = starved["steps"][0][0]
    assert deploy["template"] == "opensearch-2-19-single-node"
    arguments = {
        parameter["name"]: parameter["value"]
        for parameter in deploy["arguments"]["parameters"]
    }
    assert arguments["cpu"] == "1"
    assert arguments["memory"] == "2Gi"
    assert arguments["heap"] == "1g"
    assert arguments["opensearch-config-extra"].splitlines() == [
        "thread_pool.write.size: 1",
        "thread_pool.write.queue_size: 10",
    ]
