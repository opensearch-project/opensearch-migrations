"""Script runner service for executing workflow scripts."""

import json
import logging
import os
import subprocess
from pathlib import Path
from typing import Optional, Dict, Any

logger = logging.getLogger(__name__)


class ScriptRunner:
    """Runs workflow scripts with standard interface."""

    def __init__(self, script_dir: Optional[Path] = None):
        """
        Initialize with script directory path.

        Args:
            script_dir: Optional path to scripts. If None, uses WORKFLOW_SCRIPT_DIR env var or test resources.
        """
        if script_dir is None:
            # Check for environment variable first
            env_script_dir = os.environ.get('WORKFLOW_SCRIPT_DIR')
            if env_script_dir:
                self.script_dir = Path(env_script_dir)
            else:
                # Fallback to test resources
                # Navigate from this file to test resources
                # Path: console_link/workflow/services/script_runner.py
                # Target: tests/workflow-tests/resources/scripts
                current_file = Path(__file__)
                # Go up to console_link package root
                console_link_pkg = current_file.parent.parent.parent
                # Go up one more to lib root, then to tests
                lib_root = console_link_pkg.parent
                test_resources = lib_root / "tests" / "workflow-tests" / "resources" / "scripts"
                self.script_dir = test_resources
        else:
            self.script_dir = Path(script_dir)

        if not self.script_dir.exists():
            raise ValueError(f"Script directory not found: {self.script_dir}")

        logger.debug(f"ScriptRunner initialized with script_dir: {self.script_dir}")

    def run_script(
        self,
        script_name: str,
        input_data: Optional[str] = None,
        *args: str
    ) -> str:
        """
        Run a script with standard interface.

        Args:
            script_name: Name of script (e.g., 'getSample.sh')
            input_data: Optional data to pass via stdin
            *args: Additional command line arguments

        Returns:
            Script stdout output

        Raises:
            FileNotFoundError: If script doesn't exist
            subprocess.CalledProcessError: If script fails
        """
        script_path = self.script_dir / script_name

        if not script_path.exists():
            raise FileNotFoundError(f"Script not found: {script_path}")

        cmd = [str(script_path)] + list(args)

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
            logger.error(f"Script failed with exit code {e.returncode}")
            logger.error(f"stderr: {e.stderr}")
            raise

    def get_sample_config(self) -> str:
        """Get sample workflow configuration."""
        logger.info("Getting sample configuration")
        return self.run_script("getSample.sh")

    def transform_config(self, config_data: str) -> str:
        """Transform configuration."""
        logger.info("Transforming configuration")
        return self.run_script("transformConfig.sh", config_data, "-")

    def init_workflow(self, config_data: str, prefix: Optional[str] = None) -> str:
        """Initialize workflow state, returns prefix."""
        logger.info("Initializing workflow with prefix: %s", prefix or 'auto-generated')
        args = ["-"]
        if prefix:
            args.append(prefix)
        return self.run_script("initWorkflow.sh", config_data, *args)

    def submit_workflow(
        self,
        config_data: str,
        prefix: str,
        namespace: str = "ma"
    ) -> Dict[str, Any]:
        """Submit workflow, returns workflow info as dict."""
        logger.info(f"Submitting workflow with prefix: {prefix}, namespace: {namespace}")
        output = self.run_script(
            "submitWorkflow.sh",
            config_data,
            "-",
            prefix,
            namespace
        )
        return json.loads(output)
