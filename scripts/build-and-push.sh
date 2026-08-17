#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
Usage: ./herovired-infra/scripts/build-and-push.sh <registry-prefix> <tag> <user-name>

Example:
  ./herovired-infra/scripts/build-and-push.sh 123456789012.dkr.ecr.us-east-1.amazonaws.com/harish-shopnow latest harish

Arguments:
  registry-prefix  Base registry/repository prefix without the service suffix
  tag              Tag applied to each image in addition to latest
  user-name        USER_NAME build arg for frontend and admin images

Prerequisites:
  - docker is installed and running
  - docker is already logged in to the target registry
EOF
}

if [[ $# -ne 3 ]]; then
  usage
  exit 1
fi

registry_prefix="$1"
tag="$2"
user_name="$3"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if ! command -v docker >/dev/null 2>&1; then
  echo "docker is required but was not found in PATH" >&2
  exit 1
fi

build_and_push() {
  local service="$1"
  local context_dir="$2"
  local build_arg="$3"
  local image_uri="${registry_prefix}/${service}:${tag}"
  local latest_uri="${registry_prefix}/${service}:latest"

  echo "Building ${service} from ${context_dir}/"
  (
    cd "${repo_root}/${context_dir}"
    docker build \
      --tag "shopnow-${service}:${tag}" \
      --tag "shopnow-${service}:latest" \
      ${build_arg} \
      .
  )

  docker tag "shopnow-${service}:${tag}" "${image_uri}"
  docker tag "shopnow-${service}:latest" "${latest_uri}"

  echo "Pushing ${image_uri}"
  docker push "${image_uri}"

  echo "Pushing ${latest_uri}"
  docker push "${latest_uri}"
}

build_and_push frontend frontend "--build-arg USER_NAME=${user_name}"
build_and_push admin admin "--build-arg USER_NAME=${user_name}"
build_and_push backend backend ""