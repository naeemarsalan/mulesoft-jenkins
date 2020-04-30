def call(Map pipelineParams) {

  pipeline {
    agent {
      kubernetes {
        yaml libraryResource('kube/agents/dockerInDocker.yaml')
      }
    }
    environment {
      namespace = "java-springboot-apps"
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
              // Read pom.xml and populate vars
              pom = readMavenPom file: 'pom.xml'
              artifactName = readMavenPom().getArtifactId()
              version = readMavenPom().getVersion()
              groupName = readMavenPom().getGroupId()
              if ( GIT_BRANCH =~ "develop")
                appEnv = "dev"
              if ( GIT_BRANCH =~ "master")
                appEnv = "prod"
              echo "${appEnv}"

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
            script {
              if ( appEnv =~ "dev")
                version = "latest"
            }
            withCredentials([usernamePassword(credentialsId: 'nexus', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
              sh """
                docker login ${dockerRegistryUrl} -u ${USERNAME} -p ${PASSWORD}
                docker build -t ${dockerRegistryUrl}/${appName}/${appEnv}:${version} .
                docker push ${dockerRegistryUrl}/${appName}/${appEnv}:${version}
              """
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
            writeFile([file: '${appName}-deployment.yaml', text: libraryResource('kube/manifests/javaspringboot/deployment.yaml')])
            writeFile([file: '${appName}-istio-vs.yaml', text: libraryResource('kube/manifests/javaspringboot/istioGwSnippet.yaml')])
            writeFile([file: '${appName}-istio-gw.yaml', text: libraryResource('kube/manifests/javaspringboot/istioVs.yaml')])
            sh """
              envsubst < ${appName}-deployment.yaml | tee ${appName}-deployment.yaml 1>/dev/null
              cat ${appName}-deployment.yaml
              envsubst < ${appName}-istio-vs.yaml | tee ${appName}-istio-vs.yaml 1>/dev/null
              cat ${appName}-istio-vs.yaml
              envsubst < ${appName}-istio-gw.yaml | tee ${appName}-istio-gw.yaml 1>/dev/null
              echo "This snippet should be added to k8s gateway configuration:"
              cat ${appname}-istio-gw.yaml
              # kubectl apply -f manifest/manifest-new.yaml
              # kubectl delete pods -l app=${app} -n ${namespace}-${appEnv}
            """
          }
        }
      }
    }
  }
}