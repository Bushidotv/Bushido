$ErrorActionPreference = "Stop"

$workspaceDir = "c:\Users\mutlu\Desktop\bushidoxyzyeni"
$tmpDir = Join-Path $workspaceDir "tmp_builds"
$prebuiltFile = Join-Path $workspaceDir "prebuilt\BelgeselX.cs3"
$repoUrl = (git remote get-url origin)

if (Test-Path $tmpDir) {
    Remove-Item -Recurse -Force $tmpDir
}

Write-Host "Cloning builds branch..."
git clone -b builds --single-branch $repoUrl $tmpDir

Write-Host "Copying BelgeselX.cs3..."
Copy-Item -Force $prebuiltFile (Join-Path $tmpDir "BelgeselX.cs3")

# Calculate size and hash
$fileSize = (Get-Item (Join-Path $tmpDir "BelgeselX.cs3")).Length
$hashStr = (Get-FileHash -Algorithm SHA256 (Join-Path $tmpDir "BelgeselX.cs3")).Hash.ToLower()
$fileHash = "sha256-$hashStr"

Write-Host "BelgeselX Size: $fileSize"
Write-Host "BelgeselX Hash: $fileHash"

# Update plugins.json
$pluginsJsonPath = Join-Path $tmpDir "plugins.json"
$plugins = @()
if (Test-Path $pluginsJsonPath) {
    $raw = Get-Content -Raw -Path $pluginsJsonPath -Encoding UTF8
    $plugins = [System.Collections.ArrayList]@(ConvertFrom-Json $raw)
}

# Remove existing BelgeselX if present
$toRemove = @()
foreach ($p in $plugins) {
    if ($p.internalName -eq "BelgeselX" -or $p.name -eq "BelgeselX") {
        $toRemove += $p
    }
}
foreach ($r in $toRemove) {
    $plugins.Remove($r)
}

$newEntry = [ordered]@{
    "iconUrl" = "https://raw.githubusercontent.com/Bushidotv/Bushido/master/icon.png"
    "fileHash" = $fileHash
    "apiVersion" = 1
    "repositoryUrl" = "https://github.com/Bushidotv/Bushido"
    "fileSize" = $fileSize
    "status" = 1
    "language" = "tr"
    "authors" = @("BushidoTV")
    "tvTypes" = @("Documentary", "Movie", "TvSeries")
    "version" = 9
    "internalName" = "BelgeselX"
    "description" = "BelgeselX - Belgesel, Dizi ve Film İçerikleri"
    "url" = "https://raw.githubusercontent.com/Bushidotv/Bushido/builds/BelgeselX.cs3"
    "name" = "BelgeselX"
}

$plugins.Add($newEntry)

$updatedPluginsJson = ConvertTo-Json @($plugins) -Depth 10
[System.IO.File]::WriteAllText($pluginsJsonPath, $updatedPluginsJson, [System.Text.Encoding]::UTF8)
Write-Host "Updated plugins.json successfully."

# Update repo.json
$repoJsonPath = Join-Path $tmpDir "repo.json"
$repoData = [ordered]@{
    "name" = "Bushido"
    "description" = "Bushido CloudStream Eklenti Havuzu"
    "manifestVersion" = 1
    "pluginLists" = @(
        "https://raw.githubusercontent.com/Bushidotv/Bushido/builds/plugins.json"
    )
}
$updatedRepoJson = ConvertTo-Json $repoData -Depth 5
[System.IO.File]::WriteAllText($repoJsonPath, $updatedRepoJson, [System.Text.Encoding]::UTF8)
Write-Host "Updated repo.json successfully."

# Commit and push
Push-Location $tmpDir
try {
    git config user.name "BushidoTV"
    git config user.email "bushidotv@gmail.com"
    git add .
    git commit -m "feat: BelgeselX eklendi (tvTypes: Documentary, Movie, TvSeries)"
    Write-Host "Pushing to origin builds..."
    git push origin builds
    Write-Host "Pushed successfully!"
} finally {
    Pop-Location
    Remove-Item -Recurse -Force $tmpDir
}
