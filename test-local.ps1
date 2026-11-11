# Fallback for Gradle Test worker class-loading failures in a non-ASCII Windows path.
# Uses the same compiled JUnit classes and resources; no dependencies are downloaded.
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    & .\gradlew.bat compileDebugUnitTestKotlin --offline
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $gradleCache = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
    $modules = Join-Path $gradleCache 'caches\modules-2\files-2.1'
    $jars = @('junit\junit\4.13.2', 'org.hamcrest\hamcrest-core\1.3', 'org.jetbrains.kotlin\kotlin-stdlib\2.2.21') | ForEach-Object {
        $jar = Get-ChildItem -LiteralPath (Join-Path $modules $_) -Recurse -Filter '*.jar' |
            Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1 -ExpandProperty FullName
        if (!$jar) { throw "Missing runtime jar: $_" }
        $jar
    }
    $testRoot = Join-Path $PSScriptRoot 'app\build\tmp\kotlin-classes\debugUnitTest'
    $classes = Get-ChildItem -LiteralPath $testRoot -Recurse -Filter '*Test.class' | ForEach-Object {
        $_.FullName.Substring($testRoot.Length + 1).Replace('\', '.').Replace('.class', '')
    }
    if (!$classes) { throw 'No test classes found.' }
    $classPath = (@($testRoot, (Join-Path $PSScriptRoot 'app\build\tmp\kotlin-classes\debug'),
        (Join-Path $PSScriptRoot 'app\src\test\resources')) + $jars) -join ';'
    $javaExe = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
    & $javaExe -cp $classPath org.junit.runner.JUnitCore @classes
    exit $LASTEXITCODE
} finally { Pop-Location }
