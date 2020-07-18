#!/bin/bash

generate_json_payload()
{
  cat <<EOF
    {
      "description": "MS3 Jenkins",
      "url": "${JENKINS_URL}bitbucket-hook/",
      "active": true,
      "events": [
        "pullrequest:updated",
        "pullrequest:created",
        "repo:push"
      ]
  }
EOF
}

currentHooks=$(curl -u ${serviceAccount}:${serviceAccountAppPass} https://api.bitbucket.org/2.0/repositories/${workSpaceBB}/${repoName}/hooks |grep "${JENKINS_URL}bitbucket-hook/" | head -n 1)

if [[ "$currentHooks" =~ .*"${JENKINS_URL}bitbucket-hook/".* ]]; then
    echo "Webhook is already present"
else 
    echo "Webhook not found, adding.."
    curl -X POST -u ${serviceAccount}:${serviceAccountAppPass} https://api.bitbucket.org/2.0/repositories/${workSpaceBB}/${repoName}/hooks -H 'Content-Type: application/json' --data "$(generate_json_payload)"
fi
