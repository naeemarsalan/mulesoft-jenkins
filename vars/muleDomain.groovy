// This pipeline requires no parameters as input
def call(Map pipelineParams) {

  pipeline {
    agent {
      kubernetes {
        yaml libraryResource('kube/agents/maven.yaml')
      }
    }

    stages {
      stage('Prepare Env Vars') {
        steps {
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
        // Populate env vars from pom.xml file
          env.packaging = readMavenPom().getPackaging()
          env.artifactName = readMavenPom().getArtifactId()
          env.version = readMavenPom().getVersion()
          env.groupName = readMavenPom().getGroupId()
        // Target Nexus repository depends on if the app's version is a snapshot or not
          if ("${version}" =~ "SNAPSHOT") {
            nexusUrl = nexusSnapshotUrl
          } else {
              nexusUrl = nexusReleaseUrl
            }
        }
      }
      stage('Linter') {
        when {
          expression { return readFile('pom.xml').contains('<packaging>mule-domain</packaging>') }
        }
        steps {
          container('maven') {
            script {
            // Run linter script
              writeFile([file: 'ms3-mule-domain-linter.sh', text: libraryResource('scripts/ms3-mule-domain-linter.sh')])
              sh "bash ms3-mule-domain-linter.sh"
            }
          }
        }
      }

      stage('Test') {
        when {
          expression { return readFile('pom.xml').contains('<packaging>mule-domain</packaging>') }
        }
        steps {
          container('maven') {
            script {
              configFileProvider([configFile(fileId: 'maven_settings', variable: 'MAVEN_SETTINGS_FILE')]) {
                withCredentials([usernamePassword(credentialsId: 'devoptions', passwordVariable: 'appkey', usernameVariable: 'appenv')]) {
                sh "mvn -s '$MAVEN_SETTINGS_FILE' clean test -Denv=${maven_env} -Dapp.key=${appkey}"
                }
              }
            }
          }
        }
      }

      stage('Build') {
        when {
          expression { BITBUCKET_SOURCE_BRANCH ==~ /(master|develop)/ }
        }
        steps {
          container('maven') {
            script {
              configFileProvider([configFile(fileId: 'maven_settings', variable: 'MAVEN_SETTINGS_FILE')]) {
                sh "mvn -s '$MAVEN_SETTINGS_FILE' clean package -DskipTests"
              }
            }
          }
        }
      }

      stage('Upload to Nexus') {
        when {
          expression { BITBUCKET_SOURCE_BRANCH ==~ /(master|develop)/ }
        }
        steps {
          container('maven') {
            script {
              configFileProvider([configFile(fileId: 'maven_settings', variable: 'MAVEN_SETTINGS_FILE')]) {
                dir('target') {
                  sh "mvn -s '$MAVEN_SETTINGS_FILE' deploy:deploy-file -DgroupId=${groupName} -DartifactId=${artifactName} -Dversion=${version} -DgeneratePom=true -Dpackaging=jar -DrepositoryId=nexus -Durl=${nexusUrl} -Dfile=${artifactName}-${version}-${packaging}.jar -DuniqueVersion=false -Dclassifier=${packaging}"
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
              git tag v${version}
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
              env.commentBody = "Build [#${BUILD_NUMBER}](${BUILD_URL}) with result: ${currentBuild.currentResult}  \\nPlease check [linter script output](${BUILD_URL}execution/node/40/log/)"
              writeFile([file: 'create-pr-comment.sh', text: libraryResource('scripts/bitbucket-integrations/create-pr-comment.sh')])
              sh "bash create-pr-comment.sh"
            }
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
