# Builds libkev.so (arm64-v8a) and copies it to app/src/main/jniLibs. The project path is non-ASCII, so the build
# runs through a temporary drive letter (subst) to keep every path the NDK toolchain sees ASCII.
# Not "Stop": PowerShell 5.1 turns cmake's stderr progress lines into terminating errors. Exit codes are checked instead.
$ErrorActionPreference = "Continue"
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$ndk = "$sdk\ndk\29.0.13846066"
$cmake = "$sdk\cmake\4.1.0\bin\cmake.exe"
$ninja = "$sdk\cmake\4.1.0\bin\ninja.exe"
$root = (Resolve-Path "$PSScriptRoot\..\..\..").Path   # E:\AI 키보드 앱 만들기~
$drive = "K:"

subst $drive $root
Push-Location "$drive\"   # cmake.exe crashes (0xC0000409) when started from a non-ASCII working directory
try {
    $src = "$drive\MaeumPet\native\kev"
    $build = "$drive\MaeumPet\native\kev\build-arm64"
    & $cmake -S $src -B $build -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja" `
        "-DCMAKE_TOOLCHAIN_FILE=$ndk\build\cmake\android.toolchain.cmake" `
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release `
        "-DLLAMA_SRC=$drive/dataset-evaluation/llamacpp/src" `
        "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16+i8mm"
    if ($LASTEXITCODE -ne 0) { throw "cmake configure failed" }
    & $cmake --build $build --target kev -j 8
    if ($LASTEXITCODE -ne 0) { throw "cmake build failed" }
    $out = "$drive\MaeumPet\app\src\main\jniLibs\arm64-v8a"
    New-Item -ItemType Directory -Force $out | Out-Null
    & "$ndk\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe" -o "$out\libkev.so" "$build\libkev.so"
    Get-Item "$out\libkev.so" | Select-Object Name, Length
} finally {
    Pop-Location
    subst $drive /D
}
