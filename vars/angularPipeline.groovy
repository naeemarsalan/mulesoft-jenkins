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
              // Get node application's name (from git repo name), it's version from package.json, and populate appEnv var depending on git branch
              env.appName = sh(script:'basename ${GIT_URL} |sed "s/.git//"', returnStdout: true).trim()
              sh "git rev-parse HEAD > gitid"
              if ( GIT_BRANCH ==~ /(.*master)/ ) {
                env.appVersion = sh(script: '''node -p -e "require('./package.json').version"''', returnStdout: true).trim()
                env.appEnv = "prod"
              }
              // For dev and uat environments there should be only one image tag:latest
              if ( GIT_BRANCH ==~ /(.*develop)/ ) {
                env.appVersion = "latest"
                env.appEnv = "dev"
              }
              if ( GIT_BRANCH ==~ /(.*uat)/ ) {
                env.appVersion = "latest"
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
                docker build --build-arg configuration=${configuration} -t ${dockerRegistryUrl}/${projectName}/${appName}:${appEnv}-${appVersion}-\$(cat gitid | cut -c1-7) .
                docker push ${dockerRegistryUrl}/${projectName}/${appName}:${appEnv}-${appVersion}-\$(cat gitid | cut -c1-7)
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
            withCredentials([file(credentialsId: 'k8s-east1', variable: 'FILE')]) {
              sh 'mkdir -p ~/.kube && cp "$FILE" ~/.kube/config'
            }
            writeFile([file: 'deployment.yaml', text: libraryResource('kube/manifests/angular/deployment.yaml')])
            sh """
              envsubst < deployment.yaml > ${appName}-deployment.yaml
              cat ${appName}-deployment.yaml
#              kubectl apply -f ${appName}-deployment.yaml
#              kubectl delete pods -l app=${appName} -n ${projectName}-${appEnv}
            """
          }
        }
      }
    }
  }
}