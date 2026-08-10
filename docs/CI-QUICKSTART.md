# CI Quickstart

This repo includes GitHub Actions workflows and helper scripts for building a custom Jenkins agent and Jenkinsfile Runner image, publishing them to Amazon ECR, and validating infrastructure artifacts.

## What it covers
- Build and publish a Jenkinsfile Runner image to ECR
- Build and publish a Jenkins agent image to ECR
- Run infra validation (`terraform validate`, `ansible-lint`, `kubeval`, `shellcheck`, Jenkinsfile lint)
- Set required GitHub secrets for AWS access

## Prerequisites
- Git repository cloned
- GitHub CLI installed and authenticated (`gh auth login`) if you want to use CLI commands
- AWS credentials with permissions for ECR and IAM
- Run shell commands from the repository root

## Shell environments
- Use Bash on Linux/macOS or WSL for Bash examples
- Use PowerShell for Windows PowerShell examples
- The GitHub CLI commands shown work in both shells once `gh` is installed
- On Windows, add `C:\Program Files\GitHub CLI` to your `PATH` if `gh` is not recognized.

## Required GitHub repository secrets
- `AWS_ACCESS_KEY_ID`
- `AWS_SECRET_ACCESS_KEY`
- `AWS_REGION`
- `AWS_ACCOUNT_ID`

## Set secrets using helper scripts
### Bash
```bash
GITHUB_REPO=owner/repo ./scripts/set_github_secrets.sh
```
Or non-interactive:
```bash
AWS_ACCESS_KEY_ID=AKIA... \
AWS_SECRET_ACCESS_KEY=... \
AWS_REGION=eu-west-1 \
AWS_ACCOUNT_ID=123456789012 \
./scripts/set_github_secrets.sh
```

### PowerShell
```powershell
.\scripts\set_github_secrets.ps1 -Repository owner/repo
```
Or set env vars first:
```powershell
$env:AWS_ACCESS_KEY_ID='AKIA...'
$env:AWS_SECRET_ACCESS_KEY='...'
$env:AWS_REGION='eu-west-1'
$env:AWS_ACCOUNT_ID='123456789012'
.\scripts\set_github_secrets.ps1 -Repository owner/repo
```

> If you install `gh` during this session, restart PowerShell or open a new terminal so `gh` is available.

## Run the build workflow
The Jenkinsfile Runner image workflow is defined in:
- `.github/workflows/build-jenkinsfile-runner.yml`

Trigger it manually:
```bash
gh workflow run build-jenkinsfile-runner.yml --ref main
```

> On success, the workflow reports the published ECR image URI in the run summary.

## Monitor workflow status
```bash
gh run list --workflow=build-jenkinsfile-runner.yml --limit 5
gh run view <run-id> --log
```

## Action developer checklist
1. Confirm the workflow file is committed and pushed to `main`.
2. Add `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION`, and `AWS_ACCOUNT_ID` as repository secrets.
3. Install `gh` and authenticate: `gh auth login`.
4. Run the build workflow from the repo root:
   - Bash/WSL: `gh workflow run build-jenkinsfile-runner.yml --ref main`
   - PowerShell: `gh workflow run build-jenkinsfile-runner.yml --ref main`
5. Monitor the run and inspect logs with `gh run view <run-id> --log`.
6. If the run fails, open the workflow in GitHub Actions and review logs on the failed step.

## Alternative via GitHub UI
If `gh` is unavailable, open the repo on GitHub:
- Go to `Actions`
- Choose `Build and Publish Jenkinsfile Runner Image`
- Click `Run workflow`

## Useful repository files
- `.github/workflows/build-jenkins-agent.yml`
- `.github/workflows/build-jenkinsfile-runner.yml`
- `.github/workflows/validate-infra.yml`
- `scripts/set_github_secrets.sh`
- `scripts/set_github_secrets.ps1`
- `docs/CI-SETUP.md`

## Notes
- The workflow uses `workflow_dispatch` for manual execution.
- Make sure the branch/ref exists, e.g. `main`.
- If using the UI, you can also inspect the workflow run history and logs there.
