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
              // Get the appName by the name of the repo
              env.appName = sh(script:'basename ${GIT_URL} |sed "s/.git//"', returnStdout: true).trim()
              echo "${appName}"
              // Read pom.xml and populate vars
              env.artifactName = readMavenPom().getArtifactId()
              env.appVersion = readMavenPom().getVersion()
              env.groupName = readMavenPom().getGroupId()

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
            // For dev environment there should be only one image tag:latest
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
            withCredentials([file(credentialsId: 'k8s-east1', variable: 'FILE')]) {
              sh 'mkdir -p ~/.kube && cp "$FILE" ~/.kube/config'
            }
            writeFile([file: 'deployment.yaml', text: libraryResource('kube/manifests/javaspringboot/deployment.yaml')])
            sh """
              envsubst < deployment.yaml > ${appName}-deployment.yaml
              kubectl apply -f ${appName}-deployment.yaml
              kubectl delete pods -l app=${appName} -n ${namespace}-${appEnv}
            """
            echo "Application ${appName} has been deployed. It should be accessible now through Kong Proxy, by the following URL:"
            echo "${appName}-service.${namespace}-${appEnv}.svc.cluster.local"
          }
        }
      }
    }

    post {
      always {
        script {
          if ( currentBuild.currentResult == "SUCCESS")
            env.msgColor = "good"
          else 
            env.msgColor = "danger"
          slackSend(
            color: msgColor,
            message: "*${currentBuild.currentResult}:* Job ${JOB_NAME} build ${BUILD_NUMBER}\n More info at: ${env.BUILD_URL}"
            )
        }
      }
    }
  }
}