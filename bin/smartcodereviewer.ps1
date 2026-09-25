param(
  [Parameter(Mandatory = $false)]
  [string]$FilePath
)

$ErrorActionPreference = "Stop"

$FilePath = ("" + $FilePath).Trim()
if ([string]::IsNullOrWhiteSpace($FilePath)) {
  Write-Host "SmartCodeReviewer: keine Datei uebergeben."
  Write-Host "Tipp: Tool im Editor auf einer Datei ausfuehren oder '$FilePath$' Macro nutzen."
  exit 2
}

$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot

$jarBoot = Join-Path $projectRoot "target\SmartCodeReviewer-0.0.1-SNAPSHOT-boot.jar"
$jarPlain = Join-Path $projectRoot "target\SmartCodeReviewer-0.0.1-SNAPSHOT.jar"
$jar = $jarBoot
if (-not (Test-Path $jar)) { $jar = $jarPlain }
Write-Host ("SmartCodeReviewer: starte fuer Datei: {0}" -f $FilePath)

function Needs-Build {
  if (-not (Test-Path $jarBoot) -and -not (Test-Path $jarPlain)) { return $true }

  $candidate = $jarBoot
  if (-not (Test-Path $candidate)) { $candidate = $jarPlain }
  $jarTime = (Get-Item $candidate).LastWriteTimeUtc

  $srcRoot = Join-Path $projectRoot "src\main\java"
  $resRoot = Join-Path $projectRoot "src\main\resources"
  $latestJava = $null
  $latestRes = $null

  if (Test-Path $srcRoot) {
    $latestJava = Get-ChildItem -Path $srcRoot -Recurse -File -Filter *.java |
      Sort-Object LastWriteTimeUtc -Descending |
      Select-Object -First 1
  }

  if (Test-Path $resRoot) {
    $latestRes = Get-ChildItem -Path $resRoot -Recurse -File |
      Sort-Object LastWriteTimeUtc -Descending |
      Select-Object -First 1
  }

  if (($null -eq $latestJava) -and ($null -eq $latestRes)) { return $false }

  if (($null -ne $latestJava) -and ($latestJava.LastWriteTimeUtc -gt $jarTime)) { return $true }
  if (($null -ne $latestRes) -and ($latestRes.LastWriteTimeUtc -gt $jarTime)) { return $true }

  return $false
}

if (Needs-Build) {
  Write-Host "SmartCodeReviewer: baue Projekt (mvn package)..."
  & (Join-Path $projectRoot "mvnw.cmd") -q -DskipTests package
}

if (Test-Path $jarBoot) { $jar = $jarBoot } else { $jar = $jarPlain }
& java -jar $jar $FilePath
