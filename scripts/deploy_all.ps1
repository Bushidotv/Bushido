$ErrorActionPreference = "Stop"

$workspaceDir = $PSScriptRoot | Split-Path -Parent
if (-not $workspaceDir) { $workspaceDir = "c:\Users\mutlu\Desktop\bushidoxyzyeni" }

$prebuiltDir = Join-Path $workspaceDir "prebuilt"
$tmpDir = Join-Path $workspaceDir "tmp_builds"

if (-not (Test-Path $prebuiltDir)) {
    New-Item -ItemType Directory -Force -Path $prebuiltDir | Out-Null
}

$cs3Files = Get-ChildItem -Path $prebuiltDir -Filter "*.cs3"
if ($cs3Files.Count -eq 0) {
    Write-Host "UYARI: 'prebuilt' klasorunde hic .cs3 dosyasi bulunamadi!" -ForegroundColor Yellow
    Write-Host "Lutfen eklemek istediginiz .cs3 dosyalarini '$prebuiltDir' klasorune atin ve tekrar calistirin."
    exit 0
}

Write-Host "Bulunan .cs3 dosyalari:" -ForegroundColor Green
foreach ($f in $cs3Files) {
    Write-Host " - $($f.Name)" -ForegroundColor Cyan
}

Push-Location $workspaceDir
$repoUrl = (git remote get-url origin)
Pop-Location

if (Test-Path $tmpDir) {
    Remove-Item -Recurse -Force $tmpDir
}

Write-Host "`nGitHub 'builds' dali klonlaniyor..." -ForegroundColor Yellow
git clone -b builds --single-branch $repoUrl $tmpDir

$pluginsJsonPath = Join-Path $tmpDir "plugins.json"
$list = @()
if (Test-Path $pluginsJsonPath) {
    $raw = Get-Content -Raw -Path $pluginsJsonPath -Encoding UTF8
    $parsed = ConvertFrom-Json $raw
    foreach ($item in $parsed) {
        if ($item.PSObject.Properties['value']) {
            foreach ($sub in $item.value) { $list += $sub }
        } else {
            $list += $item
        }
    }
}

Add-Type -AssemblyName System.IO.Compression.FileSystem

foreach ($file in $cs3Files) {
    $baseName = [System.IO.Path]::GetFileNameWithoutExtension($file.Name)
    $destPath = Join-Path $tmpDir $file.Name
    Copy-Item -Force $file.FullName $destPath

    $fileSize = (Get-Item $destPath).Length
    $hashStr = (Get-FileHash -Algorithm SHA256 $destPath).Hash.ToLower()
    $fileHash = "sha256-$hashStr"

    # Read manifest if available
    $manifest = $null
    try {
        $zip = [System.IO.Compression.ZipFile]::OpenRead($destPath)
        $mEntry = $zip.GetEntry("manifest.json")
        if ($mEntry) {
            $s = $mEntry.Open()
            $r = New-Object System.IO.StreamReader($s)
            $manifest = ConvertFrom-Json ($r.ReadToEnd())
            $r.Close()
            $s.Close()
        }
        $zip.Dispose()
    } catch {
        Write-Host "Manifest okunamadi ($($file.Name)), varsayilan degerler kullanilacak." -ForegroundColor Gray
    }

    $name = if ($manifest -and $manifest.name) { $manifest.name } else { $baseName }
    $version = if ($manifest -and $manifest.version) { [int]$manifest.version } else { 1 }

    # tvTypes: Documentary, Movie, TvSeries varsayılan
    $tvTypes = @("Movie", "TvSeries")
    if ($name -match "belgesel" -or $baseName -match "belgesel") {
        $tvTypes = @("Documentary", "Movie", "TvSeries")
    } elseif ($name -match "live|canli" -or $baseName -match "live|canli") {
        $tvTypes = @("Live", "Movie", "TvSeries")
    }

    # Eski ayni isimli girdiyi cikar
    $filtered = @()
    foreach ($item in $list) {
        if ($item.internalName -ne $baseName -and $item.name -ne $name) {
            $filtered += $item
        }
    }
    $list = $filtered

    $entry = [ordered]@{
        "iconUrl" = "https://raw.githubusercontent.com/Bushidotv/Bushido/master/icon.png"
        "fileHash" = $fileHash
        "apiVersion" = 1
        "repositoryUrl" = "https://github.com/Bushidotv/Bushido"
        "fileSize" = $fileSize
        "status" = 1
        "language" = "tr"
        "authors" = @("BushidoTV")
        "tvTypes" = $tvTypes
        "version" = $version
        "internalName" = $baseName
        "description" = "$name Eklentisi"
        "url" = "https://raw.githubusercontent.com/Bushidotv/Bushido/builds/$($file.Name)"
        "name" = $name
    }

    $list += [PSCustomObject]$entry
    Write-Host "Listeye eklendi: $name (Versiyon: $version, Boyut: $fileSize byte)" -ForegroundColor Green
}

$updatedPluginsJson = ConvertTo-Json -InputObject $list -Depth 10
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($pluginsJsonPath, $updatedPluginsJson, $utf8NoBom)

# repo.json garanti et
$repoJsonPath = Join-Path $tmpDir "repo.json"
$repoData = [ordered]@{
    "name" = "Bushido"
    "description" = "Bushido CloudStream Eklenti Havuzu"
    "manifestVersion" = 1
    "pluginLists" = @(
        "https://raw.githubusercontent.com/Bushidotv/Bushido/builds/plugins.json"
    )
}
$updatedRepoJson = ConvertTo-Json -InputObject $repoData -Depth 5
[System.IO.File]::WriteAllText($repoJsonPath, $updatedRepoJson, $utf8NoBom)

# Git commit ve push
Push-Location $tmpDir
try {
    git config user.name "BushidoTV"
    git config user.email "bushidotv@gmail.com"
    git add .
    git commit -m "feat: prebuilt eklentiler guncellendi"
    Write-Host "`nGitHub 'builds' dalina yukleniyor..." -ForegroundColor Yellow
    git push origin builds
    Write-Host "`nTEBRIKLER! Tum eklentiler basariyla guncellendi ve CloudStream deposuna yuklendi!" -ForegroundColor Green
} finally {
    Pop-Location
    Remove-Item -Recurse -Force $tmpDir
}
