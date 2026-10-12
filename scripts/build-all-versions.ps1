$ErrorActionPreference = 'Stop'
$editorRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$versionsFile = Join-Path $editorRoot 'gradle/minecraft-versions.properties'
$versions = @(Get-Content -LiteralPath $versionsFile | Where-Object { $_ -match '^([0-9.]+)=' } | ForEach-Object { ($_ -split '=', 2)[0] })
$logs = Join-Path $editorRoot 'build/verification/version-builds'
New-Item -ItemType Directory -Path $logs -Force | Out-Null
$failures = @()
Push-Location $editorRoot
try {
    foreach ($version in $versions) {
        $buildPath = [IO.Path]::GetFullPath((Join-Path $editorRoot "build/$version"))
        if (-not $buildPath.StartsWith($editorRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Build output escaped the editor directory' }
        # A shared task history can delete a previous target's outputs as stale.
        # Keep task ownership separate for each Minecraft target.
        $cache = Join-Path $editorRoot ".gradle/minecraft-$version"
        $log = Join-Path $logs "$version.log"
        Write-Output "Building Minecraft $version"
        & (Join-Path $editorRoot 'gradlew.bat') "-Pminecraft_version=$version" --project-cache-dir $cache clean test build *> $log
        if ($LASTEXITCODE -ne 0) { $failures += $version; Write-Output "FAILED $version ($log)" }
        else { Write-Output "PASS $version" }
    }
} finally { Pop-Location }
if ($failures.Count -gt 0) { throw "Failed targets: $($failures -join ', ')" }
