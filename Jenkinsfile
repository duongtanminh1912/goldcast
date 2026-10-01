pipeline {
    agent any

    stages {
        stage('Test backend') {
            steps {
                dir('backend') {
                    sh 'mvn -B test'
                }
            }
        }

        stage('Build image') {
            steps {
                sh 'docker build -t goldcast-backend:${BUILD_NUMBER} -t goldcast-backend:latest backend/'
            }
        }
    }
}
