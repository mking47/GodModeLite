#!/bin/bash
# 对构建产物做静态验收：清单/权限/主题/模块声明/assets/dex 类/签名/root 命令。
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK:-/usr/lib/android-sdk}"
BT="$SDK/build-tools/29.0.3"
AAPT2="${AAPT2:-$BT/aapt2}"
APK="${1:-$ROOT/dist/GodModeLite-2.0.apk}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "== APK: $APK =="
[ -f "$APK" ] || { echo "APK 不存在"; exit 1; }
sha256sum "$APK"
echo
echo "== 1) badging =="
"$AAPT2" dump badging "$APK" | head -22
echo
echo "== 2) permissions =="
"$AAPT2" dump permissions "$APK"
echo
echo "== 3) manifest 关键项 =="
"$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" > "$TMP/manifest.txt"
for key in 'xposedmodule' 'xposedminversion' 'RulesProvider' 'BlockerService' 'android.accessibilityservice'; do
  if grep -q "$key" "$TMP/manifest.txt"; then echo "OK   $key"; else echo "MISS $key"; fi
done
grep -n 'application' "$TMP/manifest.txt" | head -3
echo
echo "== 4) assets/xposed_init =="
unzip -p "$APK" assets/xposed_init 2>/dev/null && echo "OK assets" || echo "MISS assets"
echo
echo "== 5) dex 类与大小 =="
unzip -o -q "$APK" classes.dex -d "$TMP"
python3 - "$TMP/classes.dex" <<'PY'
import struct,sys
d=open(sys.argv[1],'rb').read()
sn,soff=struct.unpack_from('<II',d,56)
mn,_=struct.unpack_from('<II',d,88)
cn,coff=struct.unpack_from('<II',d,96)
tn,toff=struct.unpack_from('<II',d,64)
def s(i):
    o=struct.unpack_from('<I',d,soff+4*i)[0]; p=o
    while d[p]!=0: p+=1
    return d[o:p].decode('utf-8','replace')
def t(i): return s(struct.unpack_from('<I',d,toff+4*i)[0])[1:]
names=set(t(struct.unpack_from('<I',d,coff+32*i)[0]) for i in range(cn))
print('dex size=%d strings=%d methods=%d classes=%d' % (len(d),sn,mn,cn))
need=['Lcom/dsh/godmodelite/MainActivity;','Lcom/dsh/godmodelite/AppDetailActivity;',
      'Lcom/dsh/godmodelite/core/RulesProvider;','Lcom/dsh/godmodelite/core/Rule;',
      'Lcom/dsh/godmodelite/HelpActivity;','Lcom/dsh/godmodelite/a11y/BlockerService;','Lcom/dsh/godmodelite/a11y/FloatingBall;',
      'Lcom/dsh/godmodelite/a11y/LayerInspectorOverlay;',
      'Lcom/dsh/godmodelite/xposed/GodModeModule;','Lcom/dsh/godmodelite/xposed/ModuleEngine;',
      'Lcom/dsh/godmodelite/xposed/RuleClient;']
for n in need:
    print(('OK   ' if n in names else 'MISS ')+n)
def s2(i):
    o=struct.unpack_from('<I',d,soff+4*i)[0]; p=o
    while d[p]!=0: p+=1
    return d[o:p].decode('utf-8','replace')
strings=[s2(i) for i in range(sn)]
for kw in ['settings put secure','appops set','SYSTEM_ALERT_WINDOW','enabled_accessibility_services','debug.layout','setprop']:
    print('string-hit %-32s -> %d' % (kw, sum(1 for x in strings if kw in x)))
PY
echo
echo "== 6) 签名 =="
"$BT/apksigner" verify --verbose "$APK" | head -8
echo
echo "== 7) 大小 =="
ls -la "$APK"
