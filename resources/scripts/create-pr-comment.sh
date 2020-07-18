#!/bin/bash/

generate_json_payload()
{
  cat <<EOF
  {
    "content": {
      "raw": "${commentBody}"
    }
  }
EOF
}

curl -u ${serviceAccount}:${serviceAccountAppPass} https://api.bitbucket.org/2.0/repositories/${workSpaceBB}/${repoName}/pullrequests/${BITBUCKET_PULL_REQUEST_ID}/comments/ --request POST --header 'Content-Type: application/json' --data "$(generate_json_payload)"
