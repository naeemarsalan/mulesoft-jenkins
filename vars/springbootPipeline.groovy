def call(Map pipelineParams) {

  pipeline {
    agent {
      kubernetes {
        yaml libraryResource('kube/agents/dockerInDocker.yaml')
      }
    }
    stages {
      stage('Linter') {
        steps {
          container('jnlp') {
            // the linter script itself should be added later, for now this stage is used to populate environment variables
            script {
              // Get node application's name (from git repo name)
              env.appName = sh(script: 'basename ${GIT_URL} |sed "s/.git//"', returnStdout: true).trim()
              // Read pom.xml and populate vars
              env.artifactName = readMavenPom().getArtifactId()
              env.groupName = readMavenPom().getGroupId()
              env.appVersion = readMavenPom().getVersion()
              // get latest git commit ID
              env.gitCommitId = sh(script: 'echo ${GIT_COMMIT} | cut -c1-7', returnStdout: true).trim()
              // Set application environment variable depending on git branch
              if ( GIT_BRANCH ==~ /(.*master)/ ) {
                env.appEnv = "prod"
              }
              if ( GIT_BRANCH ==~ /(.*develop)/ ) {
                env.appEnv = "dev"
              }
              if ( GIT_BRANCH ==~ /(.*uat)/ ) {
                env.appEnv = "uat"
              }
            }
          }
        }
      }

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
            withCredentials([usernamePassword(credentialsId: 'nexus', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
              sh """
                docker login ${dockerRegistryUrl} -u ${USERNAME} -p ${PASSWORD}
                docker build -t ${dockerRegistryUrl}/${projectName}/${appName}:${appEnv}-${appVersion}-${gitCommitId} -t ${dockerRegistryUrl}/${projectName}/${appName}:${appEnv}-latest .
                docker push ${dockerRegistryUrl}/${projectName}/${appName}:${appEnv}-${appVersion}-${gitCommitId}
                docker push ${dockerRegistryUrl}/${projectName}/${appName}:${appEnv}-latest
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
            sh """
              envsubst < deployment.yaml > ${appName}.yaml
              cat ${appName}.yaml
            """
            echo "Access to application ${appName} should be set through Kong Proxy, using the internal cluster URL:"
            echo "${appName}.${projectName}-${appEnv}.svc.cluster.local"
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