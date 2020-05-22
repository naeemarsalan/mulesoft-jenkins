def call(Map pipelineParams) {

  pipeline {
    agent {
      kubernetes {
        yaml libraryResource('kube/agents/dockerInDocker.yaml')
      }
    }
    stages {
      stage('Populate Env Vars') {
        steps {
          container('jnlp') {
            script {
              // Get application's name (from git repo name)
              env.appName = sh(script: 'basename ${GIT_URL} |sed "s/.git//"', returnStdout: true).trim()
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

      stage('Build and Push Docker Image') {
        when {
          expression { GIT_BRANCH ==~ /(.*master|.*develop|.*uat)/ }
        }
        steps {
          container('dind') {
            echo "Building application for ${appEnv} environment..."
            withCredentials([usernamePassword(credentialsId: 'nexus', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
              sh """
                docker login ${dockerRegistryUrl} -u ${USERNAME} -p ${PASSWORD}
                docker build -t ${dockerRegistryUrl}/${projectName}/${appName}:${appEnv}-${gitId} -t ${dockerRegistryUrl}/${projectName}/${appName}:${appEnv}-latest .
                docker push ${dockerRegistryUrl}/${projectName}/${appName}:${appEnv}-${gitId}
                docker push ${dockerRegistryUrl}/${projectName}/${appName}:${appEnv}-latest
              """
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