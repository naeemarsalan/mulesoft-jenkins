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
          // the linter script itself should be added later, for now this stage is used to populate environment variables
          script {
            // Get node application's name (from git repo name)
            env.appName = sh(script: 'basename ${GIT_URL} |sed "s/.git//"', returnStdout: true).trim()
            // Read pom.xml and populate vars
            env.artifactName = readMavenPom().getArtifactId()
            env.groupName = readMavenPom().getGroupId()
            env.appVersion = readMavenPom().getVersion()
            env.javaVersion = readMavenPom().getProperties().getProperty('java.version')
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
            // Artifact will be uploaded to Nexus, target repository depends on is the artifacts version a -SNAPSHOT or not
            if ("${appVersion}" =~ "SNAPSHOT") {
                env.nexusUrl = nexusSnapshotUrl
            } else {
              env.nexusUrl = nexusReleaseUrl
            }
            // Check if target Java version requires a different Maven image
            if ( javaVersion == "14" ) {
              env.mvnContainerName = "maven-jdk-14"
            } else {
              env.mvnContainerName = "maven"
            }
            echo "Will use Maven container: ${mvnContainerName}"
            //check if target deployment (k8s) repo and branch is set, and if it is not, default to ms3 Kubernetes repo and master branch
            if (env.targetRepoName == null) { env.targetRepoName = "${ms3KubeRepo}"}
            if (env.targetBranch == null) { env.targetBranch = "master" }
          }
        }
      }

      stage('Test') {
        steps {
          container("${mvnContainerName}") {
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
          container("${mvnContainerName}") {
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
          container("${mvnContainerName}") {
            script {
              echo "Artifact is being uploaded to: ${nexusUrl}"
              configFileProvider([configFile(fileId: 'maven_settings', variable: 'MAVEN_SETTINGS_FILE')]) {
                dir('target') {
                  sh "mvn -s '$MAVEN_SETTINGS_FILE' deploy:deploy-file -DgroupId=${groupName} -DartifactId=${artifactName} -Dversion=${appVersion} -DgeneratePom=true -Dpackaging=jar -DrepositoryId=nexus -Durl=${nexusUrl} -Dfile=${artifactName}-${appVersion}.jar -DuniqueVersion=false"
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
          expression { targetRepoName != null }
        }
        steps {
          git (
            url: "${targetRepoName}",
            poll: false,
            changelog: false,
            credentialsId: "${serviceAccount}-bitbucket-ssh-key",
            branch: "${targetBranch}"
          )
          script {
            if (fileExists("namespaces/${projectName}-${appEnv}/${appName}.yaml")) {
              echo "Deployment manifest already exists in k8s repository. Used Docker image tag will be updated automatically in a few minutes."
            } else {
              container('git-in-docker') {
                echo 'Deployment manifest not found, adding now...'
                writeFile([file: "deployment-template.yaml", text: libraryResource("kube/manifests/javaspringboot/deployment.yaml")])
                writeFile([file: "create-pr-template.json", text: libraryResource("templates/create-pr-bitbucket.json")])
                writeFile([file: 'create-pr.sh', text: libraryResource('scripts/create-pr.sh')])
                // substitute all variables in deployment manifest and add it to target directory/namespace
                sh """
                  mkdir -p namespaces/${projectName}-${appEnv}/
                  envsubst < deployment-template.yaml > namespaces/${projectName}-${appEnv}/${appName}.yaml
                  cat namespaces/${projectName}-${appEnv}/${appName}.yaml
                """
                // parse and populate variables that will be used by deployment script
                env.targetRepoOwner = sh(script: "echo $targetRepoName | awk '\$0=\$2' FS=: RS=\\/", returnStdout: true).trim()
                env.targetRepoName = sh(script: 'basename $targetRepoName | sed "s/.git//"', returnStdout: true).trim()
                env.addedFiles = "namespaces/${projectName}-${appEnv}/${appName}.yaml"
                env.commitMessage = "Added deployment manifest for ${projectName}-${appEnv}/${appName}"
                env.featureBranch = "feature/deployment-of-${appName}-${appEnv}-build-${BUILD_NUMBER}"
                // run the PR creation script
                withCredentials([string(credentialsId: "${serviceAccount}-bitbucket-api-pass", variable: "serviceAccountAppPass")]) {
                  sshagent(["${serviceAccount}-bitbucket-ssh-key"]) { sh "sh create-pr.sh" }
                echo "Please review and merge the related Pull Request in order to deploy the application to Kubernetes:\nhttps://bitbucket.org/${targetRepoOwner}/${targetRepoName}/pull-requests/"
                }
              }
            }
          }
          echo "Access to application ${appName} should be set through Kong Proxy, using the internal cluster URL:\n${appName}.${projectName}-${appEnv}.svc.cluster.local"
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