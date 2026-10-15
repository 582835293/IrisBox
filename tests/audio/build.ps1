param([string]$SdkDir="$env:LOCALAPPDATA\Android\Sdk",[string]$JdkDir='C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot')
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$taskBuild=Join-Path $taskRoot ('out\audio-'+[DateTime]::Now.ToString('yyyyMMdd_HHmmss_fff'))
New-Item -ItemType Directory -Force -Path "$taskBuild\classes","$taskBuild\dex" | Out-Null
$taskTools=Join-Path $SdkDir 'build-tools\34.0.0'
$taskAndroid=Join-Path $SdkDir 'platforms\android-30\android.jar'
& "$JdkDir\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -cp $taskAndroid -d "$taskBuild\classes" "$taskRoot\engine\audio\AudioBridge.java"
if($LASTEXITCODE -ne 0){throw 'Audio bridge javac failed'}
$taskClasses=@(Get-ChildItem "$taskBuild\classes" -Filter '*.class' -Recurse|Select-Object -ExpandProperty FullName)
& "$JdkDir\bin\java.exe" -cp "$taskTools\lib\d8.jar" com.android.tools.r8.D8 --min-api 29 --lib $taskAndroid --output "$taskBuild\dex" $taskClasses
if($LASTEXITCODE -ne 0){throw 'Audio bridge D8 failed'}
& "$JdkDir\bin\jar.exe" cf "$taskRoot\out\RootVMOS-Audio.jar" -C "$taskBuild\dex" classes.dex
if($LASTEXITCODE -ne 0){throw 'Audio bridge jar failed'}
