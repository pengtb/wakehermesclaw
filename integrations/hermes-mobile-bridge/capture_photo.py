"""两段式拍照：camera.capture_photo（只有元数据 + id）→ camera.capture_photo.fetch（base64）→ 写文件。

前置条件：相机前台服务必须先"武装"（armed）。Android 只允许在应用有可见界面时启动相机类型的前台服务，
而这个时机就是 App 里的批准界面 —— 所以第一次拍照要先在手机上点一次「批准」；
如果选了「批准 10 分钟 / 1 小时」，服务会在这段时间里保持存活，后续拍照不用再点。

用法：
  python capture_photo.py                       # 默认写到 %LOCALAPPDATA%\\hermes\\cache\\photos\\photo-<时间戳>.jpg
  python capture_photo.py --out D:\\tmp\\a.jpg
  python capture_photo.py --out D:\\tmp          # 指定目录
  python capture_photo.py --facing front --long-edge 1568 --quality 85
  python capture_photo.py --no-fetch            # 只做第一段，打印元数据

成功时打印一行 JSON：{"ok": true, "path": ..., "bytes": ..., "width": ..., "height": ...,
"facing": ..., "id": ..., "captureMs": ..., "fetchMs": ...}
未武装时第一段会返回 {"ok": false, "code": "CAMERA_SERVICE_NOT_ARMED", ...}（退出码 1）。

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

DEFAULT_TIMEOUT = 60.0  # 相机要开设备、对焦，比截图慢
DEFAULT_LONG_EDGE = 1568
DEFAULT_QUALITY = 85
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
    return Path(base) / "hermes" / "cache" / "photos"


def resolve_out(arg: str | None) -> Path:
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    name = f"photo-{stamp}.jpg"
    if not arg:
        return default_dir() / name
    # 兼容从 bash 传进来的 MSYS 风格路径（/d/... → D:\...）
    if len(arg) > 2 and arg[0] == "/" and arg[2] == "/" and arg[1].isalpha():
        arg = f"{arg[1].upper()}:{arg[2:]}"
    p = Path(arg)
    if arg.endswith(("/", "\\")) or (p.exists() and p.is_dir()) or p.suffix == "":
        return p / name
    return p


def main(argv: list[str]) -> int:
    out_arg = None
    facing = "back"
    long_edge = DEFAULT_LONG_EDGE
    quality = DEFAULT_QUALITY
    fetch = True
    raw = False

    i = 0
    while i < len(argv):
        tok = argv[i]
        if tok == "--out":
            out_arg = argv[i + 1]; i += 2
        elif tok == "--facing":
            facing = argv[i + 1]; i += 2
        elif tok == "--long-edge":
            long_edge = int(argv[i + 1]); i += 2
        elif tok == "--quality":
            quality = int(argv[i + 1]); i += 2
        elif tok == "--no-fetch":
            fetch = False; i += 1
        elif tok == "--raw":
            raw = True; i += 1
        elif tok in ("-h", "--help"):
            print(__doc__); return 0
        else:
            raise SystemExit(f"无法识别的参数: {tok}")

    t0 = time.time()
    cap = execute("camera.capture_photo",
                  {"facing": facing, "long_edge": long_edge, "quality": quality})
    capture_ms = int((time.time() - t0) * 1000)
    result = cap.get("result") if isinstance(cap.get("result"), dict) else None
    if not result or not result.get("ok"):
        payload = {"ok": False, "stage": "capture", "captureMs": capture_ms}
        payload.update(cap if not raw else {"response": cap})
        print(json.dumps(payload, ensure_ascii=False))
        return 1

    if not fetch:
        print(json.dumps({"ok": True, "stage": "capture", "captureMs": capture_ms, **result},
                         ensure_ascii=False))
        return 0

    t1 = time.time()
    got = execute("camera.capture_photo.fetch", {"id": result["id"]})
    fetch_ms = int((time.time() - t1) * 1000)
    payload = got.get("result") if isinstance(got.get("result"), dict) else None
    b64 = payload.get("base64") if payload else None
    if not payload or not payload.get("ok") or not b64:
        print(json.dumps({"ok": False, "stage": "fetch", "id": result.get("id"),
                          "response": got}, ensure_ascii=False))
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
        "facing": result.get("facing"),
        "longEdge": result.get("longEdge"),
        "quality": result.get("quality"),
        "id": result.get("id"),
        "ttlMs": result.get("ttlMs"),
        "captureMs": capture_ms,
        "fetchMs": fetch_ms,
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
