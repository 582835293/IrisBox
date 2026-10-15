param([string]$SdkDir="$env:LOCALAPPDATA\Android\Sdk",[string]$JdkDir='C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot')
$ErrorActionPreference='Stop'
$toolDir=Join-Path $SdkDir 'build-tools\34.0.0'
$androidJar=Join-Path $SdkDir 'platforms\android-30\android.jar'
$buildDir=Join-Path $PSScriptRoot 'build'
$taskBuild=Join-Path $buildDir ('work_'+[DateTime]::Now.ToString('yyyyMMdd_HHmmss_fff'))
New-Item -ItemType Directory -Path "$taskBuild\classes","$taskBuild\dex" -Force | Out-Null
$sources=@(Get-ChildItem -LiteralPath "$PSScriptRoot\src" -Filter '*.java' -Recurse | Select-Object -ExpandProperty FullName)
& "$JdkDir\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -cp $androidJar -d "$taskBuild\classes" $sources
if($LASTEXITCODE -ne 0){throw 'Gateway javac failed'}
$classes=@(Get-ChildItem -LiteralPath "$taskBuild\classes" -Filter '*.class' -Recurse | Select-Object -ExpandProperty FullName)
& "$JdkDir\bin\java.exe" -cp "$toolDir\lib\d8.jar" com.android.tools.r8.D8 --min-api 28 --lib $androidJar --output "$taskBuild\dex" $classes
if($LASTEXITCODE -ne 0){throw 'Gateway DEX failed'}
$jar=Join-Path $buildDir 'IrisBufferViewerGateway.jar'
& "$JdkDir\bin\jar.exe" cf $jar -C "$taskBuild\dex" 'classes.dex'
if($LASTEXITCODE -ne 0){throw 'Gateway JAR failed'}
$info=[ordered]@{builtAt=[DateTime]::Now.ToString('o');jar=[ordered]@{bytes=(Get-Item -LiteralPath $jar).Length;sha256=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()};sources=@($sources|ForEach-Object{[ordered]@{file=$_.Substring($PSScriptRoot.Length+1);sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()}})}
$info | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath "$buildDir\build-info.json" -Encoding utf8
$info | ConvertTo-Json -Depth 6
