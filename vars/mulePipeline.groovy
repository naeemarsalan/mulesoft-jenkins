// This pipeline requires no parameters as input


def call(Map pipelineParams) {
  pipeline {
    agent {
      kubernetes {
        yaml libraryResource('kubernetes.yaml')
      }
    }

    stages {
      stage('Test') {
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
    }


  }
}
