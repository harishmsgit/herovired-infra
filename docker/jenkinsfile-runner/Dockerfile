FROM jenkins/jenkinsfile-runner:latest

 # Run as root for installation steps and to ensure container can run on varied hosts
USER root

# Install CA certificates and Java CA integration so runtime HTTPS downloads succeed
RUN if [ -x "$(command -v apt-get)" ]; then \
			apt-get update && apt-get install -y --no-install-recommends ca-certificates ca-certificates-java openjdk-11-jre-headless && \
			rm -rf /var/lib/apt/lists/* ; \
		elif [ -x "$(command -v apk)" ]; then \
			apk add --no-cache ca-certificates openjdk11-jre ; \
		fi && \
		update-ca-certificates || true && \
		if [ -f /etc/ssl/certs/java/cacerts ] && [ -d /opt/java/openjdk/jre/lib/security ]; then \
			cp /etc/ssl/certs/java/cacerts /opt/java/openjdk/jre/lib/security/cacerts || true ; \
		fi

# Prefer installed system Java runtime over the bundled OpenJDK 8 runtime
ENV JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
ENV PATH="$JAVA_HOME/bin:$PATH"

# Allow building image with a pinned Jenkins WAR (prod-ready). If not available,
# Jenkinsfile Runner will download the WAR at runtime (dev fallback).
ARG JENKINS_VERSION=2.568.2
ENV JENKINS_VERSION=${JENKINS_VERSION}

# Pre-fetch a pinned Jenkins WAR into the path jenkinsfile-runner expects so
# runtime downloads are unnecessary in CI/production. Failure is non-fatal and
# runner will still attempt a runtime download when absent.
RUN mkdir -p /root/.jenkinsfile-runner/war/${JENKINS_VERSION} && \
		if command -v curl >/dev/null 2>&1; then \
			curl -fsSL "https://get.jenkins.io/war-stable/${JENKINS_VERSION}/jenkins.war" -o /root/.jenkinsfile-runner/war/${JENKINS_VERSION}/jenkins-war-${JENKINS_VERSION}.war || echo "WARNING: pre-fetch failed, runner will download at runtime"; \
		else \
			echo "curl not found; skipping pre-fetch (runner will download at runtime)"; \
		fi || true

# Copy plugin list and install plugins (plugin install is best-effort)
COPY plugins.txt /usr/share/jenkins/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/plugins.txt || true

# Keep running as root so Jenkinsfile Runner can execute on hosts where the base image lacks a jenkins user.
USER root
