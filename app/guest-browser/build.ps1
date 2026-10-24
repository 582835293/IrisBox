param([string]$SdkDir="$env:LOCALAPPDATA\Android\Sdk",[string]$JdkDir='C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot')
$ErrorActionPreference='Stop'
$taskSource=$PSScriptRoot
$taskBuild=Join-Path $taskSource ('build\work_'+[DateTime]::Now.ToString('yyyyMMdd_HHmmss_fff'))
$taskTools=Join-Path $SdkDir 'build-tools\34.0.0'
$taskAndroid=Join-Path $SdkDir 'platforms\android-30\android.jar'
New-Item -ItemType Directory -Force -Path "$taskBuild\classes","$taskBuild\dex","$taskBuild\generated" | Out-Null
Push-Location $taskSource
try {
    & "$taskTools\aapt2.exe" compile --dir res -o "$taskBuild\resources.zip"
    if($LASTEXITCODE -ne 0){throw 'Guest browser resources failed'}
    & "$taskTools\aapt2.exe" link -I $taskAndroid --manifest AndroidManifest.xml --java "$taskBuild\generated" -o "$taskBuild\base.apk" "$taskBuild\resources.zip"
    if($LASTEXITCODE -ne 0){throw 'Guest browser manifest failed'}
} finally {Pop-Location}
$taskSources=@(Get-ChildItem -LiteralPath "$taskSource\src","$taskBuild\generated" -Filter '*.java' -Recurse | Select-Object -ExpandProperty FullName)
& "$JdkDir\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -cp $taskAndroid -d "$taskBuild\classes" $taskSources
if($LASTEXITCODE -ne 0){throw 'Guest browser javac failed'}
$taskClasses=@(Get-ChildItem "$taskBuild\classes" -Filter '*.class' -Recurse | Select-Object -ExpandProperty FullName)
& "$JdkDir\bin\java.exe" -cp "$taskTools\lib\d8.jar" com.android.tools.r8.D8 --min-api 29 --lib $taskAndroid --output "$taskBuild\dex" $taskClasses
if($LASTEXITCODE -ne 0){throw 'Guest browser D8 failed'}
Copy-Item -LiteralPath "$taskBuild\base.apk" -Destination "$taskBuild\unsigned.apk"
Push-Location "$taskBuild\dex"
try {& "$taskTools\aapt.exe" add "$taskBuild\unsigned.apk" classes.dex; if($LASTEXITCODE -ne 0){throw 'Guest browser DEX packaging failed'}} finally {Pop-Location}
& "$taskTools\zipalign.exe" -f -p 4 "$taskBuild\unsigned.apk" "$taskBuild\aligned.apk"
if($LASTEXITCODE -ne 0){throw 'Guest browser alignment failed'}
$taskKey=Join-Path $taskSource 'build\browser-test.keystore'
if(-not (Test-Path -LiteralPath $taskKey)){
    & "$JdkDir\bin\keytool.exe" -genkeypair -keystore $taskKey -alias browser-test -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname 'CN=IrisBox Guest Browser,O=IrisBox,C=CN'
    if($LASTEXITCODE -ne 0){throw 'Guest browser key generation failed'}
}
$taskApk=Join-Path $taskSource 'build\IrisBoxBrowser.apk'
& "$JdkDir\bin\java.exe" -jar "$taskTools\lib\apksigner.jar" sign --ks $taskKey --ks-key-alias browser-test --ks-pass pass:android --key-pass pass:android --out $taskApk "$taskBuild\aligned.apk"
if($LASTEXITCODE -ne 0){throw 'Guest browser signing failed'}
& "$JdkDir\bin\java.exe" -jar "$taskTools\lib\apksigner.jar" verify $taskApk
if($LASTEXITCODE -ne 0){throw 'Guest browser signature failed'}
Get-FileHash -LiteralPath $taskApk -Algorithm SHA256
