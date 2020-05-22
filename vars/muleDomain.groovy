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
          expression { return readFile('pom.xml').contains('<packaging>mule-domain</packaging>') }
        }
        steps {
          container('maven') {
            script {
              def scriptContent = libraryResource "ms3-mule-domain-linter.sh"
              writeFile file: 'ms3-mule-domain-linter.sh', text: scriptContent
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
      }

      stage('Build') {
        when {
          expression { GIT_BRANCH ==~ /(.*master|.*develop)/ }
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
        }
        steps {
          container('maven') {
            script {
              configFileProvider([configFile(fileId: 'maven_settings', variable: 'MAVEN_SETTINGS_FILE')]) {
                // populate pom variables
                pom = readMavenPom file: 'pom.xml'
                packaging = readMavenPom().getPackaging()
                artifactName = readMavenPom().getArtifactId()
                version = readMavenPom().getVersion()
                groupName = readMavenPom().getGroupId()

                // global env vars
                env.packaging = readMavenPom().getPackaging()
                env.artifactName = readMavenPom().getArtifactId()
                env.version = readMavenPom().getVersion()
                env.groupName = readMavenPom().getGroupId()

                dir('target') {
                  if ("${version}" =~ "SNAPSHOT")
                    sh "mvn -s '$MAVEN_SETTINGS_FILE' deploy:deploy-file -DgroupId=${groupName} -DartifactId=${artifactName} -Dversion=${version} -DgeneratePom=true -Dpackaging=jar -DrepositoryId=nexus -Durl=${nexusSnapshotUrl} -Dfile=${artifactName}-${version}-${packaging}.jar -DuniqueVersion=false -Dclassifier=${packaging}"
                  else
                    sh "mvn -s '$MAVEN_SETTINGS_FILE' deploy:deploy-file -DgroupId=${groupName} -DartifactId=${artifactName} -Dversion=${version} -DgeneratePom=true -Dpackaging=jar -DrepositoryId=nexus -Durl=${nexusReleaseUrl} -Dfile=${artifactName}-${version}-${packaging}.jar -DuniqueVersion=false -Dclassifier=${packaging}"
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
