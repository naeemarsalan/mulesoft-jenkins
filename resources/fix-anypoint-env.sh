#!/bin/bash
# Fix lowercase env name for DOPS-219

dev_old=$(ls src/main/resources/|grep dev)
dev_new=$(echo "$dev_old" | sed 's/dev/Dev/')
if [ ! -z "$dev_old" ]; then
    cp src/main/resources/$dev_old src/main/resources/$dev_new
fi

prod_old=$(ls src/main/resources/|grep prod)
prod_new=$(echo "$prod_old" | sed 's/prod/Production/')
if [ ! -z "$prod_old" ]; then
    cp src/main/resources/$prod_old src/main/resources/$prod_new
fi

ls src/main/resources
