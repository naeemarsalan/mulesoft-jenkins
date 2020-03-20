// This pipeline requires no parameters as input

import groovy.transform.SourceURI
import java.nio.file.Path
import java.nio.file.Paths

class ScriptSourceUri {
    @SourceURI
    static URI uri
}

def call() {
    Path scriptLocation = Paths.get(ScriptSourceUri.uri)
    return scriptLocation.getParent().getParent().resolve('resources').toString()
}

def call(Map pipelineParams) {
  pipeline {
    agent any
    stages {
      stage('Test') {
        steps {
          sh "ls ${get_resource_dir()}"
        }
      }
    }
  }
}
