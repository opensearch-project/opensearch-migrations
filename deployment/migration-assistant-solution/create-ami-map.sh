#!/bin/bash

# AMI name to look up
AMI_NAME="al2023-ami-2023.6.20250218.2-kernel-6.1-x86_64"
OWNER="amazon"

# Get the list of all available AWS regions
REGIONS=$(aws ec2 describe-regions --query "Regions[].RegionName" --output text)

declare -A amiMap

echo "Looking up AMI IDs for '$AMI_NAME' owned by '$OWNER' in all regions..."

for region in $REGIONS; do
    echo "Searching in region: $region"
    ami_id=$(aws ec2 describe-images \
        --region $region \
        --owners $OWNER \
        --filters "Name=name,Values=$AMI_NAME" \
        --query "Images[0].ImageId" \
        --output text)
    
    if [ "$ami_id" != "None" ]; then
        amiMap[$region]=$ami_id
        echo "Found AMI ID: $ami_id in region $region"
    else
        echo "No AMI found in region $region"
    fi
done

# Generate the AMI map as typescript
echo ""
echo "AMI Map:"
echo "const amiMap = {"
for region in "${!amiMap[@]}"; do
    echo "  '$region': '${amiMap[$region]}',"
done
echo "};"
