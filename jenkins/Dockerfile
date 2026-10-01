FROM maven:3.9-eclipse-temurin-21 AS maven
FROM docker:27-cli AS docker

FROM jenkins/jenkins:lts-jdk21

USER root
COPY --from=maven /usr/share/maven /opt/maven
COPY --from=docker /usr/local/bin/docker /usr/local/bin/docker
RUN ln -s /opt/maven/bin/mvn /usr/local/bin/mvn
USER jenkins
