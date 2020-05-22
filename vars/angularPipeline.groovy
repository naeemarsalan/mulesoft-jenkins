def msgColorMap = [
    'SUCCESS': 'good',
    'FAILURE': 'danger',
]
def call(Map pipelineParams) {

  pipeline {
    agent {
      kubernetes {
        yaml libraryResource('kube/agents/node-dind.yaml')
      }
    }
    stages {
      stage('Linter') {
        steps {
          container('node') {
            // the linter script itself should be added later, for now this stage is used to populate environment variables
            script {
              // Get node application's name (from git repo name)
              env.appName = sh(script: 'basename ${GIT_URL} |sed "s/.git//"', returnStdout: true).trim()
              // Get application version from package.json
              env.appVersion = sh(script: '''node -p -e "require('./package.json').version"''', returnStdout: true).trim()
              // get latest git commit ID
              env.gitId = sh(script: 'echo ${GIT_COMMIT} | cut -c1-7', returnStdout: true).trim()
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
            }
          }
        }
      }

      stage('Test') {
        when {
          // Tests are not run when deploying from master branch
          expression { GIT_BRANCH != "master" }
        }
        steps {
          container('node') {
            script {
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
          expression { GIT_BRANCH ==~ /(.*master|.*develop|.*uat)/ }
        }
        steps {
          container('dind') {
            echo "Building application for ${appEnv} environment..."
            withCredentials([usernamePassword(credentialsId: 'nexus', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
              sh """
                docker login ${dockerRegistryUrl} -u ${USERNAME} -p ${PASSWORD}
                docker build --build-arg configuration=${configuration} -t ${dockerRegistryUrl}/${projectName}/${appName}:${appEnv}-${appVersion}-${gitId} -t ${dockerRegistryUrl}/${projectName}/${appName}:${appEnv}-latest .
                docker push ${dockerRegistryUrl}/${projectName}/${appName}:${appEnv}-${appVersion}-${gitId}
                docker push ${dockerRegistryUrl}/${projectName}/${appName}:${appEnv}-latest
              """
            }
          }
        }
      }

      stage('Deploy to k8s') {
        when {
          expression { GIT_BRANCH ==~ /(.*master|.*develop|.*uat)/ }
        }
        steps {
          container('kubectl') {
            writeFile([file: 'deployment.yaml', text: libraryResource('kube/manifests/angular/deployment.yaml')])
            sh """
              envsubst < deployment.yaml > ${appName}-deployment.yaml
              echo "The following manifest should be added to the k8s repo manually, as ./namespaces/${projectName}-${appEnv}/${appName}.yaml"
              cat ${appName}-deployment.yaml
            """
          }
        }
      }
    }

    post {
      always {
        script {
          slackSend(
            color: msgColorMap[currentBuild.currentResult],
            message: "*${currentBuild.currentResult}:* Job ${JOB_NAME} build ${BUILD_NUMBER}\n More info at: ${env.BUILD_URL}")
          }
      }
    }
  }
}