#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .bootstrap
url=https://services.gradle.org/distributions/gradle-8.13-bin.zip
curl --fail --location --retry 2 "$url" -o .bootstrap/gradle.zip
curl --fail --location --retry 2 "$url.sha256" -o .bootstrap/gradle.sha256
hash=$(tr -d '[:space:]' < .bootstrap/gradle.sha256)
[[ "$hash" =~ ^[a-fA-F0-9]{64}$ ]]
printf '%s  .bootstrap/gradle.zip\n' "$hash" | sha256sum --check
unzip -q -o .bootstrap/gradle.zip -d .bootstrap
# Generate official wrapper in an isolated project, without requiring Android SDK.
mkdir -p .bootstrap/wrapper-project
printf 'rootProject.name = "wrapper-bootstrap"\n' > .bootstrap/wrapper-project/settings.gradle.kts
.bootstrap/gradle-8.13/bin/gradle -p .bootstrap/wrapper-project wrapper --gradle-version 8.13 --distribution-type bin --gradle-distribution-sha256-sum "$hash"
cp .bootstrap/wrapper-project/gradle/wrapper/* gradle/wrapper/
cp .bootstrap/wrapper-project/gradlew .bootstrap/wrapper-project/gradlew.bat .
chmod +x gradlew
