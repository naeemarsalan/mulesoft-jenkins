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
          sh 'echo ok'
        }
      }
    }
  }
}
