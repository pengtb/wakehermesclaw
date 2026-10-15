"""经 Mobile Bridge 调手机相机拍照（Hermes -> 手机）。

用法（纯标准库，不需要 requests；一定要绕代理，否则 mihomo 会插手）：
    python bridge_camera_test.py                 # 用 camera.capture_photo
    python bridge_camera_test.py camera.capture_photo '{"facing":"back"}'

流程：/health 探活 -> /manifest 看相机能力的参数 schema -> /execute 执行 -> 把返回的图片落盘并校验。
若返回 approval_required，需要你在手机上点确认后再跑一次。
"""
import base64
import hashlib
import json
import os
import sys
import urllib.error
import urllib.request

URL = os.environ.get("AGENT_VOICE_BRIDGE_URL", "").rstrip("/")
TOK = os.environ.get("AGENT_VOICE_BRIDGE_TOKEN", "")
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
OUT_DIR = os.path.expandvars(r"%LOCALAPPDATA%\hermes\cache\scratch")


def call(path, method="GET", payload=None, timeout=45, auth=True):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(URL + path, data=data, method=method)
    if data:
        req.add_header("Content-Type", "application/json")
    if auth:
        req.add_header("Authorization", f"Bearer {TOK}")
    try:
        with OPENER.open(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8", "replace")
            return resp.status, (json.loads(body) if body.strip().startswith(("{", "[")) else body)
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8", "replace")[:400]
    except Exception as exc:  # noqa: BLE001
        return None, f"{type(exc).__name__}: {exc}"


def save_image(raw_b64, tag):
    blob = base64.b64decode(raw_b64)
    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, f"bridge-camera-{tag}.jpg")
    with open(path, "wb") as fh:
        fh.write(blob)
    return path, len(blob), hashlib.sha256(blob).hexdigest()[:16]


def find_image(node):
    """在返回体里找 base64 图片字段（不同版本字段名可能不同）。"""
    if isinstance(node, dict):
        for key in ("base64", "data", "image", "imageBase64", "jpeg", "photo"):
            value = node.get(key)
            if isinstance(value, str) and len(value) > 512:
                return value
        for value in node.values():
            found = find_image(value)
            if found:
                return found
    elif isinstance(node, list):
        for value in node:
            found = find_image(value)
            if found:
                return found
    return None


capability = sys.argv[1] if len(sys.argv) > 1 else "camera.capture_photo"
args = json.loads(sys.argv[2]) if len(sys.argv) > 2 else None

print(f"桥地址: {URL}")
print(f"token: len={len(TOK)} sha8={hashlib.sha256(TOK.encode()).hexdigest()[:8]}")

status, health = call("/health")
print(f"/health -> {status} {health}")
if status != 200:
    print("桥不可达：先在手机上把 Mobile Bridge 打开（Bind mode = LAN/VPN），再重跑。")
    raise SystemExit(1)

status, manifest = call("/manifest")
caps = manifest.get("capabilities") if isinstance(manifest, dict) else None
if isinstance(caps, list):
    cam = [c for c in caps if isinstance(c, dict) and "camera" in str(c.get("name", "")).lower()]
    print(f"/manifest -> {status}，共 {len(caps)} 项能力，其中相机相关 {len(cam)} 项：")
    for c in cam:
        print("  " + json.dumps(c, ensure_ascii=False)[:260])
    granted = {g.get("capability") for g in (manifest.get("grants") or []) if isinstance(g, dict)}
    print(f"已授权（grants）：{sorted(granted) if granted else '（无）'}")
else:
    print(f"/manifest -> {status} {str(manifest)[:200]}")

payload = {"capability": capability}
if args:
    payload["args"] = args
print(f"\n/execute {capability} args={args} ...")
status, body = call("/execute", method="POST", payload=payload, timeout=90)
print(f"-> HTTP {status}")
if isinstance(body, dict):
    print("   status:", body.get("status"), " requestId:", body.get("requestId") or body.get("id"))
    result = body.get("result", body)
    print("   result:", json.dumps(result, ensure_ascii=False)[:300])
    image = find_image(body)
    if image:
        path, size, sha = save_image(image, "photo")
        print(f"   已落盘: {path}  ({size} 字节, sha256[:16]={sha})")
        with open(path, "rb") as fh:
            head = fh.read(4)
        print(f"   JPEG 魔数: {head!r} -> {'OK' if head[:3] == b'\\xff\\xd8\\xff' else '不是 JPEG，需人工看一眼'}")
    elif body.get("status") == "approval_required":
        print("   需要你在手机上点确认（BridgeApprovalActivity 弹窗），确认后重新跑本脚本。")
else:
    print("   ", str(body)[:400])
