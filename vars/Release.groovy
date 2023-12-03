def pom

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
            stage('Initialize and Validate') {
                steps {
                    script {

                        pom = readMavenPom file: 'pom.xml'
                        CODEARTIFACT_AUTH_TOKEN = generateCodeArtifactToken()

                        sh 'printenv'

                        if (hasPackage(env.RELEASES_REPO, pom.groupId, pom.artifactId, pom.version)) {
                            error("Release version already exists in the repository.")
                        }
                    }
                }
            }

            stage('Build') {
                steps {
                    script {
                        runMvn("clean package")
                    }
                }
            }

            stage('Check Staging and Pending Builds') {
                steps {
                    script {
                        if (hasPackage(env.STAGING_REPO, pom.groupId, pom.artifactId, pom.version)) {
                            input message: "Package already exists in staging. Delete it?", ok: 'Yes'
                            deletePackage(env.STAGING_REPO, pom.groupId, pom.artifactId, pom.version)
                        }
                    }
                }
            }

            stage('Promote to Staging') {
                steps {
                    script {
                        runMvn("deploy -DaltDeploymentRepository=staging-repo")
                    }
                }
            }

            stage('QA Confirmation') {
                steps {
                    script {
                        input message: "Has QA approved the staging artifacts?", ok: 'Yes'
                    }
                }
            }

            stage('Promote to Release') {
                steps {
                    script {
                        // Logic to promote from staging to Release
                        println 'Release'
                    }
                }
            }
        }

        post {
            success {
                println 'W'
            }
            failure {
                println 'L'
            }
        }
    }
}

def runMvn(String command) {
    configFileProvider([configFile(fileId: env.MVN_SETTINGS_FILE_ID, variable: 'SETTINGS_XML')]) {
        // Directly output Maven command logs to console
        sh "${env.MVN_HOME}/bin/mvn -s ${SETTINGS_XML} ${command}"
    }
}

def generateCodeArtifactToken() {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: env.AWS_CREDENTIALS_ID]]) {
        return sh(script: "aws codeartifact get-authorization-token --domain ${env.AWS_DOMAIN} --domain-owner ${env.AWS_DOMAIN_OWNER} --region ${env.AWS_DEFAULT_REGION} --query authorizationToken --output text", returnStdout: true).trim()
    }
}

def hasPackage(String repository, String packageGroup, String packageName, String packageVersion) {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: env.AWS_CREDENTIALS_ID]]) {
        def command = "aws codeartifact list-package-versions --domain ${env.AWS_DOMAIN} --domain-owner ${env.AWS_DOMAIN_OWNER} --repository ${repository} --namespace ${packageGroup} --package ${packageName} --query \"versions[?version=='${packageVersion}'].version\" --format maven --output text"
        def output = sh(script: command, returnStdout: true).trim()
        return output && output == packageVersion
    }
}

def deletePackage(String repository, String packageGroup, String packageName, String packageVersion) {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: env.AWS_CREDENTIALS_ID]]) {
        sh(script: "aws codeartifact delete-package-versions --domain ${env.AWS_DOMAIN} --domain-owner ${env.AWS_DOMAIN_OWNER} --repository ${repository} --format maven --namespace ${packageGroup} --package ${packageName} --versions ${packageVersion}")
    }
}
