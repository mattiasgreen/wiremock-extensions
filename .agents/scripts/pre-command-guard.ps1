$ErrorActionPreference = 'Stop'
$inputJson = [Console]::In.ReadToEnd()
if ([string]::IsNullOrWhiteSpace($inputJson)) {
    Write-Output '{"decision":"allow"}'
    exit 0
}

$payload = $inputJson | ConvertFrom-Json
$cmd = $payload.toolCall.args.CommandLine

if ($cmd -match '(git\s+credential|gh\s+auth\s+token|\.git-credentials|\.netrc|\.ssh/id_|cmdkey)') {
    Write-Output '{"decision":"deny","reason":"Blocked by AGENTS.md Zero-Credential-Inspection policy."}'
    exit 0
}

Write-Output '{"decision":"allow"}'
