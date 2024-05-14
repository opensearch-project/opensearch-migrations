#!/bin/bash

if [ "$#" -ne 2 ]; then
    echo "Usage: $0 <source_directory> <destination_directory>"
    exit 1
fi

destination_dir=`cd $2; echo $PWD`
cd $1 || exit

copy_files() {
#    set -x
    local file="$1"
    local destination_dir="$2"
    local relative_path="${file#./}"

    if git check-ignore -q "$relative_path"; then
        echo "Ignoring: $relative_path"
    else
        mkdir -p "$destination_dir/$(dirname "$relative_path")"
        cp -r "$relative_path" "$destination_dir/$relative_path"
    fi
}

export -f copy_files
git ls-files -z | parallel -0 copy_files {} "$destination_dir"
