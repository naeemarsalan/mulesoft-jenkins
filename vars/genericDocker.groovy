def call(Map pipelineParams) {

  pipeline {
    agent {
      kubernetes {
        yaml libraryResource('kube/agents/dockerInDocker.yaml')
      }
    }
    stages {
      stage('Prepare Env Vars') {
        steps {
          script {
          // Notify BitBucket that a build was started
            env.repoName = sh(script: 'basename $GIT_URL | sed "s/.git//"', returnStdout: true).trim()
            env.gitCommitID = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
            bitbucketStatusNotify(buildState: 'INPROGRESS', repoSlug: "${repoName}", commitId: "${gitCommitID}")
            // Set application environment variable depending on git branch
            if ( GIT_BRANCH ==~ /(.*master)/ ) {
              env.appEnv = "prod"
            }
            if ( GIT_BRANCH ==~ /(.*develop)/ ) {
              env.appEnv = "dev"
            }
            if ( GIT_BRANCH ==~ /(.*uat)/ ) {
              env.appEnv = "uat"
            }
            //Check if imageIsPublic variable is set in Job properties, and if it is not - set it to "false" (var type: string)
            if (imageIsPublic == null) {
              env.imageIsPublic = "false"
            }
            // Set docker registry depending on imageIsPublic
            if (imageIsPublic == "true") {
              env.dockerUrl = dockerPublicRegistryUrl
            } else {
              env.dockerUrl = dockerPrivateRegistryUrl
            }
            echo "Public: ${imageIsPublic}, Docker URL: ${dockerUrl}"
          }
        }
      }

      stage('Build and Push Docker Image') {
        when {
          expression { GIT_BRANCH ==~ /(.*master|.*develop|.*uat)/ }
        }
        steps {
          container('dind') {
            echo "Building application for ${appEnv} environment..."
            withCredentials([usernamePassword(credentialsId: 'nexus', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
              sh """
                docker login ${dockerUrl} -u ${USERNAME} -p ${PASSWORD}
                docker build -t ${dockerUrl}/${projectName}/${repoName}:${appEnv}-\$(echo ${gitCommitID} | cut -c1-7) -t ${dockerUrl}/${projectName}/${repoName}:${appEnv}-latest .
                docker push ${dockerUrl}/${projectName}/${repoName}:${appEnv}-\$(echo ${gitCommitID} | cut -c1-7)
                docker push ${dockerUrl}/${projectName}/${repoName}:${appEnv}-latest
              """
            }
          }
        }
      }
    }

    // POST-BUILD NOTIFICATIONS AND INTEGRATIONS
    post {
      success {
        bitbucketStatusNotify(buildState: 'SUCCESSFUL', repoSlug: "${repoName}", commitId: "${gitCommitID}")
      }
      failure {
        bitbucketStatusNotify(buildState: 'FAILED', repoSlug: "${repoName}", commitId: "${gitCommitID}")
      }
      always {
        script {
          //Check if the webhook exists in BitBucket and add it if it doesn't exist
          env.workSpaceBB = sh(script: "echo $GIT_URL | awk '\$0=\$2' FS=: RS=\\/", returnStdout: true).trim()
          withCredentials([string(credentialsId: "${serviceAccount}-bitbucket-app-pass", variable: "serviceAccountAppPass")]) {
            writeFile([file: 'create-bb-webhook.sh', text: libraryResource('scripts/bitbucket-integrations/create-bb-webhook.sh')])
            sh "bash create-bb-webhook.sh"
          }
          // Post a notification in Slack channel
          if ( currentBuild.currentResult == "SUCCESS")
            env.msgColor = "good"
          else 
            env.msgColor = "danger"
          slackSend(
            color: msgColor,
            message: "*${currentBuild.currentResult}:* Job ${JOB_NAME} build ${BUILD_NUMBER}\n More info at: ${env.BUILD_URL}"
          )
        }
      }
    }
  }
}