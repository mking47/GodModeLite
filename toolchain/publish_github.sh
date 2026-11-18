#!/bin/bash
# 发布 GodModeLite 3.0 到 GitHub：建仓库（含 README）→ 建 Release → 上传 APK/源码/文档
# 用法：
#   GITHUB_TOKEN=ghp_xxx bash toolchain/publish_github.sh [仓库名] [public|private]
# 说明：token 只在本进程内存中使用，不落盘；建议用后到 GitHub 撤销。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOKEN="${GITHUB_TOKEN:-}"
REPO="${1:-GodModeLite}"
VIS="${2:-public}"
API="https://api.github.com"
UP="https://uploads.github.com"
TAG="v3.0"
TITLE="上帝模式 Lite v3.0"
APK="$ROOT/dist/GodModeLite-3.0.apk"
SRC="$ROOT/dist/源码.zip"
NOTES="$ROOT/release/RELEASE_NOTES_v3.0.md"
README="$ROOT/release/README.md"

if [ -z "$TOKEN" ]; then echo "错误：需要 GITHUB_TOKEN 环境变量"; exit 1; fi
for f in "$APK" "$SRC" "$NOTES" "$README"; do
  [ -f "$f" ] || { echo "缺少文件：$f"; exit 1; }
done

api() { # api METHOD PATH [JSON]
  local method="$1" path="$2" data="${3:-}"
  if [ -n "$data" ]; then
    curl -sS -X "$method" -H "Authorization: Bearer $TOKEN" \
      -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28" \
      -H "Content-Type: application/json" --data "$data" "$API$path"
  else
    curl -sS -X "$method" -H "Authorization: Bearer $TOKEN" \
      -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28" \
      "$API$path"
  fi
}

echo "== 1) 校验 token =="
USER_JSON="$(api GET /user)"
LOGIN="$(printf '%s' "$USER_JSON" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("login",""))' 2>/dev/null || true)"
if [ -z "$LOGIN" ]; then echo "token 无效或没有权限：$USER_JSON"; exit 1; fi
echo "登录账号：$LOGIN"

echo "== 2) 创建仓库 $LOGIN/$REPO ($VIS) =="
PRIVATE=false; [ "$VIS" = "private" ] && PRIVATE=true
CREATE_BODY="$(python3 - "$REPO" "$PRIVATE" <<'PY'
import json,sys
print(json.dumps({
 "name": sys.argv[1],
 "description": "上帝模式 Lite：root 免框架遮挡 + LSPosed 模块真删 View 的安卓界面元素工具",
 "private": sys.argv[2] == "true",
 "has_issues": True, "has_projects": False, "has_wiki": False, "auto_init": True
}))
PY
)"
CREATE_JSON="$(api POST /user/repos "$CREATE_BODY")"
if printf '%s' "$CREATE_JSON" | grep -q '"full_name"'; then
  echo "仓库已创建：$LOGIN/$REPO"
elif printf '%s' "$CREATE_JSON" | grep -q 'already exists'; then
  echo "仓库已存在，继续使用：$LOGIN/$REPO"
else
  echo "创建仓库失败：$CREATE_JSON"; exit 1
fi

echo "== 3) 写入 README =="
sleep 2
SHA="$(api GET "/repos/$LOGIN/$REPO/contents/README.md" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("sha",""))' 2>/dev/null || true)"
README_B64="$(base64 -w0 "$README")"
README_BODY="$(python3 - "$README_B64" "$SHA" <<'PY'
import json,sys
body={"message":"docs: 添加中文 README","content":sys.argv[1]}
if sys.argv[2]:
    body["sha"]=sys.argv[2]
print(json.dumps(body))
PY
)"
api PUT "/repos/$LOGIN/$REPO/contents/README.md" "$README_BODY" >/dev/null && echo "README 已写入"

echo "== 4) 创建 Release $TAG =="
NOTES_JSON="$(python3 - "$NOTES" "$TAG" "$TITLE" <<'PY'
import json,sys
body={"tag_name":sys.argv[2],"name":sys.argv[3],"body":open(sys.argv[1],encoding='utf-8').read(),"draft":False,"prerelease":False}
print(json.dumps(body))
PY
)"
REL_JSON="$(api POST "/repos/$LOGIN/$REPO/releases" "$NOTES_JSON")"
UPLOAD_URL="$(printf '%s' "$REL_JSON" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("upload_url","").split("{")[0])' 2>/dev/null || true)"
if [ -z "$UPLOAD_URL" ]; then
  echo "创建 release 失败：$REL_JSON"
  exit 1
fi
echo "Release 已创建"

echo "== 5) 上传资产 =="
upload() { # upload FILE ASSET_NAME
  local file="$1" name="$2"
  local size; size=$(stat -c%s "$file")
  echo "-- $name ($size B)"
  curl -sS -X POST -H "Authorization: Bearer $TOKEN" \
    -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28" \
    -H "Content-Type: application/octet-stream" --data-binary "@$file" \
    "$UPLOAD_URL?name=$name" | grep -q '"browser_download_url"' && echo "   OK" || echo "   失败（可能已存在同名资产）"
}
upload "$APK" "GodModeLite-3.0.apk"
upload "$SRC" "source-v3.0.zip"
upload "$README" "README-zh.md"
upload "$NOTES" "release-notes-v3.0.md"
upload "$ROOT/docs/使用说明-3.0.md" "使用说明.md" 2>/dev/null || true
upload "$ROOT/docs/验收报告-3.0.md" "acceptance-report-3.0.md" 2>/dev/null || true

echo
echo "完成： https://github.com/$LOGIN/$REPO/releases/tag/$TAG"
