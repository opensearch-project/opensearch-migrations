#!/usr/bin/env python3
import logging
import sys
import os
import yaml
from enum import Enum
from jinja2 import Environment, FileSystemLoader
from pathlib import Path

logging.basicConfig(format='%(asctime)s [%(levelname)s] %(message)s', level=logging.INFO)
logger = logging.getLogger(__name__)

FieldType = Enum("FieldType", ["INTEGER", "BOOLEAN"])


def to_yaml_filter(value):
    """Ensures None values are empty in generated YAML"""
    def none_representer(dumper, data):
        return dumper.represent_scalar('tag:yaml.org,2002:null', '')

    yaml.add_representer(type(None), none_representer)
    return yaml.dump(value, default_flow_style=False).strip()


def pop_value(dictionary, key, default=None):
    """Remove and return a value from a nested dictionary using dot notation."""
    keys = key.split('.')
    current = dictionary

    # Navigate to the parent of the target key
    for k in keys[:-1]:
        if k not in current:
            return default
        current = current[k]

    # Pop the final key
    return current.pop(keys[-1], default)


def reconcile_dicts(default_dict, override_dict):
    return (default_dict | override_dict) if default_dict and override_dict else default_dict or override_dict or None


def add_to_dict_if_present(dict1, key_name, value, impose_type=None):
    if value:
        if impose_type:
            try:
                if impose_type == FieldType.INTEGER:
                    value = int(value)
                elif impose_type == FieldType.BOOLEAN:
                    value = False if value.casefold() == "false" else True
                else:
                    logger.error(f"Unknown type '{impose_type}' provided for key '{key_name}', "
                                 f"skipping type casting for key.")
            except Exception as e:
                logger.error(f"Unexpected error processing key '{key_name}' with provided type '{impose_type}': {e}")
        dict1[key_name] = value


def add_to_dict(new_dict, new_key, old_dict, old_key, impose_type=None):
    existing_value = old_dict.get(old_key, None)
    add_to_dict_if_present(new_dict, new_key, existing_value, impose_type)


def generate_formatted_cluster_dict(default_dict, override_dict):
    cluster_dict = reconcile_dicts(default_dict, override_dict)
    if not cluster_dict:
        return cluster_dict
    formatted_dict = {}
    add_to_dict(formatted_dict, "endpoint", cluster_dict, "endpoint")
    add_to_dict(formatted_dict, "allow_insecure", cluster_dict, "allowInsecure", FieldType.BOOLEAN)
    add_to_dict(formatted_dict, "version", cluster_dict, "version")
    auth_type = cluster_dict.get("authType", None)
    if auth_type:
        auth_dict = {}
        formatted_dict[auth_type] = auth_dict
        add_to_dict(auth_dict, "username", cluster_dict, "basicAuthUsername")
        add_to_dict(auth_dict, "password", cluster_dict, "basicAuthPassword")
        add_to_dict(auth_dict, "user_secret_arn", cluster_dict, "basicAuthUserSecretArn")
        add_to_dict(auth_dict, "region", cluster_dict, "region")
        add_to_dict(auth_dict, "service", cluster_dict, "service")
        formatted_dict[auth_type] = None if not auth_dict else auth_dict
    return formatted_dict


def generate_formatted_kafka_dict(default_dict, override_dict):
    kafka_dict = reconcile_dicts(default_dict, override_dict)
    if not kafka_dict:
        return kafka_dict
    formatted_dict = {}
    add_to_dict(formatted_dict, "broker_endpoints", kafka_dict, "brokers")
    kafka_type = kafka_dict.get("kafkaType", None)
    if kafka_type:
        formatted_dict[kafka_type] = None
    return formatted_dict


def generate_formatted_rfs_dict(namespace):
    formatted_dict = {
        'reindex_from_snapshot': {
            'k8s': {
                'namespace': namespace,
                'deployment_name': f'{namespace}-bulk-document-loader'
            }
        }
    }
    return formatted_dict


def generate_formatted_replay_dict(namespace):
    formatted_dict = {
        'k8s': {
            'namespace': namespace,
            'deployment_name': f'{namespace}-replayer'
        }
    }
    return formatted_dict


def generate_formatted_snapshot_dict(default_dict, override_dict):
    snapshot_dict = reconcile_dicts(default_dict, override_dict)
    if not snapshot_dict:
        return snapshot_dict
    formatted_dict = {}
    add_to_dict(formatted_dict, "snapshot_name", snapshot_dict, "snapshotName")
    add_to_dict(formatted_dict, "otel_endpoint", snapshot_dict, "otelEndpoint")
    snapshot_type = snapshot_dict.get("snapshotType", None)
    if snapshot_type:
        type_dict = {}
        formatted_dict[snapshot_type] = type_dict
        add_to_dict(type_dict, "repo_path", snapshot_dict, "repoPath")
        add_to_dict(type_dict, "repo_uri", snapshot_dict, "repoUri")
        add_to_dict(type_dict, "aws_region", snapshot_dict, "awsRegion")
        add_to_dict(type_dict, "role", snapshot_dict, "role")
    return formatted_dict


def generate_formatted_metrics_source_dict(default_dict, override_dict):
    observability_dict = reconcile_dicts(default_dict, override_dict)
    if not observability_dict:
        return observability_dict
    formatted_dict = {}
    observability_type = observability_dict.get("observabilityType", None)
    if observability_type:
        type_dict = {}
        formatted_dict[observability_type] = type_dict
        add_to_dict(type_dict, "endpoint", observability_dict, "endpoint")
        add_to_dict(type_dict, "aws_region", observability_dict, "awsRegion")
    return formatted_dict


def generate_formatted_metadata_dict(default_dict, override_dict):
    metadata_dict = reconcile_dicts(default_dict, override_dict)
    if not metadata_dict:
        return metadata_dict
    formatted_dict = {}
    add_to_dict(formatted_dict, "otel_endpoint", metadata_dict, "otelEndpoint")
    add_to_dict(formatted_dict, "cluster_awareness_attributes", metadata_dict,
                "clusterAwarenessAttributes", FieldType.INTEGER)
    add_to_dict(formatted_dict, "index_allowlist", metadata_dict, "indexAllowlist")
    add_to_dict(formatted_dict, "index_template_allowlist", metadata_dict, "indexTemplateAllowlist")
    add_to_dict(formatted_dict, "component_template_allowlist", metadata_dict, "componentTemplateAllowlist")
    add_to_dict(formatted_dict, "source_cluster_version", metadata_dict, "sourceClusterVersion")
    metadata_type = metadata_dict.get("metadataType", None)
    if metadata_type:
        formatted_dict[metadata_type] = None
    return formatted_dict


def generate_formatted_client_options_dict(default_dict, override_dict):
    client_options_dict = reconcile_dicts(default_dict, override_dict)
    if not client_options_dict:
        return client_options_dict
    formatted_dict = {}
    add_to_dict(formatted_dict, "user_agent_extra", client_options_dict, "userAgentExtra")
    return formatted_dict


class YAMLTemplateConverter:
    def __init__(self, namespace, template_dir=Path(__file__).parent, template_file='migration_services.yaml.j2'):
        """
        Initialize the converter with template directory and file.

        Args:
            namespace (str): Namespace of the Migration Assistant resources
            template_dir (str): Directory containing the template files
            template_file (str): Name of the template file
        """
        self.namespace = namespace
        self.template_dir = template_dir
        self.template_file = template_file

    def convert(self, in_stream, out_stream):
        # Read YAML from stdin
        values = yaml.safe_load(in_stream)

        # Setup Jinja2 environment
        env = Environment(loader=FileSystemLoader(self.template_dir))
        env.filters['to_yaml'] = to_yaml_filter
        env.filters['pop_value'] = pop_value

        template = env.get_template(self.template_file)
        parsed_values = {}
        add_to_dict_if_present(parsed_values, "source_cluster",
                               generate_formatted_cluster_dict(values.get("source-cluster-default", None),
                                                               values.get("source-cluster", None)))
        add_to_dict_if_present(parsed_values, "target_cluster",
                               generate_formatted_cluster_dict(values.get("target-cluster-default", None),
                                                               values.get("target-cluster", None)))
        add_to_dict_if_present(parsed_values, "kafka",
                               generate_formatted_kafka_dict(values.get("kafka-brokers-default", None),
                                                             values.get("kafka-brokers", None)))
        add_to_dict_if_present(parsed_values, "backfill", generate_formatted_rfs_dict(self.namespace))
        add_to_dict_if_present(parsed_values, "replay", generate_formatted_replay_dict(self.namespace))
        add_to_dict_if_present(parsed_values, "snapshot",
                               generate_formatted_snapshot_dict(values.get("snapshot-default", None),
                                                                values.get("snapshot", None)))
        add_to_dict_if_present(parsed_values, "metrics_source",
                               generate_formatted_metrics_source_dict(values.get("metrics-source-default", None),
                                                                      values.get("metrics-source", None)))
        add_to_dict_if_present(parsed_values, "metadata",
                               generate_formatted_metadata_dict(values.get("metadata-migration-default", None),
                                                                values.get("metadata-migration", None)))
        add_to_dict_if_present(parsed_values, "client_options",
                               generate_formatted_client_options_dict(values.get("client-options-default", None),
                                                                      values.get("client-options", None)))
        out_stream.write(template.render(values=parsed_values))


def main():
    template_path = sys.argv[1] if len(sys.argv) > 1 else 'migration_services.yaml.j2'
    template_dir = os.path.dirname(template_path) or Path(__file__).parent
    template_file = os.path.basename(template_path)

    try:
        YAMLTemplateConverter(template_dir, template_file).convert(sys.stdin, sys.stdout)
    except yaml.YAMLError as e:
        logger.error(f"Error parsing YAML input: {e}")
        sys.exit(1)
    except Exception as e:
        logger.error(f"Error: {e}")
        sys.exit(1)


if __name__ == '__main__':
    main()
