import com.meshiq.jenkins.CodeArtifact
import groovy.transform.Field

@Field
CodeArtifact codeArtifact

@Field
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
        }

        stages {
            stage('Initialize and Validate') {
                steps {
                    script {
                        // Load variables from an external file within the shared library
                        def envVars = libraryResource('Env.groovy')
                        if (envVars) {
                            load envVars
                        }

                        // Dump all environment variables
                        sh 'printenv'

                        pom = readMavenPom file: 'pom.xml'
                        codeArtifact = new CodeArtifact(env.AWS_DOMAIN, env.AWS_DOMAIN_OWNER, env.AWS_DEFAULT_REGION)
                        env.CODEARTIFACT_AUTH_TOKEN = codeArtifact.generateToken()

                        if (codeArtifact.hasPackage('releases', pom.groupId, pom.artifactId, pom.version)) {
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
                        if (codeArtifact.hasPackage('staging', pom.groupId, pom.artifactId, pom.version)) {
                            input message: "Package already exists in staging. Delete it?", ok: 'Yes'
                            codeArtifact.deletePackage('staging', pom.groupId, pom.artifactId, pom.version)
                        }

                        // Logic to check and cancel pending Jenkins builds if needed
                        // This requires additional implementation
                    }
                }
            }

            stage('Promote to Staging') {
                steps {
                    script {
                        runMvn("deploy -DaltDeploymentRepository=staging-repo")
                        // Additional steps might be needed for promotion
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
                        // This may involve copying artifacts and updating metadata
                        println 'Release'
                    }
                }
            }
        }

        post {
            success {
                // Post-success steps
                println 'W'
            }
            failure {
                // Failure handling
                println 'L'
            }
        }
    }
}

def runMvn(String command) {
    withCredentials([file(credentialsId: env.MVN_SETTINGS_FILE_ID, variable: 'SETTINGS_XML')]) {
        sh "${env.MVN_HOME}/bin/mvn -s ${SETTINGS_XML} ${command}"
    }
}

