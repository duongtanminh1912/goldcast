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
    }
}
