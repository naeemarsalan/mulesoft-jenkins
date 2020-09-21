def call(Map pipelineParams) {

  pipeline {
    agent {
      kubernetes {
        yaml libraryResource('kube/agents/simple-dind.yaml')
      }
    }
    stages {
      stage('Prepare Env Vars') {
        steps {
          script {
            // Bitbucket Push and Pull Request Plugin provides this variable containing the branch that triggered this build
            // In very rare circumstances it happens to be null when job is triggered manually, in that case assign to it value of default $GIT_BRANCH variable
              if (env.BITBUCKET_SOURCE_BRANCH == null) { 
                env.BITBUCKET_SOURCE_BRANCH = sh(script: "echo $GIT_BRANCH | grep -oP '(?<=origin/).*'", returnStdout: true).trim()
              }
            // Checkout provided branch 
              echo "Now checking out source branch: ${BITBUCKET_SOURCE_BRANCH}"
              git (url: "${GIT_URL}",
              credentialsId: "${serviceAccount}-bitbucket-ssh-key",
              branch: "${BITBUCKET_SOURCE_BRANCH}")
            // Notify BitBucket that a build was started
            env.repoName = sh(script: 'basename $GIT_URL | sed "s/.git//"', returnStdout: true).trim()
            env.gitCommitID = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
            bitbucketStatusNotify(buildState: 'INPROGRESS', repoSlug: "${repoName}", commitId: "${gitCommitID}")
            // If not set in job properties, set application environment variable depending on git branch
            if (env.appEnv == null) {
              switch(BITBUCKET_SOURCE_BRANCH) {
                case "master":
                  env.appEnv = "prod"
                  break
                case "uat":
                  env.appEnv = "uat"
                  break
                default:
                  env.appEnv = "dev"
                  break
              }
            }
            //Check if imageIsPublic variable is set in Job properties, and if it is not - set it to "false" (var type: string)
            if (env.imageIsPublic == null) {
              env.imageIsPublic = "false"
            }
            // Set docker registry depending on imageIsPublic
            if (env.imageIsPublic == "true") {
              env.dockerUrl = dockerPublicRegistryUrl
            } else {
              env.dockerUrl = dockerPrivateRegistryUrl
            }
            echo "Public image: ${env.imageIsPublic}\nImage env: ${env.appEnv}\nUploaded to: ${env.dockerUrl}"
          }
        }
      }

      stage('Build and Push Docker Image') {
        when {
          expression { env.BITBUCKET_PULL_REQUEST_ID == null }
        }
        steps {
          container('dind') {
            echo "Building application for ${appEnv} environment..."
            withCredentials([usernamePassword(credentialsId: 'nexus', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
              sh """
                docker login ${dockerUrl} -u ${USERNAME} -p ${PASSWORD}
                docker build \
                  -t ${dockerUrl}/${repoName}/${appEnv}:\$(echo ${gitCommitID} | cut -c1-7) \
                  -t ${dockerUrl}/${repoName}/${appEnv}:latest .
                docker push ${dockerUrl}/${repoName}/${appEnv}:\$(echo ${gitCommitID} | cut -c1-7)
                docker push ${dockerUrl}/${repoName}/${appEnv}:latest
              """
            }
          }
        }
      }
    }

    // POST-BUILD NOTIFICATIONS AND INTEGRATIONS
    post {
      always {
        script {
          // Get Build result
          switch(currentBuild.currentResult) {
            case "SUCCESS":
              env.msgColor = "good"
              bitbucketStatusNotify(buildState: 'SUCCESSFUL', repoSlug: "${repoName}", commitId: "${gitCommitID}")
              break
            case "UNSTABLE":
              env.msgColor = "warning"
              bitbucketStatusNotify(buildState: 'SUCCESSFUL', repoSlug: "${repoName}", commitId: "${gitCommitID}")
              break
            default:
              env.msgColor = "danger"
              bitbucketStatusNotify(buildState: 'FAILED', repoSlug: "${repoName}", commitId: "${gitCommitID}")
              break
          }
          //Check if the webhook exists in BitBucket and add it if it doesn't exist
          env.workSpaceBB = sh(script: "echo $GIT_URL | awk '\$0=\$2' FS=: RS=\\/", returnStdout: true).trim()
          withCredentials([string(credentialsId: "${serviceAccount}-bitbucket-app-pass", variable: "serviceAccountAppPass")]) {
            writeFile([file: 'create-bb-webhook.sh', text: libraryResource('scripts/bitbucket-integrations/create-bb-webhook.sh')])
            sh "bash create-bb-webhook.sh"
          }
          // Post a notification in Slack channel
          slackReportMessage = "*${currentBuild.currentResult}:* <${env.BUILD_URL}display/redirect|${env.JOB_NAME}> BUILD #${BUILD_NUMBER}:\n${currentBuild.getBuildCauses()[0].shortDescription}\nTime total: " + sh(script: "echo '${currentBuild.durationString}' | sed 's/and counting//'", returnStdout: true).trim()
          slackSend(
            color: msgColor,
            message: slackReportMessage
          )
        }
      }
    }
  }
}