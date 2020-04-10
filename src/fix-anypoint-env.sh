#!/bin/bash
# Fix lowercase env name for DOPS-219


env_file=$(ls src/main/resources/|grep .yaml$ |grep 'dev')
if [ ! -z "$env_file" ]; then
    cp src/main/resources/$env_file src/main/resources/Dev.yaml
fi

