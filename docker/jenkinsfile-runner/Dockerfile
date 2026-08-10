FROM jenkins/jenkinsfile-runner:latest

# Run as root for installation steps and to ensure container can run on varied hosts
USER root

# Install CA certificates and Java CA integration so runtime HTTPS downloads succeed
RUN if [ -x "$(command -v apt-get)" ]; then \
			apt-get update && apt-get install -y --no-install-recommends ca-certificates ca-certificates-java && \
			rm -rf /var/lib/apt/lists/* ; \
		elif [ -x "$(command -v apk)" ]; then \
			apk add --no-cache ca-certificates ; \
		fi && \
		update-ca-certificates || true

# Copy plugin list and install plugins (plugin install is best-effort)
COPY plugins.txt /usr/share/jenkins/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/plugins.txt || true

# Keep running as root so Jenkinsfile Runner can execute on hosts where the base image lacks a jenkins user.
USER root
