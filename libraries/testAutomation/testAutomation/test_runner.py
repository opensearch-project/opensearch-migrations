import argparse
import ast
import datetime
import json
import logging
import pandas as pd
import random
import string
import subprocess
import sys
from tabulate import tabulate
import time

from kubernetes import client, config, stream


logging.basicConfig(format='%(asctime)s [%(levelname)s] %(message)s', level=logging.INFO)
logger = logging.getLogger(__name__)

SOURCE_RELEASE_NAME = "source"
TARGET_RELEASE_NAME = "target"
MA_RELEASE_NAME = "ma"


class HelmCommandFailed(Exception):
    pass


class KubectlCommandFailed(Exception):
    pass


class TestsFailed(Exception):
    pass


class TestClusterEnvironment:
    def __init__(self, source_version: str,
                 source_helm_values_path: str,
                 source_chart_path: str,
                 target_version: str,
                 target_helm_values_path: str,
                 target_chart_path: str):

        self.source_version = source_version
        self.source_helm_values_path = source_helm_values_path
        self.source_chart_path = source_chart_path
        self.target_version = target_version
        self.target_helm_values_path = target_helm_values_path
        self.target_chart_path = target_chart_path


class K8sService:
    def __init__(self, namespace: str = "ma"):
        self.namespace = namespace
        config.load_kube_config()
        self.k8s_client = client.CoreV1Api()

    def run_command(self, command: list, stdout=subprocess.PIPE, stderr=subprocess.PIPE, ignore_errors=False):
        """Runs a command using subprocess."""
        logger.info(f"Performing command: {' '.join(command)}")
        try:
            result = subprocess.run(command, stdout=stdout, stderr=stderr, check=True, text=True)
            return result
        except subprocess.CalledProcessError as e:
            if not ignore_errors:
                logger.error(f"Error executing {' '.join(command)}: {e.stderr}")
                raise e
            logger.debug(f"Error executing {' '.join(command)}: {e.stderr}")
            return None

    def wait_for_all_healthy_pods(self, timeout=180):
        """Waits for all pods in the namespace to be in a ready state, failing after the specified timeout in seconds."""
        logger.info("Waiting for pods to become ready...")
        start_time = time.time()

        while time.time() - start_time < timeout:
            pods = self.k8s_client.list_namespaced_pod(self.namespace).items
            unhealthy_pods = [pod.metadata.name for pod in pods if not self._is_pod_ready(pod)]
            if not unhealthy_pods:
                logger.info("All pods are healthy.")
                return
            time.sleep(3)

        raise TimeoutError(f"Timeout reached: Not all pods became healthy within {timeout} seconds. "
                           f"Unhealthy pods: {', '.join(unhealthy_pods)}")

    def _is_pod_ready(self, pod):
        """Checks if a pod is in a Ready state."""
        for condition in pod.status.conditions or []:
            if condition.type == "Ready" and condition.status == "True":
                return True
        return False

    def get_migration_console_pod_id(self):
        logger.debug("Retrieving the latest migration console pod...")
        pods = self.k8s_client.list_namespaced_pod(
            namespace=self.namespace,
            label_selector=f"app={self.namespace}-migration-console",
            field_selector="status.phase=Running",
            limit=1
        ).items

        if not pods:
            raise RuntimeError("No running migration console pod found.")

        console_pod_id = pods[-1].metadata.name
        return console_pod_id

    def exec_migration_console_cmd(self, command_list: list, unbuffered=True):
        """Executes a command inside the latest migration console pod"""
        console_pod_id = self.get_migration_console_pod_id()
        logger.info(f"Executing command in pod: {console_pod_id}")

        # Open a streaming connection
        resp = stream.stream(
            self.k8s_client.connect_get_namespaced_pod_exec,
            console_pod_id,
            self.namespace,
            command=command_list,
            stderr=True,
            stdin=False,
            stdout=True,
            tty=False,
            _preload_content=(not unbuffered)  # Allow printing as output comes in
        )

        if unbuffered:
            # Read from the stream while it is open
            while resp.is_open():
                # Wait for data to be available
                resp.update(timeout=1)
                if resp.peek_stdout():
                    print(resp.read_stdout(), end="")
                if resp.peek_stderr():
                    print(resp.read_stderr(), end="")
        return resp


    def delete_all_pvcs(self):
        """Deletes all PersistentVolumeClaims (PVCs) in the namespace."""
        logger.info(f"Removing all PVCs in '{self.namespace}' namespace")
        pvcs = self.k8s_client.list_namespaced_persistent_volume_claim(self.namespace).items

        for pvc in pvcs:
            self.k8s_client.delete_namespaced_persistent_volume_claim(
                name=pvc.metadata.name,
                namespace=self.namespace
            )
            logger.debug(f"Deleted PVC: {pvc.metadata.name}")

        # Wait for PVCs to finish terminating
        timeout_seconds = 120
        poll_interval = 5  # check every 5 seconds
        start_time = time.time()

        while True:
            remaining_pvcs = self.k8s_client.list_namespaced_persistent_volume_claim(self.namespace).items
            if not remaining_pvcs:
                logger.info("All PVCs have been deleted.")
                break

            elapsed = time.time() - start_time
            if elapsed > timeout_seconds:
                raise TimeoutError(f"Timeout reached: Not all PVCs were deleted within {timeout_seconds} seconds. "
                                   f"Remaining PVCs: {', '.join(remaining_pvcs)}")

            logger.info(f"Waiting for PVCs to be deleted. Remaining: {[pvc.metadata.name for pvc in remaining_pvcs]}")
            time.sleep(poll_interval)

    def check_helm_release_exists(self, release_name):
        logger.info(f"Checking if {release_name} is already deployed in '{self.namespace}' namespace")
        check_command = ["helm", "status", release_name, "-n", self.namespace]
        status_result = self.run_command(check_command, ignore_errors=True)
        return True if status_result and status_result.returncode == 0 else False

    def helm_upgrade(self, chart_path: str, release_name: str, values_file: str = None):
        logger.info(f"Upgrading {release_name} from {chart_path} with values {values_file}")
        command = ["helm", "upgrade", release_name, chart_path, "-n", self.namespace]
        if values_file:
            command.extend(["-f", values_file])
        return self.run_command(command)

    def helm_dependency_update(self, script_path: str):
        logger.info(f"Updating Helm dependencies")
        command = [script_path]
        return self.run_command(command, stdout=None, stderr=None)

    def helm_install(self, chart_path: str, release_name: str, values_file: str = None):
        helm_release_exists = self.check_helm_release_exists(release_name=release_name)
        if helm_release_exists:
            logger.info(f"Helm release {release_name} already exists, skipping install")
            return True
        logger.info(f"Installing {release_name} from {chart_path} with values {values_file}")
        command = ["helm", "install", release_name, chart_path, "-n", self.namespace, "--create-namespace"]
        if values_file:
            command.extend(["-f", values_file])
        return self.run_command(command)

    def helm_uninstall(self, release_name):
        helm_release_exists = self.check_helm_release_exists(release_name=release_name)
        if not helm_release_exists:
            logger.info(f"Helm release {release_name} doesn't exist, skipping uninstall")
            return True

        logger.info(f"Uninstalling {release_name}...")
        return self.run_command(["helm", "uninstall", release_name, "-n", self.namespace])


class TestRunner:

    def __init__(self, k8s_service: K8sService, test_directory: str, unique_id: str, test_ids: list, ma_chart_path: str,
                 ma_chart_values_path: str, helm_dependency_script_path, test_cluster_environments: [TestClusterEnvironment]):
        self.k8s_service = k8s_service
        self.test_directory = test_directory
        self.unique_id = unique_id
        self.test_ids = test_ids
        self.ma_chart_path = ma_chart_path
        self.ma_chart_values_path = ma_chart_values_path
        self.helm_dependency_script_path = helm_dependency_script_path
        self.test_cluster_environments = test_cluster_environments

    def _print_summary_table(self, reports: list):
        test_cases = {test['name'] for report in reports for test in report['tests']}
        test_cases = sorted(test_cases)
        table_rows = []
        # Generate test matrix table
        for report in reports:
            version = f"{report['summary']['source_version']} -> {report['summary']['target_version']}"
            row = {"Version": version}
            # Initialize all test columns with an empty string.
            for case in test_cases:
                row[case] = ""
            for test in report['tests']:
                row[test['name']] = "✓" if test['result'] == "passed" else "X"
            table_rows.append(row)
        # Generate test description table
        unique_tests = {}
        for report in reports:
            for case in report["tests"]:
                name = case["name"]
                description = case["description"]
                if name not in unique_tests:
                    unique_tests[name] = description
        # Convert the dictionary to a list of lists for tabulate.
        description_table = [[name, desc] for name, desc in unique_tests.items()]

        # Create a pandas DataFrame and print
        df = pd.DataFrame(table_rows).set_index("Version")
        print("\nTest Matrix:")
        print(tabulate(df, headers="keys", tablefmt="fancy_grid"))
        print("\nDetailed Test Case Information:")
        print(tabulate(description_table, headers=["Test Name", "Description"], tablefmt="fancy_grid"))

    def run_tests(self, clusters: TestClusterEnvironment):
        """Runs pytest tests."""
        logger.info(f"Executing migration test cases with pytest and test ID filters: {self.test_ids}")
        self.k8s_service.exec_migration_console_cmd(["pipenv",
                                                     "run",
                                                     "pytest",
                                                     "/root/lib/integ_test/integ_test/ma_workflow_test.py",
                                                     f"--unique_id={self.unique_id}",
                                                     f"--test_ids={','.join(self.test_ids)}"])
        output_file_path = f"/root/lib/integ_test/results/{self.unique_id}/test_report.json"
        logger.info(f"Retrieving test report at {output_file_path}")
        cmd_response = self.k8s_service.exec_migration_console_cmd(command_list=["cat", output_file_path],
                                                                   unbuffered=False)
        test_data = ast.literal_eval(cmd_response)
        logger.debug(f"Received the following test data: {test_data}")
        tests_passed = int(test_data['summary']['passed'])
        tests_failed = int(test_data['summary']['failed'])
        print(f"Test cases passed: {tests_passed}")
        print(f"Test cases failed: {tests_failed}")
        self._print_summary_table(reports=[test_data])
        if tests_passed == 0 or tests_failed > 0:
            return False
        return True

    def cleanup_deployment(self):
        self.k8s_service.helm_uninstall(release_name=SOURCE_RELEASE_NAME)
        self.k8s_service.helm_uninstall(release_name=TARGET_RELEASE_NAME)
        self.k8s_service.helm_uninstall(release_name=MA_RELEASE_NAME)
        self.k8s_service.wait_for_all_healthy_pods()
        self.k8s_service.delete_all_pvcs()

    def run(self, skip_delete = False):
        self.k8s_service.helm_dependency_update(script_path=self.helm_dependency_script_path)
        for clusters in self.test_cluster_environments:
            try:
                logger.info(f"Performing helm deployment for migration testing environment from {clusters.source_version} to {clusters.target_version}")

                if not self.k8s_service.helm_install(chart_path=self.ma_chart_path, release_name=MA_RELEASE_NAME,
                                                     values_file=self.ma_chart_values_path):
                    raise HelmCommandFailed("Helm install of Migrations Assistant chart failed")

                if not self.k8s_service.helm_install(chart_path=clusters.source_chart_path,
                                                     release_name=SOURCE_RELEASE_NAME,
                                                     values_file=clusters.source_helm_values_path):
                    raise HelmCommandFailed("Helm install of source cluster chart failed")

                if not self.k8s_service.helm_install(chart_path=clusters.target_chart_path,
                                                     release_name=TARGET_RELEASE_NAME,
                                                     values_file=clusters.target_helm_values_path):
                    raise HelmCommandFailed("Helm install of target cluster chart failed")

                self.k8s_service.wait_for_all_healthy_pods()

                tests_passed = self.run_tests(clusters)

                if not tests_passed:
                    raise TestsFailed(f"Tests failed (or no tests executed) for upgrade from {clusters.source_version} to {clusters.target_version}.")
                else:
                    logger.info(f"Tests passed successfully for upgrade from {clusters.source_version} to {clusters.target_version}.")
            except HelmCommandFailed as helmError:
                logger.error(f"Helm command failed with error: {helmError}. Testing may be incomplete")
            except TimeoutError as timeoutError:
                logger.error(f"Timeout error encountered: {timeoutError}. Testing may be incomplete")

            if not skip_delete:
                self.cleanup_deployment()

        logger.info("Test execution completed.")


def _parse_test_ids(test_ids_str: str) -> list:
    # Split the string by commas and remove extra whitespace
    return [tid.strip() for tid in test_ids_str.split(",") if tid.strip()]


def _generate_unique_id():
    """Generate a human-readable unique ID with a timestamp and a 4-character random string."""
    timestamp = datetime.datetime.now().strftime("%Y%m%d%H%M%S")
    random_part = ''.join(random.choices(string.ascii_lowercase + string.digits, k=4))
    return f"{random_part}-{timestamp}"


def parse_args():
    parser = argparse.ArgumentParser(
        description="Process inputs for test automation runner"
    )
    parser.add_argument(
        "--source-version",
        default="ES_5.6",
        type=str,
        help="Source version e.g. ES_5.6"
    )
    parser.add_argument(
        "--target-version",
        default="OS_2.17",
        type=str,
        help="Target version e.g. OS_2.x"
    )
    parser.add_argument(
        "--skip-delete",
        action="store_true",
        help="If set, skip deletion operations."
    )
    parser.add_argument(
        "--delete-only",
        action="store_true",
        help="If set, only perform deletion operations."
    )
    parser.add_argument(
        '--unique-id',
        type=str,
        default=_generate_unique_id(),
        help="Provide a unique ID for labeling test resources, or generate one by default"
    )
    parser.add_argument(
        "--test-ids",
        type=_parse_test_ids,
        default=[],
        help="Comma-separated list of test IDs to run (e.g. 0001,0003)"
    )
    return parser.parse_args()


def main():
    args = parse_args()
    k8s_service = K8sService()
    helm_k8s_base_path = "../../deployment/k8s"
    helm_dependency_script_path = f"{helm_k8s_base_path}/update_deps.sh"
    helm_charts_base_path = f"{helm_k8s_base_path}/charts"
    ma_chart_path = f"{helm_charts_base_path}/aggregates/migrationAssistant"
    elasticsearch_cluster_chart_path = f"{helm_charts_base_path}/components/elasticsearchCluster"
    opensearch_cluster_chart_path = f"{helm_charts_base_path}/components/opensearchCluster"

    ma_chart_values_path = "es-5-values.yaml"
    es_5_6_values = f"{helm_charts_base_path}/components/elasticsearchCluster/environments/es-5-6-single-node-cluster.yaml"
    os_2_17_values = f"{helm_charts_base_path}/components/opensearchCluster/environments/os-2-latest-single-node-cluster.yaml"
    test_cluster_env = TestClusterEnvironment(source_version=args.source_version,
                                              source_helm_values_path=es_5_6_values,
                                              source_chart_path=elasticsearch_cluster_chart_path,
                                              target_version=args.target_version,
                                              target_helm_values_path=os_2_17_values,
                                              target_chart_path=opensearch_cluster_chart_path)

    test_runner = TestRunner(k8s_service=k8s_service,
                             test_directory="tests",
                             unique_id=args.unique_id,
                             test_ids=args.test_ids,
                             ma_chart_path=ma_chart_path,
                             ma_chart_values_path=ma_chart_values_path,
                             helm_dependency_script_path=helm_dependency_script_path,
                             test_cluster_environments=[test_cluster_env])

    if args.delete_only:
        return test_runner.cleanup_deployment()
    test_runner.run(skip_delete=args.skip_delete)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        # Handle Ctrl+C cleanly too
        sys.exit(0)
    except Exception as e:
        logger.error(f"Fatal error: {e}")
        sys.exit(1)