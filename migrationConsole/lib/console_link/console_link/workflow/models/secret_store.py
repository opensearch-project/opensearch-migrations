"""Storage implementation for sensitive credentials using Kubernetes Secrets."""

import logging
from typing import Optional, List, Dict, Set

from kubernetes import client
from kubernetes.client.rest import ApiException

from console_link.workflow.models.utils import load_k8s_config

logger = logging.getLogger(__name__)


class SecretStore:
    """
    Secret store using Kubernetes Secrets.
    Stores sensitive credentials (usernames, passwords, tokens) persistently in the cluster.

    See WorkflowConfigStore, which inspired this class
    """

    def __init__(
            self,
            namespace: str = "default",
            secret_prefix: str = "workflow-secret",
            default_labels: Optional[Dict[str, str]] = None,
            k8s_client: Optional[client.CoreV1Api] = None
    ):
        """Initialize the store with Kubernetes configuration

        Args:
            namespace: Kubernetes namespace to use for Secrets
            secret_prefix: Prefix for Secret names (unused but kept for consistency)
            default_labels: Default labels to apply to all secrets created by this store
            k8s_client: Optional pre-configured Kubernetes client (for testing)
        """
        self.namespace = namespace
        self.secret_prefix = secret_prefix

        # Set default labels - can be overridden per secret
        self.default_labels = default_labels or {
            "app": "migration-assistant",
            "component": "workflow-secret",
        }

        if k8s_client:
            # Use provided client (useful for testing)
            self.v1 = k8s_client
            logger.info("Using provided Kubernetes client")
        else:
            load_k8s_config()
            api_client = client.ApiClient(client.Configuration.get_default_copy())
            self.v1 = client.CoreV1Api(api_client)

    def save_secret(
            self,
            basic_creds_resource_name: str,
            credentials: Dict[str, str],
            secret_type: str = "Opaque",
            labels: Optional[Dict[str, str]] = None,
            annotations: Optional[Dict[str, str]] = None
    ) -> str:
        """Save credentials to Kubernetes Secret

        Args:
            basic_creds_resource_name: Name of the secret (e.g., "source-cluster", "target-cluster")
            credentials: Dictionary of key-value pairs (e.g., {"username": "admin", "password": "secret"})
            secret_type: Kubernetes secret type (default: "Opaque")
            labels: Additional labels to merge with default_labels
            annotations: Optional annotations to add to the secret

        Returns:
            A message describing the action taken (created/updated)

        Raises:
            ApiException: If Kubernetes API call fails
            Exception: For other errors during save operation
        """
        # Merge provided labels with defaults
        merged_labels = {**self.default_labels, "secret-name": basic_creds_resource_name}
        if labels:
            merged_labels.update(labels)

        # Kubernetes requires secret data to be base64-encoded
        # The Python client handles this automatically when we pass strings
        encoded_data = {
            key: value.encode('utf-8').decode('utf-8') if isinstance(value, str) else value
            for key, value in credentials.items()
        }

        # Create Secret body
        secret_body = client.V1Secret(
            metadata=client.V1ObjectMeta(
                name=basic_creds_resource_name,
                labels=merged_labels,
                annotations=annotations
            ),
            type=secret_type,
            string_data=encoded_data  # string_data handles encoding automatically
        )

        try:
            # Try to update existing Secret
            self.v1.patch_namespaced_secret(
                name=basic_creds_resource_name,
                namespace=self.namespace,
                body=secret_body
            )
            logger.info(f"Updated secret: {basic_creds_resource_name}")
            return f"Secret updated: {basic_creds_resource_name}"
        except ApiException as e:
            if e.status == 404:
                # Secret doesn't exist, create it
                self.v1.create_namespaced_secret(
                    namespace=self.namespace,
                    body=secret_body
                )
                logger.info(f"Created secret: {basic_creds_resource_name}")
                return f"Secret created: {basic_creds_resource_name}"
            else:
                logger.error(f"Kubernetes API error saving secret {basic_creds_resource_name}: {e}")
                raise

    def load_secret(
            self,
            basic_creds_resource_name: str,
            decode: bool = True
    ) -> Optional[Dict[str, str]]:
        """Load credentials from Kubernetes Secret

        Args:
            basic_creds_resource_name: Name of the secret to load
            decode: Whether to decode base64 values (default: True)

        Returns:
            Dictionary of credentials if found, None if not found

        Raises:
            ApiException: If Kubernetes API call fails (except 404)
            Exception: For other errors during load operation
        """
        try:
            secret = self.v1.read_namespaced_secret(
                name=basic_creds_resource_name,
                namespace=self.namespace
            )
        except ApiException as e:
            if e.status == 404:
                logger.info(f"No secret found: {basic_creds_resource_name}")
                return None
            else:
                logger.error(f"Kubernetes API error loading secret {basic_creds_resource_name}: {e}")
                raise

        if not secret.data:
            logger.info(f"Secret {basic_creds_resource_name} exists but has no data")
            return None

        # Kubernetes secret data is base64-encoded
        import base64
        credentials = {}
        for key, value in secret.data.items():
            if decode:
                # Decode base64 to string
                credentials[key] = base64.b64decode(value).decode('utf-8')
            else:
                # Return raw base64 string
                credentials[key] = value

        logger.info(f"Loaded secret: {basic_creds_resource_name} (keys: {list(credentials.keys())})")
        return credentials

    def delete_secret(self, basic_creds_resource_name: str) -> str:
        """Delete secret from Kubernetes

        Args:
            basic_creds_resource_name: Name of the secret to delete

        Returns:
            A message describing the deletion

        Raises:
            ApiException: If Kubernetes API call fails (including 404 if not found)
            Exception: For other errors during delete operation
        """
        try:
            self.v1.delete_namespaced_secret(
                name=basic_creds_resource_name,
                namespace=self.namespace
            )
            logger.info(f"Deleted secret: {basic_creds_resource_name}")
            return f"Secret deleted: {basic_creds_resource_name}"
        except ApiException as e:
            if e.status == 404:
                logger.warning(f"No secret found: {basic_creds_resource_name}")
                raise ApiException(status=404, reason=f"No secret found: {basic_creds_resource_name}")
            else:
                logger.error(f"Kubernetes API error deleting secret {basic_creds_resource_name}: {e}")
                raise

    def list_secrets(
            self,
            label_selector: Optional[str] = None,
            additional_labels: Optional[Dict[str, str]] = None
    ) -> List[str]:
        """List secrets matching label criteria

        Args:
            label_selector: Custom label selector string (e.g., "environment=prod,team=backend")
                          If None, uses default labels to filter
            additional_labels: Additional label requirements to add to default labels
                             Only used if label_selector is None

        Returns:
            List of secret names

        Raises:
            ApiException: If Kubernetes API call fails
            Exception: For other errors during list operation
        """
        if label_selector is None:
            # Build selector from default labels and any additional ones
            labels_to_match = self.default_labels.copy()
            if additional_labels:
                labels_to_match.update(additional_labels)

            label_selector = ",".join([f"{k}={v}" for k, v in labels_to_match.items()])

        # List Secrets with label selector
        secrets = self.v1.list_namespaced_secret(
            namespace=self.namespace,
            label_selector=label_selector
        )

        basic_creds_resource_names: List[str] = []
        for secret in secrets.items:
            if secret.metadata and secret.metadata.name:
                basic_creds_resource_names.append(secret.metadata.name)

        logger.info(f"Found {len(basic_creds_resource_names)} secrets matching: {label_selector}")
        return basic_creds_resource_names

    def secrets_exist(
            self,
            basic_creds_resource_names: List[str]
    ) -> Dict[str, bool]:
        """Check existence of multiple secrets efficiently in a single API call

        This is much more efficient than calling secret_exists() N times,
        as it fetches all secrets matching our labels in one call and checks locally.

        Args:
            basic_creds_resource_names: List of secret names to check

        Returns:
            Dictionary mapping secret names to existence (True/False)

        Raises:
            ApiException: If Kubernetes API call fails
        """
        if not basic_creds_resource_names:
            return {}

        # Get all secrets matching our default labels
        try:
            secrets = self.v1.list_namespaced_secret(
                namespace=self.namespace,
                label_selector=",".join([f"{k}={v}" for k, v in self.default_labels.items()])
            )
        except ApiException as e:
            logger.error(f"Kubernetes API error listing secrets: {e}")
            raise

        # Build a set of existing secret names
        existing_secrets: Set[str] = {
            secret.metadata.name
            for secret in secrets.items
            if secret.metadata and secret.metadata.name
        }

        # Check each requested secret
        result = {name: name in existing_secrets for name in basic_creds_resource_names}

        found_count = sum(1 for exists in result.values() if exists)
        logger.info(f"Checked {len(basic_creds_resource_names)} secrets: "
                    f"{found_count} exist, "
                    f"{len(basic_creds_resource_names) - found_count} missing")

        return result

    def secret_exists(self, basic_creds_resource_name: str) -> bool:
        """Check if a single secret exists


        Args:
            basic_creds_resource_name: Name of the secret to check

        Returns:
            True if secret exists, False otherwise
        """
        try:
            self.v1.read_namespaced_secret(
                name=basic_creds_resource_name,
                namespace=self.namespace
            )
            return True
        except ApiException as e:
            if e.status == 404:
                return False
            else:
                logger.error(f"Kubernetes API error checking secret {basic_creds_resource_name}: {e}")
                raise

    def get_secret_keys(self, basic_creds_resource_name: str) -> Optional[List[str]]:
        """Get the keys stored in a secret without retrieving values

        Args:
            basic_creds_resource_name: Name of the secret

        Returns:
            List of keys in the secret, None if secret not found

        Raises:
            ApiException: If Kubernetes API call fails (except 404)
        """
        try:
            secret = self.v1.read_namespaced_secret(
                name=basic_creds_resource_name,
                namespace=self.namespace
            )
            if secret.data:
                return list(secret.data.keys())
            return []
        except ApiException as e:
            if e.status == 404:
                logger.info(f"No secret found: {basic_creds_resource_name}")
                return None
            else:
                logger.error(f"Kubernetes API error reading secret {basic_creds_resource_name}: {e}")
                raise

    def close(self):
        """Close any connections (no-op for Kubernetes client)"""
        pass
