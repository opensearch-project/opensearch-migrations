from subprocess import CompletedProcess

from kubernetes import client, config, stream
from kubernetes.client import V1Pod
from kubernetes.stream.ws_client import WSClient
import logging
import subprocess
import time
from typing import List

logging.basicConfig(format='%(asctime)s [%(levelname)s] %(message)s', level=logging.INFO)
logger = logging.getLogger(__name__)


class HelmCommandFailed(Exception):
    pass


class K8sService:
    def __init__(self, namespace: str = "ma") -> None:
        self.namespace = namespace
        config.load_kube_config()
        self.k8s_client = client.CoreV1Api()

    def run_command(self, command: List,
                    stdout: int | None = subprocess.PIPE,
                    stderr: int | None = subprocess.PIPE,
                    ignore_errors: bool = False) -> CompletedProcess | None:
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

    def wait_for_all_healthy_pods(self, timeout: int = 180) -> bool:
        """Waits for all pods in the namespace to be in a ready state, fails after the specified timeout in seconds."""
        logger.info("Waiting for pods to become ready...")
        start_time = time.time()

        while time.time() - start_time < timeout:
            pods = self.k8s_client.list_namespaced_pod(self.namespace).items
            unhealthy_pods = [pod.metadata.name for pod in pods if not self._is_pod_ready(pod)]
            if not unhealthy_pods:
                logger.info("All pods are healthy.")
                return True
            time.sleep(3)

        raise TimeoutError(f"Timeout reached: Not all pods became healthy within {timeout} seconds. "
                           f"Unhealthy pods: {', '.join(unhealthy_pods)}")

    def _is_pod_ready(self, pod: V1Pod) -> bool:
        """Checks if a pod is in a Ready state."""
        for condition in pod.status.conditions or []:
            if condition.type == "Ready" and condition.status == "True":
                return True
        return False

    def get_migration_console_pod_id(self) -> str:
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

    def exec_migration_console_cmd(self, command_list: List, unbuffered: bool = True) -> str | WSClient:
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

    def delete_all_pvcs(self) -> None:
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

    def check_helm_release_exists(self, release_name: str) -> bool:
        logger.info(f"Checking if {release_name} is already deployed in '{self.namespace}' namespace")
        check_command = ["helm", "status", release_name, "-n", self.namespace]
        status_result = self.run_command(check_command, ignore_errors=True)
        return True if status_result and status_result.returncode == 0 else False

    def helm_upgrade(self, chart_path: str, release_name: str, values_file: str = None) -> CompletedProcess:
        logger.info(f"Upgrading {release_name} from {chart_path} with values {values_file}")
        command = ["helm", "upgrade", release_name, chart_path, "-n", self.namespace]
        if values_file:
            command.extend(["-f", values_file])
        return self.run_command(command)

    def helm_dependency_update(self, script_path: str) -> CompletedProcess:
        logger.info("Updating Helm dependencies")
        command = [script_path]
        return self.run_command(command, stdout=None, stderr=None)

    def helm_install(self, chart_path: str, release_name: str,
                     values_file: str = None) -> CompletedProcess | bool:
        helm_release_exists = self.check_helm_release_exists(release_name=release_name)
        if helm_release_exists:
            logger.info(f"Helm release {release_name} already exists, skipping install")
            return True
        logger.info(f"Installing {release_name} from {chart_path} with values {values_file}")
        command = ["helm", "install", release_name, chart_path, "-n", self.namespace, "--create-namespace"]
        if values_file:
            command.extend(["-f", values_file])
        return self.run_command(command)

    def helm_uninstall(self, release_name: str) -> CompletedProcess | bool:
        helm_release_exists = self.check_helm_release_exists(release_name=release_name)
        if not helm_release_exists:
            logger.info(f"Helm release {release_name} doesn't exist, skipping uninstall")
            return True

        logger.info(f"Uninstalling {release_name}...")
        return self.run_command(["helm", "uninstall", release_name, "-n", self.namespace])
