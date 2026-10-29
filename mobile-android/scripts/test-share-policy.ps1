$ErrorActionPreference = 'Stop'
$moduleRoot = Split-Path $PSScriptRoot -Parent
$classes = Join-Path $moduleRoot 'build/share-policy-tests'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
& (Join-Path $env:JAVA_HOME 'bin/javac.exe') --release 17 -d $classes (Join-Path $moduleRoot 'app/src/main/java/com/recovery/companion/SharePolicy.java') (Join-Path $moduleRoot 'app/src/test/java/com/recovery/companion/SharePolicyTest.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& (Join-Path $env:JAVA_HOME 'bin/java.exe') -cp $classes com.recovery.companion.SharePolicyTest
exit $LASTEXITCODE
