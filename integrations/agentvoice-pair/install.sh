#!/usr/bin/env bash
# agentvoice-pair installer
#
#   bash install.sh            # bash 版（默认；只要 bash/curl/awk/od/gzip，不需要 Python）
#   bash install.sh --python   # 原版 helper（hermes_pair.py，需要 python3）
#
# Windows（git-bash / MSYS / Cygwin）下会自动装 PowerShell 版 agentvoice-pair.ps1，
# 并生成 agentvoice-pair.cmd 包装，使 `agentvoice-pair` 在 cmd/PowerShell 里可直接调用。
#
# 环境变量：
#   AGENT_VOICE_INSTALL_DIR   安装目录（默认 $HOME/.local/bin）
#   AGENT_VOICE_RAW_BASE      下载源（默认 GitHub raw main 分支）
#
# 本目录里同时存在脚本时（例如从仓库 checkout 安装），直接用本地文件而不联网。
set -euo pipefail

RAW_BASE="${AGENT_VOICE_RAW_BASE:-https://raw.githubusercontent.com/yuga-hashimoto/openclaw-assistant/main}"
INSTALL_DIR="${AGENT_VOICE_INSTALL_DIR:-$HOME/.local/bin}"
MODE="bash"
FORCE_POSIX=0

for arg in "$@"; do
  case "$arg" in
    --python) MODE="python";;
    --bash) MODE="bash";;
    --posix) FORCE_POSIX=1;;          # 即使在 git-bash 里也装 bash 版（而不是 PowerShell 版）
    -h|--help) sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'; exit 0;;
    *) echo "未知参数：$arg（-h 看用法）" >&2; exit 2;;
  esac
done

HERE="$(cd "$(dirname "$0")" && pwd)"
SH_LOCAL="$HERE/agentvoice-pair.sh"
PS1_LOCAL="$HERE/agentvoice-pair.ps1"
SH_URL="$RAW_BASE/integrations/agentvoice-pair/agentvoice-pair.sh"
PS1_URL="$RAW_BASE/integrations/agentvoice-pair/agentvoice-pair.ps1"
PY_URL="$RAW_BASE/integrations/hermes-mobile-bridge/hermes_pair.py"

is_windows() { case "$(uname -s 2>/dev/null || true)" in MINGW*|MSYS*|CYGWIN*) return 0;; esac; return 1; }

fetch() { # $1 本地路径或 URL，$2 目标文件
  local src="$1" dest="$2"
  if [ -f "$src" ]; then
    cp "$src" "$dest"
  else
    curl -fsSL "$src" -o "$dest"
  fi
}

pick() { # 本地优先，否则给 URL
  if [ -f "$1" ]; then printf '%s' "$1"; else printf '%s' "$2"; fi
}

mkdir -p "$INSTALL_DIR"

if is_windows && [ "$FORCE_POSIX" != 1 ]; then
  target="$INSTALL_DIR/agentvoice-pair.ps1"
  fetch "$(pick "$PS1_LOCAL" "$PS1_URL")" "$target"
  cat > "$INSTALL_DIR/agentvoice-pair.cmd" <<'CMD'
@echo off
rem agentvoice-pair —— 调用同目录的 PowerShell 版；参数按 PowerShell 风格传（-Code / -Open / -SelfTest，-h 看全表）
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0agentvoice-pair.ps1" %*
CMD
  if command -v powershell >/dev/null 2>&1; then
    # PowerShell 是原生程序，喂 MSYS 路径（/tmp/...、/c/...）它解析不了，要用 Windows 形式
    if command -v cygpath >/dev/null 2>&1; then ps_target="$(cygpath -w "$target")"; else ps_target="$target"; fi
    echo "自检中（-SelfTest）..."
    if powershell -NoProfile -ExecutionPolicy Bypass -File "$ps_target" -SelfTest >/dev/null 2>&1; then
      echo "自检通过"
    else
      echo "warning: PowerShell 版自检未通过，请手动跑 -SelfTest 看看" >&2
    fi
  else
    ps_target="$target"
  fi
  echo "Installed: $target"
  echo "Installed: $INSTALL_DIR/agentvoice-pair.cmd"
  echo "Run: agentvoice-pair        （或 powershell -ExecutionPolicy Bypass -File \"$ps_target\"）"
  exit 0
fi

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

if [ "$MODE" = "python" ]; then
  if ! command -v python3 >/dev/null 2>&1; then
    echo "error: python3 is required for --python" >&2
    exit 1
  fi
  fetch "$PY_URL" "$tmp"
  python3 -m py_compile "$tmp"
  install -m 0755 "$tmp" "$INSTALL_DIR/agentvoice-pair"
  echo "Installed agentvoice-pair (python edition) to $INSTALL_DIR/agentvoice-pair"
else
  fetch "$(pick "$SH_LOCAL" "$SH_URL")" "$tmp"
  bash -n "$tmp"                       # 语法自检，装坏了不如不装
  install -m 0755 "$tmp" "$INSTALL_DIR/agentvoice-pair"
  if "$INSTALL_DIR/agentvoice-pair" --self-test >/dev/null 2>&1; then
    echo "Installed agentvoice-pair (bash edition) to $INSTALL_DIR/agentvoice-pair （自检通过）"
  else
    echo "Installed agentvoice-pair (bash edition) to $INSTALL_DIR/agentvoice-pair"
    echo "warning: --self-test 未通过，请手动跑一次看看" >&2
  fi
fi

case ":$PATH:" in
  *":$INSTALL_DIR:"*) ;;
  *)
    echo "Add this to your shell profile if agentvoice-pair is not found:"
    echo "  export PATH=\"$INSTALL_DIR:\$PATH\""
    ;;
esac
echo "Run: agentvoice-pair"
