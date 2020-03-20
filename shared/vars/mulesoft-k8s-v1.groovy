// This pipeline requires no parameters as input

def call(Map pipelineParams) {
  pipeline {
    agent none
    stages {
      stage('Test') {
        steps {
          sh 'ok'
        }
      }
    }
  }
}
