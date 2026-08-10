# CI Setup — GitHub Actions secrets and triggering

Prerequisites:
- `gh` (GitHub CLI) installed and authenticated (`gh auth login`).
- On Windows, add `C:\Program Files\GitHub CLI` to your `PATH` environment variable and reopen PowerShell if `gh` is not recognized.

  Example PowerShell command:
  ```powershell
  [Environment]::SetEnvironmentVariable('PATH', "$env:PATH;C:\Program Files\GitHub CLI", 'User')
  ```

1) Add repository secrets (bash):

```bash
# interactive prompt or provide env vars
GITHUB_REPO=owner/repo ./scripts/set_github_secrets.sh
# or non-interactive
AWS_ACCESS_KEY_ID=AKIA... \ 
AWS_SECRET_ACCESS_KEY=... \ 
AWS_REGION=eu-west-1 \ 
AWS_ACCOUNT_ID=123456789012 \ 
./scripts/set_github_secrets.sh
```

2) Add repository secrets (PowerShell):

```powershell
# interactive
.\scripts\set_github_secrets.ps1 -Repository owner/repo
# non-interactive (from PowerShell env)
$env:AWS_ACCESS_KEY_ID='AKIA...'
$env:AWS_SECRET_ACCESS_KEY='...'
$env:AWS_REGION='eu-west-1'
$env:AWS_ACCOUNT_ID='123456789012'
.\scripts\set_github_secrets.ps1 -Repository owner/repo
```

> If you install `gh` during this session, restart PowerShell or open a new terminal so `gh` is available.

3) Trigger the workflow (manual):

```bash
# Run from the repo root
gh workflow run build-jenkinsfile-runner.yml --ref main
# Or visit Actions → Build and Publish Jenkinsfile Runner Image → Run workflow
```

> On success, the workflow reports the published ECR image URI in the run summary.

Notes:
- The workflow `build-jenkinsfile-runner.yml` already contains `workflow_dispatch:` so it can be run manually.
- Ensure the branch/ref you pass to `gh workflow run` exists (e.g., `main` or `master`).
- The secrets required are: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION`, `AWS_ACCOUNT_ID`.
- If Docker is unavailable locally, validate the Jenkinsfile remotely by running `validate-infra.yml` in GitHub Actions.

Remote Jenkinsfile validation example:
```bash
gh workflow run validate-infra.yml --ref main
```
