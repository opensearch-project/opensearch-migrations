#!/bin/bash

# Function to create symlinks for a chart's dependencies
process_chart() {
    local chart_dir="$1"
    local chart_yaml="$chart_dir/Chart.yaml"

    # Check if Chart.yaml exists
    if [[ ! -f "$chart_yaml" ]]; then
        return
    fi

    echo "Processing $chart_yaml..."

    # Find file:// dependencies using yq
    local deps=$(yq e '.dependencies[] | select(.repository | test("^file://")) | [.name, .repository] | @csv' "$chart_yaml")

    # Check if there are any dependencies
    if [ -z "$deps" ]; then
        echo "No file:// dependencies found in $chart_yaml"
        return
    fi

    # Create charts directory if it doesn't exist
    mkdir -p "$chart_dir/charts"


    while IFS=, read -r name repo; do
        # Clean up the quotes and file:// prefix
        name=$(echo "$name" | tr -d '"')
        repo=$(echo "$repo" | tr -d '"' | sed 's|file://||')

        # Convert relative path to absolute based on Chart.yaml location
        local abs_repo="$(cd "$(dirname "$chart_yaml")/$repo"; pwd)"

        # Get the actual chart name from the target's Chart.yaml
        local target_name=$(yq e '.name' "$abs_repo/Chart.yaml")
        local link_path="$chart_dir/charts/$target_name"

        echo "Creating symlink for $target_name (from dependency $name): $abs_repo -> $link_path"

        # Remove existing symlink if it exists
        [ -L "$link_path" ] && rm "$link_path"

        # Create the symlink with absolute path
        ln -s "$abs_repo" "$link_path"

    done <<< "$deps"

    echo "Running helm dependency build for $chart_dir..."
    helm dependency build "$chart_dir"

    # Remove any tgz files that were created
    rm -f "$chart_dir/charts"/*.tgz
}

# Find all Chart.yaml files and process them
find . -name Chart.yaml -exec dirname {} \; | while read -r chart_dir; do
    process_chart "$chart_dir"
done