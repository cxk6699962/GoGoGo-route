"""一键发布：推送代码 + 创建 Release + 上传附件 + 设置 topics。

用法：
    python push.py <你的 GitHub PAT>

PAT 需要 repo 权限（classic: 勾 repo；fine-grained: Contents=Read/Write + Administration=Read/Write）。
只在本机使用，脚本不会把 token 写到任何文件里。
"""

import json
import os
import subprocess
import sys
import urllib.parse
import urllib.request

REPO = "cxk6699962/GoGoGo-route"
TAG = "v1.13.3"
TITLE = "v1.13.3 — 路径点可视化 / 重叠防护 / 巡航状态复位"
HERE = os.path.dirname(os.path.abspath(__file__))
REL_DIR = os.path.abspath(os.path.join(HERE, "..", "..", "out", "release-1.13.3"))
ASSETS = [
    "Go_1.13.3_arm64-v8a_release.apk",
    "Go_1.13.3_arm64-v8a_debug.apk",
    "GoGoGo-1.13.3-source.zip",
    "SHA256SUMS.txt",
]


def api(path, method="GET", data=None, raw=None, content_type="application/json"):
    url = "https://api.github.com" + path
    body = raw if raw is not None else (json.dumps(data).encode() if data is not None else None)
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Authorization", "Bearer " + TOKEN)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    if body is not None:
        req.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            payload = r.read()
            return r.status, (json.loads(payload) if payload else {})
    except urllib.error.HTTPError as e:
        return e.code, {"error": e.read().decode("utf-8", "ignore")[:500]}


def sh(args):
    print("$", " ".join(a if TOKEN not in a else a.replace(TOKEN, "***") for a in args))
    p = subprocess.run(args, cwd=HERE, capture_output=True, text=True)
    out = (p.stdout or "") + (p.stderr or "")
    print(out.strip()[:1500])
    return p.returncode


TOKEN = (sys.argv[1] if len(sys.argv) > 1 else "").strip()
if not TOKEN:
    print(__doc__)
    sys.exit(1)

remote = "https://x-access-token:%s@github.com/%s.git" % (TOKEN, REPO)

print("== 1/4 推送 main")
if sh(["git", "push", remote, "main:main", "-f"]) != 0:
    sys.exit(2)

print("== 2/4 推送 tag")
if sh(["git", "push", remote, TAG + ":" + TAG, "-f"]) != 0:
    sys.exit(3)

print("== 3/4 创建 Release")
body = open(os.path.join(REL_DIR, "RELEASE_NOTES.md"), encoding="utf-8").read()
status, rel = api("/repos/%s/releases" % REPO, "POST", {
    "tag_name": TAG,
    "target_commitish": "main",
    "name": TITLE,
    "body": body,
    "draft": False,
    "prerelease": False,
})
if status not in (200, 201):
    print("创建 Release 失败:", status, rel)
    sys.exit(4)
upload_url = rel["upload_url"].split("{")[0]
html_url = rel["html_url"]
print("Release:", html_url)

print("== 4/4 上传附件")
for name in ASSETS:
    path = os.path.join(REL_DIR, name)
    if not os.path.exists(path):
        print("  跳过（不存在）:", name)
        continue
    raw = open(path, "rb").read()
    status, resp = api(upload_url + "?name=" + urllib.parse.quote(name), "POST",
                       raw=raw, content_type="application/octet-stream")
    size_mb = len(raw) / 1048576.0
    print("  %-42s %s  %.1f MB" % (name, status, size_mb))

print("== 设置 topics")
api("/repos/%s/topics" % REPO, "PUT", {"names": ["android", "mock-location", "baidu-map", "gogogo", "gplv3"]})

print("\n完成：", html_url)
