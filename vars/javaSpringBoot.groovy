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
              configFileProvider([configFile(fileId: 'maven_settings', variable: 'MAVEN_SETTINGS_FILE')]) {
                withCredentials([usernamePassword(credentialsId: 'devoptions', passwordVariable: 'appkey', usernameVariable: 'devenv')]) {
                  sh "mvn -s '$MAVEN_SETTINGS_FILE' clean test"
                }
              }
            } 
          }
        }
      }

      stage('Build Java Artifact') {
        when {
          expression { GIT_BRANCH ==~ /(.*master|.*develop)/ }
        }
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
        when {
          expression { GIT_BRANCH ==~ /(.*master|.*develop)/ }
        }
        steps {
          container('maven') {
            script {
              // Read pom.xml
              pom = readMavenPom file: 'pom.xml'
              artifactName = readMavenPom().getArtifactId()
              version = readMavenPom().getVersion()
              groupName = readMavenPom().getGroupId()
              // Upload to nexus
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

      stage('Build Docker Image') {
        when {
          expression { GIT_BRANCH ==~ /(.*master|.*develop)/ }
        }
        steps {
          container('dind') {
            configFileProvider([configFile(fileId: 'maven_settings', variable: 'MAVEN_SETTINGS_FILE')]) {
              sh "cp '$MAVEN_SETTINGS_FILE' mavensettings.xml"
            }
            withCredentials([usernamePassword(credentialsId: 'nexus', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
              sh '''
                docker login ${dockerRegistryUrl} -u $USERNAME -p $PASSWORD
                docker build -t ${dockerRegistryUrl}/${app}/${GIT_BRANCH}:${version} -t ${dockerRegistryUrl}/${app}/${GIT_BRANCH}:latest .
                docker push ${dockerRegistryUrl}/${app}/${GIT_BRANCH}:${version}
                docker push ${dockerRegistryUrl}/${app}/${GIT_BRANCH}:latest
              '''
            }
          }
        }
      }

      stage('Deploy to k8s') {
        when {
          expression { GIT_BRANCH ==~ /(.*master|.*develop)/ }
        }
        steps {
          container('kubectl') {
            withCredentials([file(credentialsId: 'k8s-east1', variable: 'FILE')]) {
              sh 'mkdir -p ~/.kube && cp "$FILE" ~/.kube/config'
            }
            writeFile([file: 'javaSpringBoot.yaml', text: libraryResource('kube/manifests/javaSpringBoot.yaml')])
            sh '''
              envsubst < javaSpringBoot.yaml | tee javaSpringBootParsed.yaml 1>/dev/null
              echo "This manifest should be deployed to k8s via PR from jenkins to k8s repo (DOPS-242). But for now the deployment is manual. TBD"
              cat javaSpringBootParsed.yaml 
            '''
          }
        }
      }
    }
  }
}