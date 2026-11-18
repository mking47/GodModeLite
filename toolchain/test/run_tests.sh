#!/bin/bash
# JVM 侧备份逻辑自测：不依赖 Android 运行时，验证 Rule JSON 与原版 ZIP 备份兼容解析。
set -e
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
AJ="${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}"
JSON="$ROOT/toolchain/test/json.jar"
OUT="/tmp/gm_lite_test_classes"
if [ ! -f "$JSON" ]; then
  echo "缺少 $JSON，请先下载 org.json（Maven Central: org/json/json/20231013）"
  exit 1
fi
rm -rf "$OUT" && mkdir -p "$OUT"
javac -source 8 -target 8 -bootclasspath "$AJ" -classpath "$JSON" -encoding UTF-8 \
  -d "$OUT" \
  "$ROOT/app/src/com/dsh/godmodelite/core/Rule.java" \
  "$ROOT/app/src/com/dsh/godmodelite/core/BackupManager.java" \
  "$ROOT/app/src/com/dsh/godmodelite/core/ConfigStore.java" \
  "$ROOT/app/src/com/dsh/godmodelite/core/AutoBackupManager.java" \
  "$ROOT/toolchain/test/TestBackup.java" \
  "$ROOT/toolchain/test/TestAutoBackup.java" 2>&1 | grep -v "bootstrap class path\|obsolete\|warning\|unchecked" || true
java -cp "$OUT:$JSON:$AJ" TestBackup
java -cp "$OUT:$JSON:$AJ" TestAutoBackup
