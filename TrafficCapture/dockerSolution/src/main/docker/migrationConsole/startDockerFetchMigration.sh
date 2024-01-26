#!/bin/bash

container_id=$(docker ps -a | grep "fetch-migration" | awk '{print $1}')

if [ ! -z "$container_id" ]; then
    docker start $container_id
else
    echo "No container found with 'fetch-migration' in its name"
fi