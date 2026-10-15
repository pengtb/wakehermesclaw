"""逐个实测 Mobile Bridge 能力（只碰无副作用 / 可还原的），结果写入 JSON 供汇总。

安全策略：
- 只读能力：直接测
- 可还原的：改前先读原值，测完写回（clipboard.write / audio.volume.set）
- 会产生真实副作用的（短信、联系人、日程、通知消除、点击滑动、媒体控制）：不测，标 skipped
"""
import json
import os
import time
import urllib.error
import urllib.request

URL = os.environ["AGENT_VOICE_BRIDGE_URL"].rstrip("/")
TOK = os.environ["AGENT_VOICE_BRIDGE_TOKEN"]
OP = urllib.request.build_opener(urllib.request.ProxyHandler({}))
OUT = os.path.expandvars(r"%LOCALAPPDATA%\hermes\cache\scratch\bridge-capability-results.json")

results = []


def ex(cap, args=None, timeout=60, note=""):
    payload = {"capability": cap}
    if args:
        payload["arguments"] = args
    req = urllib.request.Request(URL + "/execute", data=json.dumps(payload).encode(), method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", f"Bearer {TOK}")
    started = time.time()
    try:
        with OP.open(req, timeout=timeout) as resp:
            body = json.loads(resp.read().decode())
            status = body.get("status")
            err = (body.get("error") or {}).get("code")
            result = body.get("result")
    except urllib.error.HTTPError as exc:
        status, err, result = f"HTTP {exc.code}", exc.read().decode()[:200], None
    except Exception as exc:  # noqa: BLE001
        status, err, result = f"{type(exc).__name__}", str(exc)[:200], None
    elapsed = round(time.time() - started, 2)
    ok = status == "completed"
    results.append({"capability": cap, "status": status, "error": err, "result": result,
                    "seconds": elapsed, "note": note, "ok": ok})
    brief = json.dumps(result, ensure_ascii=False) if result is not None else "-"
    print(f"{cap:30} {status:12} {('err=' + str(err)) if err else '':26} {brief[:90]}")
    return result


def skip(cap, note):
    results.append({"capability": cap, "status": "skipped", "error": None, "result": None,
                    "seconds": 0, "note": note, "ok": None})
    print(f"{cap:30} {'skipped':12} {note}")


print("=== 只读能力 ===")
ex("device.info")
ex("apps.list")
ex("wifi.status")
ex("location.get")
ex("notifications.active.list")
ex("audio.volume.get")
ex("brightness.get")

print("\n=== 屏幕读取（无障碍）===")
ex("screen.window.describe")
ex("screen.hash")
nodes = ex("screen.find_nodes", {"clickable": "true"}) or {}
node_list = nodes.get("nodes") if isinstance(nodes, dict) else None
first_id = None
if isinstance(node_list, list) and node_list:
    first = node_list[0]
    first_id = first.get("nodeId") or first.get("id")
if first_id:
    ex("screen.describe_node", {"nodeId": str(first_id)}, note="nodeId 取自 find_nodes 第一条")
else:
    skip("screen.describe_node", "find_nodes 没返回 nodeId，无法链式测试")
prev_hash = None
hash_result = ex("screen.hash")
if isinstance(hash_result, dict):
    prev_hash = hash_result.get("hash") or hash_result.get("screenHash")
if prev_hash:
    ex("screen.diff", {"previousHash": str(prev_hash)}, note="previousHash 取自 screen.hash")
else:
    skip("screen.diff", "screen.hash 没返回 hash，无法链式测试")
ex("screen.screenshot")

print("\n=== 可还原的写操作 ===")
clip = ex("clipboard.read")
original_clip = clip.get("text") if isinstance(clip, dict) else None
ex("clipboard.write", {"text": "hermes-bridge-test-2026"}, note="测完写回原文")
back = ex("clipboard.read")
if isinstance(back, dict) and back.get("text") == "hermes-bridge-test-2026":
    results[-2]["note"] += "；写入已生效"
else:
    results[-2]["note"] += "；写入后读回不一致"
if original_clip is not None:
    ex("clipboard.write", {"text": original_clip}, note="还原原始剪贴板内容")
    restored = ex("clipboard.read")
    if isinstance(restored, dict) and restored.get("text") == original_clip:
        results[-2]["note"] += "；已还原"
else:
    skip("clipboard.write(还原)", "原剪贴板为空或不可读，未还原")

vol = ex("audio.volume.get")
original_vol = vol.get("volume") if isinstance(vol, dict) else None
if original_vol is not None:
    target = 1 if original_vol != 1 else 0
    ex("audio.volume.set", {"volume": target}, note=f"临时设为 {target}，随后还原为 {original_vol}")
    set_index = len(results) - 1
    now = ex("audio.volume.get")
    if isinstance(now, dict) and now.get("volume") == target:
        results[set_index]["note"] += "；设置已生效"
    ex("audio.volume.set", {"volume": original_vol}, note="还原原始音量")
    restore_index = len(results) - 1
    after = ex("audio.volume.get")
    if isinstance(after, dict) and after.get("volume") == original_vol:
        results[restore_index]["note"] += "；已还原"
else:
    skip("audio.volume.set", "读不到原始音量，未测试")

print("\n=== 启动 App + 回桌面（可还原）===")
ex("apps.launch", {"packageName": "com.android.settings"}, note="打开系统设置，随后按 Home 返回")
time.sleep(2)
ex("screen.window.describe", note="验证前台已变成设置")
ex("screen.home", note="按 Home 回到桌面")

print("\n=== 已知不可用 / 未实测 ===")
ex("camera.capture_photo", note="代码里 isAvailable=false 的占位实现")
skip("brightness.set", "未出现在 /manifest（运行时 isAvailable=false：需 WRITE_SETTINGS）")
skip("screen.tap", "会真的点屏幕，可能误触未知控件")
skip("screen.swipe", "同上")
skip("screen.long_press", "同上")
skip("screen.drag", "同上")
skip("screen.back", "会把当前界面往回退，未测")
skip("media.play_pause", "会切换正在播放的媒体，未测")
skip("media.next", "会切歌，未测")
skip("media.previous", "会切歌，未测")
skip("notification.dismiss", "会消除真实通知，未测")
skip("sms.send", "会真的发短信，未测")
skip("contacts.create", "会真的写联系人，未测")
skip("contacts.delete", "会真的删联系人，未测")
skip("calendar.create", "会真的写日程，未测")
skip("calendar.delete", "会真的删日程，未测")

with open(OUT, "w", encoding="utf-8") as fh:
    json.dump(results, fh, ensure_ascii=False, indent=2)
done = sum(1 for r in results if r["ok"] is True)
fail = sum(1 for r in results if r["ok"] is False)
print(f"\n实测完成 {done} 项，失败 {fail} 项，跳过 {sum(1 for r in results if r['ok'] is None)} 项")
print("明细 JSON:", OUT)
