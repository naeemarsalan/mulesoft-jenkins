#!/bin/sh
# This script works with BitBucket API to open a Pull Request from feature to target branch.
# Requires binaries: git, curl, envsubst, awk, sed, cut, jq
#   There is an alpine-based docker image that contains all required binaries:
#   docker-public.kube.cloudapps.ms3-inc.com/cicd-tools/git-in-docker:dev-latest
# Requires additional input from pipeline: 
#   targetRepoOwner             same as git username
#   targetRepoName              name of the repo
#   serviceAccountAppPass       BitBucket App Password
#   targetBranch        
#   featureBranch       
#   addedFiles
#   commitMessage
set -v
# add bitbucket.org to known hosts and setup git
mkdir -p ~/.ssh && ssh-keyscan -t rsa bitbucket.org >> ~/.ssh/known_hosts
git config --global user.email "jenkins@ms3-inc.com"
git config --global user.name "MS3 Jenkins"

# parse reply from BB API and get repo's UUID:
export targetRepoUuid=$(curl -s https://api.bitbucket.org/2.0/repositories/$targetRepoOwner/$targetRepoName/ -u $serviceAccount:$serviceAccountAppPass | jq . | grep uuid |head -1 | awk '$0=$2' FS={ RS=\})

# get target branch's last commit ID, assuming it is already checked out by the pipeline
export targetBrCommitId=$(git log -p -1 | sed -n -e "s/commit //p" | cut -c1-12)

# start a feature branch and add files
git checkout -b $featureBranch
git add $addedFiles
git commit -am "$commitMessage"
git push origin $featureBranch

# get feature branch's last commit ID
export featureBrCommitId=$(git log -p -1 | sed -n -e "s/commit //p" | cut -c1-12)

# parse json file and populate it with data
envsubst < create-pr-template.json > create-pr.json 

# create a PR using POST request to BitBucket's API
curl -s https://api.bitbucket.org/2.0/repositories/$targetRepoOwner/$targetRepoName/pullrequests \
    -u $serviceAccount:$serviceAccountAppPass \
    --request POST \
    --header 'Content-Type: application/json' \
    --data @create-pr.json
set +v