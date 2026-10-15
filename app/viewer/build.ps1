param(
    [string]$SdkDir = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$BuildToolsVersion = '34.0.0',
    [string]$Platform = 'android-30',
    [string]$NdkVersion = '28.2.13676358',
    [string]$JdkDir = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot'
)
$ErrorActionPreference = 'Stop'
$sourceDir = $PSScriptRoot
$buildDir = Join-Path $sourceDir 'build'
$toolDir = Join-Path $SdkDir "build-tools\$BuildToolsVersion"
$androidJar = Join-Path $SdkDir "platforms\$Platform\android.jar"
if (-not (Test-Path -LiteralPath $androidJar)) { throw "Android SDK platform missing: $androidJar" }
$java = Join-Path $JdkDir 'bin\java.exe'
$javac = Join-Path $JdkDir 'bin\javac.exe'
$keytool = Join-Path $JdkDir 'bin\keytool.exe'
if (-not (Test-Path -LiteralPath $javac)) { throw "JDK missing: $JdkDir" }

# Fresh temporary directories keep stale class files out, without deleting any source or previous APK.
$taskName = 'work_' + [DateTime]::Now.ToString('yyyyMMdd_HHmmss_fff')
$taskBuild = Join-Path $buildDir $taskName
New-Item -ItemType Directory -Path "$taskBuild\classes", "$taskBuild\dex", "$taskBuild\generated", "$taskBuild\lib\arm64-v8a" -Force | Out-Null
$clang = Join-Path $SdkDir "ndk\$NdkVersion\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android29-clang++.cmd"
if (-not (Test-Path -LiteralPath $clang)) { throw "NDK missing: $NdkVersion" }
$nativeLib = Join-Path $taskBuild 'lib\arm64-v8a\libirisbufferviewer.so'
& $clang -std=c++17 -O2 -fPIC -shared -static-libstdc++ -Wall -Wextra '-Wl,-soname,libirisbufferviewer.so' "$sourceDir\native\buffer_viewer.cpp" -o $nativeLib -landroid -llog
if ($LASTEXITCODE -ne 0) { throw 'Native library compilation failed' }
# aapt2's Windows directory reader cannot open this workspace's Chinese absolute
# path, while relative UTF-8 resource paths work. Build from the source directory.
Push-Location $sourceDir
try {
    & "$toolDir\aapt2.exe" compile --dir 'res' -o "build\$taskName\resources.zip"
    if ($LASTEXITCODE -ne 0) { throw 'Resource packaging failed' }
    & "$toolDir\aapt2.exe" link -I $androidJar --manifest 'AndroidManifest.xml' --java "build\$taskName\generated" -o "build\$taskName\base.apk" "build\$taskName\resources.zip"
    if ($LASTEXITCODE -ne 0) { throw 'Manifest packaging failed' }
} finally { Pop-Location }
$javaSources = @(Get-ChildItem -LiteralPath "$sourceDir\src", "$taskBuild\generated" -Filter '*.java' -Recurse | Select-Object -ExpandProperty FullName)
& $javac -encoding UTF-8 -source 8 -target 8 -cp $androidJar -d "$taskBuild\classes" $javaSources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
$classFiles = @(Get-ChildItem -LiteralPath "$taskBuild\classes" -Filter '*.class' -Recurse | Select-Object -ExpandProperty FullName)
& $java -cp "$toolDir\lib\d8.jar" com.android.tools.r8.D8 --min-api 29 --lib $androidJar --output "$taskBuild\dex" $classFiles
if ($LASTEXITCODE -ne 0) { throw 'DEX conversion failed' }
Copy-Item -LiteralPath "$taskBuild\base.apk" -Destination "$taskBuild\unaligned.apk"
Push-Location "$taskBuild\dex"
try { & "$toolDir\aapt.exe" add "$taskBuild\unaligned.apk" 'classes.dex'; if ($LASTEXITCODE -ne 0) { throw 'DEX packaging failed' } }
finally { Pop-Location }
if (Test-Path -LiteralPath "$sourceDir\assets") {
    $assetFiles = @(Get-ChildItem -LiteralPath "$sourceDir\assets" -Recurse -File | ForEach-Object { $_.FullName.Substring($sourceDir.Length + 1).Replace('\','/') })
    Push-Location $sourceDir
    try { & "$toolDir\aapt.exe" add "$taskBuild\unaligned.apk" $assetFiles; if ($LASTEXITCODE -ne 0) { throw 'Asset packaging failed' } }
    finally { Pop-Location }
}
Push-Location $taskBuild
try { & "$toolDir\aapt.exe" add 'unaligned.apk' 'lib/arm64-v8a/libirisbufferviewer.so'; if ($LASTEXITCODE -ne 0) { throw 'Native library packaging failed' } }
finally { Pop-Location }
& "$toolDir\zipalign.exe" -f -p 4 "$taskBuild\unaligned.apk" "$taskBuild\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed' }
$keyStore = Join-Path $buildDir 'viewer-test.keystore'
if (-not (Test-Path -LiteralPath $keyStore)) {
    & $keytool -genkeypair -keystore $keyStore -alias viewer-test -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname 'CN=Iris Local Test,O=Iris,C=CN'
    if ($LASTEXITCODE -ne 0) { throw 'Local test signing key creation failed' }
}
$apk = Join-Path $buildDir 'IrisBufferViewer.apk'
& $java -jar "$toolDir\lib\apksigner.jar" sign --ks $keyStore --ks-key-alias viewer-test --ks-pass pass:android --key-pass pass:android --out $apk "$taskBuild\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'APK signing failed' }
& $java -jar "$toolDir\lib\apksigner.jar" verify --verbose $apk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
Get-Item -LiteralPath $apk | Select-Object FullName,Length
Get-FileHash -LiteralPath $apk -Algorithm SHA256

$buildInfo = [ordered]@{
    builtAt = [DateTime]::Now.ToString('o')
    buildTools = $BuildToolsVersion
    platform = $Platform
    jdk = $JdkDir
    ndk = $NdkVersion
    native = [ordered]@{abi='arm64-v8a';bytes=(Get-Item -LiteralPath $nativeLib).Length;sha256=(Get-FileHash -LiteralPath $nativeLib -Algorithm SHA256).Hash.ToLowerInvariant()}
    apk = [ordered]@{file='IrisBufferViewer.apk'; bytes=(Get-Item -LiteralPath $apk).Length; sha256=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()}
    sources = @((Get-ChildItem -LiteralPath "$sourceDir\src", "$sourceDir\res", "$sourceDir\native" -File -Recurse) + (Get-Item -LiteralPath "$sourceDir\AndroidManifest.xml", "$sourceDir\build.ps1") | ForEach-Object { [ordered]@{file=$_.FullName.Substring($sourceDir.Length+1);sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()} })
}
$buildInfo | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath "$buildDir\build-info.json" -Encoding utf8



