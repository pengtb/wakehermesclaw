#!/usr/bin/env bash
# agentvoice-pair.sh —— agentvoice-pair 的 bash 版（与同目录 agentvoice-pair.ps1 对应）
#
# 1) 生成 WakeHermesClaw / OpenClaw Assistant 的配对二维码（API 后端 + Terminal）
# 2) 用 6 位配对码或 agentvoice://pair 深链换 Mobile Bridge token
#
# 二维码编码器按官方 hermes_pair.py 的算法实现（LOW 纠错、byte 模式、mask 0），
# 可用 --self-test 与 Python 版矩阵哈希对照，确保扫出来的内容一致。
#
# 依赖：bash 4.3+、curl、awk、od、gzip（PNG 需要，缺了只出终端二维码/SVG）
# 调试：AVP_DEBUG=1 打印版本/尺寸/中间量；AVP_KEEP_TMP=1 保留 PNG 组装用的临时目录
# 例：
#   ./agentvoice-pair.sh
#   ./agentvoice-pair.sh -o /tmp/qr.png --open --svg
#   ./agentvoice-pair.sh -u https://flow.taile4bd3b.ts.net:8443 -k "$API_SERVER_KEY"
#   ./agentvoice-pair.sh --code ABC234 --write-env ~/.hermes/.env
#   ./agentvoice-pair.sh --deeplink-qr -o /tmp/qr-deeplink.png   # 二维码用 agentvoice:// 深链，任何扫码 App 都能扫
#   ./agentvoice-pair.sh --self-test

set -uo pipefail
export LC_ALL=C

URLS=(); KEY=""; MODEL="default"; NAME="Hermes"
NO_RUNS=0; NO_STREAMING=0
TERMINAL_URL=""; TERMINAL_TOKEN=""; NO_TERMINAL=0
OUT=""; SVG=0; NO_TERMINAL_QR=0; DO_OPEN=0
CODE=""; DEEPLINK=""; BRIDGE_URL=""; WRITE_ENV=""; PAYLOAD=""; DEEPLINK_QR=0
SERVE_PORT=8443; TERMINAL_SERVE_PORT=10000; API_PORT=8642; DASHBOARD_PORT=9119
SHOW_SECRETS=0; SELF_TEST=0; QUIET=0

die() { echo "!! $*" >&2; exit 1; }
say() { [ "$QUIET" = 1 ] || echo "$*"; }

while [ $# -gt 0 ]; do
  case "$1" in
    -u|--url) URLS+=("$2"); shift 2;;
    -k|--key) KEY="$2"; shift 2;;
    --model) MODEL="$2"; shift 2;;
    --name) NAME="$2"; shift 2;;
    --no-runs) NO_RUNS=1; shift;;
    --no-streaming) NO_STREAMING=1; shift;;
    --terminal-url) TERMINAL_URL="$2"; shift 2;;
    --terminal-token) TERMINAL_TOKEN="$2"; shift 2;;
    --no-terminal) NO_TERMINAL=1; shift;;
    -o|--out) OUT="$2"; shift 2;;
    --svg) SVG=1; shift;;
    --no-terminal-qr) NO_TERMINAL_QR=1; shift;;
    --deeplink-qr) DEEPLINK_QR=1; shift;;
    --) shift; [ "$#" -eq 0 ] || die "不认识的参数：$*（本脚本不接受位置参数）";;
    --open) DO_OPEN=1; shift;;
    --code) CODE="$2"; shift 2;;
    --deeplink) DEEPLINK="$2"; shift 2;;
    --bridge-url) BRIDGE_URL="$2"; shift 2;;
    --write-env) WRITE_ENV="$2"; shift 2;;
    --serve-port) SERVE_PORT="$2"; shift 2;;
    --terminal-serve-port) TERMINAL_SERVE_PORT="$2"; shift 2;;
    --api-port) API_PORT="$2"; shift 2;;
    --dashboard-port) DASHBOARD_PORT="$2"; shift 2;;
    --payload) PAYLOAD="$2"; shift 2;;
    --show-secrets) SHOW_SECRETS=1; shift;;
    --self-test) SELF_TEST=1; shift;;
    -q|--quiet) QUIET=1; shift;;
    -h|--help) sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'; exit 0;;
    *) die "未知参数：$1（-h 看用法）";;
  esac
done

# ---- 参数校验：写错/冲突直接拒绝，不要"带了错参数也照跑" ----
if [ -n "$CODE$DEEPLINK" ] && [ "$DEEPLINK_QR" = 1 ]; then
  die "--code/--deeplink（配对码换 token）与 --deeplink-qr（生成二维码）不能混用"
fi
for pair in "--serve-port:$SERVE_PORT" "--terminal-serve-port:$TERMINAL_SERVE_PORT" "--api-port:$API_PORT" "--dashboard-port:$DASHBOARD_PORT"; do
  flag=${pair%%:*}; value=${pair#*:}
  case "$value" in ''|*[!0-9]*) die "$flag 必须是数字，当前是 '$value'";; esac
  [ "$value" -ge 1 ] && [ "$value" -le 65535 ] || die "$flag 必须在 1..65535，当前是 $value"
done
if [ -n "$CODE" ]; then
  case "$CODE" in *[!A-Za-z0-9]*) die "--code 看起来不是配对码（应为 4-16 位字母或数字）：$CODE";; esac
fi

sha256_stdin() { if command -v sha256sum >/dev/null 2>&1; then sha256sum | cut -d' ' -f1; else shasum -a 256 | cut -d' ' -f1; fi; }
sha8() { [ -n "${1:-}" ] || { printf '%s' '-'; return; }; printf '%s' "$1" | sha256_stdin | cut -c1-8; }
urlenc() {
  local s=$1 i c out=""
  for ((i=0;i<${#s};i++)); do
    c=${s:i:1}
    case "$c" in
      [a-zA-Z0-9.~_-]) out+="$c";;
      *) out+=$(printf '%%%02X' "'$c");;
    esac
  done
  printf '%s' "$out"
}

# ================================================================ 二维码算法
QR_ECC_LOW=(-1 7 10 15 20 26 18 20 24 30 18 20 24 26 30 22 24 28 30 28 28 28 28 30 30 26 28 30 30 30 30 30 30 30 30 30 30 30 30 30 30)
QR_BLOCKS_LOW=(-1 1 1 1 1 1 2 2 2 2 4 4 4 4 4 6 6 6 6 7 8 8 9 9 10 12 12 12 13 14 15 16 17 18 19 19 20 21 22 24 25)

qr_raw_codewords() {
  local v=$1 r na
  r=$(( (16*v + 128)*v + 64 ))
  if [ "$v" -ge 2 ]; then na=$(( v/7 + 2 )); r=$(( r - (25*na - 10)*na + 55 )); fi
  if [ "$v" -ge 7 ]; then r=$(( r - 36 )); fi
  printf '%s' $(( r / 8 ))
}

qr_alignment_positions() {
  local v=$1 na step pos out i
  if [ "$v" -eq 1 ]; then printf ''; return; fi
  na=$(( v/7 + 2 ))
  if [ "$v" -eq 32 ]; then step=26; else step=$(( ((v*4 + na*2 + 1) / (na*2 - 2)) * 2 )); fi
  out="6"; pos=$(( v*4 + 10 ))
  for ((i=0;i<na-1;i++)); do out="$pos $out"; pos=$(( pos - step )); done
  printf '%s' "$out"
}

RSM=0
rs_mul() {
  local x=$1 y=$2 z=0 i
  for ((i=7;i>=0;i--)); do
    z=$(( (z<<1) ^ ( ((z>>7)&1) * 0x11D ) ))
    z=$(( z ^ ( ((y>>i)&1) * x ) ))
  done
  RSM=$z
}

DIV=()
rs_divisor() {
  local d=$1 i j root=1 v
  DIV=(); for ((i=0;i<d;i++)); do DIV+=(0); done; DIV[$((d-1))]=1
  for ((i=0;i<d;i++)); do
    for ((j=0;j<d;j++)); do
      v=${DIV[j]}
      if [ "$v" -ne 0 ]; then rs_mul "$v" "$root"; v=$RSM; fi
      if [ "$((j+1))" -lt "$d" ]; then v=$(( v ^ DIV[j+1] )); fi
      DIV[$j]=$v
    done
    rs_mul "$root" 2; root=$RSM
  done
}

REM=()
rs_remainder() { # 读全局 BLOCK 与 DIV，写 REM
  local i n=${#DIV[@]} b factor coef j v
  REM=(); for ((i=0;i<n;i++)); do REM+=(0); done
  for b in "${BLOCK[@]}"; do
    factor=$(( b ^ REM[0] ))
    for ((j=0;j<n-1;j++)); do REM[$j]=${REM[$((j+1))]}; done
    REM[$((n-1))]=0
    for ((j=0;j<n;j++)); do
      coef=${DIV[j]}
      if [ "$coef" -ne 0 ] && [ "$factor" -ne 0 ]; then
        rs_mul "$coef" "$factor"; v=$RSM
        REM[$j]=$(( REM[j] ^ v ))
      fi
    done
  done
}

push_bits() { # $1 array-name $2 value $3 length
  local -n _arr=$1
  local value=$2 length=$3 i
  for ((i=length-1;i>=0;i--)); do _arr+=( $(( (value>>i) & 1 )) ); done
}

CW_VERSION=0; CW=()
qr_encode() {
  local payload=$1 v cap length_bits pad n k j bit val b
  local -a bytes=()
  while IFS= read -r b; do [ -n "$b" ] && bytes+=("$b"); done < <(printf '%s' "$payload" | od -An -v -tu1 | tr -s ' ' '\n')
  local len=${#bytes[@]}
  for ((v=1;v<=40;v++)); do
    cap=$(( ( $(qr_raw_codewords "$v") - QR_ECC_LOW[v]*QR_BLOCKS_LOW[v] ) * 8 ))
    if [ "$v" -le 9 ]; then length_bits=8; else length_bits=16; fi
    local -a bits=()
    push_bits bits 4 4
    push_bits bits "$len" "$length_bits"
    for b in "${bytes[@]}"; do push_bits bits "$b" 8; done
    if [ -n "${AVP_DEBUG:-}" ]; then echo "debug: v=$v cap=$cap bits=${#bits[@]} bytes=$len" >&2; fi
    [ "${#bits[@]}" -gt "$cap" ] && continue
    local pad_bits=$(( cap - ${#bits[@]} )); [ "$pad_bits" -gt 4 ] && pad_bits=4
    push_bits bits 0 "$pad_bits"
    while [ $(( ${#bits[@]} % 8 )) -ne 0 ]; do bits+=(0); done
    pad=236
    while [ "${#bits[@]}" -lt "$cap" ]; do
      push_bits bits "$pad" 8
      pad=$(( pad ^ 236 ^ 17 ))
    done
    n=$(( ${#bits[@]} / 8 ))
    local -a data=()
    for ((k=0;k<n;k++)); do
      val=0
      for ((j=0;j<8;j++)); do bit=${bits[$((k*8+j))]}; val=$(( (val<<1) | bit )); done
      data+=("$val")
    done
    CW_VERSION=$v
    qr_ecc_interleave "$v" "${data[@]}"
    return 0
  done
  die "配对载荷太大，单张二维码放不下（可减少端点或去掉 token）"
}

qr_ecc_interleave() { # $1 version，其余为 data codewords
  local v=$1; shift
  local -a alldata=("$@")
  local nb be raw short_n short_len i j off dataLen maxLen
  nb=${QR_BLOCKS_LOW[v]}; be=${QR_ECC_LOW[v]}; raw=$(qr_raw_codewords "$v")
  short_n=$(( nb - raw % nb ))
  short_len=$(( raw / nb - be ))
  rs_divisor "$be"
  local -a blockstr=() eccstr=()
  off=0
  for ((i=0;i<nb;i++)); do
    dataLen=$short_len
    if [ "$i" -ge "$short_n" ]; then dataLen=$(( dataLen + 1 )); fi
    BLOCK=("${alldata[@]:off:dataLen}")
    off=$(( off + dataLen ))
    rs_remainder
    blockstr+=("${BLOCK[*]}")
    eccstr+=("${REM[*]}")
  done
  CW=()
  maxLen=0
  local -a blk=()
  for ((i=0;i<nb;i++)); do
    read -r -a blk <<< "${blockstr[i]}"
    if [ "${#blk[@]}" -gt "$maxLen" ]; then maxLen=${#blk[@]}; fi
  done
  for ((j=0;j<maxLen;j++)); do
    for ((i=0;i<nb;i++)); do
      read -r -a blk <<< "${blockstr[i]}"
      if [ "$j" -lt "${#blk[@]}" ]; then CW+=("${blk[j]}"); fi
    done
  done
  for ((j=0;j<be;j++)); do
    for ((i=0;i<nb;i++)); do
      read -r -a blk <<< "${eccstr[i]}"
      CW+=("${blk[j]}")
    done
  done
}

MAT=(); FN=(); MAT_SIZE=0
qr_setf() { MAT[$(( $2*MAT_SIZE + $1 ))]=$3; FN[$(( $2*MAT_SIZE + $1 ))]=1; }

qr_make_matrix() {
  qr_encode "$1"
  local v=$CW_VERSION size i j x y dx dy cx cy dist ax ay center
  size=$(( v*4 + 17 )); MAT_SIZE=$size
  MAT=(); FN=()
  local total=$(( size*size ))
  for ((i=0;i<total;i++)); do MAT+=(0); FN+=(0); done

  for center in "3 3" "$(( size-4 )) 3" "3 $(( size-4 ))"; do
    read -r cx cy <<< "$center"
    for ((dy=-4;dy<=4;dy++)); do
      for ((dx=-4;dx<=4;dx++)); do
        x=$(( cx+dx )); y=$(( cy+dy ))
        if [ "$x" -ge 0 ] && [ "$x" -lt "$size" ] && [ "$y" -ge 0 ] && [ "$y" -lt "$size" ]; then
          ax=${dx#-}; ay=${dy#-}; dist=$ax
          if [ "$ay" -gt "$dist" ]; then dist=$ay; fi
          if [ "$dist" -ne 2 ] && [ "$dist" -ne 4 ]; then qr_setf "$x" "$y" 1; else qr_setf "$x" "$y" 0; fi
        fi
      done
    done
  done

  local positions; positions=$(qr_alignment_positions "$v")
  if [ -n "$positions" ]; then
    for cy in $positions; do
      for cx in $positions; do
        if [ "${FN[$(( cy*size + cx ))]}" = 1 ]; then continue; fi
        for ((dy=-2;dy<=2;dy++)); do
          for ((dx=-2;dx<=2;dx++)); do
            ax=${dx#-}; ay=${dy#-}; dist=$ax
            if [ "$ay" -gt "$dist" ]; then dist=$ay; fi
            if [ "$dist" -ne 1 ]; then qr_setf $(( cx+dx )) $(( cy+dy )) 1; else qr_setf $(( cx+dx )) $(( cy+dy )) 0; fi
          done
        done
      done
    done
  fi

  for ((i=0;i<size;i++)); do
    if [ "${FN[$(( 6*size + i ))]}" != 1 ]; then
      if [ $(( i % 2 )) -eq 0 ]; then qr_setf "$i" 6 1; else qr_setf "$i" 6 0; fi
    fi
    if [ "${FN[$(( i*size + 6 ))]}" != 1 ]; then
      if [ $(( i % 2 )) -eq 0 ]; then qr_setf 6 "$i" 1; else qr_setf 6 "$i" 0; fi
    fi
  done
  qr_setf 8 $(( size-8 )) 1
  for ((i=0;i<9;i++)); do
    if [ "$i" -ne 6 ]; then qr_setf 8 "$i" 0; qr_setf "$i" 8 0; fi
  done
  for ((i=0;i<8;i++)); do
    qr_setf $(( size-1-i )) 8 0
    qr_setf 8 $(( size-1-i )) 0
  done

  if [ "$v" -ge 7 ]; then
    local rem=$v b a bb vbits
    for ((i=0;i<12;i++)); do rem=$(( (rem<<1) ^ ( ((rem>>11)&1) * 0x1F25 ) )); done
    vbits=$(( (v<<12) | rem ))
    for ((i=0;i<18;i++)); do
      b=$(( (vbits>>i) & 1 ))
      a=$(( size-11 + i%3 )); bb=$(( i/3 ))
      qr_setf "$a" "$bb" "$b"; qr_setf "$bb" "$a" "$b"
    done
  fi

  local bitIndex=0 upward=1 xx vert dark bit masked
  local totalBits=$(( ${#CW[@]} * 8 ))
  x=$(( size-1 ))
  while [ "$x" -ge 1 ]; do
    if [ "$x" -eq 6 ]; then x=$(( x-1 )); fi
    for ((vert=0;vert<size;vert++)); do
      if [ "$upward" = 1 ]; then y=$(( size-1-vert )); else y=$vert; fi
      for ((dx=0;dx<2;dx++)); do
        xx=$(( x-dx ))
        if [ "${FN[$(( y*size + xx ))]}" = 1 ]; then continue; fi
        dark=0
        if [ "$bitIndex" -lt "$totalBits" ]; then
          bit=$(( (CW[bitIndex/8]>>(7 - bitIndex%8)) & 1 ))
          if [ "$bit" = 1 ]; then dark=1; fi
          bitIndex=$(( bitIndex+1 ))
        fi
        masked=$dark
        if [ $(( (xx+y) % 2 )) -eq 0 ]; then masked=$(( dark ^ 1 )); fi
        MAT[$(( y*size + xx ))]=$masked
      done
    done
    if [ "$upward" = 1 ]; then upward=0; else upward=1; fi
    x=$(( x-2 ))
  done

  local fmtData=$(( 1<<3 )) rem2=$(( 1<<3 ))
  for ((i=0;i<10;i++)); do rem2=$(( (rem2<<1) ^ ( ((rem2>>9)&1) * 0x537 ) )); done
  local fmtBits=$(( ((fmtData<<10) | rem2) ^ 0x5412 ))
  for ((i=0;i<6;i++)); do qr_setf 8 "$i" $(( (fmtBits>>i) & 1 )); done
  qr_setf 8 7 $(( (fmtBits>>6) & 1 ))
  qr_setf 8 8 $(( (fmtBits>>7) & 1 ))
  qr_setf 7 8 $(( (fmtBits>>8) & 1 ))
  for ((i=9;i<15;i++)); do qr_setf $(( 14-i )) 8 $(( (fmtBits>>i) & 1 )); done
  for ((i=0;i<8;i++)); do qr_setf $(( size-1-i )) 8 $(( (fmtBits>>i) & 1 )); done
  for ((i=8;i<15;i++)); do qr_setf 8 $(( size-15+i )) $(( (fmtBits>>i) & 1 )); done
  qr_setf 8 $(( size-8 )) 1
}

qr_matrix_text() {
  local size=$MAT_SIZE y x line
  for ((y=0;y<size;y++)); do
    line=""
    for ((x=0;x<size;x++)); do line+="${MAT[$(( y*size + x ))]}"; done
    printf '%s\n' "$line"
  done
}

qr_matrix_hash() { qr_make_matrix "$1"; qr_matrix_text | tr -d '\n' | sha256_stdin | cut -c1-16; }

# ================================================================ 渲染
qr_render_terminal() {
  qr_make_matrix "$1"
  local size=$MAT_SIZE quiet=1 y x by top bottom line ch
  for ((y=-quiet;y<size+quiet;y+=2)); do
    line=""
    for ((x=-quiet;x<size+quiet;x++)); do
      top=0; bottom=0
      if [ "$x" -ge 0 ] && [ "$x" -lt "$size" ] && [ "$y" -ge 0 ] && [ "$y" -lt "$size" ] \
         && [ "${MAT[$(( y*size + x ))]}" = 1 ]; then top=1; fi
      by=$(( y+1 ))
      if [ "$x" -ge 0 ] && [ "$x" -lt "$size" ] && [ "$by" -ge 0 ] && [ "$by" -lt "$size" ] \
         && [ "${MAT[$(( by*size + x ))]}" = 1 ]; then bottom=1; fi
      if [ "$top" = 1 ] && [ "$bottom" = 1 ]; then ch="█"
      elif [ "$top" = 1 ]; then ch="▀"
      elif [ "$bottom" = 1 ]; then ch="▄"
      else ch=" "; fi
      line+="$ch"
    done
    printf '%s\n' "$line"
  done
}

qr_render_svg() { # $1 payload $2 path
  qr_make_matrix "$1"
  local size=$MAT_SIZE quiet=4 cell=10 dim=$(( MAT_SIZE + 8 )) y x start dark
  {
    printf '%s\n' '<?xml version="1.0" encoding="UTF-8"?>'
    printf '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %s %s" width="%s" height="%s" shape-rendering="crispEdges">\n' \
      "$dim" "$dim" "$(( dim*cell ))" "$(( dim*cell ))"
    printf '<rect width="%s" height="%s" fill="#fff"/>\n' "$dim" "$dim"
    for ((y=0;y<size;y++)); do
      start=-1
      for ((x=0;x<=size;x++)); do
        dark=0
        if [ "$x" -lt "$size" ] && [ "${MAT[$(( y*size + x ))]}" = 1 ]; then dark=1; fi
        if [ "$dark" = 1 ] && [ "$start" -lt 0 ]; then
          start=$x
        elif [ "$dark" = 0 ] && [ "$start" -ge 0 ]; then
          printf '<rect x="%s" y="%s" width="%s" height="1" fill="#000"/>\n' "$(( start + quiet ))" "$(( y + quiet ))" "$(( x - start ))"
          start=-1
        fi
      done
    done
    printf '%s\n' '</svg>'
  } > "$2"
}

crc32_of_chunk() { # $1 type $2 datafile -> 8 位十六进制（对 type+data 求 CRC32）
  # 只用算术版：本机 gawk 5.3 的 and/xor/rshift 在 32 位值上算出来是 ffffffff（已用
  # "123456789"→cbf43926 的已知向量验证过，算术版正确）。
  local tmpf out; tmpf=$(mktemp)
  printf '%s' "$1" > "$tmpf"; cat "$2" >> "$tmpf"
  out=$(od -An -v -tu1 "$tmpf" | awk '
    function and32(a,b,  r,p) { r=0; p=1; while (a>0 || b>0) { if (a%2==1 && b%2==1) r+=p; a=int(a/2); b=int(b/2); p*=2 } return r }
    function xor32(a,b,  r,p) { r=0; p=1; while (a>0 || b>0) { if ((a%2)!=(b%2)) r+=p; a=int(a/2); b=int(b/2); p*=2 } return r }
    BEGIN { for (i=0;i<256;i++) { c=i; for (k=0;k<8;k++) { if (c%2==1) c=xor32(int(c/2), 0xEDB88320); else c=int(c/2) } T[i]=c } crc=4294967295 }
    { for (i=1;i<=NF;i++) { idx = xor32(and32(crc,255), $i); crc = xor32(T[idx], int(crc/256)) } }
    END { printf "%08x", xor32(crc, 4294967295) }')
  rm -f "$tmpf"
  printf '%s' "$out"
}

adler32_of_file() { # $1 file -> "a b"（十进制）
  od -An -v -tu1 "$1" | awk '
    BEGIN { a=1; b=0 }
    { for (i=1;i<=NF;i++) { a = (a + $i) % 65521; b = (b + a) % 65521 } }
    END { printf "%d %d", a, b }'
}

write_be32() { # 用法：printf 的格式串里写 \x 会和 %02x 抢位置（missing hex digit），
  # 而 "\0%03o" 又会变成 4 位八进制（\0000 → NUL + 字符 0，正是 PNG 流被多塞字符的元凶），
  # 所以统一用 \\%03o（恰好 3 位八进制）再交给 %b 解释。
  local v=$1
  printf '%b' "$(printf '\\%03o\\%03o\\%03o\\%03o' \
    $(( (v>>24)&255 )) $(( (v>>16)&255 )) $(( (v>>8)&255 )) $(( v&255 )))"
}

qr_render_png() { # $1 payload $2 path；成功 0，缺 gzip 返回 1
  command -v gzip >/dev/null 2>&1 || return 1
  qr_make_matrix "$1"
  local size=$MAT_SIZE scale=12 quiet=4
  local px=$(( (size + quiet*2) * scale ))
  local tmp; tmp=$(mktemp -d)
  qr_matrix_text > "$tmp/matrix.txt"

  awk -v size="$size" -v scale="$scale" -v quiet="$quiet" '
    BEGIN {
      blk=""; wht=""
      for (i=0;i<3*scale;i++) { blk = blk sprintf("%c", 0); wht = wht sprintf("%c", 255) }
    }
    { grid[NR]=$0 }
    END {
      px = (size + 2*quiet) * scale
      for (py=0; py<px; py++) {
        y = int(py/scale) - quiet
        row = sprintf("%c", 0)
        for (x=0; x<size+2*quiet; x++) {
          mx = x - quiet
          dark = (y>=0 && y<size && mx>=0 && mx<size && substr(grid[y+1], mx+1, 1) == "1")
          row = row (dark ? blk : wht)
        }
        printf "%s", row
      }
    }' "$tmp/matrix.txt" > "$tmp/raw.bin"

  # adler32 单独用 od+awk 扫一遍 raw.bin（曾经在生成扫描线的同一个 awk 里按 run 累加，
  # 结果算出来是错的——分开算更直观也更容易验证）
  read -r av bv <<< "$(adler32_of_file "$tmp/raw.bin")"
  gzip -n -9 -c "$tmp/raw.bin" > "$tmp/raw.gz"
  local gzsize; gzsize=$(wc -c < "$tmp/raw.gz")
  tail -c +11 "$tmp/raw.gz" | head -c $(( gzsize - 18 )) > "$tmp/deflate.bin"
  if [ -n "${AVP_DEBUG:-}" ]; then
    echo "debug[png]: raw=$(wc -c < "$tmp/raw.bin") gz=$gzsize deflate=$(wc -c < "$tmp/deflate.bin") adler=$av,$bv px=$px" >&2
  fi

  # zlib 尾部的 adler32 是「b 在高 16 位、a 在低 16 位」，写成 (av<<16)|bv 会高低颠倒，
  # Python 侧只会报 "incorrect data check"，很难看出原因。
  { printf '\x78\x9c'; cat "$tmp/deflate.bin"; write_be32 $(( (bv<<16) | av )); } > "$tmp/zlib.bin"
  { write_be32 "$px"; write_be32 "$px"; printf '\x08\x02\x00\x00\x00'; } > "$tmp/ihdr.bin"

  local outdir; outdir=$(dirname "$2")
  [ -n "$outdir" ] && mkdir -p "$outdir"
  : > "$2"
  printf '\x89PNG\r\n\x1a\n' >> "$2"
  # 注意块顺序：长度 -> 类型 -> 数据 -> CRC（PNG 规范），写反了 Python 会报 "incomplete or truncated stream"
  write_be32 13 >> "$2"; printf 'IHDR' >> "$2"; cat "$tmp/ihdr.bin" >> "$2"
  write_be32 $(( 16#$(crc32_of_chunk IHDR "$tmp/ihdr.bin") )) >> "$2"
  write_be32 "$(wc -c < "$tmp/zlib.bin")" >> "$2"; printf 'IDAT' >> "$2"; cat "$tmp/zlib.bin" >> "$2"
  write_be32 $(( 16#$(crc32_of_chunk IDAT "$tmp/zlib.bin") )) >> "$2"
  write_be32 0 >> "$2"; printf 'IEND' >> "$2"; write_be32 $(( 16#AE426082 )) >> "$2"
  if [ -n "${AVP_KEEP_TMP:-}" ]; then echo "debug[png]: 保留临时目录 $tmp" >&2; else rm -rf "$tmp"; fi
  return 0
}

# ================================================================ 探测
ENV_FILES=()
[ -n "${LOCALAPPDATA:-}" ] && ENV_FILES+=("$LOCALAPPDATA/hermes/.env")
[ -n "${APPDATA:-}" ] && ENV_FILES+=("$APPDATA/hermes/.env")
ENV_FILES+=("$HOME/.hermes/.env" "./.env")

env_value() {
  local name v line f
  for name in "$@"; do
    v="${!name:-}"
    [ -n "$v" ] && { printf '%s' "$v"; return; }
  done
  for f in "${ENV_FILES[@]}"; do
    [ -f "$f" ] || continue
    while IFS= read -r line; do
      line=${line%$'\r'}
      case "$line" in \#*|"") continue;; esac
      for name in "$@"; do
        case "$line" in
          "$name="*)
            v=${line#*=}
            case "$v" in \"*\") v=${v#\"}; v=${v%\"};; \'*\') v=${v#\'}; v=${v%\'};; esac
            [ -n "$v" ] && { printf '%s' "$v"; return; }
            ;;
        esac
      done
    done < "$f"
  done
}

http_status() { # 注意：Windows 原生 curl 下 -o /dev/null 会退出码 23，且不能用 || echo 0 兜底（会拼出 "2000"）
  local tmp out
  tmp=$(mktemp)
  out=$(curl -s --noproxy '*' -o "$tmp" -m "${2:-4}" -w '%{http_code}' "$1" 2>/dev/null)
  rm -f "$tmp"
  [ -n "$out" ] || out=0
  printf '%s' "$out"
}
http_body() { curl -s --noproxy '*' -m "${2:-6}" "$1" 2>/dev/null || true; }

first_lan_ip() {
  local ip
  ip=$(ip route get 1.1.1.1 2>/dev/null | sed -n 's/.*src \([0-9.]*\).*/\1/p' | head -1)
  if [ -z "$ip" ]; then
    # git-bash/Windows：跳过 Tailscale（100.64/10）与 loopback，优先 192.168/10/172.16-31
    ip=$(ipconfig 2>/dev/null | sed -n 's/.*IPv4[^:]*: *\([0-9.]*\).*/\1/p' \
         | grep -E '^(192\.168\.|10\.|172\.(1[6-9]|2[0-9]|3[01])\.)' | grep -v '^100\.' | head -1)
  fi
  [ -n "$ip" ] && printf '%s' "$ip"
}

TS_EXE=""
find_tailscale() {
  TS_EXE=$(command -v tailscale 2>/dev/null || true)
  if [ -z "$TS_EXE" ] && [ -x "/Applications/Tailscale.app/Contents/MacOS/Tailscale" ]; then TS_EXE="/Applications/Tailscale.app/Contents/MacOS/Tailscale"; fi
  if [ -z "$TS_EXE" ] && [ -n "${ProgramFiles:-}" ] && [ -x "$ProgramFiles/Tailscale/tailscale.exe" ]; then TS_EXE="$ProgramFiles/Tailscale/tailscale.exe"; fi
}

tailscale_dns_name() {
  find_tailscale
  [ -n "$TS_EXE" ] || return 1
  "$TS_EXE" status --json 2>/dev/null | sed -n 's/.*"DNSName"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1 | sed 's/\.$//'
}

dashboard_terminal() { # $1 local url，其余公网候选 -> stdout "url token"
  local local_url=$1; shift
  local html token url cand probe
  [ "$(http_status "$local_url/" 3)" = "200" ] || return 1
  html=$(http_body "$local_url/" 5)
  token=$(printf '%s' "$html" | sed -n 's/.*__HERMES_SESSION_TOKEN__[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)
  [ -n "$token" ] || return 1
  if ! printf '%s' "$html" | grep -q '__HERMES_DASHBOARD_EMBEDDED_CHAT__=true'; then
    echo "!! dashboard 页面显示 Terminal 未启用，请用 hermes dashboard --tui 启动" >&2
    return 1
  fi
  url="$local_url"
  for cand in "$@"; do
    [ -n "$cand" ] || continue
    probe=$(http_body "$cand/" 6)
    case "$probe" in *__HERMES_SESSION_TOKEN__*) url="$cand"; break;; esac
  done
  printf '%s %s' "$url" "$token"
}

json_str() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }

build_payload() {
  local urls_json="" first=1 u
  for u in "${FINAL_URLS[@]}"; do
    [ "$first" = 1 ] || urls_json+=","
    first=0
    urls_json+="\"$(json_str "$u")\""
  done
  local json="{\"type\":\"agentvoice_setup\",\"version\":1,\"hermes\":{\"urls\":[${urls_json}],"
  json+="\"model\":\"$(json_str "$MODEL")\","
  json+="\"runs\":$([ "$NO_RUNS" = 1 ] && echo false || echo true),"
  json+="\"streaming\":$([ "$NO_STREAMING" = 1 ] && echo false || echo true),"
  json+="\"key\":\"$(json_str "$KEY")\""
  [ -n "$NAME" ] && json+=",\"name\":\"$(json_str "$NAME")\""
  [ -n "$TERM_URL" ] && json+=",\"terminal\":{\"url\":\"$(json_str "$TERM_URL")\",\"token\":\"$(json_str "$TERM_TOKEN")\"}"
  json+="}}"
  printf '%s' "$json"
}

build_uri() {
  local u first=1 uri="agentvoice://setup?"
  for u in "${FINAL_URLS[@]}"; do
    if [ "$first" = 1 ]; then first=0; else uri+="&"; fi
    uri+="hu=$(urlenc "$u")"
  done
  uri+="&hk=$(urlenc "$KEY")&hm=$(urlenc "$MODEL")&hr=$([ "$NO_RUNS" = 1 ] && echo 0 || echo 1)&hs=$([ "$NO_STREAMING" = 1 ] && echo 0 || echo 1)"
  [ -n "$NAME" ] && uri+="&hn=$(urlenc "$NAME")"
  [ -n "$TERM_URL" ] && uri+="&htu=$(urlenc "$TERM_URL")&htt=$(urlenc "$TERM_TOKEN")"
  printf '%s' "$uri"
}

# ================================================================ 自检
if [ "$SELF_TEST" = 1 ]; then
  fail=0
  check() {
    local text=$1 want_v=$2 want_h=$3 h v
    qr_make_matrix "$text"
    v=$CW_VERSION
    h=$(qr_matrix_text | tr -d '\n' | sha256_stdin | cut -c1-16)
    if [ "$v" = "$want_v" ] && [ "$h" = "$want_h" ]; then
      printf 'OK   len=%-4s version=%-2s size=%-3s hash=%s\n' "${#text}" "$v" "$MAT_SIZE" "$h"
    else
      printf 'FAIL len=%-4s version=%s(期望 %s) hash=%s(期望 %s)\n' "${#text}" "$v" "$want_v" "$h" "$want_h"
      fail=$(( fail + 1 ))
    fi
  }
  check '{"type":"agentvoice_setup","version":1}' 3 cfdf5f5bf2bc7094
  check 'agentvoice-selftest' 2 076aedb870d3d6ea
  check '{"type":"agentvoice_setup","version":1,"hermes":{"urls":["https://flow.taile4bd3b.ts.net:8443"],"model":"default","runs":true,"streaming":true,"key":"00000000000000000000000000000000","name":"Hermes","terminal":{"url":"https://flow.taile4bd3b.ts.net:10000","token":"0000000000000000000000000000000000000000000"}}}' 11 d5c1f83da6fa8c01
  long=$(printf 'X%.0s' $(seq 1 900))
  check "$long" 21 2a3ac23f5b0aef6d
  if [ "$fail" = 0 ]; then echo "自检通过：二维码实现与 Python 版逐位一致"; exit 0; fi
  echo "自检失败 $fail 项"; exit 1
fi

# ================================================================ 模式一：配对码换 token
if [ -n "$CODE" ] || [ -n "$DEEPLINK" ]; then
  PAIR_CODE="$CODE"; BRIDGE="$BRIDGE_URL"
  if [ -n "$DEEPLINK" ]; then
    q=${DEEPLINK#*\?}
    [ -n "$PAIR_CODE" ] || PAIR_CODE=$(printf '%s' "$q" | tr '&' '\n' | sed -n 's/^c=//p' | head -1)
    [ -n "$BRIDGE" ] || BRIDGE=$(printf '%s' "$q" | tr '&' '\n' | sed -n 's/^u=//p' | head -1 | sed 's#%3A#:#g; s#%2F#/#g')
  fi
  [ -n "$BRIDGE" ] || BRIDGE=$(env_value AGENT_VOICE_BRIDGE_URL)
  [ -n "$BRIDGE" ] || BRIDGE="http://127.0.0.1:8787"
  BRIDGE=${BRIDGE%/}
  [ -n "$PAIR_CODE" ] || die "需要 --code 或 --deeplink 里的 c= 参数"
  case "$BRIDGE" in *"<"*|*"device-ip"*) die "桥地址里还有占位符：$BRIDGE";; esac

  say "桥地址: $BRIDGE"
  st=$(http_status "$BRIDGE/health" 8)
  say "/health -> HTTP $st"
  if [ "$st" != "200" ]; then
    say "连不上桥。检查：App 里 Mobile Bridge 已开启？Bind mode = LAN/VPN？手机与本机同一 tailnet？"
    exit 3
  fi
  resp=$(curl -s --noproxy '*' -m 15 -X POST -H 'Content-Type: application/json' -d "{\"code\":\"$PAIR_CODE\"}" "$BRIDGE/pair" 2>/dev/null || true)
  TOKEN=$(printf '%s' "$resp" | sed -n 's/.*"token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)
  if [ -z "$TOKEN" ]; then
    say "!! /pair 失败：$resp"
    say "   配对码是一次性的、约 5 分钟过期；回 App 重新生成一个。"
    exit 4
  fi
  say "/pair -> 拿到 token（${#TOKEN} 字符，前 4 位 ${TOKEN:0:4}…）"
  mresp=$(curl -s --noproxy '*' -m 15 -H "Authorization: Bearer $TOKEN" "$BRIDGE/manifest" 2>/dev/null || true)
  caps=$(printf '%s' "$mresp" | tr ',' '\n' | grep -c '"name"' || true)
  say "/manifest -> 能力 $caps 项"
  say ""
  say "在 Hermes 侧需要这两个变量："
  say "  AGENT_VOICE_BRIDGE_URL=$BRIDGE"
  say "  AGENT_VOICE_BRIDGE_TOKEN=$TOKEN"
  if [ -n "$WRITE_ENV" ]; then
    [ -f "$WRITE_ENV" ] || : > "$WRITE_ENV"
    tmpf=$(mktemp)
    grep -v -E '^(AGENT_VOICE_BRIDGE_URL|AGENT_VOICE_BRIDGE_TOKEN)=' "$WRITE_ENV" > "$tmpf" || true
    printf 'AGENT_VOICE_BRIDGE_URL=%s\nAGENT_VOICE_BRIDGE_TOKEN=%s\n' "$BRIDGE" "$TOKEN" >> "$tmpf"
    cat "$tmpf" > "$WRITE_ENV"; rm -f "$tmpf"
    say ""
    say "已写入 $WRITE_ENV（同名行原地替换）；两个变量按进程读，改完要重启 Hermes 会话。"
  fi
  exit 0
fi

# ================================================================ 模式二：生成二维码
render_all() {
  [ "$NO_TERMINAL_QR" = 1 ] || qr_render_terminal "$1"
  [ -n "$OUT" ] || OUT="$HOME/Downloads/agentvoice-pair-qr.png"
  if qr_render_png "$1" "$OUT"; then say ""; say "PNG: $OUT"; fi
  if [ "$SVG" = 1 ]; then
    svg="${OUT%.*}.svg"; qr_render_svg "$1" "$svg"; say "SVG: $svg"
  fi
  if [ "$DO_OPEN" = 1 ]; then
    if command -v xdg-open >/dev/null 2>&1; then xdg-open "$OUT" >/dev/null 2>&1 || true
    elif command -v open >/dev/null 2>&1; then open "$OUT" >/dev/null 2>&1 || true
    fi
  fi
}

if [ -n "$PAYLOAD" ]; then
  render_all "$PAYLOAD"
  exit 0
fi

[ -n "$KEY" ] || KEY=$(env_value HERMES_API_KEY HERMES_API_SERVER_KEY API_SERVER_KEY AGENT_VOICE_HERMES_KEY)

FINAL_URLS=()
for u in "${URLS[@]:-}"; do [ -n "$u" ] && FINAL_URLS+=("${u%/}"); done

DNS_NAME=$(tailscale_dns_name || true)
LAN_IP=$(first_lan_ip || true)
SERVE_URL=""; TERMINAL_SERVE_URL=""
if [ -n "$DNS_NAME" ]; then
  [ "$(http_status "https://$DNS_NAME:$SERVE_PORT/health" 6)" = "200" ] && SERVE_URL="https://$DNS_NAME:$SERVE_PORT"
  [ "$(http_status "https://$DNS_NAME:$TERMINAL_SERVE_PORT/" 6)" = "200" ] && TERMINAL_SERVE_URL="https://$DNS_NAME:$TERMINAL_SERVE_PORT"
fi
say "探测：MagicDNS=${DNS_NAME:-无}  LAN=${LAN_IP:-无}  API serve=${SERVE_URL:-无}  Terminal serve=${TERMINAL_SERVE_URL:-无}"

if [ "${#FINAL_URLS[@]}" -eq 0 ]; then
  if [ -n "${HERMES_API_URL:-}" ]; then
    say "使用环境变量 HERMES_API_URL：$HERMES_API_URL"
    FINAL_URLS+=("${HERMES_API_URL%/}")
  elif [ -n "$SERVE_URL" ]; then
    FINAL_URLS+=("$SERVE_URL")
  elif [ -n "$LAN_IP" ] && [ "$(http_status "http://$LAN_IP:$API_PORT/health" 5)" = "200" ]; then
    FINAL_URLS+=("http://$LAN_IP:$API_PORT")
  elif [ "$(http_status "http://127.0.0.1:$API_PORT/health" 3)" = "200" ]; then
    say "警告：只探测到 loopback 地址，手机连不上；建议先用 Tailscale Serve 暴露 https"
    FINAL_URLS+=("http://127.0.0.1:$API_PORT")
  fi
fi
if [ "${#FINAL_URLS[@]}" -eq 0 ] && [ -s "${HOME}/.local/state/agentvoice-pair-last-url" ]; then
  cached=$(tr -d '\r\n' < "${HOME}/.local/state/agentvoice-pair-last-url")
  if [ -n "$cached" ] && [ "$(http_status "$cached/health" 5)" = "200" ]; then
    say "自动探测没成功，但上次用过的地址仍可用：$cached"
    FINAL_URLS+=("$cached")
  fi
fi
[ "${#FINAL_URLS[@]}" -gt 0 ] && { mkdir -p "${HOME}/.local/state" 2>/dev/null; printf '%s' "${FINAL_URLS[0]}" > "${HOME}/.local/state/agentvoice-pair-last-url" 2>/dev/null || true; }
[ "${#FINAL_URLS[@]}" -gt 0 ] || {
  say "探测明细：MagicDNS=${DNS_NAME:-无}  LAN=${LAN_IP:-无}  API serve=${SERVE_URL:-无}  127.0.0.1:$API_PORT/health=$(http_status "http://127.0.0.1:$API_PORT/health" 3)"
  say "排查顺序："
  say "  1) Hermes API 是否在跑：curl http://127.0.0.1:$API_PORT/health 应为 200"
  say "  2) Tailscale Serve 是否还在：tailscale serve status 里应有 :$SERVE_PORT -> http://127.0.0.1:$API_PORT"
  say "  3) 直接指定：agentvoice-pair.sh -u https://<你的MagicDNS>:$SERVE_PORT"
  say "  4) 或设环境变量 HERMES_API_URL 后重跑"
  die "没探测到可用的 Hermes API 地址，请用 -u 指定"
}
[ -n "$KEY" ] || die "没找到 Hermes API key，请用 -k 指定（或在 .env 里设置 API_SERVER_KEY）"

TERM_URL=""; TERM_TOKEN=""
if [ "$NO_TERMINAL" = 0 ]; then
  cands=()
  [ -n "$TERMINAL_SERVE_URL" ] && cands+=("$TERMINAL_SERVE_URL")
  [ -n "$LAN_IP" ] && cands+=("http://$LAN_IP:$DASHBOARD_PORT")
  if pair=$(dashboard_terminal "http://127.0.0.1:$DASHBOARD_PORT" "${cands[@]:-}"); then
    TERM_URL=${pair%% *}; TERM_TOKEN=${pair#* }
    [ -n "$TERMINAL_URL" ] && TERM_URL=${TERMINAL_URL%/}
    [ -n "$TERMINAL_TOKEN" ] && TERM_TOKEN="$TERMINAL_TOKEN"
  fi
fi
case "$TERM_URL" in http://127.*|http://localhost*) say "提示：Terminal 地址是 loopback，手机连不上（建议把 $TERMINAL_SERVE_PORT 经 Tailscale Serve 指到 dashboard）";; esac

if [ "$DEEPLINK_QR" = 1 ]; then
  PAYLOAD=$(build_uri)
else
  PAYLOAD=$(build_payload)
fi

say "=== 配对概览 ==="
for u in "${FINAL_URLS[@]}"; do say "API 地址   : $u"; done
say "API key    : len=${#KEY} sha8=$(sha8 "$KEY")"
if [ -n "$TERM_URL" ]; then
  say "Terminal   : $TERM_URL"
  say "Terminal 令牌: len=${#TERM_TOKEN} sha8=$(sha8 "$TERM_TOKEN")"
else
  say "Terminal   : （未包含）"
fi
masked="$PAYLOAD"
if [ "$SHOW_SECRETS" = 0 ]; then
  if [ "$DEEPLINK_QR" = 1 ]; then
    masked=$(printf '%s' "$masked" | sed -e 's/\(hk=\)[^&]*/\1***/' -e 's/\(htt=\)[^&]*/\1***/')
  else
    masked=$(printf '%s' "$masked" | sed -e 's/\("key":"\)[^"]*/\1***/' -e 's/\("token":"\)[^"]*/\1***/')
  fi
fi
say "载荷       : $masked"
say "矩阵哈希   : $(qr_matrix_hash "$PAYLOAD")"
say ""

render_all "$PAYLOAD"

uri=$(build_uri)
if [ "$SHOW_SECRETS" = 0 ]; then
  uri=$(printf '%s' "$uri" | sed -e 's/\(hk=\)[^&]*/\1***/' -e 's/\(htt=\)[^&]*/\1***/')
fi
say "深链: $uri"
