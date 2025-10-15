#!/bin/bash
#
# This assumes all of the OS-level configuration has been completed and git repo has already been cloned
#
# This script should be run from the repo's deployment directory
# cd deployment
# ./build-open-source-dist.sh solution-name
#
# Parameters:
#  - solution-name: name of the solution for consistency

# Check to see if input has been provided:
if [ -z "$1" ]; then
    echo "Please provide the trademark approved solution name for the open source package."
    echo "For example: ./build-open-source-dist.sh trademarked-solution-name"
    exit 1
fi

# Get reference for all important folders
source_template_dir="$PWD"
dist_dir="$source_template_dir/open-source"
dist_template_dir="$dist_dir/deployment"

echo "------------------------------------------------------------------------------"
echo "Building open-source folder"
echo "------------------------------------------------------------------------------"
[ -e "$dist_dir" ] && rm -rf "$dist_dir"
rm -rf "$dist_dir"
mkdir -p "$dist_dir"
mkdir -p "$dist_template_dir"

echo "------------------------------------------------------------------------------"
echo "Copying Deployment Folder"
echo "------------------------------------------------------------------------------"
cp "$source_template_dir"/build-s3-dist.sh "$dist_template_dir"
cp "$source_template_dir"/run-unit-tests.sh "$dist_template_dir"

echo "------------------------------------------------------------------------------"
echo "Copying Relevant Root Files"
echo "------------------------------------------------------------------------------"
cp -r "$source_template_dir"/../.github "$dist_dir"
cp "$source_template_dir"/../README.md "$dist_dir"
cp "$source_template_dir"/../CHANGELOG.md "$dist_dir"
cp "$source_template_dir"/../CONTRIBUTING.md "$dist_dir"
cp "$source_template_dir"/../CODE_OF_CONDUCT.md "$dist_dir"
cp "$source_template_dir"/../SECURITY.md "$dist_dir"
cp "$source_template_dir"/../LICENSE.txt "$dist_dir"
cp "$source_template_dir"/../NOTICE.txt "$dist_dir"
cp "$source_template_dir"/../AWSSolutionsArchitecture.png "$dist_dir"

echo "------------------------------------------------------------------------------"
echo "Creating GitHub zip file"
echo "------------------------------------------------------------------------------"
cd "$dist_dir"
echo "open source files::"
ls -alh
zip -q -r9 ../"$1".zip * .github .gitignore
rm -rf * .github .gitignore
mv ../"$1".zip .
echo "Completed building $1.zip"
