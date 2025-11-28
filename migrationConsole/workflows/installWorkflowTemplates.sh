#!/bin/bash
# Development script to install or upgrade Argo Workflow templates - this will eventually be added to a helm job config

set -e

# These variables can come from a helm-instantiated job template
TEMPLATES_DIR=${WF_TEMPLATES_DIR:-"./templates"}
NAMESPACE=${WF_NAMESPACE:-"ma"}
RELEASE_NAME=${WF_RELEASE_NAME:-"migration-assistant"}
RELEASE_VERSION=${WF_RELEASE_VERSION:-"0.0.1"}
MANAGED_BY=${WF_MANAGED_BY:-"install-migration-workflow-templates-script"}

if [[ -z "${WF_TEMPLATES_DIR}" ]]; then
  # If not set, switch to the directory where the script itself is located
  SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
  cd "${SCRIPT_DIR}"
  echo "Changed directory to script location: ${SCRIPT_DIR}"
fi

# Add labels to each YAML file before applying
FILES_UPDATED=0
for template_file in $TEMPLATES_DIR/*.yaml; do
  echo "Processing $template_file..."

  temp_file=$(mktemp)

  # Add labels using yq
  cat $template_file | yq e '.metadata.labels.app = "'$RELEASE_NAME'" |
    .metadata.labels.version = "'$RELEASE_VERSION'" |
    .metadata.labels.managed-by = "'$MANAGED_BY'"' - > $temp_file

  if kubectl apply -f $temp_file -n $NAMESPACE | grep -q "configured\|created"; then
    FILES_UPDATED=1
  fi

  rm $temp_file
  echo "Applied $template_file"
done

echo "All workflow templates have been applied to namespace $NAMESPACE"

if [ $FILES_UPDATED -eq 1 ]; then
  echo -e "\033[0;33m✓ Files were updated or created\033[0m"
else
  echo -e "\033[0;32m✓ No changes - all files unchanged\033[0m"
fi

# List all workflow templates that were just installed/updated
echo "Current workflow templates:"
kubectl get workflowtemplates -n $NAMESPACE -l app=$RELEASE_NAME -l managed-by=$MANAGED_BY