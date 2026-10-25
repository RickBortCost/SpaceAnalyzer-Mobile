$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
New-Item -ItemType Directory -Force .bootstrap | Out-Null
$url = 'https://services.gradle.org/distributions/gradle-8.13-bin.zip'
Invoke-WebRequest $url -OutFile .bootstrap/gradle.zip
$hash = (Invoke-RestMethod "$url.sha256").Trim()
if ($hash -notmatch '^[a-fA-F0-9]{64}$') { throw 'Invalid checksum' }
if ((Get-FileHash .bootstrap/gradle.zip -Algorithm SHA256).Hash -ne $hash) { throw 'Checksum mismatch' }
Expand-Archive .bootstrap/gradle.zip .bootstrap -Force
New-Item -ItemType Directory -Force .bootstrap/wrapper-project | Out-Null
Set-Content .bootstrap/wrapper-project/settings.gradle.kts 'rootProject.name = "wrapper-bootstrap"'
& ./.bootstrap/gradle-8.13/bin/gradle.bat -p .bootstrap/wrapper-project wrapper --gradle-version 8.13 --distribution-type bin --gradle-distribution-sha256-sum $hash
if ($LASTEXITCODE -ne 0) { throw 'Wrapper generation failed' }
Copy-Item .bootstrap/wrapper-project/gradle/wrapper/* gradle/wrapper/ -Force
Copy-Item .bootstrap/wrapper-project/gradlew* . -Force
