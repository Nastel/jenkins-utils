pipeline {
    agent any
    stages {
        stage('Clear Maven Cache') {
            steps {
                script {
                    sh "rm -rf ~/.m2/repository"
                }
            }
        }
    }
}
