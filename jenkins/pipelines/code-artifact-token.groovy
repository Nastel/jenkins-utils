pipeline {
    agent any

    environment {
        AWS_DEFAULT_REGION = 'us-east-1'
        AWS_DOMAIN = 'meshiq'
        AWS_DOMAIN_OWNER = '592078199039'
        AWS_CREDENTIALS_ID = 'codearetifact-user'
    }

    stages {
        stage('Setup AWS CLI and Generate Token') {
            steps {
                script {
                    def token  // Declare the token variable here

                    // Use the AWS credentials
                    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: env.AWS_CREDENTIALS_ID]]) {
                        // Generate the CodeArtifact token
                        token = sh(script: "aws codeartifact get-authorization-token --domain ${AWS_DOMAIN} --query authorizationToken --output text", returnStdout: true).trim()
                    }

                    // Write the token to a properties file
                    writeFile file: 'codeartifact.properties', text: "CODEARTIFACT_AUTH_TOKEN=${token}"
                }
            }
        }
        stage('Archive Token') {
            steps {
                // Archive the properties file
                archiveArtifacts artifacts: 'codeartifact.properties', onlyIfSuccessful: true
            }
        }
    }
}
