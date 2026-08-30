#!/bin/zsh
set -euo pipefail

project_root=${0:A:h:h}
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
cd "$project_root/android"
./gradlew testDebugUnitTest assembleDebug
/bin/echo "APK: $project_root/android/app/build/outputs/apk/debug/app-debug.apk"
