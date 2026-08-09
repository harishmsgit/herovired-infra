param(
    [string]$Repository = $null
)

function Ensure-Value([string]$Name) {
    $val = (Get-Item -Path env:$Name -ErrorAction SilentlyContinue).Value
    if (-not $val) {
        $val = Read-Host "Enter $Name"
    }
    return $val
}

if ($Repository) {
    $repoArg = "--repo $Repository"
} else {
    $repoArg = ""
}

$AWS_ACCESS_KEY_ID = Ensure-Value -Name 'AWS_ACCESS_KEY_ID'
$AWS_SECRET_ACCESS_KEY = Ensure-Value -Name 'AWS_SECRET_ACCESS_KEY'
$AWS_REGION = Ensure-Value -Name 'AWS_REGION'
$AWS_ACCOUNT_ID = Ensure-Value -Name 'AWS_ACCOUNT_ID'

Write-Host "Setting GitHub secrets on repository: $Repository"
if ($repoArg) {
    gh secret set AWS_ACCESS_KEY_ID $repoArg --body $AWS_ACCESS_KEY_ID
} else {
    gh secret set AWS_ACCESS_KEY_ID --body $AWS_ACCESS_KEY_ID
}
if ($repoArg) {
    gh secret set AWS_SECRET_ACCESS_KEY $repoArg --body $AWS_SECRET_ACCESS_KEY
} else {
    gh secret set AWS_SECRET_ACCESS_KEY --body $AWS_SECRET_ACCESS_KEY
}
if ($repoArg) {
    gh secret set AWS_REGION $repoArg --body $AWS_REGION
} else {
    gh secret set AWS_REGION --body $AWS_REGION
}
if ($repoArg) {
    gh secret set AWS_ACCOUNT_ID $repoArg --body $AWS_ACCOUNT_ID
} else {
    gh secret set AWS_ACCOUNT_ID --body $AWS_ACCOUNT_ID
}

Write-Host "Secrets set. You can trigger the workflow with: gh workflow run build-jenkinsfile-runner.yml --ref main"
