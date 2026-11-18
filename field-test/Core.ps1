Set-StrictMode -Version Latest

function Write-TextFile($Path, [string]$Text) {
    $tempFile = "$Path.$([guid]::NewGuid().ToString('N')).tmp"
    try {
        [IO.File]::WriteAllText($tempFile, $Text, [Text.UTF8Encoding]::new($false))
        if ([IO.File]::Exists($Path)) {
            [IO.File]::Replace($tempFile, $Path, [NullString]::Value)
        } else {
            [IO.File]::Move($tempFile, $Path)
        }
    } finally {
        if ([IO.File]::Exists($tempFile)) { [IO.File]::Delete($tempFile) }
    }
}

function Join-ProcessArguments([string[]]$Arguments) {
    # Use the Windows C runtime's quoting rules, including a trailing backslash.
    ($Arguments | ForEach-Object {
        '"' + (($_ -replace '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"'
    }) -join ' '
}

function Invoke-FieldProcess($Executable, [string[]]$Arguments, [int]$Seconds = 8) {
    $process = [Diagnostics.Process]::new()
    $process.StartInfo.FileName = $Executable
    $process.StartInfo.Arguments = Join-ProcessArguments $Arguments
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    try {
        $null = $process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
        while (!$process.WaitForExit(100)) {
            if ($null -ne $script:fieldPump) { & $script:fieldPump }
            if ([DateTime]::UtcNow -ge $deadline) {
                $process.Kill()
                return @{ Code = -1; Out = ''; Error = ''; TimedOut = $true }
            }
        }
        # A child of adb can inherit the redirected streams. Bound that wait too.
        if (!$stdout.Wait(1000) -or !$stderr.Wait(1000)) {
            return @{ Code = -1; Out = ''; Error = ''; TimedOut = $true }
        }
        return @{ Code = $process.ExitCode; Out = $stdout.Result; Error = $stderr.Result; TimedOut = $false }
    } finally {
        $process.Dispose()
    }
}
$script:fieldPump = $null

function Find-Adb($CacheRoot) {
    $locations = @((Join-Path $CacheRoot 'platform-tools\adb.exe'))
    foreach ($sdk in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))) {
        if ($sdk) { $locations += Join-Path $sdk 'platform-tools\adb.exe' }
    }
    $onPath = Get-Command adb.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($onPath) { $locations += $onPath.Source }
    foreach ($location in $locations) {
        if (Test-Path -LiteralPath $location -PathType Leaf) { return $location }
    }
}

function Get-PhoneStatus($Adb) {
    if (!$Adb) { return @{ State = 'no-adb'; Serial = '' } }
    $listing = Invoke-FieldProcess $Adb @('devices')
    if ($listing.Code -ne 0) { return @{ State = 'adb-error'; Serial = '' } }
    $devices = @([regex]::Matches($listing.Out, '(?m)^([^\s]+)\t([^\s]+)') | ForEach-Object {
        @{ Serial = $_.Groups[1].Value; State = $_.Groups[2].Value }
    })
    if ($devices.Count -gt 1) { return @{ State = 'multiple'; Serial = '' } }
    if ($devices.Count -eq 0) { return @{ State = 'missing'; Serial = '' } }
    return $devices[0]
}

function Get-InstalledApp($Adb, $Serial, $Package) {
    $info = Invoke-FieldProcess $Adb @('-s', $Serial, 'shell', 'dumpsys', 'package', $Package)
    if ($info.Code -ne 0) { return @{ State = 'error'; Name = ''; Code = ''; Sha256 = '' } }
    $code = [regex]::Match($info.Out, '(?m)^\s*versionCode=(\d+)').Groups[1].Value
    $name = [regex]::Match($info.Out, '(?m)^\s*versionName=([A-Za-z0-9._-]+)\s*$').Groups[1].Value
    if (!$code) { return @{ State = 'missing'; Name = ''; Code = ''; Sha256 = '' } }
    $sha = ''
    $paths = Invoke-FieldProcess $Adb @('-s', $Serial, 'shell', 'pm', 'path', $Package)
    $apkPath = [regex]::Match($paths.Out, '(?m)^package:(/[A-Za-z0-9_./~=+-]+/base\.apk)\r?$').Groups[1].Value
    if ($paths.Code -eq 0 -and $apkPath) {
        $digest = Invoke-FieldProcess $Adb @('-s', $Serial, 'shell', 'sha256sum', $apkPath)
        if ($digest.Code -eq 0) { $sha = [regex]::Match($digest.Out, '^[a-f0-9]{64}(?=\s)').Value }
    }
    return @{ State = 'installed'; Name = $name; Code = $code; Sha256 = $sha }
}

function ConvertTo-DeviceUri([string]$Address, [string]$Mode) {
    $path = if ($Mode -eq 'sim') { '/link' } else { '/rdk' }
    $port = if ($Mode -eq 'sim') { 8766 } else { 8767 }
    $raw = $Address.Trim()
    if ($raw -match '^[A-Za-z0-9.-]+$') { $raw = "ws://${raw}:${port}$path" }
    if ($raw -match '^[A-Za-z0-9.-]+:\d{1,5}$') { $raw = "ws://$raw$path" }
    $uri = $null
    if (![Uri]::TryCreate($raw, [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -notin @('ws', 'wss') -or !$uri.Host -or $uri.Port -lt 1 -or
        $uri.UserInfo -or $uri.Query -or $uri.Fragment -or $uri.AbsolutePath -cne $path -or
        $raw -notmatch '^wss?://[^/]+/[^/]+$') { return $null }
    if ($uri.Scheme -eq 'ws' -and $Mode -ne 'usb' -and $uri.Host -cne 'localhost') {
        $octets = $uri.Host.Split('.')
        if ($uri.HostNameType -ne [UriHostNameType]::IPv4 -or $octets.Count -ne 4 -or
            !($octets[0] -in @('10', '127') -or ($octets[0] -eq '192' -and $octets[1] -eq '168') -or
            ($octets[0] -eq '172' -and [int]$octets[1] -ge 16 -and [int]$octets[1] -le 31))) { return $null }
    }
    return $uri
}

function Test-DevicePort([Uri]$Uri) {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync($Uri.DnsSafeHost, $Uri.Port)
        return ($task.Wait(3500) -and $client.Connected)
    } catch { return $false } finally { $client.Close() }
}

function Test-DeviceHandshake([Uri]$Uri, [string]$Mode, [string]$Token) {
    $socket = [Net.WebSockets.ClientWebSocket]::new()
    $timeout = [Threading.CancellationTokenSource]::new(4500)
    $schema = if ($Mode -eq 'sim') { 'yuntong-link-test-v1' } else { 'yuntong-rdk-link-v1' }
    try {
        $socket.Options.Proxy = $null
        $socket.Options.SetRequestHeader('Authorization', "Bearer $Token")
        [void]$socket.ConnectAsync($Uri, $timeout.Token).GetAwaiter().GetResult()
        $hello = '{"schema":"' + $schema + '","type":"hello","client":"yuntong-android"}'
        $bytes = [Text.Encoding]::UTF8.GetBytes($hello)
        [void]$socket.SendAsync([ArraySegment[byte]]::new($bytes), 'Text', $true, $timeout.Token).GetAwaiter().GetResult()
        $buffer = [byte[]]::new(8192)
        $offset = 0
        do {
            $piece = $socket.ReceiveAsync([ArraySegment[byte]]::new($buffer, $offset, $buffer.Length - $offset), $timeout.Token).GetAwaiter().GetResult()
            if ($piece.MessageType -eq 'Close') {
                if ($piece.CloseStatus -eq 'PolicyViolation') { return 'denied' }
                return 'rejected-or-timeout'
            }
            if ($piece.MessageType -ne 'Text' -or $piece.Count -eq 0) { return 'unexpected' }
            $offset += $piece.Count
        } while (!$piece.EndOfMessage -and $offset -lt $buffer.Length)
        if (!$piece.EndOfMessage) { return 'unexpected' }
        $reply = [Text.Encoding]::UTF8.GetString($buffer, 0, $offset) | ConvertFrom-Json
        $testOnly = $Mode -eq 'sim'
        if ($reply.type -ceq 'hello.accepted' -and $reply.schema -ceq $schema -and $reply.testOnly -is [bool] -and $reply.testOnly -eq $testOnly) {
            if (!$testOnly) {
                foreach ($capability in @('navigation.receive', 'device.status', 'device.command')) {
                    if ($capability -cnotin $reply.capabilities) { return 'unexpected' }
                }
            }
            return 'accepted'
        }
        return 'unexpected'
    } catch { return 'rejected-or-timeout' } finally { $socket.Dispose(); $timeout.Dispose() }
}

function Protect-Observation([string]$Text, [string]$Token = '') {
    if ($Token) { $Text = $Text.Replace($Token, '[已隐藏]') }
    $Text = $Text -replace '(?i)\bBearer\s+\S+', 'Bearer [已隐藏]'
    $Text = $Text -replace '(?i)(?:\b(?:amap[_ -]?key|api[_ -]?key|token|password|secret|authorization|ssid)\b|密钥|密码|口令|凭据|位置|地址|经纬度)\s*[:：=]\s*[^\r\n]+', '[已隐藏]'
    $Text = $Text -replace '(?i)\b(?:https?|wss?)://\S+', '[链接已隐藏]'
    $Text = $Text -replace '(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?![\w.])', '[IP 已隐藏]'
    $Text = $Text -replace '(?<!\d)[+-]?\d{1,3}\.\d{3,}(?!\d)', '[坐标已隐藏]'
    $Text = $Text -replace '(?<![\w.])(?:\+?\d[\s().-]?){7,15}(?![\w.])', '[号码已隐藏]'
    $Text -replace '(?i)\b(?:[a-f0-9]{24,}|[A-Za-z0-9_-]{24,})\b', '[标识已隐藏]'
}

function Get-LogSummary($Adb, $Serial, $Package) {
    $pidText = (Invoke-FieldProcess $Adb @('-s', $Serial, 'shell', 'pidof', '-s', $Package)).Out.Trim()
    if ($pidText -notmatch '^\d{1,9}$') { return '应用进程不可用，未读取日志。' }
    $localLog = Invoke-FieldProcess $Adb @('-s', $Serial, 'logcat', '-d', '-t', '250', "--pid=$pidText", '*:W')
    if ($localLog.Code -ne 0) { return 'Android 日志不可用。' }
    # Only emit categories and counts. Raw messages can contain arbitrary data.
    $categories = [ordered]@{
        '应用异常' = 'FATAL EXCEPTION'
        '权限错误' = 'SecurityException|Permission Denial'
        '网络超时' = 'SocketTimeoutException|timed out'
        '连接失败' = 'ConnectException|UnknownHostException|SSLHandshakeException'
        '媒体或编码器' = 'MediaCodec|ExoPlayer|CodecException'
    }
    $counts = foreach ($category in $categories.Keys) {
        "$category $([regex]::Matches($localLog.Out, $categories[$category], 'IgnoreCase').Count)"
    }
    return '最近日志命中次数（仅当前应用进程，最多 250 行） | ' + ($counts -join ' | ')
}
