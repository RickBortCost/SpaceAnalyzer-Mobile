#!/usr/bin/env bash
# Same as build-exe.ps1 for Linux/macOS (cross-publishes the Windows exe): windows-bridge/dist/SpaceLinkBridge.exe
set -euo pipefail
cd "$(dirname "$0")"
dotnet test SpaceLinkBridge.Tests/SpaceLinkBridge.Tests.csproj -c Release
dotnet publish SpaceLinkBridge/SpaceLinkBridge.csproj -c Release -r win-x64 --self-contained true \
    -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o dist
sha256sum dist/SpaceLinkBridge.exe
