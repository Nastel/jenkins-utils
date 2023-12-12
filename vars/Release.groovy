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
            AWS_DOMAIN = 'meshiq'
            AWS_DOMAIN_OWNER = '592078199039'
            AWS_DEFAULT_REGION = 'us-east-1'

            // Jenkins AWS Credentials ID
            AWS_CREDENTIALS_ID = 'temp-ca-user'

            // Jenkins Maven Settings ID
            MVN_SETTINGS_FILE_ID = 'codeartifact-maven-settings'

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
                        currentBuild.displayName = "${pom.version} #${env.BUILD_NUMBER}"
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

            stage('Check Pending Builds') {
                steps {
                    script {
                        def jobName = env.JOB_NAME
                        def pendingBuildNumbers = [] // Store build numbers

                        // Access all builds of the current job
                        def job = Jenkins.instance.getItemByFullName(jobName)

                        // Check for pending builds and store their numbers
                        if (job && job.builds) {
                            job.builds.each { build ->
                                if (build.number != currentBuild.number && build.isBuilding()) {
                                    def buildDisplayName = build.displayName
                                    def match = buildDisplayName =~ /^(\d+\.\d+\.\d+) #(\d+)/
                                    if (match && match[0][1] == pom.version) {
                                        pendingBuildNumbers.add(match[0][2].toInteger()) // Store the build number
                                    }
                                }
                            }
                        } else {
                            echo "Unable to access job builds for ${jobName}"
                        }

                        // If there are pending builds, ask for user input
                        if (!pendingBuildNumbers.isEmpty()) {
                            def userInput = input(
                                    id: 'userInput', message: 'Pending builds found. Cancel them?', ok: 'Yes'
                            )

                            if (userInput) {
                                // Cancel pending builds based on their build numbers
                                pendingBuildNumbers.each { buildNumber ->
                                    def buildToCancel = job.builds.find { it.number == buildNumber }
                                    if (buildToCancel) {
                                        echo "Aborting build #${buildNumber} with version ${pom.version}"
                                        buildToCancel.doStop() // Abort the build
                                    }
                                }
                            }
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
                        runMvn("deploy -P staging")
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
        // First, check if the package exists
        def checkPackageCommand = "aws codeartifact list-packages --domain ${env.AWS_DOMAIN} --domain-owner ${env.AWS_DOMAIN_OWNER} --repository ${repository}"
        def packageOutput = sh(script: checkPackageCommand, returnStdout: true).trim()
        def packageJson = readJSON text: packageOutput

        if (!packageJson.packages.any { it.namespace == packageGroup && it.package == packageName }) {
            println("Package '${packageName}' in namespace '${packageGroup}' does not exist in repository '${repository}'.")
            return false
        }

        // Now, check for the specific version
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
