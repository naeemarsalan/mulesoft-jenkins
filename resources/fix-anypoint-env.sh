#!/bin/bash
# Fix lowercase env name for DOPS-219

echo "Before"
ls src/main/resources
env_file=$(ls src/main/resources/|grep 'dev')
if [ ! -z "$env_file" ]; then
    new_env_file=$(echo "env_file" | sed -e "s/dev/\Dev/g")
    cp src/main/resources/$env_file src/main/resources/$new_env_file
fi
echo "After"
ls src/main/resources
