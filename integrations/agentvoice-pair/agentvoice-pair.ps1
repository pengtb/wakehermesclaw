<#
.SYNOPSIS
  agentvoice-pair（hermes_pair.py）的 Windows 原生 PowerShell 重写版。

.DESCRIPTION
  生成 WakeHermesClaw / OpenClaw Assistant 的配对二维码（API 后端 + Terminal），
  以及用 6 位配对码换 Mobile Bridge token。纯 PowerShell，不需要 bash / Python /
  第三方模块，也不需要用 install.sh 下载任何东西。

  与官方 Python 版的取舍：
    * 保留：Hermes Key/URL/终端 token 的探测、配对 JSON 与 agentvoice:// 深链、
      二维码输出（终端 + PNG + SVG）、Mobile Bridge 配对码兑换。
    * 去掉：ngrok/cloudflared 公网隧道、Node 多路复用代理、OpenClaw 安装探测
      （这些在 Windows 上要么不可用，要么本机不需要——本机用 Tailscale Serve）。

  二维码编码器是按 hermes_pair.py 的算法逐行移植的（LOW 纠错、byte 模式、mask 0），
  用 -SelfTest 可以和 Python 版的矩阵哈希对照，确保扫出来的内容一致。

.PARAMETER Url
  Hermes API 地址，可给多个。不给则自动探测（优先 Tailscale https Serve，其次局域网）。

.PARAMETER Key
  Hermes API key（API_SERVER_KEY）。不给则从环境变量 / .env 里找。

.PARAMETER Code
  手机 App 配对页显示的 6 位配对码，用它换 Mobile Bridge token。

.PARAMETER WriteEnv
  把换到的 AGENT_VOICE_BRIDGE_URL / AGENT_VOICE_BRIDGE_TOKEN 写进指定 .env（同名行原地替换）。

.PARAMETER SelfTest
  跑内置测试向量，校验二维码实现与 Python 版一致，不联网。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File agentvoice-pair.ps1
  生成配对二维码（自动探测），PNG 落在 Downloads\agentvoice-pair-qr.png

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File agentvoice-pair.ps1 -Url https://flow.taile4bd3b.ts.net:8443 -Key <KEY> -Open

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File agentvoice-pair.ps1 -Code ABC234 -WriteEnv "$env:LOCALAPPDATA\hermes\.env"
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    [string[]]$Url,
    [string]$Key,
    [string]$Model = 'default',
    [string]$Name = 'Hermes',
    [switch]$NoRuns,
    [switch]$NoStreaming,
    [string]$TerminalUrl,
    [string]$TerminalToken,
    [switch]$NoTerminal,
    [string]$Out,
    [switch]$Svg,
    [switch]$NoTerminalQr,
    [switch]$DeepLinkQr,
    [switch]$Open,
    [string]$Code,
    [string]$Payload,
    [string]$DeepLink,
    [string]$BridgeUrl,
    [string]$WriteEnv,
    [int]$ServePort = 8443,
    [int]$TerminalServePort = 10000,
    [int]$ApiPort = 8642,
    [int]$DashboardPort = 9119,
    [switch]$ShowSecrets,
    [switch]$SelfTest,
    [Alias('h', '?')]
    [switch]$Help,
    [switch]$Quiet,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Rest
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
try {
    [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12 -bor [System.Net.SecurityProtocolType]::Tls11
} catch { }

<#
注意：Windows PowerShell 5.1 的 Invoke-WebRequest 会走系统代理（WinINET 设置），
本机装了 mihomo 时会把 flow.taile4bd3b.ts.net / 手机内网地址的请求交给代理，结果全部失败。
所以下面统一用 HttpWebRequest 并把 Proxy 显式置空——和 Python 侧 ProxyHandler({}) 是同一个道理。
#>
function Invoke-HttpRequest {
    param(
        [string]$Uri,
        [string]$Method = 'GET',
        [hashtable]$Headers = @{},
        [string]$Body,
        [double]$TimeoutSec = 6
    )
    # 优先走 HttpClient：PowerShell 7（.NET Core）下 HttpWebResponse.StatusCode 用 [int] 转换会得到 0，
    # 于是"明明连上了、body 都读到了"却被当成失败（本脚本最早就是这么误报的）。HttpClient 在 5.1 和 7 上行为一致。
    if (-not ('System.Net.Http.HttpClient' -as [type])) {
        try { Add-Type -AssemblyName System.Net.Http -ErrorAction Stop } catch { }
    }
    if ('System.Net.Http.HttpClient' -as [type]) {
        return Invoke-HttpRequestViaClient -Uri $Uri -Method $Method -Headers $Headers -Body $Body -TimeoutSec $TimeoutSec
    }
    return Invoke-HttpRequestViaWebRequest -Uri $Uri -Method $Method -Headers $Headers -Body $Body -TimeoutSec $TimeoutSec
}

function Invoke-HttpRequestViaClient {
    param(
        [string]$Uri,
        [string]$Method = 'GET',
        [hashtable]$Headers = @{},
        [string]$Body,
        [double]$TimeoutSec = 6
    )
    $handler = New-Object System.Net.Http.HttpClientHandler
    $handler.UseProxy = $false        # 同 Python 侧 ProxyHandler({})：绝不让 mihomo 插手
    $handler.AllowAutoRedirect = $false
    $client = New-Object System.Net.Http.HttpClient($handler)
    $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSec)
    $request = $null
    try {
        $request = New-Object System.Net.Http.HttpRequestMessage((New-Object System.Net.Http.HttpMethod($Method)), $Uri)
        foreach ($key in $Headers.Keys) { [void]$request.Headers.TryAddWithoutValidation($key, [string]$Headers[$key]) }
        [void]$request.Headers.TryAddWithoutValidation('User-Agent', 'AgentVoicePair/1.0 (PowerShell)')
        if ($Body) {
            $request.Content = New-Object System.Net.Http.StringContent($Body, [System.Text.Encoding]::UTF8, 'application/json')
        }
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        try {
            $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            return [pscustomobject]@{ Status = [int]$response.StatusCode; Body = $text }
        } finally {
            $response.Dispose()
        }
    } catch {
        return [pscustomobject]@{ Status = 0; Body = $_.Exception.Message }
    } finally {
        if ($request) { $request.Dispose() }
        $client.Dispose()
        $handler.Dispose()
    }
}

function Invoke-HttpRequestViaWebRequest {
    param(
        [string]$Uri,
        [string]$Method = 'GET',
        [hashtable]$Headers = @{},
        [string]$Body,
        [double]$TimeoutSec = 6
    )
    $request = [System.Net.HttpWebRequest]::Create($Uri)
    $request.Method = $Method
    $request.Proxy = $null
    $request.KeepAlive = $false   # 一请求一连接，避免复用连接带来的歧义
    $request.Timeout = [int]($TimeoutSec * 1000)
    $request.ReadWriteTimeout = [int]($TimeoutSec * 1000)
    $request.AllowAutoRedirect = $false
    $request.UserAgent = 'AgentVoicePair/1.0 (PowerShell)'
    if ($Headers) { foreach ($key in $Headers.Keys) { $request.Headers[$key] = $Headers[$key] } }
    if ($Body) {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Body)
        $request.ContentType = 'application/json'
        $request.ContentLength = $bytes.Length
        $stream = $request.GetRequestStream()
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Close()
    }
    try {
        $response = $request.GetResponse()
    } catch [System.Net.WebException] {
        if ($_.Exception.Response) {
            $errorResponse = $_.Exception.Response
            $errorReader = New-Object System.IO.StreamReader($errorResponse.GetResponseStream())
            $errorText = $errorReader.ReadToEnd()
            $errorReader.Close()
            return [pscustomobject]@{ Status = [int]$errorResponse.StatusCode; Body = $errorText }
        }
        return [pscustomobject]@{ Status = 0; Body = $_.Exception.Message }
    }
    $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
    $text = $reader.ReadToEnd()
    $reader.Close()
    $response.Close()
    return [pscustomobject]@{ Status = [int]$response.StatusCode; Body = $text }
}

function Write-Line { param([string]$Text = '') if (-not $Quiet) { Write-Host $Text } }

function Get-Sha8 {
    param([string]$Text)
    if ([string]::IsNullOrEmpty($Text)) { return '-' }
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($Text))
        return (($bytes[0..3] | ForEach-Object { $_.ToString('x2') }) -join '')
    } finally { $sha.Dispose() }
}

# ============================ 二维码（移植自 hermes_pair.py）============================

$script:QrEccLow = @(-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30)
$script:QrBlocksLow = @(-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25)

function Get-QrRawCodewords { param([int]$Version)
    $result = (16 * $Version + 128) * $Version + 64
    if ($Version -ge 2) {
        $numAlign = [math]::Floor($Version / 7) + 2
        $result -= (25 * $numAlign - 10) * $numAlign - 55
    }
    if ($Version -ge 7) { $result -= 36 }
    return [int][math]::Floor($result / 8)
}

function Get-QrAlignmentPositions { param([int]$Version)
    if ($Version -eq 1) { return @() }
    $numAlign = [math]::Floor($Version / 7) + 2
    if ($Version -eq 32) { $step = 26 }
    else { $step = [int]([math]::Floor(($Version * 4 + $numAlign * 2 + 1) / ($numAlign * 2 - 2)) * 2) }
    $result = New-Object System.Collections.Generic.List[int]
    $result.Add(6)
    $pos = $Version * 4 + 10
    for ($i = 0; $i -lt $numAlign - 1; $i++) {
        $result.Insert(1, $pos)
        $pos -= $step
    }
    return $result.ToArray()
}

function Invoke-RsMultiply { param([int]$X, [int]$Y)
    $z = 0
    for ($i = 7; $i -ge 0; $i--) {
        $z = (($z -shl 1) -bxor (([int]($z -shr 7)) * 0x11D))
        $z = $z -bxor (([int](($Y -shr $i) -band 1)) * $X)
    }
    return $z
}

function Get-RsDivisor { param([int]$Degree)
    $result = New-Object 'int[]' $Degree
    for ($i = 0; $i -lt $Degree - 1; $i++) { $result[$i] = 0 }
    $result[$Degree - 1] = 1
    $root = 1
    for ($d = 0; $d -lt $Degree; $d++) {
        for ($j = 0; $j -lt $Degree; $j++) {
            $result[$j] = Invoke-RsMultiply $result[$j] $root
            if ($j + 1 -lt $Degree) { $result[$j] = $result[$j] -bxor $result[$j + 1] }
        }
        $root = Invoke-RsMultiply $root 0x02
    }
    return $result
}

function Get-RsRemainder { param([int[]]$Data, [int[]]$Divisor)
    $result = New-Object 'int[]' $Divisor.Length
    foreach ($b in $Data) {
        $factor = $b -bxor $result[0]
        for ($i = 0; $i -lt $result.Length - 1; $i++) { $result[$i] = $result[$i + 1] }
        $result[$result.Length - 1] = 0
        for ($i = 0; $i -lt $Divisor.Length; $i++) {
            $result[$i] = $result[$i] -bxor (Invoke-RsMultiply $Divisor[$i] $factor)
        }
    }
    return $result
}

function Add-Bits { param($List, [int]$Value, [int]$Length)
    for ($i = $Length - 1; $i -ge 0; $i--) { [void]$List.Add(([int](($Value -shr $i) -band 1))) }
}

function Add-QrEccAndInterleave { param([int]$Version, [int[]]$Data)
    $numBlocks = $script:QrBlocksLow[$Version]
    $blockEccLen = $script:QrEccLow[$Version]
    $rawCodewords = Get-QrRawCodewords $Version
    $numShortBlocks = $numBlocks - ($rawCodewords % $numBlocks)
    $shortBlockDataLen = [int][math]::Floor($rawCodewords / $numBlocks) - $blockEccLen
    $divisor = Get-RsDivisor $blockEccLen
    $blocks = New-Object System.Collections.Generic.List[object]
    $offset = 0
    for ($i = 0; $i -lt $numBlocks; $i++) {
        $dataLen = $shortBlockDataLen
        if ($i -ge $numShortBlocks) { $dataLen += 1 }
        $blockData = New-Object 'int[]' $dataLen
        for ($j = 0; $j -lt $dataLen; $j++) { $blockData[$j] = $Data[$offset + $j] }
        $offset += $dataLen
        $blocks.Add([pscustomobject]@{ Data = $blockData; Ecc = (Get-RsRemainder $blockData $divisor) })
    }
    $result = New-Object System.Collections.Generic.List[int]
    $maxDataLen = 0
    foreach ($blk in $blocks) { if ($blk.Data.Length -gt $maxDataLen) { $maxDataLen = $blk.Data.Length } }
    for ($i = 0; $i -lt $maxDataLen; $i++) {
        foreach ($blk in $blocks) { if ($i -lt $blk.Data.Length) { [void]$result.Add($blk.Data[$i]) } }
    }
    for ($i = 0; $i -lt $blockEccLen; $i++) {
        foreach ($blk in $blocks) { [void]$result.Add($blk.Ecc[$i]) }
    }
    return $result.ToArray()
}

function Get-QrCodewords { param([string]$Payload)
    $data = [System.Text.Encoding]::UTF8.GetBytes($Payload)
    for ($version = 1; $version -le 40; $version++) {
        $capacityBits = ((Get-QrRawCodewords $version) - $script:QrEccLow[$version] * $script:QrBlocksLow[$version]) * 8
        if ($version -le 9) { $lengthBits = 8 } else { $lengthBits = 16 }
        $bits = New-Object System.Collections.Generic.List[int]
        Add-Bits $bits 0x4 4
        Add-Bits $bits $data.Length $lengthBits
        foreach ($b in $data) { Add-Bits $bits $b 8 }
        if ($bits.Count -gt $capacityBits) { continue }
        Add-Bits $bits 0 ([math]::Min(4, $capacityBits - $bits.Count))
        while ($bits.Count % 8 -ne 0) { [void]$bits.Add(0) }
        $padByte = 0xEC
        while ($bits.Count -lt $capacityBits) {
            Add-Bits $bits $padByte 8
            $padByte = $padByte -bxor 0xEC -bxor 0x11
        }
        $codewords = New-Object 'int[]' ($bits.Count / 8)
        for ($i = 0; $i -lt $codewords.Length; $i++) {
            $value = 0
            for ($j = 0; $j -lt 8; $j++) { $value = ($value -shl 1) -bor $bits[$i * 8 + $j] }
            $codewords[$i] = $value
        }
        return [pscustomobject]@{ Version = $version; Data = (Add-QrEccAndInterleave $version $codewords) }
    }
    throw "配对载荷太大，单张二维码放不下。减少端点或去掉可选 token。"
}

function Get-QrMaskBit { param([int]$Mask, [int]$X, [int]$Y)
    if ($Mask -eq 0) { return (($X + $Y) % 2 -eq 0) }
    throw "不支持的 mask: $Mask"
}

function New-QrMatrix { param([string]$Payload)
    $enc = Get-QrCodewords $Payload
    $version = $enc.Version
    $codewords = $enc.Data
    $size = $version * 4 + 17
    $modules = New-Object 'object[]' $size
    $isFunction = New-Object 'object[]' $size
    for ($i = 0; $i -lt $size; $i++) {
        $modules[$i] = New-Object 'object[]' $size
        $isFunction[$i] = New-Object 'bool[]' $size
    }

    $setFunction = {
        param([int]$x, [int]$y, [bool]$dark)
        $modules[$y][$x] = $dark
        $isFunction[$y][$x] = $true
    }

    # 三个定位符 + 对齐符 + 时序线 + 保留区
    foreach ($center in @(@(3, 3), @(($size - 4), 3), @(3, ($size - 4)))) {
        $cx = $center[0]; $cy = $center[1]
        for ($dy = -4; $dy -le 4; $dy++) {
            for ($dx = -4; $dx -le 4; $dx++) {
                $x = $cx + $dx; $y = $cy + $dy
                if ($x -ge 0 -and $x -lt $size -and $y -ge 0 -and $y -lt $size) {
                    $dist = [math]::Max([math]::Abs($dx), [math]::Abs($dy))
                    & $setFunction $x $y ($dist -ne 2 -and $dist -ne 4)
                }
            }
        }
    }
    foreach ($cy in (Get-QrAlignmentPositions $version)) {
        foreach ($cx in (Get-QrAlignmentPositions $version)) {
            if ($isFunction[$cy][$cx]) { continue }
            for ($dy = -2; $dy -le 2; $dy++) {
                for ($dx = -2; $dx -le 2; $dx++) {
                    & $setFunction ($cx + $dx) ($cy + $dy) ([math]::Max([math]::Abs($dx), [math]::Abs($dy)) -ne 1)
                }
            }
        }
    }
    for ($i = 0; $i -lt $size; $i++) {
        if (-not $isFunction[6][$i]) { & $setFunction $i 6 ($i % 2 -eq 0) }
        if (-not $isFunction[$i][6]) { & $setFunction 6 $i ($i % 2 -eq 0) }
    }
    & $setFunction 8 ($size - 8) $true
    for ($i = 0; $i -lt 9; $i++) {
        if ($i -ne 6) {
            & $setFunction 8 $i $false
            & $setFunction $i 8 $false
        }
    }
    for ($i = 0; $i -lt 8; $i++) {
        & $setFunction ($size - 1 - $i) 8 $false
        & $setFunction 8 ($size - 1 - $i) $false
    }
    if ($version -ge 7) {
        $rem = $version
        for ($i = 0; $i -lt 12; $i++) { $rem = ($rem -shl 1) -bxor (([int]($rem -shr 11)) * 0x1F25) }
        $bitsVersion = ($version -shl 12) -bor $rem
        for ($i = 0; $i -lt 18; $i++) {
            $bit = ((($bitsVersion -shr $i) -band 1) -ne 0)
            $a = $size - 11 + ($i % 3)
            $b = [int][math]::Floor($i / 3)
            & $setFunction $a $b $bit
            & $setFunction $b $a $bit
        }
    }

    # 数据区 + mask 0
    $bitIndex = 0
    $upward = $true
    $totalBits = $codewords.Length * 8
    $x = $size - 1
    while ($x -ge 1) {
        if ($x -eq 6) { $x -= 1 }
        for ($vert = 0; $vert -lt $size; $vert++) {
            if ($upward) { $y = $size - 1 - $vert } else { $y = $vert }
            for ($dx = 0; $dx -lt 2; $dx++) {
                $xx = $x - $dx
                if ($isFunction[$y][$xx]) { continue }
                $dark = $false
                if ($bitIndex -lt $totalBits) {
                    $dark = ((([int]($codewords[[int][math]::Floor($bitIndex / 8)] -shr (7 - ($bitIndex % 8)))) -band 1) -ne 0)
                    $bitIndex++
                }
                $modules[$y][$xx] = ($dark -bxor (Get-QrMaskBit 0 $xx $y))
            }
        }
        $upward = -not $upward
        $x -= 2
    }

    # 格式信息（LOW 纠错 + mask 0）
    $fmtData = (1 -shl 3) -bor 0
    $rem2 = $fmtData
    for ($i = 0; $i -lt 10; $i++) { $rem2 = ($rem2 -shl 1) -bxor (([int]($rem2 -shr 9)) * 0x537) }
    $fmtBits = ((($fmtData -shl 10) -bor $rem2) -bxor 0x5412)
    for ($i = 0; $i -lt 6; $i++) { & $setFunction 8 $i (((($fmtBits -shr $i) -band 1) -ne 0)) }
    & $setFunction 8 7 (((($fmtBits -shr 6) -band 1) -ne 0))
    & $setFunction 8 8 (((($fmtBits -shr 7) -band 1) -ne 0))
    & $setFunction 7 8 (((($fmtBits -shr 8) -band 1) -ne 0))
    for ($i = 9; $i -lt 15; $i++) { & $setFunction (14 - $i) 8 (((($fmtBits -shr $i) -band 1) -ne 0)) }
    for ($i = 0; $i -lt 8; $i++) { & $setFunction ($size - 1 - $i) 8 (((($fmtBits -shr $i) -band 1) -ne 0)) }
    for ($i = 8; $i -lt 15; $i++) { & $setFunction 8 ($size - 15 + $i) (((($fmtBits -shr $i) -band 1) -ne 0)) }
    & $setFunction 8 ($size - 8) $true

    $matrix = New-Object 'object[]' $size
    for ($y = 0; $y -lt $size; $y++) {
        $row = New-Object 'bool[]' $size
        for ($x = 0; $x -lt $size; $x++) { $row[$x] = ($modules[$y][$x] -eq $true) }
        $matrix[$y] = $row
    }
    return $matrix
}

function Get-QrMatrixHash { param($Matrix)
    $sb = New-Object System.Text.StringBuilder
    foreach ($row in $Matrix) {
        foreach ($cell in $row) { if ($cell) { [void]$sb.Append('1') } else { [void]$sb.Append('0') } }
    }
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($sb.ToString()))
        return (($bytes | ForEach-Object { $_.ToString('x2') }) -join '')
    } finally { $sha.Dispose() }
}

# ============================ PNG / SVG / 终端输出 ============================

function Get-Crc32 {
    param([byte[]]$Bytes)
    # 坑 1：PowerShell 把十六进制字面量 0xFFFFFFFF 解析成 Int32 的 -1、0xEDB88320 解析成 -306674912，
    #       再 [int64] 转换会符号扩展，CRC 高位被污染（写出的 PNG 每个块 CRC 全错，libpng 直接拒读）。
    # 坑 2：别手写十进制常量（0xEDB88320 = 3988292384，写成 3988299552 只差 0x1C00，肉眼极难发现）。
    #       这里统一用 Convert::ToUInt32(hex) 取值，避免两种坑。
    $mask = [int64][System.Convert]::ToUInt32('FFFFFFFF', 16)
    $poly = [int64][System.Convert]::ToUInt32('EDB88320', 16)
    $crc = $mask
    foreach ($b in $Bytes) {
        $crc = (($crc -bxor [int64]$b) -band $mask)
        for ($i = 0; $i -lt 8; $i++) {
            if (($crc -band 1) -ne 0) { $crc = ((($crc -shr 1) -bxor $poly) -band $mask) }
            else { $crc = (($crc -shr 1) -band $mask) }
        }
    }
    return (($crc -bxor $mask) -band $mask)
}

function Get-Adler32 {
    param([byte[]]$Bytes)
    $a = [uint32]1; $b = [uint32]0
    foreach ($byte in $Bytes) {
        $a = ($a + $byte) % 65521
        $b = ($b + $a) % 65521
    }
    return [uint32](($b -shl 16) -bor $a)
}

function Compress-ZlibDeflate {
    param([byte[]]$Bytes)
    $ms = New-Object System.IO.MemoryStream
    $ds = New-Object System.IO.Compression.DeflateStream($ms, [System.IO.Compression.CompressionMode]::Compress, $true)
    $ds.Write($Bytes, 0, $Bytes.Length)
    $ds.Dispose()
    $deflated = $ms.ToArray()
    $ms.Dispose()

    $out = New-Object System.IO.MemoryStream
    $out.WriteByte(0x78)   # zlib CMF: deflate, 32K window
    $out.WriteByte(0x9C)   # FLG: default compression, FCHECK 有效
    $out.Write($deflated, 0, $deflated.Length)
    $adler = Get-Adler32 $Bytes
    $out.WriteByte([byte](($adler -shr 24) -band 0xFF))
    $out.WriteByte([byte](($adler -shr 16) -band 0xFF))
    $out.WriteByte([byte](($adler -shr 8) -band 0xFF))
    $out.WriteByte([byte]($adler -band 0xFF))
    $result = $out.ToArray()
    $out.Dispose()
    return $result
}

function New-PngChunk {
    param([string]$Type, [byte[]]$Data)
    $ms = New-Object System.IO.MemoryStream
    $len = $Data.Length
    $ms.WriteByte([byte](($len -shr 24) -band 0xFF))
    $ms.WriteByte([byte](($len -shr 16) -band 0xFF))
    $ms.WriteByte([byte](($len -shr 8) -band 0xFF))
    $ms.WriteByte([byte]($len -band 0xFF))
    $typeBytes = [System.Text.Encoding]::ASCII.GetBytes($Type)
    $ms.Write($typeBytes, 0, 4)
    $ms.Write($Data, 0, $Data.Length)
    $crcBytes = $typeBytes + $Data
    $crc = Get-Crc32 $crcBytes
    $ms.WriteByte([byte](($crc -shr 24) -band 0xFF))
    $ms.WriteByte([byte](($crc -shr 16) -band 0xFF))
    $ms.WriteByte([byte](($crc -shr 8) -band 0xFF))
    $ms.WriteByte([byte]($crc -band 0xFF))
    $result = $ms.ToArray()
    $ms.Dispose()
    return $result
}

function Write-QrPng {
    param([string]$Payload, [string]$Path)
    $matrix = New-QrMatrix $Payload
    $moduleCount0 = $matrix.Length
    $quiet = 4
    $scale = 12
    $moduleCount = $moduleCount0 + $quiet * 2
    $size = $moduleCount * $scale

    $raw = New-Object System.IO.MemoryStream
    for ($py = 0; $py -lt $size; $py++) {
        $raw.WriteByte(0)   # filter: none
        $y = [int][math]::Floor($py / $scale) - $quiet
        $rowDark = New-Object 'bool[]' $size
        for ($px = 0; $px -lt $size; $px++) {
            $x = [int][math]::Floor($px / $scale) - $quiet
            $dark = ($x -ge 0 -and $x -lt $moduleCount0 -and $y -ge 0 -and $y -lt $moduleCount0 -and $matrix[$y][$x])
            if ($dark) { $raw.WriteByte(0); $raw.WriteByte(0); $raw.WriteByte(0) }
            else { $raw.WriteByte(255); $raw.WriteByte(255); $raw.WriteByte(255) }
        }
    }
    $rawBytes = $raw.ToArray()
    $raw.Dispose()

    $ihdr = New-Object System.IO.MemoryStream
    $ihdr.WriteByte([byte](($size -shr 24) -band 0xFF)); $ihdr.WriteByte([byte](($size -shr 16) -band 0xFF))
    $ihdr.WriteByte([byte](($size -shr 8) -band 0xFF)); $ihdr.WriteByte([byte]($size -band 0xFF))
    $ihdr.WriteByte([byte](($size -shr 24) -band 0xFF)); $ihdr.WriteByte([byte](($size -shr 16) -band 0xFF))
    $ihdr.WriteByte([byte](($size -shr 8) -band 0xFF)); $ihdr.WriteByte([byte]($size -band 0xFF))
    $ihdr.WriteByte(8)     # bit depth
    $ihdr.WriteByte(2)     # color type: truecolor RGB
    $ihdr.WriteByte(0); $ihdr.WriteByte(0); $ihdr.WriteByte(0)
    $ihdrBytes = $ihdr.ToArray()
    $ihdr.Dispose()

    $png = New-Object System.IO.MemoryStream
    $sig = [byte[]](0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    $png.Write($sig, 0, $sig.Length)
    $c1 = New-PngChunk 'IHDR' $ihdrBytes; $png.Write($c1, 0, $c1.Length)
    $c2 = New-PngChunk 'IDAT' (Compress-ZlibDeflate $rawBytes); $png.Write($c2, 0, $c2.Length)
    $c3 = New-PngChunk 'IEND' ([byte[]]@()); $png.Write($c3, 0, $c3.Length)
    $pngBytes = $png.ToArray()
    $png.Dispose()

    $dir = Split-Path -Parent $Path
    if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    [System.IO.File]::WriteAllBytes($Path, $pngBytes)
    return $Path
}

function Write-QrSvg {
    param([string]$Payload, [string]$Path)
    $matrix = New-QrMatrix $Payload
    $quiet = 4
    $size = $matrix.Length + $quiet * 2
    $cell = 10
    $parts = New-Object System.Collections.Generic.List[string]
    $parts.Add('<?xml version="1.0" encoding="UTF-8"?>')
    $parts.Add("<svg xmlns=`"http://www.w3.org/2000/svg`" viewBox=`"0 0 $size $size`" width=`"$($size * $cell)`" height=`"$($size * $cell)`" shape-rendering=`"crispEdges`">")
    $parts.Add("<rect width=`"$size`" height=`"$size`" fill=`"#fff`"/>")
    for ($y = 0; $y -lt $matrix.Length; $y++) {
        $start = -1
        for ($x = 0; $x -le $matrix.Length; $x++) {
            $dark = ($x -lt $matrix.Length -and $matrix[$y][$x])
            if ($dark -and $start -lt 0) { $start = $x }
            elseif ((-not $dark) -and $start -ge 0) {
                $parts.Add("<rect x=`"$($start + $quiet)`" y=`"$($y + $quiet)`" width=`"$($x - $start)`" height=`"1`" fill=`"#000`"/>")
                $start = -1
            }
        }
    }
    $parts.Add('</svg>')
    [System.IO.File]::WriteAllLines($Path, $parts, (New-Object System.Text.UTF8Encoding($false)))
    return $Path
}

function Write-QrTerminal {
    param([string]$Payload)
    $matrix = New-QrMatrix $Payload
    $quiet = 1
    $size = $matrix.Length
    $lines = New-Object System.Collections.Generic.List[string]
    for ($y = -$quiet; $y -lt $size + $quiet; $y += 2) {
        $sb = New-Object System.Text.StringBuilder
        for ($x = -$quiet; $x -lt $size + $quiet; $x++) {
            $top = ($x -ge 0 -and $x -lt $size -and $y -ge 0 -and $y -lt $size -and $matrix[$y][$x])
            $by = $y + 1
            $bottom = ($x -ge 0 -and $x -lt $size -and $by -ge 0 -and $by -lt $size -and $matrix[$by][$x])
            if ($top -and $bottom) { [void]$sb.Append([char]0x2588) }
            elseif ($top) { [void]$sb.Append([char]0x2580) }
            elseif ($bottom) { [void]$sb.Append([char]0x2584) }
            else { [void]$sb.Append(' ') }
        }
        $lines.Add($sb.ToString())
    }
    foreach ($line in $lines) { Write-Host $line }
}

# ============================ 探测（Hermes / .env / Tailscale / dashboard）============================

function Get-HermesEnvFiles {
    $paths = New-Object System.Collections.Generic.List[string]
    if ($env:LOCALAPPDATA) { $paths.Add((Join-Path $env:LOCALAPPDATA 'hermes\.env')) }
    if ($env:APPDATA) { $paths.Add((Join-Path $env:APPDATA 'hermes\.env')) }
    $home_env = Join-Path $HOME '.hermes\.env'
    $paths.Add($home_env)
    $paths.Add((Join-Path (Get-Location).Path '.env'))
    return $paths
}

function Get-EnvValueFromFiles {
    param([string[]]$Names)
    foreach ($name in $Names) {
        $value = [System.Environment]::GetEnvironmentVariable($name)
        if (-not [string]::IsNullOrWhiteSpace($value)) { return $value.Trim() }
    }
    foreach ($file in (Get-HermesEnvFiles)) {
        if (-not (Test-Path $file)) { continue }
        foreach ($line in [System.IO.File]::ReadAllLines($file)) {
            $trimmed = $line.Trim()
            if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
            foreach ($name in $Names) {
                if ($trimmed.StartsWith("$name=")) {
                    $value = $trimmed.Substring($name.Length + 1).Trim().Trim('"').Trim("'")
                    if ($value) { return $value }
                }
            }
        }
    }
    return $null
}

function Test-HttpStatus {
    param([string]$Url, [double]$TimeoutSec = 4)
    return (Invoke-HttpRequest -Uri $Url -TimeoutSec $TimeoutSec).Status
}

function Test-HermesApi {
    param([string]$BaseUrl, [string]$ApiKey, [double]$TimeoutSec = 5)
    if ((Test-HttpStatus "$BaseUrl/health" $TimeoutSec) -ne 200) { return $false }
    if (-not $ApiKey) { return $true }
    $response = Invoke-HttpRequest -Uri "$BaseUrl/v1/models" -Headers @{ Authorization = "Bearer $ApiKey" } -TimeoutSec $TimeoutSec
    return ($response.Status -eq 200)
}

function Get-FirstLanIp {
    try {
        $socket = New-Object System.Net.Sockets.Socket([System.Net.Sockets.AddressFamily]::InterNetwork, [System.Net.Sockets.SocketType]::Dgram, [System.Net.Sockets.ProtocolType]::Udp)
        $socket.Connect('8.8.8.8', 65530)
        $ip = $socket.LocalEndPoint.Address.ToString()
        $socket.Close()
        if ($ip -and -not $ip.StartsWith('127.')) { return $ip }
    } catch { }
    return $null
}

function Get-TailscaleExe {
    $cmd = Get-Command tailscale -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    foreach ($candidate in @("$env:ProgramFiles\Tailscale\tailscale.exe", "${env:ProgramFiles(x86)}\Tailscale\tailscale.exe")) {
        if ($candidate -and (Test-Path $candidate)) { return $candidate }
    }
    return $null
}

function Get-TailscaleDnsName {
    $exe = Get-TailscaleExe
    if (-not $exe) { return $null }
    try {
        $json = & $exe status --json 2>$null | ConvertFrom-Json
        $dns = $json.Self.DNSName
        if ($dns) { return $dns.TrimEnd('.') }
    } catch { }
    return $null
}

function Get-DashboardTerminal {
    param([string]$LocalUrl = "http://127.0.0.1:$DashboardPort", [string[]]$PublicCandidates = @())
    if ((Test-HttpStatus "$LocalUrl/" 3) -ne 200) { return $null }
    $html = (Invoke-HttpRequest -Uri "$LocalUrl/" -TimeoutSec 5).Body
    if (-not $html) { return $null }
    $tokenMatch = [regex]::Match($html, '__HERMES_SESSION_TOKEN__\s*=\s*"([^"]+)"')
    if (-not $tokenMatch.Success) { return $null }
    $chatMatch = [regex]::Match($html, '__HERMES_DASHBOARD_EMBEDDED_CHAT__\s*=\s*(true|false)')
    $chatEnabled = ($chatMatch.Success -and $chatMatch.Groups[1].Value -eq 'true')
    $url = $LocalUrl
    foreach ($candidate in $PublicCandidates) {
        $probe = Invoke-HttpRequest -Uri "$candidate/" -TimeoutSec 6
        $hasToken = ($probe.Body -match '__HERMES_SESSION_TOKEN__')
        if (-not $Quiet) {
            $preview = ($probe.Body -replace "`r?`n", ' ')
            if ($preview.Length -gt 120) { $preview = $preview.Substring(0, 120) }
            Write-Host "候选 $candidate -> HTTP $($probe.Status) bodyLen=$($probe.Body.Length) 含token=$hasToken :: $preview"
        }
        if ($probe.Status -eq 200 -and $hasToken) { $url = $candidate; break }
    }
    return [pscustomobject]@{ Url = $url; Token = $tokenMatch.Groups[1].Value; ChatEnabled = $chatEnabled }
}

# ============================ 配对载荷 ============================

function ConvertTo-JsonString {
    param([string]$Value)
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append('"')
    foreach ($ch in $Value.ToCharArray()) {
        switch ($ch) {
            '"' { [void]$sb.Append('\"') }
            '\' { [void]$sb.Append('\\') }
            "`n" { [void]$sb.Append('\n') }
            "`r" { [void]$sb.Append('\r') }
            "`t" { [void]$sb.Append('\t') }
            default {
                if ([int]$ch -lt 0x20) { [void]$sb.Append(('\u{0:x4}' -f [int]$ch)) }
                else { [void]$sb.Append($ch) }
            }
        }
    }
    [void]$sb.Append('"')
    return $sb.ToString()
}

function New-PairingJson {
    param([string[]]$Urls, [string]$Key, [string]$ModelName, [bool]$Runs, [bool]$Streaming, [string]$DisplayName, $Terminal)
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append('{"type":"agentvoice_setup","version":1,"hermes":{')
    [void]$sb.Append('"urls":[')
    $escaped = @()
    foreach ($u in $Urls) { $escaped += (ConvertTo-JsonString $u) }
    [void]$sb.Append(($escaped -join ','))
    [void]$sb.Append('],')
    [void]$sb.Append('"model":' + (ConvertTo-JsonString $ModelName) + ',')
    [void]$sb.Append('"runs":' + $(if ($Runs) { 'true' } else { 'false' }) + ',')
    [void]$sb.Append('"streaming":' + $(if ($Streaming) { 'true' } else { 'false' }) + ',')
    [void]$sb.Append('"key":' + (ConvertTo-JsonString $Key))
    if ($DisplayName) { [void]$sb.Append(',"name":' + (ConvertTo-JsonString $DisplayName)) }
    if ($Terminal) {
        [void]$sb.Append(',"terminal":{"url":' + (ConvertTo-JsonString $Terminal.Url) + ',"token":' + (ConvertTo-JsonString $Terminal.Token) + '}')
    }
    [void]$sb.Append('}}')
    return $sb.ToString()
}

function New-PairingUri {
    param([string[]]$Urls, [string]$Key, [string]$ModelName, [bool]$Runs, [bool]$Streaming, [string]$DisplayName, $Terminal)
    $parts = New-Object System.Collections.Generic.List[string]
    foreach ($u in $Urls) { $parts.Add('hu=' + [uri]::EscapeDataString($u)) }
    $parts.Add('hk=' + [uri]::EscapeDataString($Key))
    $parts.Add('hm=' + [uri]::EscapeDataString($ModelName))
    $parts.Add('hr=' + $(if ($Runs) { '1' } else { '0' }))
    $parts.Add('hs=' + $(if ($Streaming) { '1' } else { '0' }))
    if ($DisplayName) { $parts.Add('hn=' + [uri]::EscapeDataString($DisplayName)) }
    if ($Terminal) {
        $parts.Add('htu=' + [uri]::EscapeDataString($Terminal.Url))
        $parts.Add('htt=' + [uri]::EscapeDataString($Terminal.Token))
    }
    return 'agentvoice://setup?' + ($parts -join '&')
}

function Update-EnvFile {
    param([string]$Path, [string]$Name, [string]$Value)
    if (-not (Test-Path $Path)) { [System.IO.File]::WriteAllText($Path, '') }
    $lines = New-Object System.Collections.Generic.List[string]
    $replaced = $false
    foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
        if ($line.StartsWith("$Name=")) {
            $lines.Add("$Name=$Value"); $replaced = $true
        } else { $lines.Add($line) }
    }
    if (-not $replaced) { $lines.Add("$Name=$Value") }
    $text = ($lines -join "`n").TrimEnd() + "`n"
    [System.IO.File]::WriteAllText($Path, $text, (New-Object System.Text.UTF8Encoding($false)))
}

# ============================ 自检 ============================

function Test-PngChunks {
    param([byte[]]$Bytes)
    $issues = New-Object System.Collections.Generic.List[string]
    if ($Bytes.Length -lt 8 -or $Bytes[0] -ne 0x89 -or $Bytes[1] -ne 0x50 -or $Bytes[2] -ne 0x4E -or $Bytes[3] -ne 0x47) {
        $issues.Add('签名不是 PNG')
        return $issues
    }
    $i = 8
    $seenTypes = New-Object System.Collections.Generic.List[string]
    while ($i + 8 -le $Bytes.Length) {
        $len = ([int]$Bytes[$i] -shl 24) -bor ([int]$Bytes[$i + 1] -shl 16) -bor ([int]$Bytes[$i + 2] -shl 8) -bor [int]$Bytes[$i + 3]
        $type = [System.Text.Encoding]::ASCII.GetString($Bytes, $i + 4, 4)
        if ($i + 12 + $len -gt $Bytes.Length) { $issues.Add("块 $type 越界"); break }
        $chunkBytes = New-Object byte[] (4 + $len)
        [System.Array]::Copy($Bytes, $i + 4, $chunkBytes, 0, 4 + $len)
        $stored = ((([int64]$Bytes[$i + 8 + $len]) -shl 24) -bor (([int64]$Bytes[$i + 9 + $len]) -shl 16) -bor (([int64]$Bytes[$i + 10 + $len]) -shl 8) -bor [int64]$Bytes[$i + 11 + $len])
        $calc = [int64](Get-Crc32 $chunkBytes)
        if ($stored -ne $calc) { $issues.Add("块 $type CRC 不符：stored=$($stored.ToString('x8')) calc=$($calc.ToString('x8'))") }
        $seenTypes.Add($type)
        $i += 12 + $len
        if ($type -eq 'IEND') { break }
    }
    if ($i -ne $Bytes.Length) { $issues.Add('尾部有多余字节') }
    if ($seenTypes.Count -eq 0 -or $seenTypes[0] -ne 'IHDR') { $issues.Add('第一个块不是 IHDR') }
    if ($seenTypes.Count -eq 0 -or $seenTypes[$seenTypes.Count - 1] -ne 'IEND') { $issues.Add('最后一个块不是 IEND') }
    if (-not ($seenTypes -contains 'IDAT')) { $issues.Add('缺 IDAT') }
    return $issues
}

function Invoke-SelfTest {
    $vectors = @(
        @{ Text = '{"type":"agentvoice_setup","version":1}'; Version = 3; Hash = 'cfdf5f5bf2bc7094' },
        @{ Text = 'agentvoice-selftest'; Version = 2; Hash = '076aedb870d3d6ea' },
        @{ Text = '{"type":"agentvoice_setup","version":1,"hermes":{"urls":["https://flow.taile4bd3b.ts.net:8443"],"model":"default","runs":true,"streaming":true,"key":"00000000000000000000000000000000","name":"Hermes","terminal":{"url":"https://flow.taile4bd3b.ts.net:10000","token":"0000000000000000000000000000000000000000000"}}}'; Version = 11; Hash = 'd5c1f83da6fa8c01' },
        @{ Text = ('X' * 900); Version = 21; Hash = '2a3ac23f5b0aef6d' }
    )
    $failed = 0
    foreach ($vector in $vectors) {
        $enc = Get-QrCodewords $vector.Text
        $matrix = New-QrMatrix $vector.Text
        $hash = (Get-QrMatrixHash $matrix).Substring(0, 16)
        $versionOk = ($enc.Version -eq $vector.Version)
        $hashOk = ($hash -eq $vector.Hash)
        if ($versionOk -and $hashOk) {
            Write-Line ("OK   len={0,-4} version={1,-2} size={2,-3} hash={3}" -f $vector.Text.Length, $enc.Version, $matrix.Length, $hash)
        } else {
            $failed++
            Write-Line ("FAIL len={0,-4} version={1}(期望 {2}) hash={3}(期望 {4})" -f $vector.Text.Length, $enc.Version, $vector.Version, $hash, $vector.Hash)
        }
    }
    # PNG 容器自检：CRC32/Adler32 与 zlib 的固定向量对照 + 结构检查（防 CRC 这类静默回归）
    # 比较一律走十六进制字符串：PowerShell 会把 0xa12819d6 这类字面量当 Int32 解析成负数，数值比较会误判。
    $crcHex = (Get-Crc32 ([byte[]](0x49, 0x48, 0x44, 0x52, 0, 0, 2, 0x2C, 0, 0, 0x2C, 8, 2, 0, 0, 0))).ToString('x8')
    $adlerHex = (Get-Adler32 ([byte[]](1, 2, 3))).ToString('x8')
    if ($crcHex -eq 'a12819d6' -and $adlerHex -eq '000d0007') {
        Write-Line "OK   PNG crc32/adler32 与 zlib 一致 (a12819d6 / 000d0007)"
    } else {
        $failed++
        Write-Line "FAIL PNG crc32/adler32 向量不符：crc=$crcHex adler=$adlerHex（期望 a12819d6 / 000d0007）"
    }
    $probePath = Join-Path ([System.IO.Path]::GetTempPath()) ("avp-selftest-{0}.png" -f ([guid]::NewGuid().ToString('N').Substring(0, 8)))
    try {
        Write-QrPng 'agentvoice-selftest' $probePath | Out-Null
        $pngBytes = [System.IO.File]::ReadAllBytes($probePath)
        $pngIssues = Test-PngChunks $pngBytes
        if ($pngIssues.Count -eq 0) { Write-Line "OK   PNG 结构完整（IHDR/IDAT/IEND 的 CRC32 全部校验通过）" }
        else { $failed++; foreach ($issue in $pngIssues) { Write-Line "FAIL PNG $issue" } }
    } finally {
        if (Test-Path $probePath) { Remove-Item -LiteralPath $probePath -Force }
    }

    if ($failed -eq 0) { Write-Line "自检通过：二维码实现与 Python 版逐位一致" }
    else { Write-Line "自检失败 $failed 项" }
    return $failed
}

# ============================ 主流程 ============================

if ($Help) {
    @"
agentvoice-pair —— 生成 WakeHermesClaw/OpenClaw Assistant 的配对二维码，以及用配对码换 Mobile Bridge token。

用法：
  agentvoice-pair [-Url <api-url> ...] [-Key <API_SERVER_KEY>] [-Open] [-Out <png>] [-Svg]
  agentvoice-pair -Code <6位配对码> [-WriteEnv <env 路径>]
  agentvoice-pair -DeepLink 'agentvoice://pair?...'
  agentvoice-pair -SelfTest

常用参数：
  -Url <string[]>        Hermes API 地址（默认自动探测：优先 Tailscale https Serve，其次局域网）
  -Key <string>          Hermes API key（默认读环境变量 / .env 的 API_SERVER_KEY）
  -Model / -Name         配对载荷里的模型名与显示名
  -TerminalUrl/-TerminalToken   覆盖 Terminal（dashboard）地址与 session token
  -NoTerminal            载荷里不带 Terminal 段
  -Out <png>             输出 PNG 路径（默认 ~\Downloads\agentvoice-pair-qr.png），-Open 直接打开
  -Svg/-NoTerminalQr     额外输出 SVG / 不打印终端二维码
  -DeepLinkQr            二维码内容用 agentvoice://setup?… 深链（系统相机/任意扫码 App 都能用，
                         不依赖 Google Play 服务的 ML Kit 扫码模块）
  -Code/-DeepLink/-BridgeUrl/-WriteEnv   Mobile Bridge 配对码模式
  -ServePort/-TerminalServePort/-ApiPort/-DashboardPort   探测端口（8443/10000/8642/9119）
  -ShowSecrets           输出里不掩码 key/token
  -SelfTest              校验二维码实现（与官方 Python 版逐位一致）
  -Quiet                 安静模式
"@
    exit 0
}

if ($SelfTest) { exit (Invoke-SelfTest) }

# ---- 参数校验：选项写错/冲突就直接拒绝，不要"带了错参数也照跑" ----
$paramErrors = New-Object System.Collections.Generic.List[string]
if ($Rest -and $Rest.Count -gt 0) {
    foreach ($extra in $Rest) {
        if ($extra -match '^https?://') {
            $paramErrors.Add("位置参数 '$extra' 不支持；要指定地址请写成 -Url $extra")
        } else {
            $paramErrors.Add("不认识的参数：$extra（本脚本不接受位置参数）")
        }
    }
}
$qrFormCount = 0
if ($DeepLinkQr) { $qrFormCount++ }
if ($qrFormCount -gt 1) { $paramErrors.Add('-DeepLinkQr 只能出现一次') }
$bridgeMode = [bool]($Code -or $DeepLink)
if ($bridgeMode -and $qrFormCount -gt 0) {
    $paramErrors.Add('-Code/-DeepLink（用配对码换 Mobile Bridge token）与 -DeepLinkQr（生成配对二维码）是两件事，不能混用')
}
if ($bridgeMode -and ($Out -or $Svg -or $Open -or $NoTerminalQr -or $NoTerminal -or $Url -or $Key)) {
    $paramErrors.Add('配对码模式不接受 -Out / -Svg / -Open / -NoTerminalQr / -NoTerminal / -Url / -Key')
}
foreach ($portPair in @(@('ServePort', $ServePort), @('TerminalServePort', $TerminalServePort), @('ApiPort', $ApiPort), @('DashboardPort', $DashboardPort))) {
    if ($portPair[1] -lt 1 -or $portPair[1] -gt 65535) {
        $paramErrors.Add("-$($portPair[0]) 必须在 1..65535，当前是 $($portPair[1])")
    }
}
if ($Url) {
    foreach ($u in $Url) {
        if ($u -and ($u -notmatch '^https?://')) { $paramErrors.Add("-Url 必须是 http:// 或 https:// 开头，收到：$u") }
    }
}
if ($Code -and ($Code -notmatch '^[A-Za-z0-9]{4,16}$')) {
    $paramErrors.Add("-Code 看起来不是配对码（应为 4-16 位字母或数字）：$Code")
}
if ($paramErrors.Count -gt 0) {
    foreach ($err in $paramErrors) { Write-Host "参数错误：$err" }
    Write-Host '用 -h 看完整用法。'
    exit 2
}

$apiKey = $Key
if (-not $apiKey) { $apiKey = Get-EnvValueFromFiles @('HERMES_API_KEY', 'HERMES_API_SERVER_KEY', 'API_SERVER_KEY', 'AGENT_VOICE_HERMES_KEY') }
$dashboardToken = $TerminalToken
$dashboardTerminal = $null

# ---- 模式一：用配对码换 Mobile Bridge token ----
if ($Code -or $DeepLink) {
    $pairCode = $Code
    $bridge = $BridgeUrl
    if ($DeepLink) {
        $uri = [uri]$DeepLink
        $query = @{}
        foreach ($pair in $uri.Query.TrimStart('?').Split('&')) {
            if (-not $pair) { continue }
            $kv = $pair.Split('=', 2)
            if ($kv.Length -eq 2) { $query[$kv[0]] = [uri]::UnescapeDataString($kv[1]) }
        }
        if (-not $pairCode -and $query['c']) { $pairCode = $query['c'] }
        if (-not $bridge -and $query['u']) { $bridge = $query['u'] }
    }
    if (-not $bridge) { $bridge = Get-EnvValueFromFiles @('AGENT_VOICE_BRIDGE_URL') }
    if (-not $bridge) { $bridge = 'http://127.0.0.1:8787' }
    $bridge = $bridge.TrimEnd('/')
    if (-not $pairCode) { Write-Error '需要 -Code 或 -DeepLink 里的 c= 参数'; exit 2 }
    if ($bridge -match '[<>]|device-ip|your-ip') { Write-Error "桥地址里还有占位符：$bridge"; exit 2 }

    Write-Line "桥地址: $bridge"
    $health = Test-HttpStatus "$bridge/health" 8
    Write-Line "/health -> HTTP $health"
    if ($health -ne 200) {
        Write-Line '连不上桥。检查：App 里 Mobile Bridge 已开启？Bind mode = LAN/VPN？手机与本机同一 tailnet？'
        exit 3
    }
    try {
        $pairResponse = Invoke-HttpRequest -Uri "$bridge/pair" -Method 'POST' -Body (@{ code = $pairCode } | ConvertTo-Json -Compress) -TimeoutSec 15
    } catch {
        Write-Line "!! /pair 请求异常：$($_.Exception.Message)"
        exit 4
    }
    if ($pairResponse.Status -ne 200) {
        Write-Line "!! /pair 失败：HTTP $($pairResponse.Status) $($pairResponse.Body)"
        Write-Line '   配对码是一次性的、约 5 分钟过期；回 App 重新生成一个。'
        exit 4
    }
    $pairJson = $null
    try { $pairJson = $pairResponse.Body | ConvertFrom-Json } catch { }
    $token = $null
    if ($pairJson) { $token = $pairJson.token; if (-not $token) { $token = $pairJson.bridgeToken } }
    if (-not $token) { Write-Line "!! /pair 返回里没有 token：$($pairResponse.Body)"; exit 4 }
    Write-Line "/pair -> 拿到 token（$($token.Length) 字符，前 4 位 $($token.Substring(0, [math]::Min(4, $token.Length)))…）"

    $manifestResponse = Invoke-HttpRequest -Uri "$bridge/manifest" -Headers @{ Authorization = "Bearer $token" } -TimeoutSec 15
    if ($manifestResponse.Status -ne 200) {
        Write-Line "!! 用新 token 读 /manifest 失败：HTTP $($manifestResponse.Status) $($manifestResponse.Body)"
        exit 5
    }
    $capabilityCount = 0
    try { $capabilityCount = @(($manifestResponse.Body | ConvertFrom-Json).capabilities).Count } catch { }
    Write-Line "/manifest -> 能力 $capabilityCount 项"
    Write-Line ''
    Write-Line '在 Hermes 侧需要这两个变量：'
    Write-Line "  AGENT_VOICE_BRIDGE_URL=$bridge"
    Write-Line "  AGENT_VOICE_BRIDGE_TOKEN=$token"
    if ($WriteEnv) {
        Update-EnvFile $WriteEnv 'AGENT_VOICE_BRIDGE_URL' $bridge
        Update-EnvFile $WriteEnv 'AGENT_VOICE_BRIDGE_TOKEN' $token
        Write-Line ''
        Write-Line "已写入 $WriteEnv（同名行原地替换）；两个变量按进程读，改完要重启 Hermes 会话。"
    }
    exit 0
}

# ---- 模式二：生成配对二维码 ----
if ($Payload) {
    if (-not $Out) { $Out = Join-Path ([Environment]::GetFolderPath('UserProfile')) 'Downloads\agentvoice-pair-qr.png' }
    if (-not $NoTerminalQr) { Write-QrTerminal $Payload }
    $pngPath = Write-QrPng $Payload $Out
    Write-Line "PNG: $pngPath"
    if ($Svg) {
        $svgPath = [System.IO.Path]::ChangeExtension($pngPath, '.svg')
        Write-QrSvg $Payload $svgPath | Out-Null
        Write-Line "SVG: $svgPath"
    }
    if ($Open) { Start-Process $pngPath }
    exit 0
}
$urls = New-Object System.Collections.Generic.List[string]
if ($Url) { foreach ($u in $Url) { if ($u) { $urls.Add($u.Trim().TrimEnd('/')) } } }
if ($urls.Count -eq 0 -and $env:HERMES_API_URL) {
    $urls.Add($env:HERMES_API_URL.Trim().TrimEnd('/'))
    Write-Line "使用环境变量 HERMES_API_URL：$($urls[0])"
}

$dnsName = Get-TailscaleDnsName
$lanIp = Get-FirstLanIp
$serveUrl = $null
$terminalServeUrl = $null
if ($dnsName) {
    $candidate = "https://${dnsName}:$ServePort"
    if ((Test-HttpStatus "$candidate/health" 6) -eq 200) { $serveUrl = $candidate }
    $terminalCandidate = "https://${dnsName}:$TerminalServePort"
    if ((Test-HttpStatus "$terminalCandidate/" 6) -eq 200) { $terminalServeUrl = $terminalCandidate }
}
Write-Line "探测：MagicDNS=$dnsName  LAN=$lanIp  API serve=$serveUrl  Terminal serve=$terminalServeUrl"

if ($urls.Count -eq 0) {
    if ($serveUrl -and (-not $apiKey -or (Test-HermesApi $serveUrl $apiKey))) { $urls.Add($serveUrl) }
    elseif ($lanIp -and (Test-HermesApi "http://${lanIp}:$ApiPort" $apiKey)) { $urls.Add("http://${lanIp}:$ApiPort") }
    elseif ((Test-HttpStatus "http://127.0.0.1:$ApiPort/health" 3) -eq 200) {
        Write-Line '警告：只探测到 loopback 地址，手机连不上；建议用 Tailscale Serve 暴露 https 后再生成。'
        $urls.Add("http://127.0.0.1:$ApiPort")
    }
}
$lastUrlFile = Join-Path $env:LOCALAPPDATA 'hermes\.agentvoice-pair-last-url'
if ($urls.Count -eq 0 -and (Test-Path $lastUrlFile)) {
    # 兜底：记住上次成功用过的地址；若它现在仍可达就直接用，免得再被探测卡的莫名其妙
    $cached = (Get-Content -LiteralPath $lastUrlFile -Raw -ErrorAction SilentlyContinue)
    if ($cached) { $cached = $cached.Trim() }
    if ($cached -and ((Test-HttpStatus "$cached/health" 5) -eq 200)) {
        Write-Line "自动探测没成功，但上次用过的地址仍可用：$cached"
        $urls.Add($cached)
    }
}

if ($urls.Count -eq 0) {
    $probeLoopback = Invoke-HttpRequest -Uri "http://127.0.0.1:$ApiPort/health" -TimeoutSec 3
    if ($probeLoopback.Status -ne 200) {
        Start-Sleep -Milliseconds 800   # 偶发超时/瞬时拒连，再试一次再判死
        $probeLoopback = Invoke-HttpRequest -Uri "http://127.0.0.1:$ApiPort/health" -TimeoutSec 4
    }
    $probeServe = $null
    if ($dnsName) { $probeServe = Invoke-HttpRequest -Uri "https://${dnsName}:$ServePort/health" -TimeoutSec 6 }
    Write-Line '探测明细：'
    Write-Line "  MagicDNS=$dnsName  LAN=$lanIp"
    Write-Line "  http://127.0.0.1:$ApiPort/health → HTTP $($probeLoopback.Status)$(if ($probeLoopback.Status -ne 200) { "  [$($probeLoopback.Body)]" })"
    if ($probeServe) { Write-Line "  https://${dnsName}:$ServePort/health → HTTP $($probeServe.Status)$(if ($probeServe.Status -ne 200) { "  [$($probeServe.Body)]" })" }
    Write-Line "排查顺序："
    Write-Line "  1) Hermes API 是否在跑：curl http://127.0.0.1:$ApiPort/health 应为 200（不在跑就别指望探测）"
    Write-Line "  2) Tailscale Serve 是否还在：tailscale serve status 里应有 :$ServePort -> http://127.0.0.1:$ApiPort"
    Write-Line "  3) 直接指定地址：agentvoice-pair -Url https://<你的MagicDNS>:$ServePort"
    Write-Line "  4) 或设环境变量 HERMES_API_URL 后重跑"
    Write-Error '没探测到可用的 Hermes API 地址，请用 -Url 指定。'
    exit 3
}
try { Set-Content -LiteralPath $lastUrlFile -Value $urls[0] -Encoding ASCII -ErrorAction Stop } catch { }
if (-not $apiKey) { Write-Error '没找到 Hermes API key，请用 -Key 指定（或在 .env 里设置 API_SERVER_KEY）。'; exit 3 }

if (-not $NoTerminal) {
    $publicTerminalCandidates = @()
    if ($terminalServeUrl) { $publicTerminalCandidates += $terminalServeUrl }
    if ($lanIp) { $publicTerminalCandidates += "http://${lanIp}:$DashboardPort" }
    $dashboardTerminal = Get-DashboardTerminal -LocalUrl "http://127.0.0.1:$DashboardPort" -PublicCandidates $publicTerminalCandidates
    if ($dashboardTerminal) {
        if (-not $dashboardTerminal.ChatEnabled) {
            Write-Line '提示：dashboard 页面显示 Terminal 未启用，请用 hermes dashboard --tui 启动。'
            $dashboardTerminal = $null
        } elseif ($TerminalUrl -or $dashboardToken) {
            $dashboardTerminal = [pscustomobject]@{
                Url   = $(if ($TerminalUrl) { $TerminalUrl.TrimEnd('/') } else { $dashboardTerminal.Url })
                Token = $(if ($dashboardToken) { $dashboardToken } else { $dashboardTerminal.Token })
                ChatEnabled = $true
            }
        }
    }
}
if ($dashboardTerminal -and $dashboardTerminal.Url -match '^http://(127\.|localhost)') {
    Write-Line '提示：Terminal 地址是 loopback，手机连不上；建议按 README 用 Tailscale Serve 映射 10000 端口。'
}

$payload = $null
if ($DeepLinkQr) {
    # 深链形式：任何扫码 App（含系统相机）都能识别并直接打开 App，绕开 Play 服务 ML Kit 模块
    $payload = New-PairingUri -Urls $urls.ToArray() -Key $apiKey -ModelName $Model -Runs (-not $NoRuns) -Streaming (-not $NoStreaming) -DisplayName $Name -Terminal $dashboardTerminal
} else {
    $payload = New-PairingJson -Urls $urls.ToArray() -Key $apiKey -ModelName $Model -Runs (-not $NoRuns) -Streaming (-not $NoStreaming) -DisplayName $Name -Terminal $dashboardTerminal
}

Write-Line '=== 配对概览 ==='
foreach ($u in $urls) { Write-Line "API 地址   : $u" }
Write-Line "API key    : len=$($apiKey.Length) sha8=$(Get-Sha8 $apiKey)"
if ($dashboardTerminal) {
    Write-Line "Terminal   : $($dashboardTerminal.Url)"
    Write-Line "Terminal 令牌: len=$($dashboardTerminal.Token.Length) sha8=$(Get-Sha8 $dashboardTerminal.Token)"
} else {
    Write-Line 'Terminal   : （未包含，App 里终端页会提示需要重扫带 Terminal 的码）'
}
$masked = $payload
if (-not $ShowSecrets) {
    if ($DeepLinkQr) {
        $masked = $masked -replace '(hk=)[^&]*', '${1}***'
        $masked = $masked -replace '(htt=)[^&]*', '${1}***'
    } else {
        $masked = $masked -replace '(?<="key":")[^"]*', '***'
        $masked = $masked -replace '(?<="token":")[^"]*', '***'
    }
}
Write-Line "载荷       : $masked"
Write-Line "矩阵哈希   : $((Get-QrMatrixHash (New-QrMatrix $payload)).Substring(0, 16))"
Write-Line ''

if (-not $NoTerminalQr) { Write-QrTerminal $payload }

if (-not $Out) {
    $Out = Join-Path ([Environment]::GetFolderPath('UserProfile')) 'Downloads\agentvoice-pair-qr.png'
}
$pngPath = Write-QrPng $payload $Out
Write-Line ''
Write-Line "PNG: $pngPath"
if ($Svg) {
    $svgPath = [System.IO.Path]::ChangeExtension($pngPath, '.svg')
    Write-QrSvg $payload $svgPath | Out-Null
    Write-Line "SVG: $svgPath"
}
if ($Open) { Start-Process $pngPath }

# 深链（某些场景可以直接粘到手机 App 里）
$uriText = New-PairingUri -Urls $urls.ToArray() -Key $apiKey -ModelName $Model -Runs (-not $NoRuns) -Streaming (-not $NoStreaming) -DisplayName $Name -Terminal $dashboardTerminal
if (-not $ShowSecrets) {
    $uriText = $uriText -replace '(hk=)[^&]*', '${1}***'
    $uriText = $uriText -replace '(htt=)[^&]*', '${1}***'
}
Write-Line "深链: $uriText"
exit 0
