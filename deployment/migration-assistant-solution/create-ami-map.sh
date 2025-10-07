#!/bin/bash

# Get regions that are "not-opted-in"
NOT_OPTED_IN=$(aws ec2 describe-regions \
  --query "Regions[?OptInStatus=='not-opted-in'].RegionName" \
  --output text)

if [ -n "$NOT_OPTED_IN" ]; then
    echo "ERROR: The following regions have not been opted into, please use another aws account or opt into these regions:"
    # put each region on its own line
    echo "$NOT_OPTED_IN" | tr '\t' '\n' | sort
    exit 1
else
    echo "All available regions have been opted into, continuing..."
fi

# Get the list of all available AWS regions
REGIONS=$(aws ec2 describe-regions --query "Regions[].RegionName" --output text)

declare -A amiMap

# Image name to look up
IMAGE_NAME="/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-6.1-x86_64"

echo "Looking up AMI based on image name for '$IMAGE_NAME' in all regions..."

for region in $REGIONS; do
    echo "Searching in region: $region"
    ami_id=$(aws ssm get-parameter \
        --region $region \
        --name $IMAGE_NAME \
        --query 'Parameter.Value' \
        --output text
        )
    
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
echo "const amiMap: Record<string, string> = {"
for region in $(printf "%s\n" "${!amiMap[@]}" | sort); do
    echo "  '$region': '${amiMap[$region]}',"
done
echo "};"
