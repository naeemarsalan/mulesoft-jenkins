// This pipeline requires no parameters as input

def call(Map pipelineParams) {
  pipeline {
    agent {
      kubernetes {
        label 'mule'
        yamlFile "${get_resource_dir()}mule/kubernetes.yaml"
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
