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

        stage('Build backend image') {
            steps {
                sh 'docker build -t goldcast-backend:${BUILD_NUMBER} -t goldcast-backend:latest backend/'
            }
        }

        stage('Build frontend image') {
            steps {
                sh 'docker build -t goldcast-frontend:${BUILD_NUMBER} -t goldcast-frontend:latest frontend/'
            }
        }
    }
}
