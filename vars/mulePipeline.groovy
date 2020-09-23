// This pipeline requires no parameters as input
def call(Map pipelineParams) {

  pipeline {
    agent {
      kubernetes {
        yaml libraryResource('kube/agents/anypoint-cli.yaml')
      }
    }
    parameters {
      booleanParam(name: 'skipCI', defaultValue: false, description: 'Skip CI part, and deploy a previously built artifact of the same version from Nexus repository.')
    }

    stages {
      stage('Prepare Env Vars') {
        steps {
          script {
            env.LAST_EXECUTED_STAGE = STAGE_NAME
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
                env.nexusUrl = nexusSnapshotUrl
              } else {
                env.nexusUrl = nexusReleaseUrl
              }
            // If not set in job properties, set Maven environment variable depending on git branch
              if (env.maven_env == null) {
                switch(BITBUCKET_SOURCE_BRANCH) {
                  case "master":
                    env.maven_env = "prod"
                    break
                  case "uat":
                    env.maven_env = "uat"
                    break
                  default:
                    env.maven_env = "dev"
                    break
                }
              }
            // Start collecting the build report
            env.SLACK_REPORT_MSG = "\n${currentBuild.getBuildCauses()[0].shortDescription}"
            // Verify if skipping of CI part and deployment directly from Nexus repository is set to true
              echo "skipCI: ${params.skipCI}"
              if (params.skipCI == true) {
                echo "Skipping CI part, going to redeploy a previously built artifact from Nexus..."
                writeFile([file: 'download-from-nexus.sh', text: libraryResource('scripts/download-from-nexus.sh')])
                sh "bash download-from-nexus.sh"
              }
          }
        }
      }

      stage('Linter') {
        when {
          expression { return readFile('pom.xml').contains('<packaging>mule-application</packaging>') }
          expression { params.skipCI == false }
        }
        steps {
          script {
            env.LAST_EXECUTED_STAGE = STAGE_NAME
            // Run linter script
            writeFile([file: 'ms3-mule-linter.sh', text: libraryResource('scripts/ms3-mule-linter.sh')])
            sh "bash ms3-mule-linter.sh | tee linter-output.txt"
          }
        }
        post {
          cleanup {
            script {
              publishHTML (target: [
                allowMissing: true,
                alwaysLinkToLastBuild: false,
                keepAll: true,
                reportDir: '.',
                reportFiles: 'linter-output.txt',
                reportName: "Linter Output"
              ])
            }
          }
        }
      }

      stage('Unit Tests') {
        when {
          expression { return readFile('pom.xml').contains('<packaging>mule-application</packaging>') }
          expression { params.skipCI == false }
        }
        steps {
          container('maven') {
            script {
              env.LAST_EXECUTED_STAGE = STAGE_NAME
              echo "Running tests against branch: ${BITBUCKET_SOURCE_BRANCH}, env: ${maven_env}"
              configFileProvider([configFile(fileId: 'maven_settings', variable: 'MAVEN_SETTINGS_FILE')]) {
                withCredentials([string(credentialsId: "${maven_env}-encryptor-pwd", variable: 'encryptorPasswd')]) {
                  sh "mvn -s '$MAVEN_SETTINGS_FILE' clean test -Denv=${maven_env} -Dapp.key=${encryptorPasswd}"
                }
              }
            }
          }
        }
        post {
          cleanup {
            script {
              publishHTML (target: [
                allowMissing: true,
                alwaysLinkToLastBuild: false,
                keepAll: true,
                reportDir: 'target/site/munit/coverage',
                reportFiles: 'summary.html',
                reportName: "Coverage Report"
              ])
            }
          }
        }
      }

      stage('Build') {
        when {
          expression { env.BITBUCKET_PULL_REQUEST_ID == null }  // Check if this is not a test against a PR
          expression { params.skipCI == false }
        }
        steps {
          container('maven') {
            script {
              env.LAST_EXECUTED_STAGE = STAGE_NAME
              echo "Building..."
              configFileProvider([configFile(fileId: 'maven_settings', variable: 'MAVEN_SETTINGS_FILE')]) {
                sh "mvn -s '$MAVEN_SETTINGS_FILE' clean package -DskipTests"
              }
            }
          }
        }
      }

      stage('Upload to Nexus') {
        when {
          expression { env.BITBUCKET_PULL_REQUEST_ID == null }
          expression { params.skipCI == false }
        }
        steps {
          container('maven') {
            script {
              env.LAST_EXECUTED_STAGE = STAGE_NAME
              echo "Uploading..."
              configFileProvider([configFile(fileId: 'maven_settings', variable: 'MAVEN_SETTINGS_FILE')]) {
                echo "Artifact is being uploaded to: ${nexusUrl}"
                dir('target') {
                  sh "mvn -s '$MAVEN_SETTINGS_FILE' deploy:deploy-file -DgroupId=${groupName} -DartifactId=${artifactName} -Dversion=${version} -DgeneratePom=true -Dpackaging=jar -DrepositoryId=nexus -Durl=${nexusUrl} -Dfile=${artifactName}-${version}-${packaging}.jar -DuniqueVersion=false -Dclassifier=${packaging}"
                }
              }
            }
          }
        }
      }

      stage('Deploy to Anypoint') {
        when {
          expression { env.BITBUCKET_PULL_REQUEST_ID == null }
          expression { return readFile('pom.xml').contains('<packaging>mule-application</packaging>') }
        }
        steps {
          container('anypoint-cli') {
            script {
              env.LAST_EXECUTED_STAGE = STAGE_NAME
              withCredentials([usernamePassword(credentialsId: 'anypointplatform', passwordVariable: 'anypoint_pass', usernameVariable: 'anypoint_user')]) {
                  dir('target') {
                    // Get app description from Anypoint
                    env.appStatus = sh (returnStdout: true, script: "set +e; anypoint-cli --username=${anypoint_user} --password=${anypoint_pass} --environment=${anypoint_env} runtime-mgr standalone-application describe ${artifactName}; set -e")
                    echo "Application description from Anypoint:\n${env.appStatus}"
                    if (env.appStatus =~ "not found")
                      // Deploy new app if not found
                      sh "anypoint-cli --username=${anypoint_user} --password=${anypoint_pass} --environment=${anypoint_env} runtime-mgr standalone-application deploy ${anypoint_server} ${artifactName} ${artifactName}-${version}-${packaging}.jar"
                    else
                      // Modify existing app with a newer artifact
                      sh "anypoint-cli --username=${anypoint_user} --password=${anypoint_pass} --environment=${anypoint_env} runtime-mgr standalone-application modify ${artifactName} ${artifactName}-${version}-${packaging}.jar"
                  }
                  echo "Checking if the application has started (in 4 attempts with 1 minute interval)..."
                  retry(4) {
                    script {
                      sleep(time: 1, unit: 'MINUTES')
                      // Get app status from Anypoint
                      env.appStatus = sh (returnStdout: true, script: "anypoint-cli --username=${anypoint_user} --password=${anypoint_pass} --environment=${anypoint_env} runtime-mgr standalone-application describe ${artifactName} -f Status | awk '{print \$2}' | head -c -1")
                      echo "Application status: ${env.appStatus}"
                      if (env.appStatus =~ "STARTED") {
                        sh "exit 0"
                      } else {
                        sh "exit 1"
                      }
                    }
                  }
              }
            }
          }
        }
      }

      stage('Integration Tests') {
        when {
          expression { fileExists("src/test/resources/integration-tests/${repoName}.postman_collection.json") }
          expression { fileExists("src/test/resources/integration-tests/${maven_env}.postman_environment.json") }
        }
        steps {
          container('newman') {
            script {
              env.LAST_EXECUTED_STAGE = STAGE_NAME
              echo "Running Integration tests."
              catchError(buildResult: 'UNSTABLE', stageResult: 'UNSTABLE') {
                sh """
                  newman run src/test/resources/integration-tests/${repoName}.postman_collection.json \
                    -e src/test/resources/integration-tests/${maven_env}.postman_environment.json \
                    --reporters cli,html \
                    --reporter-html-export target/site/newman/integration-tests-report.html
                """
              }
            }
          }
        }
        post {
          cleanup {
            script {
              publishHTML (target: [
                allowMissing: true,
                alwaysLinkToLastBuild: false,
                keepAll: true,
                reportDir: 'target/site/newman',
                reportFiles: 'integration-tests-report.html',
                reportName: "Integration Tests Report"
              ])
            }
          }
        }
      }

      stage('Add Release Tag') {
        when {
          expression { BITBUCKET_SOURCE_BRANCH == "master" }
        }
        steps {
          script {
            env.LAST_EXECUTED_STAGE = STAGE_NAME
          }
          sshagent(["${serviceAccount}-bitbucket-ssh-key"]) {
            sh """
              mkdir -p ~/.ssh && ssh-keyscan -t rsa bitbucket.org >> ~/.ssh/known_hosts
              git config --global user.email "jenkins@ms3-inc.com"
              git config --global user.name "MS3 Jenkins"
              git tag release-v${version}
              git push origin --tags
            """
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
              // If pipeline failed, provide the name of failing step in Slack report
              env.SLACK_REPORT_MSG = env.SLACK_REPORT_MSG + "\nFailed on step: " + env.LAST_EXECUTED_STAGE
              break
          }
          //Check if the webhook exists in BitBucket, then add it if it doesn't exist
          env.workSpaceBB = sh(script: "echo $GIT_URL | awk '\$0=\$2' FS=: RS=\\/", returnStdout: true).trim()
          withCredentials([string(credentialsId: "${serviceAccount}-bitbucket-app-pass", variable: "serviceAccountAppPass")]) {
            writeFile([file: 'create-bb-webhook.sh', text: libraryResource('scripts/bitbucket-integrations/create-bb-webhook.sh')])
            sh "bash create-bb-webhook.sh"
            if (env.BITBUCKET_PULL_REQUEST_ID != null) {
              // Provide a link to the Pull Request in Slack
              env.SLACK_REPORT_MSG = env.SLACK_REPORT_MSG + "\nTests run against <${BITBUCKET_PULL_REQUEST_LINK}|Pull Request>, branch: `${BITBUCKET_SOURCE_BRANCH}`"
              // Leave a feedback in BitBucket on the Pull Request
              env.commentBody = "[BUILD #${BUILD_NUMBER}](${BUILD_URL}): ${currentBuild.currentResult}  \\n[Tests coverage report](${JOB_URL}Coverage_20Report/)  \\n[Linter script output](${env.JOB_URL}Linter_20Output)"
              writeFile([file: 'create-pr-comment.sh', text: libraryResource('scripts/bitbucket-integrations/create-pr-comment.sh')])
              sh "bash create-pr-comment.sh"
            }
          }
          // Post a notification in Slack channel
          env.SLACK_REPORT_MSG = "*${currentBuild.currentResult}:* <${env.BUILD_URL}display/redirect|${env.JOB_NAME}> BUILD #${BUILD_NUMBER}:" + env.SLACK_REPORT_MSG + "\nTime total: " + sh(script: "echo '${currentBuild.durationString}' | sed 's/and counting//'", returnStdout: true).trim()
          slackSend(
            color: env.msgColor,
            message: env.SLACK_REPORT_MSG
          )
        }
      }
    }
  }
}
