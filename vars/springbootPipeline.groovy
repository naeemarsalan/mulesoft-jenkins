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
          // Read pom.xml and populate vars
            env.artifactName = readMavenPom().getArtifactId()
            env.groupName = readMavenPom().getGroupId()
            env.appVersion = readMavenPom().getVersion()
            env.majorVer = sh(script: "echo $appVersion | awk -F\\. '{print \$1}'", returnStdout: true).trim()
            env.javaVersion = readMavenPom().getProperties().getProperty('java.version')
          // Set application environment variable depending on git branch
            if ( BITBUCKET_SOURCE_BRANCH == "master" ) {
              env.appEnv = "prod"
            } else if ( BITBUCKET_SOURCE_BRANCH == "uat" ) {
              env.appEnv = "uat"
            } else {
              env.appEnv = "dev"
            } 

          // Java artifact will be uploaded to Nexus, target repository depends on is the artifacts version a -SNAPSHOT or not
            if ("${appVersion}" =~ "SNAPSHOT") {
                env.nexusUrl = nexusSnapshotUrl
            } else {
              env.nexusUrl = nexusReleaseUrl
            }
          //check if target deployment (k8s) repo and branch is set, and if it is not, default to ms3 Kubernetes repo and master branch
            if (env.targetRepoName == null) { env.targetRepoName = "${ms3KubeRepo}"}
            if (env.targetBranch == null) { env.targetBranch = "master" }
          }
        }
      }

      stage('Test') {
        steps {
          container("maven-jdk-${javaVersion}") {
            script{
              configFileProvider([configFile(fileId: 'maven_settings', variable: 'MAVEN_SETTINGS_FILE')]) {
                sh "mvn -s '$MAVEN_SETTINGS_FILE' clean test"
              }
            } 
          }
        }
      }

      stage('Build Java Artifact') {
        when {
          expression { env.BITBUCKET_PULL_REQUEST_ID == null }
        }
        steps {
          container("maven-jdk-${javaVersion}") {
            script{
              configFileProvider([configFile(fileId: 'maven_settings', variable: 'MAVEN_SETTINGS_FILE')]) {
                sh "mvn -s '$MAVEN_SETTINGS_FILE' clean package -DskipTests"
              }
            }
          }
        }
      }

      stage('Upload To Nexus') {
        when {
          expression { env.BITBUCKET_PULL_REQUEST_ID == null }
        }
        steps {
          container("maven-jdk-${javaVersion}") {
            script {
              echo "Artifact is being uploaded to: ${nexusUrl}"
              configFileProvider([configFile(fileId: 'maven_settings', variable: 'MAVEN_SETTINGS_FILE')]) {
                dir('target') {
                  sh "mvn -s '$MAVEN_SETTINGS_FILE' deploy:deploy-file -DgroupId=${groupName} -DartifactId=${artifactName} -Dversion=${appVersion} -DgeneratePom=true -Dpackaging=jar -DrepositoryId=nexus -Durl=${nexusUrl} -Dfile=${artifactName}-${appVersion}.jar -DuniqueVersion=false"
                }
              }
            }
          }
        }
      }

      stage('Build Docker Image') {
        when {
          expression { env.BITBUCKET_PULL_REQUEST_ID == null }
        }
        steps {
          container('dind') {
            withCredentials([usernamePassword(credentialsId: 'nexus', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
              sh """
                docker login ${dockerRegistryUrl} -u ${USERNAME} -p ${PASSWORD}
                docker build -t ${dockerRegistryUrl}/${projectName}/${repoName}:${appEnv}-${appVersion}-\$(echo ${gitCommitID} | cut -c1-7) -t ${dockerRegistryUrl}/${projectName}/${repoName}:${appEnv}-latest .
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
            // check if deployment already exists
            if (fileExists("namespaces/${repoName}-${appEnv}/v${majorVer}.yaml")) {
              echo "Deployment manifest already exists in k8s repository. Used Docker image tag will be updated automatically in a few minutes."
            } else {
              container('git-in-docker') {
                echo 'Deployment manifest not found, adding now...'
                writeFile([file: "namespace-template.yaml", text: libraryResource("kube/manifests/namespace.yaml")])
                writeFile([file: "deployment-template.yaml", text: libraryResource("kube/manifests/javaspringboot/deployment.yaml")])
                // substitute all variables in deployment manifest and add it to target directory/namespace
                sh """
                  mkdir namespaces/${repoName}-${appEnv}/
                  envsubst < deployment-template.yaml > namespaces/${repoName}-${appEnv}/v${majorVer}.yaml
                  envsubst < namespace-template.yaml > namespaces/${repoName}-${appEnv}/namespace.yaml
                """
                // parse and populate variables that will be used by deployment script
                env.targetRepoOwner = sh(script: "echo $targetRepoName | awk '\$0=\$2' FS=: RS=\\/", returnStdout: true).trim()
                env.targetRepoName = sh(script: 'basename $targetRepoName | sed "s/.git//"', returnStdout: true).trim()
                env.addedFiles = "namespaces/${repoName}-${appEnv}/*"
                env.commitMessage = "Added deployment manifest for ${repoName}-${appEnv}/v${majorVer}"
                env.featureBranch = "feature/deployment-of-${repoName}-${appEnv}/v${majorVer}-build-${BUILD_NUMBER}"

                // run the PR creation script
                writeFile([file: 'create-pr-bitbucket.sh', text: libraryResource('scripts/bitbucket-integrations/create-pr.sh')])
                withCredentials([string(credentialsId: "${serviceAccount}-bitbucket-api-pass", variable: "serviceAccountAppPass")]) {
                  sshagent(["${serviceAccount}-bitbucket-ssh-key"]) { sh "sh create-pr-bitbucket.sh" }
                }
              }
            }
            echo "Access to application ${repoName} should be set through Kong Proxy, using the internal cluster URL:\nv${majorVer}.${repoName}-${appEnv}.svc.cluster.local"
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
              env.commentBody = "Build [#${BUILD_NUMBER}](${BUILD_URL}) with result: ${currentBuild.currentResult}"
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