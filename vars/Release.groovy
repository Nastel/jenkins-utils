def pom
def token
def isPackageInStagingRepo = false

def call() {
    pipeline {
        agent any

        tools {
            maven 'M3'
            jdk 'JDK11'
        }

        environment {
            MVN_HOME = tool 'M3'
            JAVA_HOME = tool 'JDK11'
            CODEARTIFACT_AUTH_TOKEN = ''

            // AWS Properties
            AWS_DOMAIN = 'nastel'
            AWS_DOMAIN_OWNER = '672553873710'
            AWS_DEFAULT_REGION = 'eu-central-1'

            // Jenkins AWS Credentials ID
            AWS_CREDENTIALS_ID = 'aws-ca-user'

            // Jenkins Maven Settings ID
            MVN_SETTINGS_FILE_ID = 'nastel-maven-settings'

            // Repository Variables
            RELEASES_REPO = 'releases'
            STAGING_REPO = 'staging'
        }

        stages {
            stage('Initialization') {
                steps {
                    script {
                        // Step 1: Read Maven POM
                        pom = readMavenPom file: 'pom.xml'

                        // Set POM_VERSION as an environment variable
                        env.POM_VERSION = pom.version
                    }
                    script {
                        // Step 2: Update display name
                        currentBuild.displayName = "${pom.artifactId}-${pom.version} #${env.BUILD_NUMBER}"
                    }
                    script {
                        // Step 3: Generate CodeArtifact Token
                        token = generateCodeArtifactToken()
                    }
                    script {
                        // Step 4: Check for existing release version
                        if (hasPackage(env.RELEASES_REPO, pom.groupId, pom.artifactId, pom.version)) {
                            error("Release version already exists in the repository.")
                        }
                    }
                    script {
                        // Step 5: Check for package in staging repository
                        isPackageInStagingRepo = hasPackage(env.STAGING_REPO, pom.groupId, pom.artifactId, pom.version)
                    }
                }
            }

            stage('Cancel Pending Builds') {
                steps {
                    script {
                        // Read the current version from POM
                        def currentVersion = pom.version

                        // Get the current job's name
                        def jobName = env.JOB_NAME

                        // Access all builds of the current job
                        def job = Jenkins.instance.getItemByFullName(jobName)

                        // Ensure we have access to the job and its builds
                        if (job && job.builds) {
                            job.builds.each { build ->
                                // Exclude the current build from the check
                                if (build.number != currentBuild.number) {
                                    // Safely access build details
                                    def buildVars = build.getBuildVariables()
                                    def buildVersion = buildVars.get('POM_VERSION')

                                    // Check if the build version matches and if it's still running
                                    if (buildVersion == currentVersion && (build.isBuilding() || build.isInQueue())) {
                                        echo "Aborting build #${build.number} with version ${buildVersion}"
                                        build.doStop() // Abort the build
                                    }
                                }
                            }
                        } else {
                            echo "Unable to access job builds for ${jobName}"
                        }
                    }

                }
            }

            stage('Staging Check') {
                when {
                    expression { isPackageInStagingRepo }
                }
                steps {
                    script {
                        // Step 1: Confirm rebuild if package exists in staging
                        input message: "Package already exists in staging. Proceed with build?", ok: 'Yes'
                    }
                }
            }

            stage('Build and Test') {
                steps {
                    script {
                        // Step 1: Execute the Maven build
                        runMvn("clean package")
                    }
                }
            }

            stage('Prepare Staging') {
                steps {
                    script {
                        // Step 1: Delete existing package in staging if necessary
                        if (isPackageInStagingRepo) {
                            deletePackage(env.STAGING_REPO, pom.groupId, pom.artifactId, pom.version)
                        }
                    }
                    script {
                        // Step 2: Deploy to staging repository
                        runMvn("deploy -DaltDeploymentRepository=staging-repo")
                    }
                }
            }

            stage('Quality Assurance') {
                steps {
                    script {
                        // Step 1: QA approval before release
                        input message: "Has QA approved the staging artifacts?", ok: 'Yes'
                    }
                }
            }

            stage('Release Promotion') {
                steps {
                    script {
                        // Step 1: Logic to promote from staging to release
                        println 'Release promotion logic goes here'
                    }
                }
            }
        }

        post {
            success {
                // Actions on successful pipeline execution
                println 'Build succeeded.'
            }
            failure {
                // Actions on pipeline failure
                println 'Build failed.'
            }
        }
    }
}

// Utility method to run Maven commands with the CodeArtifact token
def runMvn(String command) {
    configFileProvider([configFile(fileId: env.MVN_SETTINGS_FILE_ID, variable: 'SETTINGS_XML')]) {
        withEnv(["CODEARTIFACT_AUTH_TOKEN=${token}"]) {
            sh "${env.MVN_HOME}/bin/mvn -s ${SETTINGS_XML} ${command}"
        }
    }
}

// Generate an AWS CodeArtifact authorization token
def generateCodeArtifactToken() {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: env.AWS_CREDENTIALS_ID]]) {
        return sh(script: "aws codeartifact get-authorization-token --domain ${env.AWS_DOMAIN} --domain-owner ${env.AWS_DOMAIN_OWNER} --region ${env.AWS_DEFAULT_REGION} --query authorizationToken --output text", returnStdout: true).trim()
    }
}

// Check if a specific package version exists in the specified repository
def hasPackage(String repository, String packageGroup, String packageName, String packageVersion) {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: env.AWS_CREDENTIALS_ID]]) {
        def command = "aws codeartifact list-package-versions --domain ${env.AWS_DOMAIN} --domain-owner ${env.AWS_DOMAIN_OWNER} --repository ${repository} --namespace ${packageGroup} --package ${packageName} --query \"versions[?version=='${packageVersion}'].version\" --format maven --output text"
        def output = sh(script: command, returnStdout: true).trim()
        return output && output == packageVersion
    }
}

// Delete a specific package version from a repository
def deletePackage(String repository, String packageGroup, String packageName, String packageVersion) {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: env.AWS_CREDENTIALS_ID]]) {
        sh(script: "aws codeartifact delete-package-versions --domain ${env.AWS_DOMAIN} --domain-owner ${env.AWS_DOMAIN_OWNER} --repository ${repository} --format maven --namespace ${packageGroup} --package ${packageName} --versions ${packageVersion}")
    }
}