"""两段式抓屏：screen.screenshot（只有元数据 + id）→ screen.screenshot.fetch（base64）→ 写文件。

用法：
  python capture_screen.py                      # 默认写到 %LOCALAPPDATA%\\hermes\\cache\\screenshots\\screen-<时间戳>.jpg
  python capture_screen.py --out D:\\tmp\\a.jpg  # 指定文件
  python capture_screen.py --out D:\\tmp         # 指定目录
  python capture_screen.py --long-edge 1280 --quality 80
  python capture_screen.py --no-fetch           # 只做第一段，打印元数据（不下载像素）

成功时打印一行 JSON：{"ok": true, "path": ..., "bytes": ..., "width": ..., "height": ...,
"sourceWidth": ..., "sourceHeight": ..., "id": ..., "captureMs": ..., "fetchMs": ...}
失败时打印 {"ok": false, "stage": "capture"|"fetch", ...} 并以退出码 1 结束。

地址与 token 从环境变量读（Hermes 继承 %LOCALAPPDATA%\\hermes\\.env）：
  AGENT_VOICE_BRIDGE_URL / AGENT_VOICE_BRIDGE_TOKEN
"""
import base64
import json
import os
import sys
import time
import urllib.request
from datetime import datetime
from pathlib import Path

DEFAULT_TIMEOUT = 45.0
DEFAULT_LONG_EDGE = 1568
DEFAULT_QUALITY = 80
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def base_url() -> str:
    url = os.environ.get("AGENT_VOICE_BRIDGE_URL", "").strip()
    if not url:
        raise SystemExit("缺少 AGENT_VOICE_BRIDGE_URL（放在 %LOCALAPPDATA%\\hermes\\.env 里）")
    return url.rstrip("/")


def execute(capability: str, arguments: dict | None = None, timeout: float = DEFAULT_TIMEOUT) -> dict:
    body = {"capability": capability}
    if arguments:
        body["arguments"] = arguments
    req = urllib.request.Request(
        base_url() + "/execute",
        data=json.dumps(body).encode(),
        headers={
            "Authorization": f"Bearer {os.environ.get('AGENT_VOICE_BRIDGE_TOKEN', '')}",
            "Content-Type": "application/json",
        },
    )
    try:
        with OPENER.open(req, timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8", "replace"))
    except Exception as e:
        return {"status": "error", "error": f"{type(e).__name__}: {e}"}


def default_dir() -> Path:
    base = os.environ.get("LOCALAPPDATA") or str(Path.home())
    return Path(base) / "hermes" / "cache" / "screenshots"


def resolve_out(arg: str | None) -> Path:
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    name = f"screen-{stamp}.jpg"
    if not arg:
        return default_dir() / name
    p = Path(arg)
    if arg.endswith(("/", "\\")) or (p.exists() and p.is_dir()) or p.suffix == "":
        return p / name
    return p


def main(argv: list[str]) -> int:
    out_arg = None
    long_edge = DEFAULT_LONG_EDGE
    quality = DEFAULT_QUALITY
    fetch = True
    keep_remote = False

    i = 0
    while i < len(argv):
        tok = argv[i]
        if tok == "--out":
            out_arg = argv[i + 1]; i += 2
        elif tok == "--long-edge":
            long_edge = int(argv[i + 1]); i += 2
        elif tok == "--quality":
            quality = int(argv[i + 1]); i += 2
        elif tok == "--no-fetch":
            fetch = False; i += 1
        elif tok == "--keep":
            keep_remote = True; i += 1
        elif tok in ("-h", "--help"):
            print(__doc__); return 0
        else:
            raise SystemExit(f"无法识别的参数: {tok}")

    t0 = time.time()
    cap = execute("screen.screenshot", {"long_edge": long_edge, "quality": quality})
    capture_ms = int((time.time() - t0) * 1000)
    result = cap.get("result") if isinstance(cap.get("result"), dict) else None
    if not result or not result.get("ok"):
        print(json.dumps({"ok": False, "stage": "capture", "response": cap}, ensure_ascii=False))
        return 1

    if not fetch:
        print(json.dumps({"ok": True, "stage": "capture", "captureMs": capture_ms, **result}, ensure_ascii=False))
        return 0

    t1 = time.time()
    got = execute("screen.screenshot.fetch", {"id": result["id"]})
    fetch_ms = int((time.time() - t1) * 1000)
    payload = got.get("result") if isinstance(got.get("result"), dict) else None
    b64 = payload.get("base64") if payload else None
    if not payload or not payload.get("ok") or not b64:
        print(json.dumps({"ok": False, "stage": "fetch", "id": result.get("id"), "response": got}, ensure_ascii=False))
        return 1

    blob = base64.b64decode(b64)
    path = resolve_out(out_arg)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(blob)

    print(json.dumps({
        "ok": True,
        "path": str(path),
        "bytes": len(blob),
        "mime": payload.get("mime", "image/jpeg"),
        "width": payload.get("width"),
        "height": payload.get("height"),
        "sourceWidth": result.get("sourceWidth"),
        "sourceHeight": result.get("sourceHeight"),
        "longEdge": result.get("longEdge"),
        "quality": result.get("quality"),
        "id": result.get("id"),
        "ttlMs": result.get("ttlMs"),
        "captureMs": capture_ms,
        "fetchMs": fetch_ms,
        "remoteKept": keep_remote,
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
