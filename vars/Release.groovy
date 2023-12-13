def pom
def token

def call() {
    pipeline {
        agent any

        tools {
            maven 'M3'
            jdk 'JDK11'
        }

        environment {
            MVN_HOME = tool 'M3'
            MAVEN_LOCAL_REPO = "${env.JENKINS_HOME}/.m2/repository"
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
                        // Step 2: Update description
                        currentBuild.description = "${pom.version}"
                    }
                    script {
                        // Step 3: Generate CodeArtifact Token
                        token = generateCodeArtifactToken()
                    }
                }
            }

            stage('Validation') {
                steps {
                    script {
                        def jobName = env.JOB_NAME
                        def pendingBuildNumbers = extractPendingBuildNumbers(jobName, currentBuild, pom.version)

                        // If there are pending builds, ask for user input
                        if (!pendingBuildNumbers.isEmpty()) {
                            error("Pending build found, aborting.")
                        }
                    }
                    script {
                        // Step 4: Check for existing release version
                        if (hasPackage(env.RELEASES_REPO, pom.groupId, pom.artifactId, pom.version)) {
                            error("Release version ${pom.version} already exists, aborting.")
                        }
                    }
                    script {
                        // Step 6: Check for package in staging repository
                        def isPackageInStagingRepo = hasPackage(env.STAGING_REPO, pom.groupId, pom.artifactId, pom.version)

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

                        if (isPackageInStagingRepo) {
                            input message: "Package already exists in staging. Delete and rebuild?", ok: 'Yes'

                            deletePackage(env.STAGING_REPO, pom.groupId, pom.artifactId, pom.version)
                            deleteLocal(pom.groupId, pom.artifactId, pom.version)

                            if (pom.modules) {
                                pom.modules.each { module ->
                                    def submodulePom = readMavenPom file: "${module}/pom.xml"
                                    def groupId = submodulePom.groupId ?: pom.groupId
                                    def artifactId = submodulePom.artifactId
                                    def version = submodulePom.version ?: pom.version
                                    deletePackage(env.STAGING_REPO, groupId, artifactId, version)
                                    deleteLocal(groupId, artifactId, version)
                                }
                            }
                        }
                    }
                }
            }

            stage('Build & Deploy') {
                steps {
//                    script {
//                        sh "find ${env.MAVEN_LOCAL_REPO}/com/meshiq/ -type d ! -name '*-SNAPSHOT*'"
//                        sh "find ${env.MAVEN_LOCAL_REPO}/com/nastel/ -type d ! -name '*-SNAPSHOT*'"
//                    }
                    script {

                        // Step 1: Execute the Maven build
                        runMvn("clean deploy -P jenkins -P staging -Djenkins.build.number=${currentBuild.number}")
                    }
                }
            }

            stage('Fingerprint Artifacts') {
                steps {
                    script {
                        fingerprintArtifacts(pom)
                        fingerprintDependencies(pom, 'com.nastel')
                    }
                }
            }


            stage('Quality Assurance') {
                steps {
                    script {
                        // Step 1: QA approval before release
                        input message: "Has QA approved the release?", ok: 'Yes'
                    }
                }
            }

            stage('Release') {
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

def executeMvn(String command) {
    configFileProvider([configFile(fileId: env.MVN_SETTINGS_FILE_ID, variable: 'SETTINGS_XML')]) {
        withEnv(["CODEARTIFACT_AUTH_TOKEN=${token}"]) {
            return sh(script: "${env.MVN_HOME}/bin/mvn -s ${SETTINGS_XML} ${command}", returnStdout: true).trim()
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
        def command = "aws codeartifact delete-package-versions --domain ${env.AWS_DOMAIN} --domain-owner ${env.AWS_DOMAIN_OWNER} --repository ${repository} --format maven --namespace ${packageGroup} --package ${packageName} --versions ${packageVersion}"
        def status = sh(script: command, returnStatus: true)
        if (status != 0) {
            echo "Package ${packageName} version ${packageVersion} not found or could not be deleted. Continuing..."
        }
    }
}

def deleteLocal(String groupId, String artifactId, String version) {
    // Replace '.' with '/' in groupId to create the directory path
    String groupPath = groupId.replace('.', '/')

    // Construct the path to the artifact
    String artifactPath = "${env.MAVEN_LOCAL_REPO}/${groupPath}/${artifactId}/${version}"

    // Delete the artifact directory
    println "Deleting ${artifactPath}"
    sh "rm -rf ${artifactPath}"
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
                def description = build.description
                def match = description =~ /^(\d+\.\d+\.\d+)/
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


def fingerprintArtifacts(Model pom) {

    def processArtifact = { Model pomModel, String basePath ->
        def version = pomModel.version ?: pomModel.parent.version
        def artifactPath = (basePath ? "${basePath}/" : '') + "target/${pomModel.artifactId}-${version}.${pomModel.packaging}"
        println "A: ${artifactPath}"
        fingerprint artifactPath
    }

    if (pom.modules) {
        pom.modules.each { module ->
            processArtifact(readMavenPom(file: "${module}/pom.xml"), module)
        }
    } else {
        processArtifact(pom, '')
    }
}


def fingerprintDependencies(Model pom, String filterGroupId) {
    def file = 'target/dependency-tree.txt'
    runMvn("dependency:tree -DoutputFile=${file} -DoutputType='tgf'")

    def localRepoBasePath = "${env.MAVEN_LOCAL_REPO}"

    def readDependencies = { String path ->
        def content = readFile(path).readLines()
        def dependencies = []
        for (int i = 1; i < content.size(); i++) { // Start from index 1 to skip the first line - jar itself
            if (content[i].startsWith('#')) break // Stop processing when '#' is encountered
            dependencies.add(content[i].split(' ')[1]) // Extract dependency
        }
        return dependencies;
    }

    def parseDependency = { String dependency ->
        def parts = dependency.split(':')
        return [parts[0].trim(), parts[1].trim(), parts[3].trim()] // groupId, artifactId, version
    }

    def processDependencies = { Model pomModel, String basePath ->
        def infoPath = basePath ? "${basePath}/${file}" : file
        readDependencies(infoPath).findAll { it.startsWith(filterGroupId) }.each { line ->
            def (groupId, artifactId, version) = parseDependency(line)
//            def groupPath = groupId.replace('.', '/')
//            def dependencyPath = "${localRepoBasePath}/${groupPath}/${artifactId}/${version}/${artifactId}-${version}.jar"
            def dependencyPath = (basePath ? "${basePath}/" : '') + "target/dependencies/${artifactId}-${version}.jar"
            println "FP: ${dependencyPath}"
            fingerprint dependencyPath
        }
    }

    if (pom.modules) {
        pom.modules.each { module ->
            def submodulePom = readMavenPom(file: "${module}/pom.xml")
            processDependencies(submodulePom, module)
        }
    } else {
        processDependencies(pom, '')
    }
}

