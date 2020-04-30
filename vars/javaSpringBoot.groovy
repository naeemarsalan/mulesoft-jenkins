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
              // Read pom.xml and populate vars
              pom = readMavenPom file: 'pom.xml'
              artifactName = readMavenPom().getArtifactId()
              env.appVersion = readMavenPom().getVersion()
              groupName = readMavenPom().getGroupId()

              // Environmnent depends on the branch
              if ( GIT_BRANCH =~ "develop")
                env.appEnv = "dev"
              if ( GIT_BRANCH =~ "master")
                env.appEnv = "prod"
              echo "Environment = ${appEnv}" //debugging purposes

              // Upload to nexus, target repo is depending on is version a snapshot one or not
              configFileProvider([configFile(fileId: 'maven_settings', variable: 'MAVEN_SETTINGS_FILE')]) {
                dir('target') {
                  if ("${appVersion}" =~ "SNAPSHOT")
                    sh "mvn -s '$MAVEN_SETTINGS_FILE' deploy:deploy-file -DgroupId=${groupName} -DartifactId=${artifactName} -Dversion=${appVersion} -DgeneratePom=true -Dpackaging=jar -DrepositoryId=nexus -Durl=${nexusSnapshotUrl} -Dfile=${artifactName}-${appVersion}.jar -DuniqueVersion=false"
                  else
                    sh "mvn -s '$MAVEN_SETTINGS_FILE' deploy:deploy-file -DgroupId=${groupName} -DartifactId=${artifactName} -Dversion=${appVersion} -DgeneratePom=true -Dpackaging=jar -DrepositoryId=nexus -Durl=${nexusReleaseUrl} -Dfile=${artifactName}-${appVersion}.jar -DuniqueVersion=false"
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
            // For dev application there should be only one image tag:latest
            script {
              if ( appEnv =~ "dev")
                env.appVersion = "latest" 
            }
            withCredentials([usernamePassword(credentialsId: 'nexus', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
              sh """
                docker login ${dockerRegistryUrl} -u ${USERNAME} -p ${PASSWORD}
                docker build -t ${dockerRegistryUrl}/${appName}/${appEnv}:${appVersion} .
                docker push ${dockerRegistryUrl}/${appName}/${appEnv}:${appVersion}
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
            writeFile([file: 'deployment.yaml', text: libraryResource('kube/manifests/javaspringboot/deployment.yaml')])
            writeFile([file: 'istio-gw.yaml', text: libraryResource('kube/manifests/javaspringboot/istioGwSnippet.yaml')])
            writeFile([file: 'istio-vs.yaml', text: libraryResource('kube/manifests/javaspringboot/istioVs.yaml')])
            sh """
              envsubst < deployment.yaml > ${appName}-deployment.yaml
              envsubst < istio-vs.yaml > ${appName}-istio-vs.yaml
              envsubst < istio-gw.yaml > ${appName}-istio-gw.yaml
              kubectl apply -f ${appName}-deployment.yaml
              kubectl delete pods -l app=${appName} -n ${namespace}-${appEnv}
              kubectl apply -f ${appName}-istio-vs.yaml
              cat ${appName}-istio-gw.yaml
            """
          }
        }
      }
    }
  }
}