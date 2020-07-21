#!/bin/bash
# This script works with BitBucket API to refresh the webhooks pointing to Jenkins
# Requires binaries: curl, jq
#   There is an alpine-based docker image that contains all required binaries:
#   docker-public.kube.cloudapps.ms3-inc.com/cicd-tools/git-in-docker:dev-latest

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
        "pullrequest:comment_created",
        "repo:push"
      ]
  }
EOF
}

for bbRepoName in $(cat repolist.txt)
do
  echo "Cleaning up all webhooks for repository $bbRepoName that are pointing to ${JENKINS_URL}bitbucket-hook/ ..."
  for webHookId in $(curl -s -u $serviceAccount:$serviceAccountAppPass https://api.bitbucket.org/2.0/repositories/$bbWorkspace/$bbRepoName/hooks | jq -r '.values[] | select (.url=="'${JENKINS_URL}'bitbucket-hook/") | .uuid')
  do
    echo "Found webhook match with ID: $webHookId"
    if [ -z "$webHookId" ]; then
      echo "Webhook not found."
    else
      echo "Deleting webhook with ID $webHookId..."
      sleep 1s
      curl -X DELETE -u "$serviceAccount:$serviceAccountAppPass" https://api.bitbucket.org/2.0/repositories/$bbWorkspace/$bbRepoName/hooks/$webHookId
    fi
  done
  echo "Adding new webhook to repo $repository..."
  curl -s -X POST -u ${serviceAccount}:${serviceAccountAppPass} https://api.bitbucket.org/2.0/repositories/${bbWorkspace}/${bbRepoName}/hooks -H 'Content-Type: application/json' --data "$(generate_json_payload)"
  echo "Checking:"
  webHookId=$(curl -s -u $serviceAccount:$serviceAccountAppPass https://api.bitbucket.org/2.0/repositories/$bbWorkspace/$bbRepoName/hooks | jq -r '.values[] | select (.url=="'${JENKINS_URL}'bitbucket-hook/") | .uuid')
  if [ -z "$webHookId" ]; then
    echo "ERROR: Webhook was not added for repository $bbRepoName."
  else
    echo "SUCCESS: Webhook was added for repository $bbRepoName."
  fi
done
