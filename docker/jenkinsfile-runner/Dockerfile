FROM jenkins/jenkinsfile-runner:latest

USER root

# Copy plugin list and install plugins
COPY plugins.txt /usr/share/jenkins/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/plugins.txt || true

USER jenkins
