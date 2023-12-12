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
                            error("Release version ${pom.version} already exists in the repository.")
                        }
                    }
                    script {
                        def jobName = env.JOB_NAME
                        def pendingBuildNumbers = extractPendingBuildNumbers(jobName, currentBuild, pom.version)

                        // If there are pending builds, ask for user input
                        if (!pendingBuildNumbers.isEmpty()) {
                            error("Pending build exists.")
                        }
                    }
                    script {
                        // Step 6: Check for package in staging repository
                        isPackageInStagingRepo = hasPackage(env.STAGING_REPO, pom.groupId, pom.artifactId, pom.version)

                        // Check for submodules in staging repository
                        if (!isPackageInStagingRepo && pom.modules) {
                            pom.modules.each { module ->
                                def submodulePom = readMavenPom file: "${module}/pom.xml"
                                def groupId = submodulePom.groupId ?: pom.groupId
                                def artifactId = submodulePom.artifactId
                                def version = submodulePom.version ?: pom.version
                                if (hasPackage(env.STAGING_REPO, groupId, artifactId, version)) {
                                    isPackageInStagingRepo = true
                                    return // Short-circuit the loop
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

                            if (pom.modules) {
                                pom.modules.each { module ->
                                    def submodulePom = readMavenPom file: "${module}/pom.xml"
                                    def groupId = submodulePom.groupId ?: pom.groupId
                                    def artifactId = submodulePom.artifactId
                                    def version = submodulePom.version ?: pom.version
                                    deletePackage(env.STAGING_REPO, groupId, artifactId, version)
                                }
                            }
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
                        copyPackage(env.STAGING_REPO, env.RELEASES_REPO, pom.groupId, pom.artifactId, pom.version)
                    }
                }
            }
        }

        post {
            success {
                // Actions on successful pipeline execution
                println 'Build succeeded.'
                promoteBuild(promotionName: 'Golden Star', build: currentBuild, prerequisites: 'SUCCESS', criteria: 'MANUAL')
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

// Function to copy a package version from one repository to another
def copyPackage(String sourceRepository, String destinationRepository, String packageGroup, String packageName, String packageVersion) {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: env.AWS_CREDENTIALS_ID]]) {
        sh(script: "aws codeartifact copy-package-versions --domain ${env.AWS_DOMAIN} --domain-owner ${env.AWS_DOMAIN_OWNER} --source-repository ${sourceRepository} --destination-repository ${destinationRepository} --format maven --namespace ${packageGroup} --package ${packageName} --versions ${packageVersion}")
    }
}

// Utility function to extract pending build numbers
def extractPendingBuildNumbers(jobName, currentBuild, pomVersion) {
    def pendingBuildNumbers = []

    // Access all builds of the current job
    def job = Jenkins.instance.getItemByFullName(jobName)

    // Check for pending builds and store their numbers
    if (job && job.builds) {
        job.builds.each { build ->
            if (build.number != currentBuild.number && build.isBuilding()) {
                def buildDisplayName = build.displayName
                def match = buildDisplayName =~ /^(\d+\.\d+\.\d+) #(\d+)/
                if (match && match[0][1] == pomVersion) {
                    pendingBuildNumbers.add(match[0][2].toInteger()) // Store the build number
                }
            }
        }
    } else {
        echo "Unable to access job builds for ${jobName}"
    }

    return pendingBuildNumbers
}
