pipeline {
    agent any

    environment {
        REG = 'ghcr.io/duongtanminh1912'
        VPS = 'deploy@103.175.248.236'
        TAG = "${BUILD_NUMBER}"
    }

    stages {
        stage('Test backend') {
            steps {
                dir('backend') {
                    sh 'mvn -B test'
                }
            }
        }

        stage('Dang nhap GHCR') {
            steps {
                withCredentials([usernamePassword(
                        credentialsId: 'ghcr',
                        usernameVariable: 'GHCR_USER',
                        passwordVariable: 'GHCR_TOKEN')]) {
                    // Dau nhay DON: Groovy khong noi suy, shell moi mo bien.
                    // Dung nhay kep thi token bi nhung vao chuoi lenh va co the
                    // lot ra console log.

                    sh 'echo "$GHCR_TOKEN" | docker login ghcr.io -u "$GHCR_USER" --password-stdin'
                }
            }
        }

        stage('Build va day backend') {
            steps {
                sh """
                    docker buildx build --platform linux/amd64 \
                      -t ${REG}/goldcast-backend:${TAG} \
                      -t ${REG}/goldcast-backend:latest \
                      --push backend/
                """
            }
        }

        stage('Build va day frontend') {
            steps {
                sh """
                    docker buildx build --platform linux/amd64 \
                      -t ${REG}/goldcast-frontend:${TAG} \
                      -t ${REG}/goldcast-frontend:latest \
                      --push frontend/
                """
            }
        }

        stage('Deploy') {
            // BRANCH_NAME chi co o job multibranch. Job mot nhanh thi no null,
            // va job do chi build main - nen coi null la main.
            when {
                expression { return (env.BRANCH_NAME ?: 'main') == 'main' }

            }
            steps {
                sshagent(['vps-deploy']) {
                    sh """
                        ssh -o StrictHostKeyChecking=accept-new ${VPS} '
                            set -e
                            cd /opt/goldcast
                            git pull --ff-only
                            sed -i "/^THE_TAG=/d" .env
                            echo "THE_TAG=${TAG}" >> .env
                            docker compose -f docker-compose.prod.yml up -d
                            docker image prune -f
                        '
                    """
                }
            }
        }
    }

    post {
        always {
            sh 'docker logout ghcr.io || true'
        }
    }
}
