// This pipeline requires no parameters as input
def call(Map pipelineParams) {

  pipeline {
    agent {
      kubernetes {
        yaml libraryResource('kube/agents/maven.yaml')
      }
    }

    stages {

      stage('Linter') {
        when {
          expression { return readFile('pom.xml').contains('<packaging>mule-extension</packaging>') }
        }
        steps {
          container('maven') {
            script {
              // Populate env vars from pom.xml file
              env.artifactName = readMavenPom().getArtifactId()
              env.version = readMavenPom().getVersion()
              env.groupName = readMavenPom().getGroupId()
              env.classifier = "mule-plugin"
              // Target Nexus repository depends on if the app's version is a snapshot or not
              if ("${version}" =~ "SNAPSHOT") {
                nexusUrl = nexusSnapshotUrl
              } else {
                  nexusUrl = nexusReleaseUrl
                }
              // Run linter script
              writeFile([file: 'ms3-mule-plugin-linter.sh', text: libraryResource('scripts/ms3-mule-plugin-linter.sh')])
              sh "bash ms3-mule-plugin-linter.sh"
            }
          }
        }
      }

      stage('Test') {
        when {
          expression { return readFile('pom.xml').contains('<packaging>mule-extension</packaging>') }
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
          expression { GIT_BRANCH ==~ /(.*master|.*develop)/ }
          expression { return readFile('pom.xml').contains('<packaging>mule-extension</packaging>') }
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
          expression { return readFile('pom.xml').contains('<packaging>mule-extension</packaging>') }
        }
        steps {
          container('maven') {
            script {
              configFileProvider([configFile(fileId: 'maven_settings', variable: 'MAVEN_SETTINGS_FILE')]) {
                dir('target') {
                  sh "mvn -s '$MAVEN_SETTINGS_FILE' deploy:deploy-file -DgroupId=${groupName} -DartifactId=${artifactName} -Dversion=${version} -DgeneratePom=true -Dpackaging=jar -DrepositoryId=nexus -Durl=${nexusUrl} -Dfile=${artifactName}-${version}-${classifier}.jar -DuniqueVersion=false -Dclassifier=${classifier}"
                }
              }
            }
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
