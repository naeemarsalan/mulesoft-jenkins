// This pipeline requires no parameters as input

def call(Map pipelineParams) {
  pipeline {
    agent {
      kubernetes {
        label 'mule'
        yaml "kubernetes.yaml"
      }
    }
    stages {
      stage('Test') {
        steps {
          sh 'pwd'
        }
      }
    }
  }
}
