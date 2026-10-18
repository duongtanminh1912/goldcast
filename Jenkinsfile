pipeline {
    agent any
    options {
        disableConcurrentBuilds()
    }
    environment {
        REG = 'ghcr.io/duongtanminh1912'
        VPS = 'deploy@103.175.248.236'
    }
    stages {
        stage('Checkout identity') {
            steps {
                script {
                    env.SHA = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
                    def jobKey = sh(script: 'printf "%s" "$JOB_NAME" | sha256sum | cut -c1-12', returnStdout: true).trim()
                    env.RELEASE_ID = "${env.SHA}-${jobKey}-${env.BUILD_NUMBER}"
                    env.CI_PROJECT = "g2-${env.RELEASE_ID}"
                    env.RELEASE_DIR = "/opt/goldcast/releases/${env.RELEASE_ID}"
                    // Also works for a single-branch job; never assume missing BRANCH_NAME means main.
                    def branch = env.BRANCH_NAME ?: env.GIT_LOCAL_BRANCH ?: env.GIT_BRANCH ?:
                        sh(script: 'git symbolic-ref --short -q HEAD || git name-rev --name-only HEAD', returnStdout: true).trim()
                    env.SOURCE_BRANCH = branch.replaceFirst('^remotes/origin/', '').replaceFirst('^origin/', '')
                }
            }
        }
        stage('G1 - code') {
            steps {
                dir('backend') { sh 'mvn -B test' }
                // Tests use only Python's standard library and POSIX sh; no Docker socket.
                sh '''
                    tar -cf - scripts |
                      docker run --rm -i python:3.13-alpine sh -ec '
                        mkdir /src; cd /src; tar -xf -;
                        python3 -m unittest discover -s scripts/tests -v'
                '''
                // Jenkins has Docker and Maven; Node runs in a disposable container.
                sh '''
                    tar -C frontend --exclude=node_modules --exclude=.next -cf - . |
                      docker run --rm -i node:22-alpine sh -ec '
                        mkdir /src; cd /src; tar -xf -; npm ci; npm run typecheck'
                '''
            }
        }
        stage('Registry login') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'ghcr', usernameVariable: 'GHCR_USER', passwordVariable: 'GHCR_TOKEN')]) {
                    sh 'echo "$GHCR_TOKEN" | docker login ghcr.io -u "$GHCR_USER" --password-stdin'
                }
            }
        }
        stage('Build images once') {
            steps {
                sh '''
                    set -eu
                    : > release.env

                    for service in backend frontend; do
                        image="$REG/goldcast-$service:$RELEASE_ID"
                        metadata_file="build-metadata-$service.json"

                        docker buildx build \
                            --platform linux/amd64 \
                            -t "$image" \
                            --metadata-file "$metadata_file" \
                            --push "$service/"

                    digest=$(
                        docker run --rm -i python:3.13-alpine \
                            python3 -c 'import json,sys; print(json.load(sys.stdin)["containerimage.digest"])' \
                            < "$metadata_file"
                )

                    echo "$digest" | grep -Eq '^sha256:[a-f0-9]{64}$'

                    key=$(printf '%s' "$service" | tr '[:lower:]' '[:upper:]')

                    printf '%s_IMAGE=%s/goldcast-%s@%s\\n' \
                        "$key" "$REG" "$service" "$digest" >> release.env
                done
            '''
            archiveArtifacts artifacts: 'release.env', fingerprint: true
    }
}
        stage('G2 - isolated image tests') {
            steps { sh 'sh scripts/g2.sh' }
        }
        stage('Production') {
            when { expression { env.SOURCE_BRANCH == 'main' && !env.CHANGE_ID } }
            stages {
                stage('Deploy tested digests') {
                    steps {
                        withCredentials([sshUserPrivateKey(credentialsId: 'vps-deploy', keyFileVariable: 'SSH_KEY')]) {
                            sh '''
                                set -eu
                                mkdir -p ~/.ssh
                                ssh -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new "$VPS" "mkdir -p '$RELEASE_DIR'"
                                tar -cf - release.env docker-compose.prod.yml deploy/Caddyfile scripts/release.sh scripts/check-pages.mjs |
                                  ssh -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new "$VPS" "tar -xf - -C '$RELEASE_DIR'"
                                ssh -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new "$VPS" "sh '$RELEASE_DIR/scripts/release.sh' deploy '$RELEASE_DIR' '$BUILD_URL'"
                            '''
                        }
                    }
                }
                stage('G3 - verify and promote') {
                    steps {
                        withCredentials([sshUserPrivateKey(credentialsId: 'vps-deploy', keyFileVariable: 'SSH_KEY')]) {
                            sh 'ssh -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new "$VPS" "sh \'$RELEASE_DIR/scripts/release.sh\' verify \'$RELEASE_DIR\'"'
                        }
                    }
                }
            }
            post {
                unsuccessful {
                    withCredentials([sshUserPrivateKey(credentialsId: 'vps-deploy', keyFileVariable: 'SSH_KEY')]) {
                        sh 'ssh -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new "$VPS" "test ! -f \'$RELEASE_DIR/scripts/release.sh\' || sh \'$RELEASE_DIR/scripts/release.sh\' rollback \'$RELEASE_DIR\'"'
                    }
                }
            }
        }
    }
    post {
        always { sh 'docker logout ghcr.io || true' }
    }
}
