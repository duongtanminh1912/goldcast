FROM python:3.13-alpine
RUN apk add --no-cache openssh-client docker-cli
# Source and SSH credentials arrive through stdin for this run only. Backups live
# in a private named volume on the Jenkins host, never in the source checkout.
