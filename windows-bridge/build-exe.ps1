# Builds the single-file Windows executable: windows-bridge\dist\SpaceLinkBridge.exe (needs the .NET 8 SDK; the exe itself needs nothing).
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    dotnet test SpaceLinkBridge.Tests/SpaceLinkBridge.Tests.csproj -c Release
    if ($LASTEXITCODE -ne 0) { throw 'Test falliti' }
    dotnet publish SpaceLinkBridge/SpaceLinkBridge.csproj -c Release -r win-x64 --self-contained true `
        -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o dist
    if ($LASTEXITCODE -ne 0) { throw 'Pubblicazione fallita' }
    Get-FileHash dist/SpaceLinkBridge.exe -Algorithm SHA256 | Format-List
} finally { Pop-Location }
