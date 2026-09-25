FROM maven:3.9-eclipse-temurin-21 AS maven

FROM jenkins/jenkins:lts-jdk21

USER root
COPY --from=maven /usr/share/maven /opt/maven
RUN ln -s /opt/maven/bin/mvn /usr/local/bin/mvn
USER jenkins
