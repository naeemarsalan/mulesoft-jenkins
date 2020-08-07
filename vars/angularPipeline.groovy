def call(Map pipelineParams) {

  pipeline {
    agent {
      kubernetes {
        yaml libraryResource('kube/agents/node-dind.yaml')
      }
    }
    stages {
      stage('Prepare Env Vars') {
        steps {
          // the linter script itself should be added later, for now this stage is used to populate environment variables
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
          // Set application environment variable depending on git branch
            if ( BITBUCKET_SOURCE_BRANCH == "master" ) {
              env.appEnv = "prod"
            } else if ( BITBUCKET_SOURCE_BRANCH == "uat" ) {
              env.appEnv = "uat"
            } else {
              env.appEnv = "dev"
            } 
            //check if target deployment (k8s) repo and branch is set, and if it is not, default to ms3 Kubernetes repo and master branch
            if (env.targetRepoName == null) { env.targetRepoName = "${ms3KubeRepo}"}
            if (env.targetBranch == null) { env.targetBranch = "master" }
          }
        }
      }

      stage('Test') {
        steps {
          container('node') {
            script {
              // Get application version from package.json
              env.appVersion = sh(script: '''node -p -e "require('./package.json').version"''', returnStdout: true).trim()
              // Run tests with coverage report
              sh """
                npm install
                npm run test-coverage
                ls -lsa
              """
              publishHTML (target: [
                allowMissing: false,
                alwaysLinkToLastBuild: false,
                keepAll: true,
                reportDir: 'coverage/lcov-report',
                reportFiles: 'index.html',
                reportName: "Coverage Report"
              ])
            }
          }
        }
      }

      stage('Build and Push Docker') {
        when {
          expression { env.BITBUCKET_PULL_REQUEST_ID == null }
        }
        steps {
          container('dind') {
            echo "Building application for ${appEnv} environment..."
            withCredentials([usernamePassword(credentialsId: 'nexus', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
              sh """
                docker login ${dockerRegistryUrl} -u ${USERNAME} -p ${PASSWORD}
                docker build --build-arg configuration=${configuration} -t ${dockerRegistryUrl}/${projectName}/${repoName}:${appEnv}-${appVersion}-\$(echo ${gitCommitID} | cut -c1-7) -t ${dockerRegistryUrl}/${projectName}/${repoName}:${appEnv}-latest .
                docker push ${dockerRegistryUrl}/${projectName}/${repoName}:${appEnv}-${appVersion}-\$(echo ${gitCommitID} | cut -c1-7)
                docker push ${dockerRegistryUrl}/${projectName}/${repoName}:${appEnv}-latest
              """
            }
          }
        }
      }

      stage('Generate App Manifest') {
        when {
          expression { env.BITBUCKET_PULL_REQUEST_ID == null }
        }
        steps {
          git (
            url: "${targetRepoName}",
            poll: false,
            changelog: false,
            credentialsId: "${serviceAccount}-bitbucket-ssh-key",
            branch: "${targetBranch}"
          )
          script {
            if (fileExists("namespaces/${projectName}-${appEnv}/${repoName}.yaml")) {
              echo "Deployment manifest already exists in k8s repository. Used Docker image tag will be updated automatically in a few minutes."
            } else {
              container('git-alpine') {
                echo 'Deployment manifest not found, adding now...'
                writeFile([file: "deployment-template.yaml", text: libraryResource("kube/manifests/angular/deployment.yaml")])
                writeFile([file: "create-pr-template.json", text: libraryResource("templates/create-pr-bitbucket.json")])
                writeFile([file: 'create-pr.sh', text: libraryResource('scripts/create-pr.sh')])
                // substitute all variables in deployment manifest and add it to target directory/namespace
                sh """
                  mkdir -p namespaces/${projectName}-${appEnv}/
                  envsubst < deployment-template.yaml > namespaces/${projectName}-${appEnv}/${repoName}.yaml
                  cat namespaces/${projectName}-${appEnv}/${repoName}.yaml
                """
                // parse and populate variables that will be used by deployment script
                env.targetRepoOwner = sh(script: "echo $targetRepoName | awk '\$0=\$2' FS=: RS=\\/", returnStdout: true).trim()
                env.targetRepoName = sh(script: 'basename $targetRepoName | sed "s/.git//"', returnStdout: true).trim()
                env.addedFiles = "namespaces/${projectName}-${appEnv}/${repoName}.yaml"
                env.commitMessage = "Added deployment manifest for ${projectName}-${appEnv}/${repoName}"
                env.featureBranch = "feature/deployment-of-${repoName}-${appEnv}-build-${BUILD_NUMBER}"
                // run the PR creation script
                withCredentials([string(credentialsId: "${serviceAccount}-bitbucket-api-pass", variable: "serviceAccountAppPass")]) {
                  sshagent(["${serviceAccount}-bitbucket-ssh-key"]) { sh "sh create-pr.sh" }
                echo "Please review and merge the related Pull Request in order to deploy the application to Kubernetes:\nhttps://bitbucket.org/${targetRepoOwner}/${targetRepoName}/pull-requests/"
                }
              }
            }
          }
        }
      }

      stage('Add Version Tag') {
        when {
          expression { BITBUCKET_SOURCE_BRANCH == "master" }
        }
        steps {
           sshagent(["${serviceAccount}-bitbucket-ssh-key"]) {
            sh """
              mkdir -p ~/.ssh && ssh-keyscan -t rsa bitbucket.org >> ~/.ssh/known_hosts
              git config --global user.email "jenkins@ms3-inc.com"
              git config --global user.name "MS3 Jenkins"
              git tag v${appVersion}
              git push origin --tags
            """
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
          //add a comment to Pull Request if this is a PR, using the same credentials
            if (env.BITBUCKET_PULL_REQUEST_ID != null) {
              env.commentBody = "Build [#${BUILD_NUMBER}](${BUILD_URL}) with result: ${currentBuild.currentResult}  \\n[Tests coverage report](${JOB_URL}Coverage_20Report/)"
              writeFile([file: 'create-pr-comment.sh', text: libraryResource('scripts/bitbucket-integrations/create-pr-comment.sh')])
              sh "bash create-pr-comment.sh"
            } else {
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
  }
}