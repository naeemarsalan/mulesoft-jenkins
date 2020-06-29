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

      stage('Linter') {
        when {
          expression { return readFile('pom.xml').contains('<packaging>mule-application</packaging>') }
        }
        steps {
          container('jnlp') {
            script {
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
              // Verify if skipping of CI part and deployment directly from Nexus repository is set to true
              echo "skipCI: ${params.skipCI}"
              if (params.skipCI == true) {
                echo "Skipping CI part, going to deploy a previously built artifact from Nexus..."
                writeFile([file: 'download-from-nexus.sh', text: libraryResource('scripts/download-from-nexus.sh')])
                sh "bash download-from-nexus.sh"
              }
              // Run linter script
              writeFile([file: 'ms3-mule-linter.sh', text: libraryResource('scripts/ms3-mule-linter.sh')])
              sh "bash ms3-mule-linter.sh"
            }
          }
        }
      }

      stage('Test') {
        when {
          expression { return readFile('pom.xml').contains('<packaging>mule-application</packaging>') }
          expression { params.skipCI == false }
        }
        steps {
          container('maven') {
            script {
              configFileProvider([configFile(fileId: 'maven_settings', variable: 'MAVEN_SETTINGS_FILE')]) {
                if (env.maven_env == "prod") {
                  credsid = "prodoptions"
                } else {
                  credsid = "devoptions"
                }
                withCredentials([usernamePassword(credentialsId: credsid, passwordVariable: 'appkey', usernameVariable: 'appenv')]) {
                  if (env.maven_env) {
                    sh "mvn -s '$MAVEN_SETTINGS_FILE' clean test -Denv=${maven_env} -Dapp.key=${appkey}"
                  } else {
                    sh "mvn -s '$MAVEN_SETTINGS_FILE' clean test -Denv=${anypoint_env} -Dapp.key=${appkey}"
                  }
                }
                publishHTML (target: [
                  allowMissing: false,
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
      }

      stage('Build') {
        when {
          expression { GIT_BRANCH ==~ /(.*master|.*develop)/ }
          expression { params.skipCI == false }
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
          expression { GIT_BRANCH ==~ /(.*master|.*develop)/ }
          expression { params.skipCI == false }
        }
        steps {
          container('maven') {
            script {
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
          expression { GIT_BRANCH ==~ /(.*master|.*develop)/ }
          expression { return readFile('pom.xml').contains('<packaging>mule-application</packaging>') }
        }
        steps {
          container('anypoint-cli') {
            script {
              withCredentials([usernamePassword(credentialsId: 'anypointplatform', passwordVariable: 'anypoint_pass', usernameVariable: 'anypoint_user')]) {
                dir('target') {
                  ApplicationList = sh (returnStdout: true, script: 'anypoint-cli --username=${anypoint_user} --password=${anypoint_pass} --environment=${anypoint_env} runtime-mgr standalone-application list -f Name --limit 1000')
                  if ("${ApplicationList}" =~ "${artifactName}")
                    sh "anypoint-cli --username=${anypoint_user} --password=${anypoint_pass} --environment=${anypoint_env} runtime-mgr standalone-application modify ${artifactName} ${artifactName}-${version}-${packaging}.jar"
                  else
                    sh "anypoint-cli --username=${anypoint_user} --password=${anypoint_pass} --environment=${anypoint_env} runtime-mgr standalone-application deploy ${anypoint_server} ${artifactName} ${artifactName}-${version}-${packaging}.jar"
                }
              }
            }
          }
        }
      }

      stage('Add version tag') {
        when {
          expression { GIT_BRANCH == "master" }
        }
        steps {
           sshagent(["bitbucket to jenkins"]) {
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

    post {
      always {
        script {
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
