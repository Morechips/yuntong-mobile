param([Parameter(Mandatory)][string]$ScratchDirectory)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\Core.ps1"

function Assert($Condition, $Message) { if (!$Condition) { throw $Message } }
$uri = ConvertTo-DeviceUri '192.168.1.2' 'lan'
Assert ($uri.AbsoluteUri -ceq 'ws://192.168.1.2:8767/rdk') 'RDK default endpoint'
Assert (!(ConvertTo-DeviceUri '203.0.113.2' 'lan')) 'Reject direct public cleartext'
Assert ((ConvertTo-DeviceUri 'wss://board.example/rdk' 'lan').Scheme -ceq 'wss') 'Allow trusted TLS host'
Assert (!(ConvertTo-DeviceUri 'ws://baduser:badpass@board.local:8767/rdk' 'lan')) 'Reject URL credentials'
Assert (!(ConvertTo-DeviceUri 'ws://board.local:8767/link' 'lan')) 'Reject simulator URL in RDK mode'
Assert (!(ConvertTo-DeviceUri 'ws://board.local:8767/rdk?token=x' 'lan')) 'Reject query'
Assert ((ConvertTo-DeviceUri '127.0.0.1:8766' 'sim').AbsolutePath -ceq '/link') 'Simulator default path'
$private = 'token=testlocal; 位置: 31.234567,121.234567'
$private += "`r`n13800138000 192.168.77.3 31.234567 https://board.local/private 123456789abcdef123456789abcdef12"
$clean = Protect-Observation $private
Assert ($clean -notmatch 'testlocal|13800138000|192\.168|31\.2345|board\.local|123456789abcdef') 'Observation leak'
Assert ((Protect-Observation '实际看到了 preview' '') -ceq '实际看到了 preview') 'Visible observation preserved'

$testRoot = Join-Path $ScratchDirectory ('路径 空格-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $testRoot -Force
$saved = Join-Path $testRoot '记录.json'
Write-TextFile $saved '{"count":1}'
Write-TextFile $saved '{"count":2}'
Assert ((Get-Content -LiteralPath $saved -Raw -Encoding UTF8 | ConvertFrom-Json).count -eq 2) 'Atomic replacement'

$pwsh = (Get-Process -Id $PID).Path
$readFile = Join-Path $testRoot 'read.ps1'
Write-TextFile $readFile 'param($path) [Console]::Write([IO.File]::ReadAllText($path))'
$result = Invoke-FieldProcess $pwsh @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $readFile, $saved)
Assert ($result.Code -eq 0 -and $result.Out -ceq '{"count":2}') 'Process received Unicode path and spaces'
$timeout = Invoke-FieldProcess $pwsh @('-NoProfile', '-Command', 'Start-Sleep -Seconds 10') 1
Assert $timeout.TimedOut 'Child process timeout'

Assert ((Get-PhoneStatus '').State -eq 'no-adb') 'Missing ADB'
function Invoke-FieldProcess($Executable, $Arguments) {
    if ($Arguments -contains 'pidof' -and $script:fakeAdb.ContainsKey('Log')) {
        return @{ Code = 0; Out = '1234' }
    }
    return $script:fakeAdb
}
foreach ($name in @('missing', 'unauthorized', 'offline', 'device', 'multiple', 'adb-error')) {
    $value = switch ($name) {
        missing { '' }
        multiple { "fake1`tdevice`r`nfake2`tdevice" }
        'adb-error' { '' }
        default { "fake1`t$name" }
    }
    $script:fakeAdb = @{ Code = [int]($name -eq 'adb-error'); Out = "List of devices attached`r`n$value`r`n" }
    Assert ((Get-PhoneStatus 'fake.exe').State -eq $name) "ADB state $name"
}
$script:fakeAdb = @{ Code = 0; Out = '' }
Assert ((Get-PhoneStatus 'fake.exe').State -eq 'missing') 'Cable interruption'
$script:fakeAdb.Out = "fake1`tdevice`r`n"
Assert ((Get-PhoneStatus 'fake.exe').State -eq 'device') 'Retry after reconnect'
$script:fakeAdb = @{ Code = 0; Log = $true; Out = @'
I/YuntongMedia( 1234): preview_fps=3
I/YuntongMedia( 1234): capture_displayed
I/YuntongMedia( 1234): capture_timeout unexpected-secret
I/Other( 1234): fake-private-address
'@ }
$sample = Get-MediaSummary 'fake.exe' 'fixture' 'fixture.app'
Assert ($sample -match 'preview_fps=3' -and $sample -match 'capture_displayed' -and
    $sample -notmatch 'timeout|secret|address') 'Export only allowlisted media diagnostics'

Add-Type -Path "$PSScriptRoot\Relay.cs"
$server = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$server.Start()
$port = $server.LocalEndpoint.Port
$probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$probe.Start()
try { Assert ((Get-PortState '127.0.0.1' $probe.LocalEndpoint.Port) -eq 'open') 'TCP service reachable' }
finally { $probe.Stop() }
$relay = [FieldRelay]::new('127.0.0.1', $port)
$local = [Net.Sockets.TcpClient]::new()
$remote = $null
try {
    $accept = $server.AcceptTcpClientAsync()
    $local.Connect('127.0.0.1', $relay.Port)
    Assert ($accept.Wait(4000)) 'Relay connects upstream'
    $remote = $accept.Result
    $inbound = $remote.GetStream()
    $outbound = $local.GetStream()
    $inbound.ReadTimeout = 4000
    $outbound.ReadTimeout = 4000
    $outbound.WriteByte(65)
    Assert ($inbound.ReadByte() -eq 65) 'Relay phone to board'
    $inbound.WriteByte(66)
    Assert ($outbound.ReadByte() -eq 66) 'Relay board to phone'
    $relay.Dispose()
    Assert ($outbound.ReadByte() -eq -1) 'Relay shutdown closes active socket'
    Assert $server.Server.IsBound 'Unrelated listener is still active'
} finally {
    if ($remote) { $remote.Close() }
    $local.Close()
    $relay.Dispose()
    $server.Stop()
}

$smokeState = Join-Path $testRoot '重启记录'
foreach ($attempt in 1..2) {
    & $pwsh -NoProfile -STA -ExecutionPolicy Bypass -File "$PSScriptRoot\Start.ps1" -SmokeTest -SmokeStatePath $smokeState
    Assert ($LASTEXITCODE -eq 0) "WinForms smoke run $attempt"
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$meta = Get-Content -LiteralPath "$PSScriptRoot\round.json" -Raw -Encoding UTF8 | ConvertFrom-Json
$latestZip = Get-ChildItem -LiteralPath (Join-Path $smokeState "$($meta.version)-source") -Filter '*.zip' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
$zip = [IO.Compression.ZipFile]::OpenRead($latestZip.FullName)
try {
    Assert (($zip.Entries.Name | Sort-Object) -join ',' -ceq 'diagnostics.txt,report.md,result.json') 'Export file allowlist'
} finally { $zip.Dispose() }
Write-Output 'Field tool checks passed'
