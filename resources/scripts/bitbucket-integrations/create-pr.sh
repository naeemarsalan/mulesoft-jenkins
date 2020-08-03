#!/bin/sh
# This script works with BitBucket API to open a Pull Request from feature to target branch.
# Requires binaries: git, curl, envsubst, awk, sed, cut, jq
#   There is an alpine-based docker image that contains all required binaries:
#   docker-public.kube.cloudapps.ms3-inc.com/cicd-tools/git-in-docker:dev-latest
# Requires additional input from pipeline: 
#   targetRepoOwner             same as git username
#   targetRepoName              name of the repo
#   serviceAccountAppPass       BitBucket App Password
#   targetBranch                target branch of the PR
#   featureBranch               source branch of the PR 
#   addedFiles                  
#   commitMessage

generate_json_payload()
{
  cat <<EOF
{
  "rendered": {
    "description": {
      "raw": "",
      "markup": "markdown",
      "html": "",
      "type": "rendered"
    },
    "title": {
      "raw": "${commitMessage}",
      "markup": "markdown",
      "html": "<p>${commitMessage}</p>",
      "type": "rendered"
    }
  },
  "type": "pullrequest",
  "description": "",
  "title": "${commitMessage}",
  "close_source_branch": true,
  "reviewers": [
    {
      "display_name": "Mohammad Naeem",
      "uuid": "{1332b350-2d23-4c5e-8f66-5a629d826ea2}",
      "links": {
        "self": {
          "href": "https://api.bitbucket.org/2.0/users/%7B1332b350-2d23-4c5e-8f66-5a629d826ea2%7D"
        },
        "html": {
          "href": "https://bitbucket.org/%7B1332b350-2d23-4c5e-8f66-5a629d826ea2%7D/"
        },
        "avatar": {
          "href": "https://secure.gravatar.com/avatar/197c5f3f1b80c3e8d1481db1b8c675a7?d=https%3A%2F%2Favatar-management--avatars.us-west-2.prod.public.atl-paas.net%2Finitials%2FMN-1.png"
        }
      },
      "nickname": "Mohammad Naeem",
      "type": "user",
      "account_id": "5cc5afb2a6e4950feacb55bf"
    }
  ],
  "destination": {
    "commit": {
      "hash": "${targetBrCommitId}",
      "type": "commit",
      "links": {
        "self": {
          "href": "https://api.bitbucket.org/2.0/repositories/${targetRepoOwner}/${targetRepoName}/commit/${targetBrCommitId}"
        },
        "html": {
          "href": "https://bitbucket.org/${targetRepoOwner}/${targetRepoName}/commits/${targetBrCommitId}"
        }
      }
    },
    "repository": {
      "links": {
        "self": {
          "href": "https://api.bitbucket.org/2.0/repositories/${targetRepoOwner}/${targetRepoName}"
        },
        "html": {
          "href": "https://bitbucket.org/${targetRepoOwner}/${targetRepoName}"
        },
        "avatar": {
          "href": "https://bytebucket.org/ravatar/%7B${targetRepoUuid}%7D?ts=default"
        }
      },
      "type": "repository",
      "name": "${targetRepoName}",
      "full_name": "${targetRepoOwner}/${targetRepoName}",
      "uuid": "${targetRepoUuid}"
    },
    "branch": {
      "name": "${targetBranch}"
    }
  },
  "source": {
    "commit": {
      "hash": "${featureBrCommitId}",
      "type": "commit",
      "links": {
        "self": {
          "href": "https://api.bitbucket.org/2.0/repositories/${targetRepoOwner}/${targetRepoName}/commit/${featureBrCommitId}"
        },
        "html": {
          "href": "https://bitbucket.org/${targetRepoOwner}/${targetRepoName}/commits/${featureBrCommitId}"
        }
      }
    },
    "repository": {
      "links": {
        "self": {
          "href": "https://api.bitbucket.org/2.0/repositories/${targetRepoOwner}/${targetRepoName}"
        },
        "html": {
          "href": "https://bitbucket.org/${targetRepoOwner}/${targetRepoName}"
        }
      },
      "type": "repository",
      "name": "${targetRepoName}",
      "full_name": "${targetRepoOwner}/${targetRepoName}",
      "uuid": "${targetRepoUuid}"
    },
    "branch": {
      "name": "${featureBranch}"
    }
  },
  "state": "OPEN"
}
EOF
}

# add bitbucket.org to known hosts and setup git
mkdir -p ~/.ssh && ssh-keyscan -t rsa bitbucket.org >> ~/.ssh/known_hosts
git config --global user.email "jenkins@ms3-inc.com"
git config --global user.name "MS3 Jenkins"

# parse reply from BB API and get repo's UUID:
export targetRepoUuid=$(curl -s https://api.bitbucket.org/2.0/repositories/$targetRepoOwner/$targetRepoName/ -u $serviceAccount:$serviceAccountAppPass | jq -r '.uuid')

# get target branch's last commit ID, assuming it is already checked out by the pipeline
export targetBrCommitId=$(git rev-parse HEAD | cut -c1-12)

# start a feature branch, add files and push to the repo
git checkout -b $featureBranch
git add $addedFiles
git commit -am "$commitMessage"
git push origin $featureBranch

# get feature branch's last commit ID
export featureBrCommitId=$(git rev-parse HEAD | cut -c1-12)

# create a PR using POST request to BitBucket's API
curl -s https://api.bitbucket.org/2.0/repositories/$targetRepoOwner/$targetRepoName/pullrequests -u $serviceAccount:$serviceAccountAppPass --request POST --header 'Content-Type: application/json' --data "$(generate_json_payload)"

printf "Please review and merge the following Pull Request in order to deploy the application to Kubernetes:\nhttps://bitbucket.org/${targetRepoOwner}/${targetRepoName}/pull-requests/"
