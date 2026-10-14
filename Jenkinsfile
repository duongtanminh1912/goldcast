pipeline {
    agent any

    environment {
        REG = 'ghcr.io/duongtanminh1912'
        VPS = 'deploy@103.175.248.236'
        TAG = "${BUILD_NUMBER}"
    }

    stages {
        stage('Lay ma commit') {
            steps {
                script {
                    env.SHA = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                    echo "Commit dang build: ${env.SHA}"
                }
            }
        }

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
                      -t ${REG}/goldcast-backend:${env.SHA} \
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
                      -t ${REG}/goldcast-frontend:${env.SHA} \
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
                // Dung withCredentials thay sshagent: khong can plugin SSH Agent.
                // No ghi khoa ra file tam, xoa sau khi khoi lenh ket thuc.
                withCredentials([sshUserPrivateKey(
                        credentialsId: 'vps-deploy',
                        keyFileVariable: 'SSH_KEY')]) {
                    sh """
                        mkdir -p ~/.ssh
                        ssh -i \$SSH_KEY -o StrictHostKeyChecking=accept-new ${VPS} '
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

        stage('G2 - kiem noi dung sau deploy') {
            when {
                expression { return (env.BRANCH_NAME ?: 'main') == 'main' }
            }
            steps {
                // Kiem 1: trang cong khai co phuc vu khong.
                // -f lam curl tra ma loi khi HTTP >= 400, nho do stage do theo.
                sh 'curl -fsS -o /dev/null -w "trang cong khai: %{http_code}\n" https://goldcast.click'

                // Kiem 2: noi dung API co giu dung bat bien khong.
                // Chay TRONG mang compose: Caddy chi dinh tuyen ve frontend nen
                // /api/v1 khong ra toi internet. Dung container node vi Jenkins
                // khong cai Node.
                withCredentials([sshUserPrivateKey(
                        credentialsId: 'vps-deploy',
                        keyFileVariable: 'SSH_KEY')]) {
                    sh """
                        ssh -i \$SSH_KEY -o StrictHostKeyChecking=accept-new ${VPS} '
                            docker run --rm \\
                              --network goldcast_default \\
                              -v /opt/goldcast/scripts:/s:ro \\
                              node:22-alpine \\
                              node /s/smoke.mjs http://backend:8080
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
