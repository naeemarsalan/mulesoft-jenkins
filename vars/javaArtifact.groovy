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
        when {
          expression { return readFile('pom.xml').contains('<packaging>jar</packaging>') }
        }
        steps {
          container('maven') {
            script {
              configFileProvider([configFile(fileId: 'maven_settings', variable: 'MAVEN_SETTINGS_FILE')]) {
                withCredentials([usernamePassword(credentialsId: 'devoptions', passwordVariable: 'appkey', usernameVariable: 'appenv')]) {
                sh "mvn -s '$MAVEN_SETTINGS_FILE' clean test -Dapp.key=${appkey}"
                }
              }
            }
          }
        }
      }

      stage('Build') {
        when {
          expression { GIT_BRANCH ==~ /(.*master|.*develop)/ }
        }
        steps {
          container('maven') {
            script {
              configFileProvider([configFile(fileId: 'maven_settings', variable: 'MAVEN_SETTINGS_FILE')]) {
                // populate pom variables
                pom = readMavenPom file: 'pom.xml'
                packaging = readMavenPom().getPackaging()
                artifactName = readMavenPom().getArtifactId()
                version = readMavenPom().getVersion()
                groupName = readMavenPom().getGroupId()
                sh "mvn -s '$MAVEN_SETTINGS_FILE' clean package -DskipTests -Djar.finalName=${artifactName}-${version}-${packaging}"
              }
            }
          }
        }
      }

      stage('Upload to Nexus') {
        when {
          expression { GIT_BRANCH ==~ /(.*master|.*develop)/ }
        }
        steps {
          container('maven') {
            script {
              configFileProvider([configFile(fileId: 'maven_settings', variable: 'MAVEN_SETTINGS_FILE')]) {
                dir('target') {
                  if ("${version}" =~ "SNAPSHOT")
                    sh "mvn -s '$MAVEN_SETTINGS_FILE' deploy:deploy-file -DgroupId=${groupName} -DartifactId=${artifactName} -Dversion=${version} -DgeneratePom=true -Dpackaging=jar -DrepositoryId=nexus -Durl=${nexusSnapshotUrl} -Dfile=${artifactName}-${version}-${packaging}.jar -DuniqueVersion=false -Dclassifier=${packaging}"
                  else
                    sh "mvn -s '$MAVEN_SETTINGS_FILE' deploy:deploy-file -DgroupId=${groupName} -DartifactId=${artifactName} -Dversion=${version} -DgeneratePom=true -Dpackaging=jar -DrepositoryId=nexus -Durl=${nexusReleaseUrl} -Dfile=${artifactName}-${version}-${packaging}.jar -DuniqueVersion=false -Dclassifier=${packaging}"
                }
              }
            }
          }
        }
      }
    }
  }
}
