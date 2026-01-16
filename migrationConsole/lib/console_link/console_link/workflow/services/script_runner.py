"""Script runner service for executing workflow scripts."""

import json
import logging
import os
import shutil
import subprocess
import tempfile
from pathlib import Path
from typing import Optional, Dict, Any

logger = logging.getLogger(__name__)


class ScriptRunner:
    """Runs workflow scripts with standard interface."""

    def __init__(self, script_dir: Optional[Path] = None):
        """
        Initialize with script directory path.

        Args:
            script_dir: Optional path to scripts. If None, uses CONFIG_PROCESSOR_DIR environment variable.
                       Raises ValueError if neither is provided or if the directory doesn't exist.
        """
        if script_dir is None:
            config_processor_dir = os.environ.get('CONFIG_PROCESSOR_DIR')
            if not config_processor_dir:
                raise ValueError(
                    "CONFIG_PROCESSOR_DIR environment variable must be set when script_dir is not provided"
                )
            self.config_processor_dir = config_processor_dir  # Store the env var value
            self.script_dir = Path(config_processor_dir)
            logger.debug(f"Using CONFIG_PROCESSOR_DIR: {self.script_dir}")
        else:
            self.script_dir = Path(script_dir)
            logger.debug(f"Using provided script_dir: {self.script_dir}")

        if not self.script_dir.exists():
            raise ValueError(f"Script directory not found: {self.script_dir}")

        logger.debug(f"ScriptRunner initialized with script_dir: {self.script_dir}")

    def run(
            self,
            program_name: str,
            input_data: Optional[str] = None,
            *args: str,
            direct_output: bool = False
    ) -> str:
        """
        Run a program with standard interface.

        Args:
            program_name: Name of command
            input_data: Optional data to pass via stdin
            *args: Additional command line arguments
            direct_output: If True, output errors directly to stderr instead of using logger

        Returns:
            stripped output (stdout) from running program_name

        Raises:
            FileNotFoundError: If script doesn't exist
            subprocess.CalledProcessError: If script fails
        """
        if not shutil.which(program_name):
            raise FileNotFoundError(f"Command not found: {program_name}")

        cmd = [str(program_name), *args]

        logger.debug(f"Running script: {' '.join(cmd)}")
        if input_data:
            logger.debug(f"Input data length: {len(input_data)} bytes")

        try:
            result = subprocess.run(
                cmd,
                input=input_data,
                capture_output=True,
                text=True,
                check=True,
                cwd=str(self.script_dir)
            )

            logger.debug("Script completed successfully")
            return result.stdout.strip()

        except subprocess.CalledProcessError as e:
            if direct_output:
                import sys
                print(f"Script failed with exit code {e.returncode}", file=sys.stderr)
                if e.stderr:
                    print(f"stderr: {e.stderr}", file=sys.stderr)
            else:
                logger.error(f"Script failed with exit code {e.returncode}")
                if e.stderr:
                    logger.error(f"stderr: {e.stderr}")
            raise subprocess.CalledProcessError(
                e.returncode, e.cmd, e.stdout, e.stderr
            ) from None

    def run_script(
            self,
            script_name: str,
            input_data: Optional[str] = None,
            *args: str,
            direct_output: bool = False
    ) -> str:
        """
        Run a script with standard interface.

        Args:
            script_name: Name of script (e.g., 'createMigrationWorkflowFromUserConfiguration.sh')
            input_data: Optional data to pass via stdin
            *args: Additional command line arguments
            direct_output: If True, output errors directly to stderr instead of using logger

        Returns:
            Script stdout output

        Raises:
            FileNotFoundError: If script doesn't exist
            subprocess.CalledProcessError: If script fails
        """
        return self.run(self.script_dir / script_name, input_data, *args, direct_output=direct_output)

    def run_config_processor_node_script(
            self,
            processor_name: str,
            *args: str,
            nodejs_location: Optional[str] = None,
            input_data: Optional[str] = None
    ) -> str:
        """
        Run a config processor command (aka script) through node
        """
        if nodejs_location is None:
            nodejs_location = os.environ.get('NODEJS', 'node')
            if not nodejs_location:
                raise ValueError(
                    "nodejs_location environment variable must be set when nodejs_location is not provided"
                )
        script_entrypoint = os.path.join(self.config_processor_dir, "index.js")
        return self.run(nodejs_location, input_data, script_entrypoint, processor_name, *args)

    def _get_blank_starter_config(self) -> str:
        """Get a minimal blank starter configuration template.

        Returns an empty string to allow users to start from scratch.

        Returns:
            Empty string
        """
        return ""

    def get_sample_config(self) -> str:
        """Get sample workflow configuration.

        Reads sample.yaml from the configured script directory. If sample.yaml
        doesn't exist (e.g., when CONFIG_PROCESSOR_DIR is not set), returns a
        blank starter configuration template instead.

        Returns:
            YAML content as string (either from sample.yaml or blank starter)

        Raises:
            IOError: If sample.yaml exists but cannot be read
        """
        logger.info("Getting sample configuration")
        sample_path = self.script_dir / "sample.yaml"

        if not sample_path.exists():
            logger.info(f"Sample configuration not found at {sample_path}, using blank starter config")
            return self._get_blank_starter_config()

        try:
            with open(sample_path, 'r') as f:
                content = f.read()
            logger.debug(f"Loaded sample config from {sample_path} ({len(content)} bytes)")
            return content
        except IOError as e:
            logger.error(f"Failed to read sample configuration: {e}")
            raise

    def submit_workflow(
        self,
        config_data: str,
        args: list[str],
    ) -> Dict[str, Any]:
        """Submit workflow using config processor submission script.

        Calls: {script_dir}/createMigrationWorkflowFromUserConfiguration.sh <temp_file> <ARGS>

        The script creates the workflow in Kubernetes and returns workflow information.

        Args:
            config_data: User configuration YAML as string
            args: Command line arguments to pass to the submission script

        Returns:
            Dict with workflow_name, workflow_uid, and namespace

        Raises:
            FileNotFoundError: If submission script doesn't exist
            subprocess.CalledProcessError: If script fails
            ValueError: If script output cannot be parsed
        """
        logger.info(f"Submitting workflow with args: {args}")

        # Create temporary file with config data
        with tempfile.NamedTemporaryFile(mode='w', suffix='.yaml', delete=False) as temp_file:
            temp_file.write(config_data)
            temp_file_path = temp_file.name

        try:
            logger.debug(f"Config file: {temp_file_path}")
            output = self.run_script("createMigrationWorkflowFromUserConfiguration.sh", None,
                                     *([temp_file_path] + args), direct_output=True)

            # Parse kubectl output to extract workflow information
            # The script should output workflow creation details
            logger.debug(f"Submission script output: {output}")

            # Try to parse as JSON first (if script returns JSON)
            try:
                workflow_info = json.loads(output)
                logger.info(f"Workflow submitted successfully: {workflow_info.get('workflow_name', 'unknown')}")
                return workflow_info
            except json.JSONDecodeError:
                # If not JSON, parse kubectl output format
                # Expected format: "workflow.argoproj.io/<workflow-name> created"
                # or similar kubectl output
                workflow_name = self._parse_kubectl_output(output)

                workflow_info = {
                    'workflow_name': workflow_name
                }

                logger.info(f"Workflow submitted successfully: {workflow_name}")
                return workflow_info

        finally:
            # Clean up temporary file
            try:
                os.unlink(temp_file_path)
                logger.debug(f"Cleaned up temporary file: {temp_file_path}")
            except OSError as e:
                logger.warning(f"Failed to clean up temporary file {temp_file_path}: {e}")

    def _parse_kubectl_output(self, output: str) -> str:
        """Parse kubectl output to extract workflow name.

        Args:
            output: kubectl command output

        Returns:
            Workflow name extracted from output

        Raises:
            ValueError: If workflow name cannot be extracted
        """
        # Try to extract workflow name from kubectl output
        # Format: "workflow.argoproj.io/<workflow-name> created"
        lines = output.strip().split('\n')
        for line in lines:
            if 'workflow' in line.lower() and ('created' in line.lower() or 'submitted' in line.lower()):
                # Extract workflow name from "workflow.argoproj.io/<name> created"
                parts = line.split()
                if parts:
                    resource = parts[0]
                    if '/' in resource:
                        workflow_name = resource.split('/')[-1]
                        return workflow_name

        # If we can't parse it, raise an error
        raise ValueError(f"Could not extract workflow name from output: {output}")

    def get_basic_creds_secrets_in_config(self, config_data: str):
        # Create temporary file with config data
        with tempfile.NamedTemporaryFile(mode='w', suffix='.yaml', delete=False) as temp_file:
            temp_file.write(config_data)
            temp_file_path = temp_file.name

        try:
            result_str = self.run_config_processor_node_script("findSecrets", temp_file_path)
            return json.loads(result_str)
        finally:
            # Clean up temporary file
            try:
                # os.unlink(temp_file_path)
                logger.debug(f"Cleaned up temporary file: {temp_file_path}")
            except OSError as e:
                logger.warning(f"Failed to clean up temporary file {temp_file_path}: {e}")
                raise e
