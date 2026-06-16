$ErrorActionPreference = "Stop"

$projectDir = "$env:TEMP\opencode\SwitchToGuest"
$sdkRoot = "$env:TEMP\opencode\android-sdk"
$buildTools = "$sdkRoot\build-tools\34.0.0"
$platform = "$sdkRoot\platforms\android-34"
$javaHome = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"

$env:JAVA_HOME = $javaHome
$env:PATH = "$buildTools;$javaHome\bin;$env:PATH"

Write-Output "=== Step 1: Compile Java to .class ==="
New-Item -ItemType Directory -Path "$projectDir\out\classes" -Force | Out-Null
& "$javaHome\bin\javac.exe" --release 11 -classpath "$platform\android.jar" -d "$projectDir\out\classes" "$projectDir\src\MainActivity.java" "$projectDir\src\StatusService.java" "$projectDir\src\SwitchTileService.java"
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Output "=== Step 2: Convert .class to .dex ==="
New-Item -ItemType Directory -Path "$projectDir\out\dex" -Force | Out-Null
& "$javaHome\bin\jar.exe" cf "$projectDir\out\classes.jar" -C "$projectDir\out\classes" .
& "$buildTools\d8.bat" --lib "$platform\android.jar" --output "$projectDir\out\dex" "$projectDir\out\classes.jar"
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Output "=== Step 3: Generate resources ==="
New-Item -ItemType Directory -Path "$projectDir\out\res" -Force | Out-Null
& "$buildTools\aapt2.exe" compile -o "$projectDir\out\res\res.zip" --dir "$projectDir\res" 2>$null

Write-Output "=== Step 4: Link APK ==="
& "$buildTools\aapt2.exe" link --manifest "$projectDir\androidmanifest.xml" `
    -I "$platform\android.jar" `
    --java "$projectDir\out\gen" `
    -o "$projectDir\out\unaligned.apk" `
    --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Output "=== Step 5: Add DEX to APK ==="
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$projectDir\out\unaligned.apk", 'Update')
$dexBytes = [System.IO.File]::ReadAllBytes("$projectDir\out\dex\classes.dex")
$entry = $zip.CreateEntry("classes.dex")
$entryStream = $entry.Open()
$entryStream.Write($dexBytes, 0, $dexBytes.Length)
$entryStream.Close()
$zip.Dispose()

Write-Output "=== Step 6: Zipalign ==="
& "$buildTools\zipalign.exe" -f -v 4 "$projectDir\out\unaligned.apk" "$projectDir\out\aligned.apk" 2>&1
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Output "=== Step 7: Generate keystore (if needed) ==="
$keystoreFile = "$projectDir\out\debug.keystore"
if (-not (Test-Path $keystoreFile)) {
    & "$javaHome\bin\keytool.exe" -genkey -v -keystore $keystoreFile -alias debug `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass android -keypass android -dname "CN=Debug, OU=Debug, O=Debug, L=Debug, ST=Debug, C=US"
}

Write-Output "=== Step 8: Sign APK ==="
& "$buildTools\apksigner.bat" sign --ks $keystoreFile --ks-pass pass:android `
    --ks-key-alias debug --key-pass pass:android `
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true `
    "$projectDir\out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Copy-Item "$projectDir\out\aligned.apk" "$projectDir\out\guest-switcher.apk" -Force

Write-Output "=== Build Complete ==="
Write-Output "APK: $projectDir\out\guest-switcher.apk"
