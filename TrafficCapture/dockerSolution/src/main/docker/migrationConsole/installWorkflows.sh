#!/bin/bash
# Development script to install or upgrade Argo Workflow templates - this will eventually be added to a helm job config

# These variables can eventually come from a helm-instantiated job template
TEMPLATES_DIR="./workflows"
NAMESPACE="ma"
RELEASE_NAME="migration-assistant"
RELEASE_VERSION="1.0.0"

# Add labels to each YAML file before applying
for template_file in $TEMPLATES_DIR/*.yaml; do
  echo "Processing $template_file..."

  # Create a temporary file for the modified template
  temp_file=$(mktemp)

  # Add labels using yq (install with: pip install yq)
  cat $template_file | yq e '.metadata.labels.app = "'$RELEASE_NAME'" |
    .metadata.labels.version = "'$RELEASE_VERSION'" |
    .metadata.labels.managed-by = "kubectl-script"' - > $temp_file

  # Apply the modified template
  kubectl apply -f $temp_file -n $NAMESPACE

  # Clean up temp file
  rm $temp_file

  echo "Applied $template_file"
done

echo "All workflow templates have been applied to namespace $NAMESPACE"

# List all workflow templates that were just installed/updated
echo "Current workflow templates:"
kubectl get workflowtemplates -n $NAMESPACE -l app=$RELEASE_NAME