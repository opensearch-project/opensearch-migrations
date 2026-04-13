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
    def __init__(self, namespace: str = "ma", kube_context: str = None) -> None:
        self.namespace = namespace
        self.kube_context = kube_context
        config.load_kube_config(context=kube_context)
        self.k8s_client = client.CoreV1Api()

    def _kubectl_base(self) -> List[str]:
        """Return kubectl command prefix with optional --context."""
        cmd = ["kubectl"]
        if self.kube_context:
            cmd.append(f"--context={self.kube_context}")
        return cmd

    def _helm_base(self) -> List[str]:
        """Return helm command prefix with optional --kube-context."""
        cmd = ["helm"]
        if self.kube_context:
            cmd.append(f"--kube-context={self.kube_context}")
        return cmd

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
        """Checks if pod is Running and all containers are ready."""
        if pod.status.phase != "Running":
            return False
        # Check all init containers completed
        for status in pod.status.init_container_statuses or []:
            if not status.ready and not (status.state and status.state.terminated):
                return False
        # Check all containers ready
        for status in pod.status.container_statuses or []:
            if not status.ready:
                return False
        return True

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

    def exec_background_cmd(self, command_list: List, log_file: str, exit_code_file: str) -> None:
        """Launch a command in the background inside the migration console pod.

        The command's output is written to log_file so Jenkins can tail it.
        On completion, the exit code is written to exit_code_file.
        """
        inner_cmd = " ".join(shlex.quote(arg) for arg in command_list)
        # Clean up stale files from any previous run
        self.exec_migration_console_cmd(
            command_list=["sh", "-c", f"rm -f {log_file} {exit_code_file}"],
            unbuffered=False)
        wrapper = (
            f"nohup sh -c '"
            f"{inner_cmd} > {log_file} 2>&1; "
            f"echo $? > {exit_code_file}"
            f"' > /dev/null 2>&1 &"
        )
        self.exec_migration_console_cmd(command_list=["sh", "-c", wrapper], unbuffered=False)
        time.sleep(2)
        check = self.exec_migration_console_cmd(
            command_list=["sh", "-c", f"test -f {log_file} && echo ok || echo missing"],
            unbuffered=False)
        if "missing" in (check or ""):
            raise RuntimeError(f"Background command failed to start — {log_file} not created")
        logger.info(f"Background command launched. Log: {log_file}, exit code: {exit_code_file}")

    def poll_cmd_completion(self, log_file: str, exit_code_file: str,
                            poll_interval: int = 30, timeout: int = 0) -> int:
        """Poll until a background command completes, tailing its log for Jenkins console output.

        Returns the exit code of the background command.
        """
        start_time = time.time()
        next_byte = 0
        while True:
            if timeout > 0 and (time.time() - start_time) > timeout:
                raise TimeoutError(f"Background command did not complete within {timeout}s")
            time.sleep(poll_interval)

            # Print new log bytes since last poll (no filtering/rewrapping for Jenkins parity)
            try:
                resp = self.exec_migration_console_cmd(
                    command_list=["tail", "-c", f"+{next_byte + 1}", log_file],
                    unbuffered=False)
                if resp:
                    print(resp, end="", flush=True)
                    next_byte += len(resp.encode("utf-8"))
            except Exception as e:
                logger.info(f"Log tail failed (may be transient): {e}")

            # Check if the command has finished
            try:
                result = self.exec_migration_console_cmd(
                    command_list=["cat", exit_code_file], unbuffered=False)
                if result and result.strip():
                    exit_code = int(result.strip())
                    # Print any remaining log output
                    try:
                        resp = self.exec_migration_console_cmd(
                            command_list=["tail", "-c", f"+{next_byte + 1}", log_file],
                            unbuffered=False)
                        if resp:
                            print(resp, end="", flush=True)
                    except Exception:
                        pass
                    logger.info(f"Background command completed with exit code: {exit_code}")
                    return exit_code
            except Exception:
                # exit_code_file doesn't exist yet — command still running
                pass

    def exec_migration_console_cmd(self, command_list: List, unbuffered: bool = True) -> str | WSClient:
        """Executes a command inside the latest migration console pod"""
        console_pod_id = self.get_migration_console_pod_id()
        printable_cmd = " ".join(command_list)
        logger.info(f"Executing command [{printable_cmd}] in pod: {console_pod_id}")

        # Retry exec in case container isn't fully ready for connections yet
        import time
        for attempt in range(5):
            try:
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
                    _preload_content=(not unbuffered)
                )
                break
            except Exception:
                if attempt < 4:
                    time.sleep(2)
                else:
                    raise

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
        kubectl = " ".join(self._kubectl_base())
        command_list = [
            "sh",
            "-c",
            f"rm -rf {destination} && mkdir -p {destination} && "
            f"{kubectl} -n ma exec {console_pod_id} -- sh -c "
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
                                   f"Remaining PVCs: {[pvc.metadata.name for pvc in remaining_pvcs]}")

            logger.info(f"Waiting for PVCs to be deleted. Remaining: {[pvc.metadata.name for pvc in remaining_pvcs]}")
            time.sleep(poll_interval)

    def create_namespace(self, namespace: str) -> CompletedProcess | None:
        logger.info(f"Ensuring namespace '{namespace}' exists")

        check_cmd = self._kubectl_base() + ["get", "namespace", namespace]
        result = self.run_command(check_cmd, ignore_errors=True)

        if result is None or result.returncode != 0:
            logger.info(f"Namespace '{namespace}' not found. Creating it now...")
            create_cmd = self._kubectl_base() + ["create", "namespace", namespace]
            return self.run_command(create_cmd)
        else:
            logger.info(f"Namespace '{namespace}' already exists")
            return result

    def delete_webhooks_referencing_namespace(self) -> None:
        """Delete all webhooks that reference services in this namespace."""
        logger.info(f"Deleting webhooks referencing namespace '{self.namespace}'")
        for webhook_type in ["mutatingwebhookconfigurations", "validatingwebhookconfigurations"]:
            try:
                ns = self.namespace
                jsonpath = (
                    f"jsonpath={{range .items[?(@.webhooks[*].clientConfig.service.namespace=="
                    f"\"{ns}\")]}}{{.metadata.name}}{{\"\\n\"}}{{end}}"
                )
                result = self.run_command(
                    self._kubectl_base() + ["get", webhook_type, "-o", jsonpath],
                    ignore_errors=True
                )
                if result and result.stdout.strip():
                    for name in result.stdout.strip().split("\n"):
                        if name:
                            logger.info(f"Deleting {webhook_type} '{name}' (references namespace '{self.namespace}')")
                            self.run_command(
                                self._kubectl_base() + ["delete", webhook_type, name, "--ignore-not-found"],
                                ignore_errors=True)
            except Exception as e:
                logger.warning(f"Failed to cleanup {webhook_type}: {e}")

    def wait_for_pods_terminated(self, timeout_seconds: int = 60) -> None:
        """Wait for all pods in namespace to terminate."""
        import time
        deadline = time.time() + timeout_seconds
        while True:
            result = self.run_command(
                self._kubectl_base() + ["get", "pods", "-n", self.namespace, "-o", "name"],
                ignore_errors=True
            )
            if not result or not result.stdout.strip():
                return
            if time.time() >= deadline:
                break
            time.sleep(2)
        logger.warning(f"Timeout waiting for pods to terminate in {self.namespace}")

    def wait_for_namespace_deleted(self, namespace: str, timeout_seconds: int = 120) -> None:
        """Poll until namespace is fully deleted, raise TimeoutError if not."""
        deadline = time.time() + timeout_seconds
        while time.time() < deadline:
            result = self.run_command(
                self._kubectl_base() + ["get", "namespace", namespace, "-o", "name"],
                ignore_errors=True
            )
            if not result or not result.stdout.strip():
                logger.info(f"Namespace '{namespace}' deleted successfully")
                return
            time.sleep(3)
        raise TimeoutError(f"Namespace '{namespace}' still exists after {timeout_seconds}s")

    def delete_namespace(self) -> None:
        logger.info(f"Deleting namespace '{self.namespace}'")
        # Delete kyverno webhooks first — they can block API calls during cleanup
        self.delete_kyverno_webhooks()
        # Delete webhooks referencing our namespace
        self.delete_webhooks_referencing_namespace()
        # Delete kyverno's separate namespace if it exists (ownerReference cascade
        # may not complete in time when the parent namespace is force-deleted)
        self.run_command(self._kubectl_base() + ["delete", "namespace", "kyverno-ma",
                         "--ignore-not-found", "--grace-period=0"], ignore_errors=True)
        # Delete main namespace
        self.run_command(self._kubectl_base() + ["delete", "namespace", self.namespace,
                         "--ignore-not-found", "--grace-period=0", "--force"])
        # Wait for pods to fully terminate before deleting webhooks again
        # This prevents kyverno from recreating webhooks during termination
        self.wait_for_pods_terminated()
        # Delete webhooks again in case they were recreated during pod termination
        self.delete_webhooks_referencing_namespace()

    def delete_kyverno_webhooks(self) -> None:
        """Delete all kyverno-labeled webhook configurations."""
        for webhook_type in ["mutatingwebhookconfigurations", "validatingwebhookconfigurations"]:
            self.run_command(
                self._kubectl_base() + [
                    "delete", webhook_type, "-l",
                    "app.kubernetes.io/instance=kyverno", "--ignore-not-found"],
                ignore_errors=True
            )

    def check_helm_release_exists(self, release_name: str) -> bool:
        logger.info(f"Checking if {release_name} is already deployed in '{self.namespace}' namespace")
        check_command = self._helm_base() + ["status", release_name, "-n", self.namespace]
        status_result = self.run_command(check_command, ignore_errors=True)
        return True if status_result and status_result.returncode == 0 else False

    def helm_upgrade(self, chart_path: str, release_name: str, values_file: str = None) -> CompletedProcess:
        logger.info(f"Upgrading {release_name} from {chart_path} with values {values_file}")
        command = self._helm_base() + ["upgrade", release_name, chart_path, "-n", self.namespace]
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
        command = self._helm_base() + ["install", release_name, chart_path, "-n", self.namespace, "--create-namespace",
                                       "--wait", "--timeout", "20m"]
        if values_file:
            command.extend(["-f", values_file])
        if values:
            for key, value in values.items():
                command.extend(["--set", f"{key}={shlex.quote(str(value))}"])
        try:
            return self.run_command(command)
        except subprocess.CalledProcessError:
            self._dump_helm_debug_info(release_name)
            raise

    def _dump_helm_debug_info(self, release_name: str):
        """Dump detailed debug info when helm install fails."""
        logger.error("=== BEGIN HELM INSTALL DEBUG INFO ===")
        debug_commands = [
            ("All pods", self._kubectl_base() + ["get", "pods", "--all-namespaces", "-o", "wide"]),
            ("All events", self._kubectl_base() + ["get", "events", "--all-namespaces", "--sort-by=.lastTimestamp"]),
            ("kube-system pod logs", self._kubectl_base() + [
                "logs", "-n", "kube-system", "--all-containers", "--prefix", "--tail=200",
                "-l", "tier=control-plane"]),
        ]
        debug_commands.extend([
            (f"Jobs in {self.namespace}", self._kubectl_base() + ["get", "jobs", "-n", self.namespace, "-o", "wide"]),
            ("Job status detail", self._kubectl_base() + [
                "get", "jobs", "-n", self.namespace, "-l", f"app.kubernetes.io/instance={release_name}",
                "-o", "jsonpath={range .items[*]}name={.metadata.name} succeeded={.status.succeeded} "
                "failed={.status.failed} conditions={.status.conditions[*].type} "
                "uncountedSucceeded={.status.uncountedTerminatedPods.succeeded} "
                "uncountedFailed={.status.uncountedTerminatedPods.failed}{\"\\n\"}{end}"]),
            ("Pod finalizers", self._kubectl_base() + [
                "get", "pods", "-n", self.namespace, "-l", f"app.kubernetes.io/instance={release_name}",
                "-o", "jsonpath={range .items[*]}name={.metadata.name} phase={.status.phase} "
                "finalizers={.metadata.finalizers}{\"\\n\"}{end}"]),
            ("Helm list all namespaces", self._helm_base() + ["list", "--all-namespaces"]),
        ])
        for label, cmd in debug_commands:
            try:
                result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
                logger.error(f"--- {label} ---\n{result.stdout}{result.stderr}")
            except Exception as e:
                logger.error(f"--- {label} --- FAILED: {e}")

        # Dump installer job logs if present
        try:
            chart_name = release_name.replace('ma', 'migrationAssistantWithArgo')
            pod_result = subprocess.run(
                self._kubectl_base() + ["get", "pods", "-n", self.namespace,
                                        "-l", f"app.kubernetes.io/name={chart_name}",
                                        "-o", "jsonpath={.items[*].metadata.name}"],
                capture_output=True, text=True, timeout=10)
            for pod_name in pod_result.stdout.split():
                log_result = subprocess.run(
                    self._kubectl_base() + ["logs", "-n", self.namespace, pod_name, "--tail=200"],
                    capture_output=True, text=True, timeout=30)
                logger.error(f"--- Logs for {pod_name} ---\n{log_result.stdout}{log_result.stderr}")
        except Exception as e:
            logger.error(f"--- Installer pod logs --- FAILED: {e}")

        # Describe any non-running pods
        try:
            result = subprocess.run(
                self._kubectl_base() + ["get", "pods", "--all-namespaces",
                                        "--field-selector=status.phase!=Running,status.phase!=Succeeded",
                                        "-o", "wide"],
                capture_output=True, text=True, timeout=10)
            if result.stdout.strip():
                logger.error(f"--- Non-running pods (all namespaces) ---\n{result.stdout}")
                for line in result.stdout.strip().split('\n')[1:]:
                    parts = line.split()
                    if len(parts) >= 2:
                        ns, name = parts[0], parts[1]
                        desc = subprocess.run(
                            self._kubectl_base() + ["describe", "pod", name, "-n", ns],
                            capture_output=True, text=True, timeout=15)
                        logger.error(f"--- Describe {ns}/{name} ---\n{desc.stdout[-2000:]}")
        except Exception as e:
            logger.error(f"--- Non-running pods --- FAILED: {e}")
        logger.error("=== END HELM INSTALL DEBUG INFO ===")

    def helm_uninstall(self, release_name: str) -> CompletedProcess | bool:
        helm_release_exists = self.check_helm_release_exists(release_name=release_name)
        if not helm_release_exists:
            logger.info(f"Helm release {release_name} doesn't exist, skipping uninstall")
            return True

        logger.info(f"Uninstalling {release_name}...")
        return self.run_command(self._helm_base() + ["uninstall", release_name, "-n", self.namespace])

    def cleanup_ack_dashboard_crs(self) -> None:
        """Delete Dashboard CRs and wait for ACK controller to process them before helm uninstall."""
        self.run_command(
            self._kubectl_base() + ["delete", "dashboards.cloudwatch.services.k8s.aws",
                                    "--all", "-n", self.namespace, "--timeout=60s"],
            ignore_errors=True
        )

    def get_helm_installations(self) -> List[str]:
        target_namespace = self.namespace
        # Use helm list with short output format to get just the release names
        command = self._helm_base() + ["list", "-n", target_namespace, "--short"]

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
        command = self._kubectl_base() + [
            "get", "configmaps", "-n", target_namespace,
            "--no-headers", "-o", "custom-columns=:metadata.name"]

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

    def delete_configmap(self, configmap_name: str) -> CompletedProcess:
        target_namespace = self.namespace
        logger.info(f"Deleting ConfigMap '{configmap_name}' from namespace '{target_namespace}'...")
        delete_command = self._kubectl_base() + [
            "delete", "configmap", configmap_name, "-n", target_namespace, "--ignore-not-found"
        ]
        return self.run_command(delete_command)

    def delete_all_argo_templates(self) -> None:
        """Deletes all Argo WorkflowTemplates from all namespaces."""
        logger.info("Deleting all Argo WorkflowTemplates from all namespaces")
        self.run_command(self._kubectl_base() + [
            "delete", "workflowtemplates", "--all-namespaces", "--all", "--ignore-not-found"
        ], ignore_errors=True)
