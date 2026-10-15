# agentvoice-pair

给 WakeHermesClaw / OpenClaw Assistant 做配对的小工具，两个等价实现：

| 文件 | 适用 | 依赖 |
|---|---|---|
| `agentvoice-pair.sh` | macOS / Linux / git-bash | bash 4.3+、curl、awk、od、gzip（PNG 需要） |
| `agentvoice-pair.ps1` | Windows（PowerShell 5.1 或 7） | 无（不用 Python、不用任何模块） |
| `install.sh` | 安装上面之一 | 默认装 bash 版；`--python` 装原版 `hermes_pair.py`（需要 python3） |

两件事：

1. **生成配对二维码**：探测 Hermes API 地址（优先 Tailscale https Serve，其次局域网）、
   API key、以及 dashboard 的 Terminal 地址与 session token，输出配对 JSON、
   `agentvoice://setup?...` 深链、终端二维码 + PNG（可选 SVG）。
2. **配对 Mobile Bridge**：用手机 App 上显示的 6 位配对码（或 `agentvoice://pair?...` 深链）
   换 token，并可写入 `.env`（`AGENT_VOICE_BRIDGE_URL` / `AGENT_VOICE_BRIDGE_TOKEN`）。

## 安装

```bash
bash install.sh              # bash 版 → $HOME/.local/bin/agentvoice-pair
bash install.sh --python     # 原版 Python helper
AGENT_VOICE_INSTALL_DIR=/usr/local/bin bash install.sh
```

Windows（git-bash/MSYS）下 `install.sh` 会装 `agentvoice-pair.ps1` 并生成
`agentvoice-pair.cmd` 包装，使 `agentvoice-pair` 在 cmd/PowerShell 中可直接调用。

## 用法

```bash
agentvoice-pair                      # 生成二维码（自动探测）
agentvoice-pair --open --svg         # 生成后打开 PNG，并额外写 SVG
agentvoice-pair -u https://host:8443 -k "$API_SERVER_KEY"
agentvoice-pair --code ABC234 --write-env ~/.hermes/.env
agentvoice-pair --self-test          # 校验二维码实现
```

```powershell
powershell -ExecutionPolicy Bypass -File agentvoice-pair.ps1 -Open
powershell -ExecutionPolicy Bypass -File agentvoice-pair.ps1 -Code ABC234 -WriteEnv "$env:LOCALAPPDATA\hermes\.env"
powershell -ExecutionPolicy Bypass -File agentvoice-pair.ps1 -SelfTest
```

常用开关：`-u/--url`、`-k/--key`、`--model`、`--name`、`--no-runs`、`--no-streaming`、
`--terminal-url`、`--terminal-token`、`--no-terminal`、`-o/--out`、`--svg`、`--no-terminal-qr`、
`--open`、`--code`、`--deeplink`、`--bridge-url`、`--write-env`、`--serve-port`、
`--terminal-serve-port`、`--api-port`、`--dashboard-port`、`--payload`、`--show-secrets`、
`--self-test`、`-q`。
调试：bash 版支持 `AVP_DEBUG=1`、`AVP_KEEP_TMP=1`。

## 二维码实现

两个版本都按 `integrations/hermes-mobile-bridge/hermes_pair.py` 的算法重写
（LOW 纠错、byte 模式、mask 0）：Reed-Solomon、ECC 交织、对齐符、版本信息、格式位全部一致，
PNG 也是手写的（bash 版用 `gzip -n` 造 deflate 流后拼 zlib 头与 adler32，CRC32 用 od+awk 算）。

`--self-test` / `-SelfTest` 用 4 个固定向量（版本 2/3/11/21）比对矩阵哈希，可以与 Python 版互校：

```
OK   len=39   version=3  size=29  hash=cfdf5f5bf2bc7094
OK   len=19   version=2  size=25  hash=076aedb870d3d6ea
OK   len=313  version=11 size=61  hash=d5c1f83da6fa8c01
OK   len=900  version=21 size=101 hash=2a3ac23f5b0aef6d
```

实测（2026-10-14）：自检全部通过；生成的 PNG 与 `hermes_pair.py` 的 `write_qr_png`、
以及 PowerShell/bash 两个版本互比，**像素完全一致**（短载荷 396×396、v11 载荷 828×828、
真实配对载荷 828×828）。

## 已知环境坑（都已在脚本里处理）

- **代理**：Windows PowerShell 5.1 的 `Invoke-WebRequest` 与 Python 的 `urllib` 都会走系统代理；
  本机装了 mihomo 时，ts.net 域名与手机内网地址的请求会被代理拦掉。两个版本分别用
  `HttpWebRequest` + `Proxy = $null`、`curl --noproxy '*'` 强制直连。
- 长连接复用：`.NET`/`OkHttp` 默认复用 TCP 连接，而 dashboard 反代只在首个请求上改写 Host，
  所以脚本侧一律一请求一连接（`KeepAlive = $false`）。
- bash 版：PNG 块顺序（长度→类型→数据→CRC）、zlib 里 adler32 的字节序（b 在高位）、
  `\0%03o` 会变成 4 位八进制、gawk 5.3 的 `and()/xor()/rshift()` 在 32 位值上算错、
  Windows 原生 curl 的 `-o /dev/null` 退出码是 23 —— 详见仓库根目录同主题文档。
