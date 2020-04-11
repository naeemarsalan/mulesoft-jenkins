// This pipeline requires no parameters as input
def call(Map pipelineParams) {

  pipeline {
    agent {
      kubernetes {
        yaml libraryResource('kubernetes.yaml')
      }
    }

    stages {

      stage('Linter') {
        when {
          expression { return readFile('pom.xml').contains('<packaging>mule-application</packaging>') }
        }
        steps {
          container('maven') {
            script {
              def scriptContent = libraryResource "ms3-mule-linter.sh"
              writeFile file: 'ms3-mule-linter.sh', text: scriptContent
              sh "bash ms3-mule-linter.sh"
            }
          }
        }
      }

      stage('Test') {
        when {
          expression { return readFile('pom.xml').contains('<packaging>mule-application</packaging>') }
        }
        steps {
          container('maven') {
            script {
              configFileProvider([configFile(fileId: 'maven_settings', variable: 'MAVEN_SETTINGS_FILE')]) {
                withCredentials([usernamePassword(credentialsId: 'devoptions', passwordVariable: 'appkey', usernameVariable: 'appenv')]) {
                  if (Boolean.parseBoolean(env.maven_env)) {
                    sh "mvn -s '$MAVEN_SETTINGS_FILE' clean test -Denv=${maven_env} -Dapp.key=${appkey}"
                  } else {
                    sh "mvn -s '$MAVEN_SETTINGS_FILE' clean test -Denv=${anypoint_env} -Dapp.key=${appkey}"
                  }
                }
                publishHTML (target: [
                  allowMissing: false,
                  alwaysLinkToLastBuild: false,
                  keepAll: true,
                  reportDir: 'target/site/munit/coverage',
                  reportFiles: 'summary.html',
                  reportName: "Coverage Report"
                ])
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
                sh "mvn -s '$MAVEN_SETTINGS_FILE' clean package -DskipTests"
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
                // populate pom variables
                pom = readMavenPom file: 'pom.xml'
                packaging = readMavenPom().getPackaging()
                artifactName = readMavenPom().getArtifactId()
                version = readMavenPom().getVersion()
                groupName = readMavenPom().getGroupId()

                // global env vars
                env.packaging = readMavenPom().getPackaging()
                env.artifactName = readMavenPom().getArtifactId()
                env.version = readMavenPom().getVersion()
                env.groupName = readMavenPom().getGroupId()

                dir('target') {
                  sh "mvn -s '$MAVEN_SETTINGS_FILE' deploy:deploy-file -DgroupId=${groupName} -DartifactId=${artifactName} -Dversion=${version} -DgeneratePom=true -Dpackaging=jar -DrepositoryId=nexus -Durl=https://maven.ms3-inc.com/repository/maven-snapshots/ -Dfile=${artifactName}-${version}-${packaging}.jar -DuniqueVersion=false -Dclassifier=${packaging}"
                }
              }
            }
          }
        }
      }

      stage('Deploy to Anypoint') {
        when {
          expression { GIT_BRANCH ==~ /(.*master|.*develop)/ }
          expression { return readFile('pom.xml').contains('<packaging>mule-application</packaging>') }
        }

        steps {
          container('anypoint-cli') {
            script {
              withCredentials([usernamePassword(credentialsId: 'anypointplatform', passwordVariable: 'anypoint_pass', usernameVariable: 'anypoint_user')]) {
                dir('target') {
                  ApplicationList = sh (returnStdout: true, script: 'anypoint-cli --username=${anypoint_user} --password=${anypoint_pass} --environment=${anypoint_env} runtime-mgr standalone-application list -f Name --limit 1000')
                  if ("${ApplicationList}" =~ "${artifactName}")
                    sh "anypoint-cli --username=${anypoint_user} --password=${anypoint_pass} --environment=${anypoint_env} runtime-mgr standalone-application modify ${artifactName} ${artifactName}-${version}-${packaging}.jar"
                  else
                    sh "anypoint-cli --username=${anypoint_user} --password=${anypoint_pass} --environment=${anypoint_env} runtime-mgr standalone-application deploy ${anypoint_server} ${artifactName} ${artifactName}-${version}-${packaging}.jar"
                }
              }
            }
          }
        }
      }

    }
  }
}
