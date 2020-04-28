def call(Map pipelineParams) {

  pipeline {
    agent {
      kubernetes {
        yaml libraryResource('kube/agents/dockerInDocker.yaml')
      }
    }
    stages {
      stage('Test') {
        steps {
          container('maven') {
            script{
              // Read pom.xml:
              pom = readMavenPom file: 'pom.xml'
              artifactName = readMavenPom().getArtifactId()
              version = readMavenPom().getVersion()
              groupName = readMavenPom().getGroupId()
              // Run tests:
              configFileProvider([configFile(fileId: 'maven_settings', variable: 'MAVEN_SETTINGS_FILE')]) {
                withCredentials([usernamePassword(credentialsId: 'devoptions', passwordVariable: 'appkey', usernameVariable: 'devenv')]) {
                  sh "mvn -s '$MAVEN_SETTINGS_FILE' clean test"
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
      }

      stage('Build Java Artifact') {
        steps {
          container('maven') {
            script{
              configFileProvider([configFile(fileId: 'maven_settings', variable: 'MAVEN_SETTINGS_FILE')]) {
                sh "mvn -s '$MAVEN_SETTINGS_FILE' clean package -DskipTests"
              }
            }
          }
        }
      }

      stage('Upload To Nexus') {
        steps {
          container('maven') {
            script {
              configFileProvider([configFile(fileId: 'maven_settings', variable: 'MAVEN_SETTINGS_FILE')]) {
                dir('target') {
                  if ("${version}" =~ "SNAPSHOT")
                    sh "mvn -s '$MAVEN_SETTINGS_FILE' deploy:deploy-file -DgroupId=${groupName} -DartifactId=${artifactName} -Dversion=${version} -DgeneratePom=true -Dpackaging=jar -DrepositoryId=nexus -Durl=${nexusSnapshotUrl} -Dfile=${artifactName}-${version}.jar -DuniqueVersion=false"
                  else
                    sh "mvn -s '$MAVEN_SETTINGS_FILE' deploy:deploy-file -DgroupId=${groupName} -DartifactId=${artifactName} -Dversion=${version} -DgeneratePom=true -Dpackaging=jar -DrepositoryId=nexus -Durl=${nexusReleaseUrl} -Dfile=${artifactName}-${version}.jar -DuniqueVersion=false"
                }
              }
            }
          }
        }
      }

      stage('Build Docker image') {
        steps {
          container('dind') {
            withCredentials([usernamePassword(credentialsId: 'nexus', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
              sh '''    
                docker login docker.kube.cloudapps.ms3-inc.com -u $USERNAME -p $PASSWORD
                docker build -t docker.kube.cloudapps.ms3-inc.com/${app}/dev:${GIT_COMMIT} .
                docker push docker.kube.cloudapps.ms3-inc.com/${app}/dev:${GIT_COMMIT}
              '''
            }
          }
        }

      stage('Deploy') {
        steps {
          container('kubectl') {
            withCredentials([file(credentialsId: 'k8s-east1', variable: 'FILE')]) {
              sh 'mkdir -p ~/.kube && cp "$FILE" ~/.kube/config'
            }
//            checkout([$class: 'GitSCM', branches: [[name: '*/'+"$dbranch"]], doGenerateSubmoduleConfigurations: false, extensions: [], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'bitbucket to jenkins', url: "$dapplication_git_url"]]])
            writefile([file: 'javaSpringBoot.yaml', text: libraryResource('resources/kube/manifests/javaSpringBoot.yaml')])
            sh '''
              envsubst < javaSpringBoot.yaml | tee javaSpringBootParsed.yaml 1>/dev/null
              cat javaSpringBootParsed.yaml 
            '''
/*            sh '''
              envsubst < javaSpringBoot.yaml | tee javaSpringBootParsed.yaml 1>/dev/null
              kubectl apply -f javaSpringBootParsed.yaml 
              kubectl delete pods -l app=${app} -n ${namespace}
            '''
*/
          }
        }
      }
    }
  }
}