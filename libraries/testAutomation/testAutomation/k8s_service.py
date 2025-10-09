from subprocess import CompletedProcess

from kubernetes import client, config, stream
from kubernetes.client import V1Pod
from kubernetes.stream.ws_client import WSClient
import logging
import shlex
import subprocess
import time
from typing import Dict, List

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
        """Waits for all pods in the namespace to be in a ready state,
        ignoring completed pods, and fails after the specified timeout in seconds.
        """
        logger.info("Waiting for pods to become ready...")
        start_time = time.time()

        # Exclude Argo workflow pods by label (pods without the label only)
        argo_exclude_selector = '!workflows.argoproj.io/workflow'

        while time.time() - start_time < timeout:
            pods = self.k8s_client.list_namespaced_pod(
                namespace=self.namespace,
                label_selector=argo_exclude_selector
            ).items

            # Exclude pods that are in the "Succeeded" phase (Completed jobs)
            unhealthy_pods = [
                pod.metadata.name
                for pod in pods
                if pod.status.phase != "Succeeded" and not self._is_pod_ready(pod)
            ]
            if not unhealthy_pods:
                logger.info("All non-workflow pods are healthy.")
                return True
            logger.info(f"The following pods are not healthy yet: [{', '.join(unhealthy_pods)}]")
            time.sleep(3)

        raise TimeoutError(
            f"Timeout reached: Not all pods became healthy within {timeout} seconds. "
            f"Unhealthy pods: {', '.join(unhealthy_pods)}"
        )

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
            label_selector="app=migration-console",
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
        printable_cmd = " ".join(command_list)
        logger.info(f"Executing command [{printable_cmd}] in pod: {console_pod_id}")

        # Open a streaming connection
        resp = stream.stream(
            self.k8s_client.connect_get_namespaced_pod_exec,
            console_pod_id,
            self.namespace,
            command=command_list,
            container="console",
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

    def copy_log_files(self, destination: str):
        console_pod_id = self.get_migration_console_pod_id()
        command_list = [
            "sh",
            "-c",
            f"rm -rf {destination} && mkdir -p {destination} && "
            f"kubectl -n ma exec {console_pod_id} -- sh -c "
            f"'cd /shared-logs-output && tar -cf - fluentbit-*' | "
            f"tar -xf - -C {destination}"
        ]
        self.run_command(command=command_list, ignore_errors=True)

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

    def create_namespace(self, namespace: str) -> CompletedProcess | None:
        logger.info(f"Ensuring namespace '{namespace}' exists")

        check_cmd = ["kubectl", "get", "namespace", namespace]
        result = self.run_command(check_cmd, ignore_errors=True)

        if result is None or result.returncode != 0:
            logger.info(f"Namespace '{namespace}' not found. Creating it now...")
            create_cmd = ["kubectl", "create", "namespace", namespace]
            return self.run_command(create_cmd)
        else:
            logger.info(f"Namespace '{namespace}' already exists")
            return result

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

    def helm_install(self, chart_path: str, release_name: str,
                     values_file: str = None, values: Dict[str, str] = None) -> CompletedProcess | bool:
        helm_release_exists = self.check_helm_release_exists(release_name=release_name)
        if helm_release_exists:
            logger.info(f"Helm release {release_name} already exists, skipping install")
            return True
        logger.info(f"Installing {release_name} from {chart_path} with values {values_file}")
        command = ["helm", "install", release_name, chart_path, "-n", self.namespace, "--create-namespace"]
        if values_file:
            command.extend(["-f", values_file])
        if values:
            for key, value in values.items():
                command.extend(["--set", f"{key}={shlex.quote(str(value))}"])
        return self.run_command(command)

    def helm_uninstall(self, release_name: str) -> CompletedProcess | bool:
        helm_release_exists = self.check_helm_release_exists(release_name=release_name)
        if not helm_release_exists:
            logger.info(f"Helm release {release_name} doesn't exist, skipping uninstall")
            return True

        logger.info(f"Uninstalling {release_name}...")
        return self.run_command(["helm", "uninstall", release_name, "-n", self.namespace])

    def get_helm_installations(self) -> List[str]:
        target_namespace = self.namespace
        # Use helm list with short output format to get just the release names
        command = ["helm", "list", "-n", target_namespace, "--short"]
        
        try:
            result = self.run_command(command)
            if result and result.stdout:
                # Split the output by lines and filter out empty lines
                release_names = [line.strip() for line in result.stdout.strip().split('\n') if line.strip()]
                logger.info(f"Found {len(release_names)} helm installations in "
                            f"namespace '{target_namespace}': {release_names}")
                return release_names
            else:
                logger.info(f"No helm installations found in namespace '{target_namespace}'")
                return []
        except subprocess.CalledProcessError as e:
            logger.error(f"Failed to list helm installations in namespace '{target_namespace}': {e.stderr}")
            raise HelmCommandFailed(f"Helm list command failed: {e.stderr}")

    def get_configmaps(self) -> List[str]:
        target_namespace = self.namespace
        # Use kubectl get configmaps to get just the ConfigMap names
        command = ["kubectl", "get", "configmaps", "-n", target_namespace, "--no-headers", "-o",
                   "custom-columns=:metadata.name"]
        
        try:
            result = self.run_command(command)
            if result and result.stdout:
                # Split the output by lines and filter out empty lines
                configmap_names = [line.strip() for line in result.stdout.strip().split('\n') if line.strip()]
                logger.info(f"Found {len(configmap_names)} ConfigMaps in "
                            f"namespace '{target_namespace}': {configmap_names}")
                return configmap_names
            else:
                logger.info(f"No ConfigMaps found in namespace '{target_namespace}'")
                return []
        except subprocess.CalledProcessError as e:
            logger.error(f"Failed to list ConfigMaps in namespace '{target_namespace}': {e.stderr}")
            raise subprocess.CalledProcessError(e.returncode, e.cmd, e.stderr)

    def delete_configmap(self, configmap_name: str) -> CompletedProcess | bool:
        target_namespace = self.namespace
        
        # Check if ConfigMap exists first
        check_command = ["kubectl", "get", "configmap", configmap_name, "-n", target_namespace, "--ignore-not-found"]
        check_result = self.run_command(check_command, ignore_errors=True)
        
        if not check_result or not check_result.stdout.strip():
            logger.info(f"ConfigMap '{configmap_name}' doesn't exist in namespace '{target_namespace}', "
                        f"skipping delete")
            return True
        
        logger.info(f"Deleting ConfigMap '{configmap_name}' from namespace '{target_namespace}'...")
        delete_command = ["kubectl", "delete", "configmap", configmap_name, "-n", target_namespace]
        return self.run_command(delete_command)
