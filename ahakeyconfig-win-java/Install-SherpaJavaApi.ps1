param(
    [string]$RepositoryPath = (Join-Path $PSScriptRoot ".m2repo")
)

$ErrorActionPreference = "Stop"

$SherpaVersion = "1.13.3"
$ExpectedSha256 = "4A1FABF547D5E3BB5C9B0DDFC767142692CABE658DA84F16ADFAFD519149B082"
$DownloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SherpaVersion/sherpa-onnx-v$SherpaVersion.jar"
$RepositoryRoot = [IO.Path]::GetFullPath($RepositoryPath)
$ArtifactDirectory = Join-Path $RepositoryRoot "com\k2fsa\sherpa-onnx-java-api\$SherpaVersion"
$ArtifactPath = Join-Path $ArtifactDirectory "sherpa-onnx-java-api-$SherpaVersion.jar"
$PomPath = Join-Path $ArtifactDirectory "sherpa-onnx-java-api-$SherpaVersion.pom"

if ((Test-Path -LiteralPath $ArtifactPath) -and (Test-Path -LiteralPath $PomPath)) {
    $ExistingHash = (Get-FileHash -LiteralPath $ArtifactPath -Algorithm SHA256).Hash
    if ($ExistingHash -eq $ExpectedSha256) {
        Write-Host "sherpa-onnx Java API $SherpaVersion is already verified in $RepositoryRoot"
        return
    }
}

$TemporaryDirectory = Join-Path ([IO.Path]::GetTempPath()) ("arist-sherpa-" + [guid]::NewGuid().ToString("N"))
$DownloadedJar = Join-Path $TemporaryDirectory "sherpa-onnx-v$SherpaVersion.jar"

try {
    New-Item -ItemType Directory -Path $TemporaryDirectory | Out-Null
    Write-Host "Downloading official sherpa-onnx Java API v$SherpaVersion..."
    Invoke-WebRequest -Uri $DownloadUrl -OutFile $DownloadedJar -UseBasicParsing

    $DownloadedHash = (Get-FileHash -LiteralPath $DownloadedJar -Algorithm SHA256).Hash
    if ($DownloadedHash -ne $ExpectedSha256) {
        throw "SHA-256 mismatch for sherpa-onnx Java API. Expected $ExpectedSha256 but received $DownloadedHash."
    }

    & mvn "-Dmaven.repo.local=$RepositoryRoot" `
        org.apache.maven.plugins:maven-install-plugin:3.1.3:install-file `
        "-Dfile=$DownloadedJar" `
        "-DgroupId=com.k2fsa" `
        "-DartifactId=sherpa-onnx-java-api" `
        "-Dversion=$SherpaVersion" `
        "-Dpackaging=jar" `
        "-DgeneratePom=true"

    if ($LASTEXITCODE -ne 0) {
        throw "Maven could not install sherpa-onnx Java API into $RepositoryRoot."
    }

    if (-not (Test-Path -LiteralPath $ArtifactPath)) {
        throw "Maven completed without creating the expected artifact: $ArtifactPath"
    }

    $InstalledHash = (Get-FileHash -LiteralPath $ArtifactPath -Algorithm SHA256).Hash
    if ($InstalledHash -ne $ExpectedSha256) {
        throw "Installed sherpa-onnx Java API failed SHA-256 verification."
    }

    Write-Host "Installed and verified sherpa-onnx Java API v$SherpaVersion."
} finally {
    if (Test-Path -LiteralPath $TemporaryDirectory) {
        Remove-Item -LiteralPath $TemporaryDirectory -Recurse -Force
    }
}
