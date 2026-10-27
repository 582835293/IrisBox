param(
    [string]$SdkDir="$env:LOCALAPPDATA\Android\Sdk",
    [string]$JdkDir='C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot',
    [string]$NdkVersion='28.2.13676358',
    [string]$Python='python',
    [switch]$AppOnly,
    [switch]$RebuildProducer,
    [string]$ProducerHost=$env:IRISBOX_BUILD_HOST,
    [string]$ProducerKey=$env:IRISBOX_BUILD_KEY,
    [string]$AospDir=$env:IRISBOX_AOSP_DIR
)
$ErrorActionPreference='Stop'
$taskRoot=$PSScriptRoot
$taskNdk=Join-Path $SdkDir "ndk\$NdkVersion\toolchains\llvm\prebuilt\windows-x86_64\bin"
New-Item -ItemType Directory -Force -Path "$taskRoot\out" | Out-Null
& $Python "$taskRoot\tools\generate_i18n.py"
if($LASTEXITCODE -ne 0){throw 'Translation generation failed'}
New-Item -ItemType Directory -Force -Path "$taskRoot\app\viewer\assets\host-tools" | Out-Null
Copy-Item -LiteralPath "$taskRoot\engine\runtime\root\magisk_30_7\busybox" -Destination "$taskRoot\app\viewer\assets\host-tools\busybox"
if (-not $AppOnly) {
& "$taskRoot\app\guest-browser\build.ps1" -SdkDir $SdkDir -JdkDir $JdkDir | Out-File "$taskRoot\out\guest-browser-build.log"
if($LASTEXITCODE -ne 0){throw 'Guest browser build failed'}
New-Item -ItemType Directory -Force -Path "$taskRoot\engine\runtime\browser" | Out-Null
Copy-Item -LiteralPath "$taskRoot\app\guest-browser\build\IrisBoxBrowser.apk" -Destination "$taskRoot\engine\runtime\browser\IrisBoxBrowser.apk"
if($RebuildProducer){
    if(-not $ProducerHost -or -not $AospDir){throw 'RebuildProducer requires ProducerHost and AospDir (or IRISBOX_BUILD_HOST / IRISBOX_AOSP_DIR).'}
    $taskProducerArgs=@('--host',$ProducerHost,'--aosp',$AospDir)
    if($ProducerKey){$taskProducerArgs+=@('--key',$ProducerKey)}
    & $Python "$taskRoot\tools\build_producer.py" @taskProducerArgs
    if($LASTEXITCODE -ne 0){throw 'Producer build failed'}
}
& "$taskRoot\tests\audio\build.ps1" -SdkDir $SdkDir -JdkDir $JdkDir | Out-File "$taskRoot\out\audio-build.log"
if($LASTEXITCODE -ne 0){throw 'Audio bridge build failed'}
Copy-Item -LiteralPath "$taskRoot\out\IrisBox-Audio.jar" -Destination "$taskRoot\engine\runtime\audio\audio.jar"
& "$taskNdk\aarch64-linux-android30-clang.cmd" -static -O2 -Wall -Wextra -Werror -I "$taskRoot\engine\native\inet" "$taskRoot\engine\native\inet\policy.c" -o "$taskRoot\engine\runtime\iris_inet_policy"
if($LASTEXITCODE -ne 0){throw 'INET helper build failed'}
& "$taskNdk\aarch64-linux-android30-clang.cmd" -static -O2 -Wall -Wextra -Werror "$taskRoot\engine\native\iris_runtime_ns.c" -o "$taskRoot\engine\runtime\irisbox_ns"
if($LASTEXITCODE -ne 0){throw 'Namespace helper build failed'}
& "$taskNdk\aarch64-linux-android30-clang.cmd" -std=c11 -O2 -static -Wall -Wextra -Werror "$taskRoot\engine\native\userns\irisbox_userns.c" -o "$taskRoot\engine\runtime\irisbox_userns"
if($LASTEXITCODE -ne 0){throw 'Mapped namespace launcher build failed'}
& "$taskRoot\app\broker\build.ps1" -SdkDir $SdkDir -JdkDir $JdkDir | Out-File "$taskRoot\out\broker-build.log"
if($LASTEXITCODE -ne 0){throw 'Broker build failed'}
& "$taskRoot\app\gateway\build.ps1" -SdkDir $SdkDir -JdkDir $JdkDir | Out-File "$taskRoot\out\gateway-build.log"
if($LASTEXITCODE -ne 0){throw 'Gateway build failed'}
Copy-Item -LiteralPath "$taskRoot\app\broker\build\IrisBufferBroker.jar" -Destination "$taskRoot\engine\runtime\display\buffer_viewer\broker.jar"
Copy-Item -LiteralPath "$taskRoot\app\gateway\build\IrisBufferViewerGateway.jar" -Destination "$taskRoot\engine\runtime\display\buffer_viewer\gateway.jar"
& $Python "$taskRoot\tools\package_engine.py"
if($LASTEXITCODE -ne 0){throw 'Engine packaging failed'}
}
& "$taskRoot\app\viewer\build.ps1" -SdkDir $SdkDir -JdkDir $JdkDir -NdkVersion $NdkVersion
if($LASTEXITCODE -ne 0){throw 'APK build failed'}
$taskAppVersion=([xml](Get-Content -LiteralPath "$taskRoot\app\viewer\AndroidManifest.xml" -Raw)).manifest.versionName
Copy-Item -LiteralPath "$taskRoot\app\viewer\build\IrisBufferViewer.apk" -Destination "$taskRoot\out\IrisBox-$taskAppVersion.apk"
Get-FileHash -Algorithm SHA256 -LiteralPath "$taskRoot\out\IrisBox-$taskAppVersion.apk"
