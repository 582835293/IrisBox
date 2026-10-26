param([string]$SdkDir="$env:LOCALAPPDATA\Android\Sdk",[string]$JdkDir='C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot',[switch]$NoInternet)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$taskBuild=Join-Path $taskRoot ('out\probe-'+[DateTime]::Now.ToString('yyyyMMdd_HHmmss'))
New-Item -ItemType Directory -Force -Path "$taskBuild\classes","$taskBuild\dex" | Out-Null
$taskTools=Join-Path $SdkDir 'build-tools\34.0.0'
$taskAndroid=Join-Path $SdkDir 'platforms\android-30\android.jar'
& "$JdkDir\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -cp $taskAndroid -d "$taskBuild\classes" "$PSScriptRoot\ProbeActivity.java"
if($LASTEXITCODE -ne 0){throw 'Probe javac failed'}
$taskClasses=@(Get-ChildItem "$taskBuild\classes" -Filter '*.class' -Recurse|Select-Object -ExpandProperty FullName)
& "$JdkDir\bin\java.exe" -cp "$taskTools\lib\d8.jar" com.android.tools.r8.D8 --min-api 29 --lib $taskAndroid --output "$taskBuild\dex" $taskClasses
if($LASTEXITCODE -ne 0){throw 'Probe D8 failed'}
$taskManifest="$PSScriptRoot\AndroidManifest.xml"
$taskOutput="$taskRoot\out\IrisBox-Probe.apk"
if($NoInternet){
    $taskText=[IO.File]::ReadAllText($taskManifest).Replace('package="com.irisbox.probe"','package="com.irisbox.noinet"').Replace('android:name=".ProbeActivity"','android:name="com.irisbox.probe.ProbeActivity"').Replace('<uses-permission android:name="android.permission.INTERNET" />','').Replace('IrisBox验收','IrisBox无网络权限验收')
    $taskManifest="$taskBuild\AndroidManifest.xml"
    [IO.File]::WriteAllText($taskManifest,$taskText,[Text.UTF8Encoding]::new($false))
    $taskOutput="$taskRoot\out\IrisBox-NoInternet.apk"
}
& "$taskTools\aapt2.exe" link -I $taskAndroid --manifest $taskManifest -o "$taskBuild\base.apk"
if($LASTEXITCODE -ne 0){throw 'Probe APK failed'}
Push-Location "$taskBuild\dex"
try{ & "$taskTools\aapt.exe" add "$taskBuild\base.apk" classes.dex }finally{Pop-Location}
& "$taskTools\zipalign.exe" -f 4 "$taskBuild\base.apk" "$taskBuild\aligned.apk"
& "$JdkDir\bin\java.exe" -jar "$taskTools\lib\apksigner.jar" sign --ks "$taskRoot\app\viewer\build\viewer-test.keystore" --ks-key-alias viewer-test --ks-pass pass:android --key-pass pass:android --out $taskOutput "$taskBuild\aligned.apk"
if($LASTEXITCODE -ne 0){throw 'Probe signing failed'}
Get-Item $taskOutput | Select-Object FullName,Length
