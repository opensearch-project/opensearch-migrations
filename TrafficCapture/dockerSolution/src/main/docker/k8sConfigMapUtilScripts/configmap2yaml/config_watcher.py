import argparse
from format_services_yaml import YAMLTemplateConverter
from io import StringIO
from kubernetes import client, config, watch
import logging
import os
import signal
import tempfile
from typing import Dict, Any
import sys
import yaml


logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class ConfigMapWatcher:
    def __init__(self, label_selector: str, namespace: str, output_file: str):
        self.label_selector = label_selector
        self.namespace = namespace
        self.output_file = output_file
        self.current_data: Dict[str, Any] = {}
        self.formatter = YAMLTemplateConverter()

        # Validate output file path
        output_dir = os.path.dirname(output_file)
        if not os.path.exists(output_dir):
            raise ValueError(f"Output directory does not exist: {output_dir}")
        if not os.access(output_dir, os.W_OK):
            raise ValueError(f"Output directory is not writable: {output_dir}")

        try:
            config.load_incluster_config()
        except config.ConfigException:
            logger.warning("Unable to load in-cluster config, falling back to local kubeconfig")
            config.load_kube_config()

        self.v1 = client.CoreV1Api()

    def update_yaml_file(self) -> None:
        """Update the output YAML file with new ConfigMap data"""
        try:
            # Create a temporary file in the same directory as the target file
            output_dir = os.path.dirname(self.output_file)
            with tempfile.NamedTemporaryFile(mode='w', dir=output_dir, delete=False) as temp_file:
                YAMLTemplateConverter().convert(StringIO(yaml.safe_dump(self.current_data)), temp_file)
                temp_file.flush()
                os.fsync(temp_file.fileno())  # Ensure all data is written to disk

            # Atomic rename
            os.rename(temp_file.name, self.output_file)
            logger.info(f"Updated {self.output_file} with new configuration")
        except Exception as e:
            logger.error(f"Error updating YAML file: {e}")
            # Clean up temporary file if it exists
            if 'temp_file' in locals():
                try:
                    os.unlink(temp_file.name)
                except OSError:
                    pass
            raise

    def watch_configmaps(self) -> None:
        """Watch ConfigMaps for changes and write the contents at startup and upon an configMap changes"""
        w = watch.Watch()

        # First, get existing ConfigMaps
        logger.info(f"Loading existing ConfigMaps for {self.namespace} and {self.label_selector}")
        existing_configmaps = self.v1.list_namespaced_config_map(
            namespace=self.namespace,
            label_selector=self.label_selector
        )
        logger.info(f"Got configmaps: {existing_configmaps}")
        for configmap in existing_configmaps.items:
            logger.info(f"configmap={configmap}")
            self.current_data[configmap.metadata.name] = configmap.data if configmap.data else {}

        self.update_yaml_file()

        # Then watch for changes
        try:
            for event in w.stream(
                    self.v1.list_namespaced_config_map,
                    namespace=self.namespace,
                    label_selector=self.label_selector
            ):
                configmap = event['object']
                event_type = event['type']

                if event_type in ['ADDED', 'MODIFIED']:
                    self.current_data[configmap.metadata.name] = configmap.data if configmap.data else {}
                elif event_type == 'DELETED':
                    name = configmap.metadata.name
                    if name in self.current_data:
                        logger.info(f"Removing ConfigMap: {name}")
                        del self.current_data[name]

                self.update_yaml_file()

        except Exception as e:
            logger.error(f"Error watching ConfigMaps: {e}")
            raise


def parse_args():
    parser = argparse.ArgumentParser(
        description='Watch Kubernetes ConfigMaps and update a YAML file'
    )
    parser.add_argument(
        '--outfile',
        required=True,
        help='Path to output YAML file (required)'
    )
    parser.add_argument(
        '--label-selector',
        default=os.getenv('LABEL_SELECTOR', ''),
        help='Label selector for ConfigMaps'
    )
    parser.add_argument(
        '--namespace',
        default=os.getenv('NAMESPACE', 'default'),
        help='Kubernetes namespace (default: default)'
    )
    return parser.parse_args()


def sigterm_handler(signum, frame):
    # Clean exit without traceback
    sys.exit(0)


if __name__ == "__main__":
    args = parse_args()

    # Register the signal handler
    try:
        signal.signal(signal.SIGTERM, sigterm_handler)
        watcher = ConfigMapWatcher(
            label_selector=args.label_selector,
            namespace=args.namespace,
            output_file=args.outfile
        )
        watcher.watch_configmaps()
    except KeyboardInterrupt:
        # Handle Ctrl+C cleanly too
        sys.exit(0)
    except Exception as e:
        logger.error(f"Fatal error: {e}")
        sys.exit(1)
