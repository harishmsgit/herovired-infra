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

# Copy plugin list and install plugins (plugin install is best-effort)
COPY plugins.txt /usr/share/jenkins/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/plugins.txt || true

# Keep running as root so Jenkinsfile Runner can execute on hosts where the base image lacks a jenkins user.
USER root
