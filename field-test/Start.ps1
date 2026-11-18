param([switch]$SmokeTest, [string]$SmokeStatePath)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
Add-Type -AssemblyName System.IO.Compression.FileSystem
. "$PSScriptRoot\Core.ps1"

$round = Get-Content -LiteralPath "$PSScriptRoot\round.json" -Raw -Encoding UTF8 | ConvertFrom-Json
$apkPath = Join-Path $PSScriptRoot 'yuntong-field-test.apk'
$appId = $round.packageName
if ($appId -cne 'cn.yuntong.companion.fieldtest') { throw '测试包包名无效，请重新下载。' }
$homePath = Join-Path $env:LOCALAPPDATA 'Yuntong\FieldTest'
if ($SmokeTest) {
    $homePath = if ($SmokeStatePath) { $SmokeStatePath } else { Join-Path $env:TEMP ('YuntongSmoke-' + [guid]::NewGuid().ToString('N')) }
}
$null = New-Item -ItemType Directory -Path $homePath -Force
$mutex = [Threading.Mutex]::new($false, 'Local\YuntongFieldTest')
try { $locked = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $locked = $true }
if (!$locked -and !$SmokeTest) {
    [void][Windows.Forms.MessageBox]::Show('测试窗口已打开。请返回原窗口继续。', '云瞳联调')
    return
}

function Read-LocalJson($Path) {
    if (Test-Path -LiteralPath $Path) {
        try { return Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json } catch {
            $backup = "$Path.corrupt-$([DateTime]::UtcNow.ToString('yyyyMMdd-HHmmssfff'))"
            [IO.File]::Move($Path, $backup)
            [void][Windows.Forms.MessageBox]::Show('原记录无法读取，已保留备份。窗口将使用空白记录。', '云瞳联调')
        }
    }
}

$roundKey = if ($round.apkSha256 -match '^[a-fA-F0-9]{64}$') { $round.apkSha256.Substring(0, 12) } else { 'source' }
$roundPath = Join-Path $homePath "$($round.version)-$roundKey"
$null = New-Item -ItemType Directory -Path $roundPath -Force
$configPath = Join-Path $homePath 'connection.json'
$statePath = Join-Path $roundPath 'session.json'
$evidencePath = Join-Path $roundPath '截图-仅本机'
$savedConfig = Read-LocalJson $configPath
$loaded = Read-LocalJson $statePath
$entries = @{}
$events = [Collections.Generic.List[object]]::new()
$sessionId = [guid]::NewGuid().ToString('N')
$latestPhone = '未检查'
$latestApp = '未检查'
$installed = $null
if ($loaded) {
    $sessionId = $loaded.sessionId
    foreach ($entry in $loaded.tests) { $entries[$entry.key] = $entry }
    foreach ($event in $loaded.diagnostics) { $events.Add($event) }
    $latestPhone = $loaded.phone
    $latestApp = $loaded.app
    $installed = $loaded.android
}
$phone = @{ State = 'missing'; Serial = '' }
$modeIds = @('phone', 'lan', 'usb', 'sim')
$modeNames = @('手机独立', '真实 RDK（手机局域网）', '真实 RDK（USB 转发）', '模拟 RDK（电脑模拟端）')
$phoneStatus = @{
    'no-adb' = '未找到 ADB。点击「准备 ADB」下载所需组件。'
    'adb-error' = 'ADB 未响应。重新连接 USB 后点击「重新检查」。'
    missing = '未发现手机。解锁手机，换可传数据的 USB 线或 USB 口，并开启 USB 调试。'
    unauthorized = '手机未授权。在已解锁的手机上点「允许 USB 调试」；仅在信任此电脑时同意。'
    offline = '手机离线。重新插线、解锁手机，再点「重新检查」。'
    multiple = '发现多台设备。只保留本轮测试手机，关闭其他模拟器后重试。'
    device = '手机已连接，USB 调试可用。'
}

$cases = @{
    install = @('安装与启动', '连接手机并允许 USB 调试。点击「重新检查」核对版本；如缺少该包，点击「安装本轮 APK」。', '点「打开测试应用」。手机显示「云瞳 · 联调测试」首页；现有云瞳仍可打开。', 45)
    connect = @('连接与状态', '确认 RDK 和眼镜已上电。点击「重新检查」；需要时点击「检查协议」核对服务，随后在手机上配对。', '手机打开「我的 → 设备与连接」，把窗口给出的地址和本地配对密钥填进手机，再点「连接」。手机应显示已连接，且能读取眼镜状态。', 20)
    preview = @('实时预览', '手机已连接真实 RDK，眼镜已上电。若观察已经从其他入口运行，请由原入口处理。', '在「设备与连接」点击「启动眼镜静默观察」（最多等 45 秒）。回首页点「查看画面 → 继续接收设备图传」。移动静止台架前的纸卡，确认手机画面跟随变化，记录卡顿或空档。', 60)
    capture = @('主动取新图', '已看到实时画面。准备一张无个人信息的纸卡，写上本次用的字母。', '在「查看画面」点击「暂停接收并保留当前画面」。换一张纸卡，再点「拍摄眼镜新画面」。预期 2–5 秒内显示新卡，或明确报告本次无新图；记录是否误显示旧图。', 10)
    stop = @('停止观察', '只停止本轮从手机开启的观察任务。', '在「我的 → 设备与连接」点「停止眼镜观察」，等待状态显示已停止。使用 USB 转发时，确认后再点击电脑「停止转发」。', 20)
    sim = @('模拟端连接与画面', '使用团队事先启动的 Android 仓库电脑模拟端。只在界面选择模拟模式。', '在手机填入当轮模拟端地址及测试令牌，连接后点「查看画面 → 继续接收设备图传」。观察示例画面与模拟连接标识。', 25)
}

[Windows.Forms.Application]::EnableVisualStyles()
$form = [Windows.Forms.Form]::new()
$form.Text = "云瞳一键联调 · $($round.version)"
$form.StartPosition = 'CenterScreen'
$form.AutoScaleMode = 'Dpi'
$form.ClientSize = [Drawing.Size]::new(926, 690)
$form.FormBorderStyle = 'FixedDialog'
$form.MaximizeBox = $false
$form.AutoScroll = $true
$form.Font = [Drawing.Font]::new('Microsoft YaHei UI', 9)

function Place($Item, [int]$X, [int]$Y, [int]$Width, [int]$Height) {
    $Item.SetBounds($X, $Y, $Width, $Height)
    $null = $form.Controls.Add($Item)
    return $Item
}
function New-Label($Text, $X, $Y, $Width, $Height) {
    $label = Place ([Windows.Forms.Label]::new()) $X $Y $Width $Height
    $label.Text = $Text
    return $label
}
$buttons = [Collections.Generic.List[Windows.Forms.Button]]::new()
function New-Button($Text, $X, $Y, $Width, [scriptblock]$Action) {
    $button = Place ([Windows.Forms.Button]::new()) $X $Y $Width 32
    $button.Text = $Text
    $button.Add_Click($Action)
    $buttons.Add($button)
    return $button
}
function Tell($Text) {
    [void][Windows.Forms.MessageBox]::Show($form, $Text, '云瞳联调')
}
function Confirm-Action($Text) {
    [Windows.Forms.MessageBox]::Show($form, $Text, '云瞳联调', 'YesNo', 'Question', 'Button2') -eq 'Yes'
}
function Run-Ui($Caption, [scriptblock]$Action) {
    if ($script:busy) { return }
    $script:busy = $true
    $status.Text = $Caption
    foreach ($control in @($mode, $address, $pairing, $list, $notes, $deviceVersions) + $buttons.ToArray()) { $control.Enabled = $false }
    try {
        $form.Refresh()
        & $Action
    } catch {
        $status.Text = '操作未完成。请重新检查，或用「无法测试」记录当前步骤。'
        Add-Evidence '工具操作异常'
    } finally {
        foreach ($control in @($mode, $address, $pairing, $list, $notes, $deviceVersions) + $buttons.ToArray()) { $control.Enabled = $true }
        $script:busy = $false
    }
}
$busy = $false
$loading = $true
$relay = $null
$forward = $null
$adb = $null
$fieldPump = { [Windows.Forms.Application]::DoEvents() }

$null = New-Label '连接方式' 14 16 82 25
$mode = Place ([Windows.Forms.ComboBox]::new()) 96 13 245 26
$mode.DropDownStyle = 'DropDownList'
$mode.AccessibleName = '连接方式'
$null = $mode.Items.AddRange($modeNames)
$null = New-Label '服务地址' 357 16 76 25
$address = Place ([Windows.Forms.TextBox]::new()) 432 13 309 26
$address.AccessibleName = 'RDK 或模拟端服务地址'
$null = New-Button '复制手机地址' 751 9 151 { Copy-PhoneAddress }
$null = New-Label '配对密钥（仅电脑协议检查，可留空）' 14 49 310 25
$pairing = Place ([Windows.Forms.TextBox]::new()) 330 45 246 26
$pairing.AccessibleName = '可选的协议检查密钥'
$pairing.UseSystemPasswordChar = $true
$null = New-Button '检查协议' 586 41 145 { Run-Ui '正在检查协议（约 5 秒）…' { Check-Protocol } }
$null = New-Button '停止转发' 751 41 151 { Run-Ui '正在停止本工具转发…' { Stop-Forward; $status.Text = '本工具转发已停止。' } }

$null = New-Button '重新检查' 14 84 150 { Run-Ui '正在检查手机与设备…' { Check-Setup } }
$null = New-Button '准备 ADB' 174 84 150 { Prepare-Adb }
$null = New-Button '安装本轮 APK' 334 84 150 { Install-Round }
$null = New-Button '打开测试应用' 494 84 150 { Open-TestApp }
$null = New-Button '使用 USB 转发' 654 84 248 { Run-Ui '正在建立 USB 转发…' { Start-Forward } }
$status = New-Label '等待检查' 14 122 895 79
$status.BorderStyle = 'FixedSingle'

$null = New-Label '测试项' 14 215 140 23
$list = Place ([Windows.Forms.ListBox]::new()) 14 241 221 228
$list.AccessibleName = '测试项'
$guide = Place ([Windows.Forms.TextBox]::new()) 250 211 652 147
$guide.Multiline = $true
$guide.ReadOnly = $true
$guide.ScrollBars = 'Vertical'
$guide.AccessibleName = '当前测试的准备条件、操作、预期和等待时间'
$null = New-Label '实际看到或听到的现象（自动保存）' 250 368 560 24
$notes = Place ([Windows.Forms.TextBox]::new()) 250 394 652 75
$notes.Multiline = $true
$notes.ScrollBars = 'Vertical'
$notes.MaxLength = 3000
$notes.AccessibleName = '实际现象'
$null = New-Button '开始 / 重试' 14 482 146 { Mark-Case '开始' }
$null = New-Button '通过' 170 482 118 { Mark-Case '通过' }
$null = New-Button '失败' 298 482 118 { Mark-Case '失败' }
$null = New-Button '无法测试 / 跳过' 426 482 153 { Mark-Case '无法测试' }
$null = New-Button '停止本项' 589 482 130 { Mark-Case '停止' }
$null = New-Button '添加截图（本机）' 729 482 173 { Attach-Screenshot }
$null = New-Label '设备版本（有标签时填 RDK 包版本、ESP 固件指纹，可留空）' 14 525 530 23
$deviceVersions = Place ([Windows.Forms.TextBox]::new()) 548 521 354 26
$deviceVersions.MaxLength = 160
$deviceVersions.AccessibleName = '已知设备版本'
$null = New-Button '打开结果目录' 14 562 180 { if (Export-Results $false) { Start-Process explorer.exe -ArgumentList (Join-ProcessArguments @($roundPath)) } }
$null = New-Button '导出反馈包' 204 562 180 { if (Export-Results $true) { Tell '反馈 ZIP 已生成。可点击「打开结果目录」找到它。' } }
$null = New-Button '提交测试结果' 394 562 180 { Submit-Results }
$null = New-Button '查看中文说明' 584 562 180 { Start-Process -FilePath 'notepad.exe' -ArgumentList (Join-ProcessArguments @("$PSScriptRoot\README.md")) }
$counter = New-Label '' 14 609 889 55

function Current-Mode { $modeIds[$mode.SelectedIndex] }
function Current-Case {
    if ($list.SelectedIndex -ge 0) { return $script:visibleCases[$list.SelectedIndex] }
}
function Current-Key {
    if (Current-Case) { return "$(Current-Mode)/$(Current-Case)" }
}
function New-Entry($Key) {
    [pscustomobject]@{ key = $Key; status = '未开始'; started = ''; ended = ''; notes = ''; screenshots = 0 }
}
function Save-Session {
    if ($script:loading) { return }
    $key = Current-Key
    if ($key -and ($notes.Text -or $entries.ContainsKey($key))) {
        if (!$entries.ContainsKey($key)) { $entries[$key] = New-Entry $key }
        $entries[$key].notes = Protect-Observation $notes.Text $pairing.Text
    }
    $payload = [ordered]@{
        sessionId = $sessionId; tests = @($entries.Values | Sort-Object key)
        diagnostics = @($events.ToArray()); deviceVersions = Protect-Observation $deviceVersions.Text $pairing.Text
        android = $script:installed; phone = $script:latestPhone; app = $script:latestApp
    }
    Write-TextFile $statePath ($payload | ConvertTo-Json -Depth 6)
    $selected = Current-Mode
    $validAddress = if ($selected -in @('lan', 'usb', 'sim')) { ConvertTo-DeviceUri $address.Text $selected } else { $null }
    $network = if ($validAddress) { $validAddress.AbsoluteUri } else { '' }
    Write-TextFile $configPath (@{ mode = $selected; address = $network } | ConvertTo-Json)
}
function Add-Evidence($Message) {
    if ($events.Count -ge 150) { $events.RemoveAt(0) }
    $events.Add([pscustomobject]@{ time = [DateTimeOffset]::Now.ToString('o'); mode = Current-Mode; message = $Message })
    Save-Session
}
function Show-Case {
    $key = Current-Key
    if (!$key) { return }
    $script:loading = $true
    try {
        $row = $cases[(Current-Case)]
        $entry = if ($entries.ContainsKey($key)) { $entries[$key] } else { New-Entry $key }
        $guide.Text = "$($row[0])   [$($entry.status)]`r`n准备：$($row[1])`r`n操作与预期：$($row[2])`r`n等待约 $($row[3]) 秒。没达到预期时，请记下现象。"
        $notes.Text = $entry.notes
    } finally { $script:loading = $false }
}
function Show-CaseList {
    $script:visibleCases = switch (Current-Mode) {
        'phone' { @('install') }
        'sim' { @('install', 'sim') }
        default { @('install', 'connect', 'preview', 'capture', 'stop') }
    }
    $script:visibleCases = @($script:visibleCases)
    $list.Items.Clear()
    foreach ($id in $script:visibleCases) {
        $key = "$(Current-Mode)/$id"
        $marker = if ($entries.ContainsKey($key)) { $entries[$key].status } else { '未开始' }
        [void]$list.Items.Add("$marker  $($cases[$id][0])")
    }
    $list.SelectedIndex = 0
}

function Mark-Case($Outcome) {
    $key = Current-Key
    if (!$key) { return }
    $index = $list.SelectedIndex
    if ($Outcome -in @('通过', '失败', '无法测试') -and !$notes.Text.Trim()) {
        Tell '请先写一句实际现象或卡在了哪一步。'
        $notes.Focus()
        return
    }
    if (!$entries.ContainsKey($key)) { $entries[$key] = New-Entry $key }
    $entry = $entries[$key]
    $now = [DateTimeOffset]::Now.ToString('o')
    if ($Outcome -eq '开始') { $entry.started = $now; $entry.ended = '' }
    else { $entry.ended = $now }
    $entry.status = $Outcome
    Add-Evidence "$($cases[(Current-Case)][0]) | $Outcome | 人工记录"
    $log = if ($Outcome -ne '开始' -and $script:phone.State -eq 'device') { $true } else { $false }
    $list.Items[$index] = "$Outcome  $($cases[($script:visibleCases[$index])][0])"
    $list.SelectedIndex = $index
    Show-Case
    if ($log) {
        Run-Ui '正在整理本项诊断…' {
            $prior = $phoneStatus[$script:phone.State]
            Add-Evidence (Get-LogSummary $script:adb $script:phone.Serial $appId)
            $status.Text = "$prior 已保存本项结果。"
        }
    } else { $status.Text = '已保存本项。' }
}

function Attach-Screenshot {
    $key = Current-Key
    if (!$key) { return }
    $dialog = [Windows.Forms.OpenFileDialog]::new()
    try {
        $dialog.Filter = '截图 (*.png;*.jpg;*.jpeg)|*.png;*.jpg;*.jpeg'
        if ($dialog.ShowDialog($form) -ne 'OK') { return }
        Run-Ui '正在保存在本机…' {
            $null = New-Item -ItemType Directory -Path $evidencePath -Force
            $file = Get-Item -LiteralPath $dialog.FileName
            if ($file.Length -gt 20MB) { Tell '请选择 20 MB 以内的截图。'; return }
            $name = $key.Replace('/', '-') + '-' + [guid]::NewGuid().ToString('N') + $file.Extension
            Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $evidencePath $name)
            if (!$entries.ContainsKey($key)) { $entries[$key] = New-Entry $key }
            $entries[$key].screenshots++
            Add-Evidence '已附本机截图，默认导出排除截图'
            $status.Text = '截图已保存在结果目录的本机截图文件夹。'
        }
    } finally { $dialog.Dispose() }
}

function Check-Setup {
    $script:adb = Find-Adb $homePath
    $script:phone = Get-PhoneStatus $script:adb
    $script:latestPhone = if ($phoneStatus.ContainsKey($script:phone.State)) { $script:phone.State } else { 'other' }
    $phoneText = if ($phoneStatus.ContainsKey($script:phone.State)) { $phoneStatus[$script:phone.State] } else { '手机状态异常，请重新插线后检查。' }
    $script:installed = $null
    $script:latestApp = '未读取'
    $appText = '应用未检查。'
    if ($script:phone.State -eq 'device') {
        $transport = switch -Regex ($script:phone.Serial) {
            '^emulator-' { 'Android 模拟器'; break }
            ':|_adb-tls' { 'ADB 网络连接'; break }
            default { 'ADB USB' }
        }
        $phoneText = "ADB 已连接（$transport）。"
        $app = Get-InstalledApp $script:adb $script:phone.Serial $appId
        $android = Invoke-FieldProcess $script:adb @('-s', $script:phone.Serial, 'shell', 'getprop', 'ro.build.version.release')
        $os = [regex]::Match($android.Out.Trim(), '^[0-9.]{1,15}$').Value
        $script:installed = @{ androidRelease = $os; transport = $transport; name = $app.Name; code = $app.Code; sha256 = $app.Sha256 }
        if ($app.State -eq 'missing') {
            $script:latestApp = '未安装'
            $appText = '尚未安装本轮联调测试版。现有云瞳可以保留。'
        } elseif ($app.State -ne 'installed') {
            $script:latestApp = '读取失败'
            $appText = '未能读出应用版本。请确认手机仍在线。'
        } elseif ($round.apkSha256 -and $app.Sha256 -eq $round.apkSha256) {
            $script:latestApp = 'APK 哈希一致'
            $appText = "已核实本轮 APK（$($app.Name)）。"
        } elseif ($app.Name -eq $round.versionName -and $app.Code -eq $round.versionCode -and (!$app.Sha256 -or !$round.apkSha256)) {
            $script:latestApp = '版本名一致，未核实 APK 哈希'
            $appText = "版本名一致（$($app.Name)），未核实 APK 哈希。"
        } else {
            $script:latestApp = '版本或 APK 不一致'
            $appText = '已有测试版与本轮版本或 APK 哈希不同。请核对测试包。'
        }
    }
    $network = '手机独立检查。'
    if ((Current-Mode) -ne 'phone') {
        $uri = ConvertTo-DeviceUri $address.Text (Current-Mode)
        if (!$uri) {
            $network = '请填写有效地址。手机直连 ws:// 请用局域网 IPv4；RDK 用 /rdk，模拟端用 /link。'
        } elseif (Test-DevicePort $uri) {
            $network = '电脑可以连接服务端口。点击「检查协议」核实服务，或直接在手机配对。'
            Add-Evidence '设备端口可连接'
        } else {
            $network = '电脑连不到服务端口。核对 RDK／眼镜供电、电脑网络和服务是否已启动，再重新检查。'
            Add-Evidence '设备端口超时或拒绝连接'
        }
    }
    $transfer = ''
    if ($script:forward) {
        if ($script:phone.Serial -eq $script:forward.Serial -and (Test-ForwardMapping $script:forward)) {
            $transfer = "`r`n手机使用 $($script:forward.Address)"
        } else {
            Stop-Forward
            $transfer = "`r`nUSB 转发已失效，请重新建立。"
        }
    }
    $status.Text = "$phoneText`r`n$appText`r`n$network$transfer"
    Add-Evidence "手机状态 $script:latestPhone | APK 核对 $script:latestApp"
}

function Check-Protocol {
    $selected = Current-Mode
    $uri = ConvertTo-DeviceUri $address.Text $selected
    if ($selected -eq 'phone' -or !$uri) { $status.Text = '请选择设备连接方式并填写地址。'; return }
    $token = $pairing.Text.Trim()
    if ($token -cnotmatch '^[!-~]{16,128}$') { $status.Text = '电脑协议检查需填 16–128 位的英文字符密钥，中间无空格。也可直接在手机配对。'; return }
    $result = Test-DeviceHandshake $uri $selected $token
    switch ($result) {
        accepted { $status.Text = '服务握手、协议和连接类别符合预期。接下来在手机上验证。' }
        denied { $status.Text = '服务拒绝配对或握手。核对地址、密钥和设备软件版本。' }
        unexpected { $status.Text = '服务响应与本轮协议不符。核对设备包版本和所选连接方式。' }
        default { $status.Text = '协议检查超时或被拒绝。核对服务、密钥和连接地址；若手机已连接，先在手机断开再检查。' }
    }
    Add-Evidence "电脑协议检查 $result"
}

function Prepare-Adb {
    if (Find-Adb $homePath) { Run-Ui '正在检查已有 ADB…' { Check-Setup }; return }
    $page = 'https://developer.android.com/tools/releases/platform-tools'
    $choice = [Windows.Forms.MessageBox]::Show($form,
        '准备 ADB 会从 Google 下载到本机用户目录，无需管理员权限。已阅读官方许可并同意下载请点「是」，需先查看许可请点「否」。',
        '准备 ADB', 'YesNoCancel', 'Information', 'Button3')
    if ($choice -eq 'No') {
        Start-Process $page
        return
    }
    if ($choice -ne 'Yes') { return }
    Run-Ui '正在向 Google 下载 ADB（最多 90 秒）…' {
        $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
        if (!$curl) { $status.Text = 'Windows 缺少下载组件。请从官方页面下载 Windows Platform-Tools，再按中文说明放置。'; return }
        $work = Join-Path $homePath ('download-' + [guid]::NewGuid().ToString('N'))
        $null = New-Item -ItemType Directory -Path $work
        $zip = Join-Path $work 'platform-tools.zip'
        try {
            $download = Invoke-FieldProcess $curl.Source @('--fail', '--silent', '--show-error', '--connect-timeout', '12', '--max-time', '85', '--proto', '=https', '--output', $zip, 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip') 90
            if ($download.Code -ne 0) {
                $status.Text = 'ADB 下载失败。检查电脑网络，重试「准备 ADB」或按中文说明使用官方下载。'
                Add-Evidence '官方 ADB 下载失败'
                return
            }
            Expand-Archive -LiteralPath $zip -DestinationPath $work
            $unpacked = Join-Path $work 'platform-tools'
            if (!(Test-Path -LiteralPath (Join-Path $unpacked 'adb.exe') -PathType Leaf)) { throw 'ADB archive incomplete' }
            [IO.Directory]::Move($unpacked, (Join-Path $homePath 'platform-tools'))
            Add-Evidence '从官方地址准备 ADB 完成'
            Check-Setup
        } finally {
            if (Test-Path -LiteralPath $zip) { [IO.File]::Delete($zip) }
            if (![IO.Directory]::EnumerateFileSystemEntries($work).GetEnumerator().MoveNext()) { [IO.Directory]::Delete($work) }
        }
    }
}

function Install-Round {
    if (!(Test-Path -LiteralPath $apkPath -PathType Leaf) -or !$round.apkSha256) {
        Tell '当前目录是源码版或缺少 APK。请使用这一轮发布的完整测试包。'
        return
    }
    if (!(Confirm-Action '安装或更新「云瞳 · 联调测试」？现有正式云瞳及其数据可保留。请解锁手机并留意 USB 安装确认。')) { return }
    Run-Ui '正在校验 APK 并安装（最多 120 秒）…' {
        $script:adb = Find-Adb $homePath
        $script:phone = Get-PhoneStatus $script:adb
        if ($script:phone.State -ne 'device') { Check-Setup; return }
        if ((Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash -ne $round.apkSha256) {
            $status.Text = 'APK 校验失败。请重新解压完整测试包。'
            Add-Evidence 'APK 校验失败'
            return
        }
        $result = Invoke-FieldProcess $script:adb @('-s', $script:phone.Serial, 'install', '-r', $apkPath) 120
        if ($result.Code -eq 0 -and $result.Out -match '(?m)^Success\s*$') { Add-Evidence 'ADB 安装成功'; Check-Setup; return }
        $message = $result.Out + $result.Error
        $reason = switch -Regex ($message) {
            'UPDATE_INCOMPATIBLE|INCONSISTENT_CERTIFICATES' { '签名不一致，请向维护者索取同签名测试包，保留原数据。'; break }
            'VERSION_DOWNGRADE' { '手机上的测试版更新。请使用对应版本或联系维护者。'; break }
            'USER_RESTRICTED|USER_CANCELLED' { '请解锁手机，在屏幕上允许本次 USB 安装。在开发者选项查看是否有「通过 USB 安装」。'; break }
            'INSUFFICIENT_STORAGE' { '手机存储空间不足。请自行检查可用空间后重试。'; break }
            default { '检查手机屏幕上的授权、USB 线与剩余空间；完成后先「重新检查」。' }
        }
        $status.Text = "安装未确认成功。$reason"
        Add-Evidence "ADB 安装未确认 | $reason"
    }
}

function Open-TestApp {
    Run-Ui '正在打开测试应用…' {
        $script:adb = Find-Adb $homePath
        $script:phone = Get-PhoneStatus $script:adb
        if ($script:phone.State -ne 'device') { Check-Setup; return }
        $launched = Invoke-FieldProcess $script:adb @('-s', $script:phone.Serial, 'shell', 'am', 'start', '-n', "$appId/cn.yuntong.companion.MainActivity")
        if ($launched.Code -eq 0 -and ($launched.Out + $launched.Error) -notmatch 'Error|Exception') {
            $status.Text = '已请求打开。请看手机屏幕核对测试版首页。'
            Add-Evidence 'ADB 已请求启动测试版'
        } else {
            $status.Text = '打开失败。请先确认手机已解锁并已安装本轮 APK。'
            Add-Evidence 'ADB 启动失败'
        }
    }
}

function Test-ForwardMapping($Mapping) {
    $existing = Invoke-FieldProcess $Mapping.Adb @('-s', $Mapping.Serial, 'reverse', '--list') 4
    return ($existing.Code -eq 0 -and $existing.Out -match "(?m)(?:^|\s)tcp:$($Mapping.PhonePort)\s+tcp:$($Mapping.PcPort)\s*$")
}

function Stop-Forward {
    $mapping = $script:forward
    $script:forward = $null
    try {
        if ($mapping -and (Test-ForwardMapping $mapping)) {
            $removed = Invoke-FieldProcess $mapping.Adb @('-s', $mapping.Serial, 'reverse', '--remove', "tcp:$($mapping.PhonePort)") 4
            if ($removed.Code -eq 0) { Add-Evidence '本次 USB 转发已移除' }
            else { Add-Evidence 'USB 映射清理未收到回执' }
        }
    } finally {
        if ($script:relay) { $script:relay.Dispose(); $script:relay = $null }
    }
}

function Start-Forward {
    $uri = ConvertTo-DeviceUri $address.Text 'usb'
    if ((Current-Mode) -ne 'usb' -or !$uri -or $uri.Scheme -ne 'ws' -or $uri.IsLoopback) {
        $status.Text = '请选择「真实 RDK（USB 转发）」并填入电脑可达的 RDK 局域网 ws:// 地址。'
        return
    }
    $script:adb = Find-Adb $homePath
    $script:phone = Get-PhoneStatus $script:adb
    if ($script:phone.State -ne 'device') { Check-Setup; return }
    if ($script:phone.Serial -match '^emulator-|:|_adb-tls') { $status.Text = '真实 RDK USB 转发需要通过数据线连接手机。'; return }
    if (!(Test-DevicePort $uri)) { $status.Text = '电脑无法连接 RDK 端口。先让电脑接入 RDK 所在网络并确认服务运行。'; return }
    if ($script:forward) {
        $status.Text = "已有本工具建立的转发：$($script:forward.Address)。需要换地址时，请先在手机停止观察，再点击「停止转发」。"
        return
    }
    if (!('FieldRelay' -as [type])) { Add-Type -Path "$PSScriptRoot\Relay.cs" }
    $script:relay = [FieldRelay]::new($uri.DnsSafeHost, $uri.Port)
    $phonePort = Get-Random -Minimum 19000 -Maximum 32000
    $pcPort = $script:relay.Port
    $target = @{ Serial = $script:phone.Serial; Adb = $script:adb; PhonePort = $phonePort; PcPort = $pcPort; Address = "ws://127.0.0.1:$phonePort/rdk" }
    try {
        $mapped = Invoke-FieldProcess $target.Adb @('-s', $target.Serial, 'reverse', '--no-rebind', "tcp:$phonePort", "tcp:$pcPort")
        if ($mapped.Code -ne 0) {
            if ($mapped.TimedOut -and (Test-ForwardMapping $target)) {
                $script:forward = $target
                Stop-Forward
            }
            $status.Text = 'USB 转发未建立。保持手机解锁，确认 USB 调试并重试。'
            Add-Evidence 'USB 反向转发失败'
            return
        }
        $script:forward = $target
        $status.Text = "本轮 USB 转发已建立。手机连接地址：$($target.Address)`r`n保持 USB 接线和此窗口打开。测试后先在手机停止观察，再停止转发。"
        Add-Evidence 'USB 反向转发已建立'
    } finally {
        if (!$script:forward -and $script:relay) { $script:relay.Dispose(); $script:relay = $null }
    }
}

function Copy-PhoneAddress {
    $selected = Current-Mode
    if ($selected -eq 'phone') { Tell '手机独立测试无需连接设备。'; return }
    if ($selected -eq 'usb') {
        if (!$script:forward) { Tell '先点击「使用 USB 转发」。'; return }
        $value = $script:forward.Address
    } else {
        $uri = ConvertTo-DeviceUri $address.Text $selected
        if (!$uri) { Tell '输入有效的服务地址后再复制。'; return }
        $value = $uri.AbsoluteUri
    }
    [Windows.Forms.Clipboard]::SetText($value)
    $status.Text = "已复制电脑窗口中的手机连接地址：$value`r`n手机在「我的 → 设备与连接」填写此地址。"
}

function Export-Results([bool]$CreateZip) {
    try {
        Save-Session
        $record = Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json
        foreach ($entry in $record.tests) { $entry.notes = Protect-Observation $entry.notes $pairing.Text }
        $record.deviceVersions = Protect-Observation $record.deviceVersions $pairing.Text
        $record | Add-Member -NotePropertyName round -NotePropertyValue $round
        $record | Add-Member -NotePropertyName exportedAt -NotePropertyValue ([DateTimeOffset]::Now.ToString('o'))
        $directory = Join-Path $roundPath '反馈'
        $null = New-Item -ItemType Directory -Path $directory -Force
        $lines = @('# 云瞳联调反馈', '', "测试包 $($round.version)", "源码提交 $($round.sourceCommit)", "预期 APK $($round.versionName) ($($round.versionCode))", "预期 APK SHA-256 $($round.apkSha256)", "设备仓库参考提交 $($round.deviceBaseline)", "RDK 协议 $($round.protocol)", "手机检查 $($record.phone)", "安装核对 $($record.app)", "设备版本（人工填写） $($record.deviceVersions)", "导出时间 $($record.exportedAt)", '', '## 人工观察', '')
        if ($record.android) { $lines += "Android $($record.android.androidRelease)，实装应用 $($record.android.name) ($($record.android.code))，实装 APK SHA-256 $($record.android.sha256)", '' }
        foreach ($entry in $record.tests) {
            $parts = $entry.key.Split('/')
            $modeName = $modeNames[[array]::IndexOf($modeIds, $parts[0])]
            $lines += "### $modeName · $($cases[$parts[1]][0])", '', "结果 $($entry.status)", "开始 $($entry.started) | 结束 $($entry.ended)", "现象 $($entry.notes)", "本机截图数 $($entry.screenshots)", ''
        }
        if (!$record.tests) { $lines += '尚无人工结果。' }
        $lines += '日志仅含分类、次数和时间。截图默认保留在本机。'
        $logLines = $record.diagnostics | ForEach-Object { "$($_.time) | $($_.mode) | $($_.message)" }
        Write-TextFile (Join-Path $directory 'report.md') ($lines -join "`r`n")
        Write-TextFile (Join-Path $directory 'result.json') ($record | ConvertTo-Json -Depth 8)
        Write-TextFile (Join-Path $directory 'diagnostics.txt') ($logLines -join "`r`n")
        if ($CreateZip) {
            $fileName = 'yuntong-{0}-{1}-{2}.zip' -f $round.version, $sessionId.Substring(0, 8), (Get-Date -Format 'yyyyMMdd-HHmmssfff')
            $zip = [IO.Compression.ZipFile]::Open((Join-Path $roundPath $fileName), 'Create')
            try {
                foreach ($publicFile in @('report.md', 'result.json', 'diagnostics.txt')) {
                    [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $directory $publicFile), $publicFile)
                }
            } finally { $zip.Dispose() }
            $script:latestZip = $fileName
        }
        return $true
    } catch {
        Tell '生成结果失败。请检查磁盘空间与目录权限，再重试。'
        return $false
    }
}

function Submit-Results {
    if (!(Export-Results $true)) { return }
    if (!(Confirm-Action "将由你检查并提交这三个文件：report.md、result.json、diagnostics.txt。`r`n反馈包：$script:latestZip`r`n请打开「反馈」目录检查现象和诊断内容。继续将打开公开 GitHub Issue 草稿和反馈包目录，最终由你点击提交。")) { return }
    $selectArgument = '/select,' + (Join-ProcessArguments @((Join-Path $roundPath $script:latestZip)))
    Start-Process explorer.exe -ArgumentList $selectArgument
    $title = [Uri]::EscapeDataString("[实测 $($round.version)] 云瞳联调反馈")
    $body = [Uri]::EscapeDataString("测试包 $($round.version)`n源码 $($round.sourceCommit)`n反馈编号 $sessionId`n`n请将刚生成的反馈 ZIP 拖到此处，检查后提交。")
    Start-Process "https://github.com/Morechips/yuntong-mobile/issues/new?title=$title&body=$body"
}

try {
    $mode.SelectedIndex = if ($savedConfig -and $savedConfig.mode -in $modeIds) { [array]::IndexOf($modeIds, $savedConfig.mode) } else { 1 }
    if ($savedConfig) { $address.Text = $savedConfig.address }
    if ($loaded) { $deviceVersions.Text = $loaded.deviceVersions }
    $list.Add_SelectedIndexChanged({ Show-Case })
    $mode.Add_SelectedIndexChanged({
        Show-CaseList
        Save-Session
        $status.Text = '连接方式已切换。点击「重新检查」。'
    })
    Show-CaseList
    $timer = [Windows.Forms.Timer]::new()
    $timer.Interval = 450
    $timer.Add_Tick({
        $timer.Stop()
        if (!$script:busy) { try { Save-Session } catch { $status.Text = '自动保存失败。请检查磁盘后导出反馈。' } }
    })
    $scheduleSave = { if (!$script:loading) { $timer.Stop(); $timer.Start() } }
    $notes.Add_TextChanged({
        $key = Current-Key
        if (!$script:loading -and $key) {
            if (!$entries.ContainsKey($key)) { $entries[$key] = New-Entry $key }
            $entries[$key].notes = Protect-Observation $notes.Text $pairing.Text
        }
        & $scheduleSave
    })
    $address.Add_TextChanged($scheduleSave)
    $deviceVersions.Add_TextChanged($scheduleSave)
    $form.Add_FormClosing({
        param($sender, $closing)
        if ($script:busy) { $closing.Cancel = $true; return }
        if ($script:forward -and !(Confirm-Action '关闭会停止本工具开的 USB 转发。若手机启动了眼镜观察，请先在手机停止确认。现在关闭？')) {
            $closing.Cancel = $true
            return
        }
        try { Save-Session } catch {
            $closing.Cancel = $true
            Tell '本次保存失败。请检查磁盘和目录权限后重试。'
        }
    })
    $form.Add_Shown({ if (!$SmokeTest) { Run-Ui '正在检查当前环境…' { Check-Setup } } })
    $counter.Text = "测试包 $($round.version) · 约 15–25 分钟。只验证安装、连接、预览和取图。`r`n首次允许 USB 调试和 USB 安装时，在手机上按屏幕提示操作。"
    if ($SmokeTest) {
        $null = $form.Handle
        Check-Setup
        $expected = '测试保存与恢复'
        if ($loaded) {
            if ($notes.Text -cne $expected) { throw 'Smoke restore failed' }
        } else {
            $notes.Text = $expected
            Save-Session
        }
        if (!(Export-Results $true) -or !(Test-Path -LiteralPath (Join-Path $roundPath $script:latestZip))) { throw 'Smoke export failed' }
        Write-Output 'WinForms initialization and export passed'
    } else {
        [void]$form.ShowDialog()
    }
} finally {
    Stop-Forward
    $form.Dispose()
    if ($locked) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
