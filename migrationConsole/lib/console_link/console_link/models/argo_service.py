import time
import json
import logging
import tempfile
import os
import uuid
import yaml
from typing import Optional, Dict, Any

from console_link.models.cluster import Cluster
from console_link.models.command_runner import CommandRunner, CommandRunnerError, FlagOnlyArgument
from console_link.models.command_result import CommandResult

logger = logging.getLogger(__name__)

ENDING_ARGO_PHASES = ["Succeeded", "Failed", "Error", "Stopped", "Terminated"]


class WorkflowEndedBeforeSuspend(Exception):
    def __init__(self, workflow_name: str, phase: str):
        super().__init__(f"The workflow '{workflow_name}' reached ending phase of {phase} before reaching "
                         f"suspend state")


class ArgoService:
    def __init__(self, namespace: str = "ma", argo_image: str = "quay.io/argoproj/argocli:v3.6.5",
                 service_account: str = "argo-workflow-executor"):
        self.namespace = namespace
        self.argo_image = argo_image
        self.service_account = service_account

    def start_workflow(self, workflow_template_name: str, parameters: Optional[Dict[str, Any]] = None) -> CommandResult:
        try:
            # Create temporary workflow file
            temp_file = self._create_workflow_yaml(workflow_template_name, parameters)

            # Use kubectl create to start the workflow
            kubectl_args = {
                "create": FlagOnlyArgument,
                "-f": temp_file,
                "--namespace": self.namespace,
                "-o": "jsonpath={.metadata.name}"
            }

            result = self._run_kubectl_command(kubectl_args)

            # Clean up temporary file
            try:
                os.unlink(temp_file)
            except OSError:
                logger.warning(f"Failed to delete temporary file: {temp_file}")

            name = result.output.stdout.strip()
            logger.info(f"Started workflow: {name}")
            self._wait_for_workflow_exists(workflow_name=name)
            return CommandResult(
                success=True,
                value=name
            )

        except Exception as e:
            logger.error(f"Failed to start workflow: {e}")
            return CommandResult(
                success=False,
                value=f"Failed to start workflow: {e}"
            )

    def resume_workflow(self, workflow_name: str) -> CommandResult:
        args = {
            "resume": workflow_name
        }
        result = self._run_argo_command(pod_action="resume-workflow", argo_args=args)
        logger.info(f"Argo workflow '{workflow_name}' has been resumed")
        return result

    def stop_workflow(self, workflow_name: str) -> CommandResult:
        args = {
            "stop": workflow_name
        }
        result = self._run_argo_command(pod_action="stop-workflow", argo_args=args)
        logger.info(f"Argo workflow '{workflow_name}' has been stopped")
        return result

    def delete_workflow(self, workflow_name: str) -> CommandResult:
        kubectl_args = {
            "delete": FlagOnlyArgument,
            "workflow": workflow_name,
            "--namespace": self.namespace
        }
        result = self._run_kubectl_command(kubectl_args)
        logger.info(f"Argo workflow '{workflow_name}' has been deleted")
        return result

    def print_workflow_details(self, workflow_name: str) -> None:
        """Print workflow details and namespace state for debugging failed workflows."""
        try:
            workflow_data = self._get_workflow_status_json(workflow_name)
            logger.info(
                f"Workflow {workflow_name} full status:\n{json.dumps(workflow_data.get('status', {}), indent=2)}")
        except Exception as e:
            logger.error(f"Failed to get workflow details: {e}")

        # Print namespace resource summary
        try:
            logger.info(f"===== Namespace {self.namespace} resource summary =====")
            summary_resources = "pods,services,deployments,statefulsets,workflows"
            get_args = {"get": summary_resources, "--namespace": self.namespace}
            self._run_kubectl_command(get_args)
        except Exception as e:
            logger.error(f"Failed to get namespace resources: {e}")

    def save_namespace_diagnostics(self, output_dir: str) -> Optional[str]:
        """Save detailed namespace diagnostics to a file for artifact collection."""
        diagnostic_resources = "pods,services,deployments,statefulsets,workflows"
        try:
            os.makedirs(output_dir, exist_ok=True)
            output_file = os.path.join(output_dir, f"namespace-{self.namespace}-diagnostics.txt")

            with open(output_file, 'w') as f:
                f.write(f"===== Namespace {self.namespace} Diagnostics =====\n\n")

                # kubectl get resources
                f.write(f"===== kubectl get {diagnostic_resources} =====\n")
                try:
                    result = CommandRunner("kubectl", {
                        "get": diagnostic_resources,
                        "--namespace": self.namespace,
                        "-o": "wide"
                    }).run()
                    f.write(result + "\n\n")
                except Exception as e:
                    f.write(f"Error: {e}\n\n")

                # kubectl describe resources
                f.write(f"===== kubectl describe {diagnostic_resources} =====\n")
                try:
                    result = CommandRunner("kubectl", {
                        "describe": diagnostic_resources,
                        "--namespace": self.namespace
                    }).run()
                    f.write(result + "\n\n")
                except Exception as e:
                    f.write(f"Error: {e}\n\n")

                # kubectl get events
                f.write("===== kubectl get events =====\n")
                try:
                    result = CommandRunner("kubectl", {
                        "get": "events",
                        "--namespace": self.namespace,
                        "--sort-by": ".lastTimestamp"
                    }).run()
                    f.write(result + "\n\n")
                except Exception as e:
                    f.write(f"Error: {e}\n\n")

            logger.info(f"Saved namespace diagnostics to {output_file}")
            return output_file
        except Exception as e:
            logger.error(f"Failed to save namespace diagnostics: {e}")
            return None

    def get_workflow_status(self, workflow_name: str) -> CommandResult:
        workflow_data = self._get_workflow_status_json(workflow_name)
        phase = workflow_data.get("status", {}).get("phase", "")

        # Check for suspended nodes
        nodes = workflow_data.get("status", {}).get("nodes", {})
        has_suspended_nodes = False
        for node_id, node in nodes.items():
            if node.get("phase") == "Running":
                if node.get("type", "") == "Suspend":
                    has_suspended_nodes = True

        status_info = {
            "phase": phase,
            "has_suspended_nodes": has_suspended_nodes
        }

        logger.info(f"Workflow {workflow_name} status: {status_info}")

        return CommandResult(
            success=True,
            value=status_info
        )

    def wait_for_suspend(self, workflow_name: str, timeout_seconds: int = 120, interval: int = 5) -> CommandResult:
        start_time = time.time()

        while time.time() - start_time < timeout_seconds:
            status_result = self.get_workflow_status(workflow_name)
            if not status_result.success:
                raise ValueError(f"Failed to get workflow status: {status_result}")

            status_info = status_result.value
            phase = status_info.get("phase", "")
            has_suspended_nodes = status_info.get("has_suspended_nodes", False)

            if phase == "Running" and has_suspended_nodes:
                return CommandResult(success=True, value=f"Workflow {workflow_name} is in a suspended state")
            elif phase in ENDING_ARGO_PHASES:
                raise WorkflowEndedBeforeSuspend(workflow_name=workflow_name, phase=phase)

            time.sleep(interval)

        raise TimeoutError(f"Workflow did not reach suspended state in timeout of {timeout_seconds} seconds")

    def is_workflow_completed(self, workflow_name: str) -> CommandResult:
        status_result = self.get_workflow_status(workflow_name)
        if not status_result.success:
            raise ValueError(f"Failed to get workflow status: {status_result}")

        status_info = status_result.value
        phase = status_info.get("phase", "")

        if phase in ENDING_ARGO_PHASES:
            return CommandResult(success=True, value=f"Workflow {workflow_name} has reached an ending phase of {phase}")
        return CommandResult(success=False, value=f"Workflow {workflow_name} has not completed and is in {phase} phase")

    def wait_for_ending_phase(self, workflow_name: str, timeout_seconds: int = 120, interval: int = 5) -> CommandResult:
        start_time = time.time()

        while time.time() - start_time < timeout_seconds:
            phase_result = self.is_workflow_completed(workflow_name)
            if phase_result.success:
                return phase_result
            time.sleep(interval)

        raise TimeoutError(f"Workflow did not reach ending state in timeout of {timeout_seconds} seconds")

    def watch_workflow(self, workflow_name: str, stream_output: bool = True) -> CommandResult:
        argo_args = {
            "logs": workflow_name,
            "--follow": FlagOnlyArgument
        }
        return self._run_argo_command(pod_action="watch-workflow", argo_args=argo_args, print_output=stream_output,
                                      stream_output=stream_output)

    def get_source_cluster_from_workflow(self, workflow_name: str) -> Cluster:
        return self._get_cluster_config_from_workflow(workflow_name, "source")

    def get_target_cluster_from_workflow(self, workflow_name: str) -> Cluster:
        return self._get_cluster_config_from_workflow(workflow_name, "target")

    def get_cluster_from_configmap(self, configmap_name_prefix: str,
                                   config_key: str = "cluster-config") -> Optional[Cluster]:
        # First, find ConfigMaps with the given prefix
        kubectl_args = {
            "get": FlagOnlyArgument,
            "configmaps": FlagOnlyArgument,
            "--namespace": self.namespace,
            "-o": "jsonpath={.items[*].metadata.name}"
        }

        result = self._run_kubectl_command(kubectl_args)
        if not result.success or not result.output.stdout.strip():
            logger.info(f"No ConfigMaps found in namespace {self.namespace}")
            return None

        configmap_names = result.output.stdout.strip().split()
        matching_configmaps = [cm for cm in configmap_names if cm.startswith(configmap_name_prefix) and
                               cm.endswith("migration-config")]

        if not matching_configmaps:
            logger.info(f"Unable to find existing cluster ConfigMap found with prefix '{configmap_name_prefix}' "
                        f"in namespace {self.namespace}")
            return None

        # If multiple ConfigMaps are found, pick the last one
        if len(matching_configmaps) > 1:
            logger.warning(f"Multiple ConfigMaps found with prefix '{configmap_name_prefix}': {matching_configmaps}. "
                           f"Picking last one: {matching_configmaps[-1]}.")
        configmap_name = matching_configmaps[-1]

        logger.info(f"Found ConfigMap '{configmap_name}' matching prefix '{configmap_name_prefix}'")

        # Get the cluster config from the ConfigMap
        kubectl_args = {
            "get": FlagOnlyArgument,
            "configmap": configmap_name,
            "--namespace": self.namespace,
            "-o": f'jsonpath={{.data.{config_key}}}'
        }

        try:
            result = self._run_kubectl_command(kubectl_args)
            if not result.success:
                raise ValueError(f"Failed to read ConfigMap '{configmap_name}'")

            config_contents = result.output.stdout.strip()
            if not config_contents:
                raise ValueError(f"ConfigMap '{configmap_name}' does not contain key '{config_key}' or it is empty")

            try:
                parsed_contents = json.loads(config_contents)
                logger.info(f"Successfully retrieved cluster configuration from ConfigMap '{configmap_name}'")
                # Convert workflow schema to Python Cluster schema if needed
                converted_config = self._convert_workflow_config_to_cluster_config(parsed_contents)
                logger.info(f"Converted cluster config from ConfigMap: {converted_config}")
                return Cluster(config=converted_config)
            except json.JSONDecodeError as jsonDecodeError:
                raise ValueError(f"Failed to parse ConfigMap '{configmap_name}' key '{config_key}' "
                                 f"as JSON: {jsonDecodeError}")

        except CommandRunnerError as e:
            logger.error(f"Failed to execute kubectl command: {e}")
            raise ValueError(f"Failed to read ConfigMap '{configmap_name}': {e}")

    def _run_argo_command(self, pod_action: str, argo_args: Dict, print_output: bool = False,
                          stream_output: bool = False) -> CommandResult:
        pod_name = f"argo-{pod_action}-{uuid.uuid4().hex[:6]}"
        command_args = {
            "run": pod_name,
            "--namespace": self.namespace,
            "--image": self.argo_image,
            "--rm": FlagOnlyArgument,
            "-i": FlagOnlyArgument,
            "--overrides": '{"spec":{"serviceAccountName":"argo-workflow-executor"}}',
            "--restart": "Never",
            "--": FlagOnlyArgument,
        }
        command_args.update(argo_args)

        runner = CommandRunner("kubectl", command_args)
        try:
            return runner.run(print_to_console=print_output, stream_output=stream_output)
        except CommandRunnerError as e:
            logger.error(f"Argo command failed: {e}")
            raise

    def _run_kubectl_command(self, kubectl_args: Dict[str, Any], print_output: bool = False) -> CommandResult:
        runner = CommandRunner("kubectl", kubectl_args)
        try:
            return runner.run(print_to_console=print_output)
        except CommandRunnerError as e:
            logger.error(f"Kubectl command failed: {e}")
            raise

    def _create_workflow_yaml(self, workflow_template_name: str, parameters: Optional[Dict[str, Any]] = None) -> str:
        """Create a temporary workflow YAML file based on the template structure."""
        workflow_data = {
            "apiVersion": "argoproj.io/v1alpha1",
            "kind": "Workflow",
            "metadata": {
                "generateName": f"{workflow_template_name}-"
            },
            "spec": {
                "workflowTemplateRef": {
                    "name": workflow_template_name
                },
                "entrypoint": "main"
            }
        }

        # Add parameters if provided
        if parameters:
            workflow_data["spec"]["arguments"] = {
                "parameters": [
                    {
                        "name": key,
                        "value": (
                            json.dumps(value, indent=2)
                            if isinstance(value, (dict, list)) else value
                        )
                    }
                    for key, value in parameters.items()
                ]
            }

        # Create temporary file
        with tempfile.NamedTemporaryFile(mode='w', suffix='.yaml', delete=False) as f:
            yaml.dump(workflow_data, f, default_flow_style=False, sort_keys=False)
            temp_file_path = f.name

        # Log created file contents
        try:
            with open(temp_file_path, 'r') as f:
                file_contents = f.read()
                logger.info(f"Created workflow YAML file at {temp_file_path}:\n{file_contents}")
        except Exception as e:
            logger.warning(f"Failed to read temporary file for debugging: {e}")

        return temp_file_path

    def _wait_for_workflow_exists(self, workflow_name: str, timeout_seconds: int = 15,
                                  interval: int = 1) -> CommandResult:
        start_time = time.time()

        while time.time() - start_time < timeout_seconds:
            kubectl_args = {
                "get": FlagOnlyArgument,
                "workflow": workflow_name,
                "--namespace": self.namespace,
                "--ignore-not-found": FlagOnlyArgument
            }

            try:
                result = self._run_kubectl_command(kubectl_args)
                if result.success and result.output and result.output.stdout.strip():
                    logger.info(f"Workflow {workflow_name} exists")
                    return CommandResult(
                        success=True,
                        value=f"Workflow {workflow_name} exists"
                    )
            except CommandRunnerError:
                pass

            time.sleep(interval)

        return CommandResult(
            success=False,
            value=f"Timeout waiting for workflow {workflow_name} to exist"
        )

    def _get_workflow_status_json(self, workflow_name: str) -> Dict[str, Any]:
        kubectl_args = {
            "get": FlagOnlyArgument,
            "workflow": workflow_name,
            "-o": "json",
            "--namespace": self.namespace
        }
        result = self._run_kubectl_command(kubectl_args)
        try:
            data = json.loads(result.output.stdout.strip())
        except json.JSONDecodeError as e:
            logger.error(f"Failed to parse 'kubectl get workflow <name> -o json' output: {e}")
            raise
        return data

    def _convert_basic_auth(self, basic: Dict[str, Any]) -> Dict[str, Any]:
        """Convert workflow basic auth config to Python schema."""
        if "secretName" in basic:
            return {"k8s_secret_name": basic["secretName"]}
        if "username" in basic and "password" in basic:
            return {"username": basic["username"], "password": basic["password"]}
        if "secretArn" in basic:
            return {"user_secret_arn": basic["secretArn"]}
        return {}

    def _convert_sigv4_auth(self, sigv4: Optional[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
        """Convert workflow sigv4 auth config to Python schema."""
        if not sigv4:
            return None
        return {"region": sigv4.get("region"), "service": sigv4.get("service", "es")}

    def _convert_auth_config(self, auth_config: Dict[str, Any]) -> Dict[str, Any]:
        """Convert workflow authConfig to Python auth fields."""
        if "basic" in auth_config:
            return {"basic_auth": self._convert_basic_auth(auth_config["basic"])}
        if "noAuth" in auth_config:
            return {"no_auth": None}
        if "sigv4" in auth_config:
            return {"sigv4": self._convert_sigv4_auth(auth_config["sigv4"])}
        return {}

    def _get_legacy_auth_fields(self, workflow_config: Dict[str, Any]) -> Dict[str, Any]:
        """Extract auth fields if workflow config already uses Python schema."""
        if "basic_auth" in workflow_config:
            return {"basic_auth": workflow_config["basic_auth"]}
        if "no_auth" in workflow_config:
            return {"no_auth": workflow_config["no_auth"]}
        if "sigv4" in workflow_config and "authConfig" not in workflow_config:
            return {"sigv4": workflow_config["sigv4"]}
        return {}

    def _convert_workflow_config_to_cluster_config(self, workflow_config: Dict[str, Any]) -> Dict[str, Any]:
        """
        Convert workflow cluster config schema to Python Cluster schema.

        Field mappings (Workflow -> Python):
          - allowInsecure         -> allow_insecure (boolean)
          - authConfig.basic      -> basic_auth (dict with username/password, k8s_secret_name, or user_secret_arn)
          - null                 -> no_auth (true)
          - authConfig.sigv4      -> sigv4 (dict with region/service)
        """
        converted: Dict[str, Any] = {"endpoint": workflow_config.get("endpoint")}

        # Convert allowInsecure -> allow_insecure
        allow_insecure = workflow_config.get("allowInsecure", workflow_config.get("allow_insecure"))
        if allow_insecure is not None:
            converted["allow_insecure"] = allow_insecure

        # Copy version if present
        if "version" in workflow_config:
            converted["version"] = workflow_config["version"]

        # Convert auth config (workflow schema takes precedence)
        auth_config = workflow_config.get("authConfig", {})
        if auth_config:
            converted.update(self._convert_auth_config(auth_config))
        else:
            # Fall back to legacy/direct auth fields
            legacy_auth = self._get_legacy_auth_fields(workflow_config)
            if legacy_auth:
                converted.update(legacy_auth)
            else:
                # Default to no_auth if no authentication is specified
                converted["no_auth"] = None

        return converted

    def _get_cluster_config_from_workflow(self, workflow_name: str, cluster_type: str) -> Cluster:
        workflow_data = self._get_workflow_status_json(workflow_name)
        nodes = workflow_data.get("status", {}).get("nodes", {})
        search = f"create-{cluster_type}-cluster"

        # find the succeeded node that created this cluster
        node = next(
            (
                n for n in nodes.values()
                if search in n.get("displayName", n.get("name", "")) and n.get("phase") == "Succeeded"
            ),
            None,
        )
        if not node:
            raise ValueError(f"Did not find {cluster_type} cluster config")

        params = node.get("outputs", {}).get("parameters", []) or []
        cfg_str = next((p.get("value") for p in params if p.get("name") == "cluster-config"), None)
        if not cfg_str:
            raise ValueError(f"Did not find {cluster_type} cluster config")

        try:
            cfg = json.loads(cfg_str)
        except json.JSONDecodeError as e:
            logger.error(f"Failed to parse {cluster_type} cluster config JSON: {e}")
            raise

        logger.info(f"Found {cluster_type} cluster config from workflow: {cfg}")

        # Convert workflow schema to Python Cluster schema
        converted_cfg = self._convert_workflow_config_to_cluster_config(cfg)
        logger.info(f"Converted {cluster_type} cluster config: {converted_cfg}")

        return Cluster(config=converted_cfg)
