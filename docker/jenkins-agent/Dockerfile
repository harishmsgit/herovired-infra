FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive

# Install common dependencies
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
      ca-certificates curl unzip gnupg lsb-release apt-transport-https && \
    rm -rf /var/lib/apt/lists/*

# Install AWS CLI v2
RUN curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o /tmp/awscliv2.zip \
  && unzip -q /tmp/awscliv2.zip -d /tmp \
  && /tmp/aws/install \
  && rm -rf /tmp/aws /tmp/awscliv2.zip

# Install kubectl (stable)
RUN KUBECTL_VER=$(curl -L -s https://dl.k8s.io/release/stable.txt) \
  && curl -LO "https://dl.k8s.io/release/${KUBECTL_VER}/bin/linux/amd64/kubectl" \
  && install -o root -g root -m 0755 kubectl /usr/local/bin/kubectl \
  && rm kubectl

# Install Helm
RUN curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash

# Create a non-root user matching typical Jenkins agent UID
RUN useradd -m -u 1000 jenkins || true
USER jenkins
WORKDIR /home/jenkins

# Default command keeps container alive for debugging. CI should override to run agent.
ENTRYPOINT ["tail", "-f", "/dev/null"]
