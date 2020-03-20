// This pipeline requires no parameters as input

def call(Map pipelineParams) {
  pipeline {
    agent any
    stages {
      stage('Test') {
        steps {
          sh 'echo ok'
        }
      }
    }
  }
}
