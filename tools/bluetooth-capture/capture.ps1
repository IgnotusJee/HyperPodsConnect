<#
.SYNOPSIS
    Bluetooth capture pipeline driver. Implements milestone M0 (preflight gate).

.DESCRIPTION
    Read-only environment and safety gate for the capture plan documented in
    docs/reverse-engineering/ROOTED_ANDROID_BLUETOOTH_CAPTURE_PLAN.md.

    Preflight never changes device state. It does not enable HCI snoop, does not
    write persist.bluetooth.* properties, does not start or stop apps, and does
    not touch system logs. The only filesystem write is creating the capture
    output root, and only when -CreateOutputRoot is passed.

.EXAMPLE
    .\capture.ps1 -Mode preflight
    .\capture.ps1 -Mode preflight -Scenario oppo-air5s-anc -CreateOutputRoot
#>
[CmdletBinding()]
param(
    [ValidateSet('preflight', 'hci-begin', 'hci-end')]
    [string]$Mode = 'preflight',

    [string]$SessionId,

    [string]$Scenario,

    [string]$OutputRoot = 'D:\HeadphoneCaptures',

    [string]$FridaHome = 'C:\Users\Ignotus\miniconda3\envs\frida-17.16.4',

    [string]$Tshark = 'C:\Program Files\Wireshark\tshark.exe',

    [string]$ExpectedFridaVersion = '17.16.4',

    [int]$FridaPort = 27052,

    [int]$MinFreeGiB = 2,

    [switch]$CreateOutputRoot,

    [string]$ReportPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$script:ScenarioDir = Join-Path $PSScriptRoot 'scenarios'
$script:AnalysisDir = Join-Path $PSScriptRoot 'analysis'
$script:Results = [System.Collections.Generic.List[object]]::new()
$script:PythonExe = Join-Path $FridaHome 'python.exe'

# btsnoop timestamps count microseconds since year 0.
$script:BtsnoopEpochOffsetUs = 0x00DCDDB30F2F8000

# Frida must never be attached to these. Injecting the Java bridge into system
# processes aborted com.android.settings on this ROM.
$script:ProcessDenylist = @(
    'system_server',
    'com.android.bluetooth',
    'com.android.settings',
    'com.android.systemui',
    'com.xiaomi.bluetooth',
    'com.milink.service'
)

# This project's own Xposed module auto-connects to any device whose name
# contains "oppo" from inside com.android.bluetooth. If it is live during a
# capture it competes for the SPP socket and injects our own packets into the
# HCI log, which would turn our implementation into its own evidence.
$script:OwnModulePackages = @('moe.chenxy.oppopods')

function Add-Result {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][ValidateSet('PASS', 'FAIL', 'WARN', 'SKIP')][string]$Status,
        [string]$Detail = '',
        [switch]$NonCritical
    )
    $script:Results.Add([pscustomobject]@{
        Name     = $Name
        Status   = $Status
        Detail   = $Detail
        Critical = -not $NonCritical
    })
}

function Invoke-Adb {
    param([Parameter(Mandatory)][string[]]$AdbArgs)
    $output = & adb @AdbArgs 2>&1
    return ($output | Out-String).Trim()
}

<#
    Runs one command as root.

    adb joins its arguments and hands the result to the remote shell without
    re-quoting, so `adb shell su -c "a; b"` makes the remote shell run `su -c a`
    and then `b` as the shell user. That silently drops privileges and surfaces
    as a misleading permission-denied with scontext=u:r:shell:s0 in dmesg.

    Wrapping the command in single quotes here keeps it a single argument to su.
    Never pass compound commands; call this once per command.
#>
function Invoke-RootCommand {
    param([Parameter(Mandatory)][string]$Command)
    if ($Command -match '[;&|]') {
        throw "Compound root command rejected (plan section 7.2): $Command"
    }
    $escaped = $Command -replace "'", "'\''"
    $output = & adb shell "su -c '$escaped'" 2>&1
    return ($output | Out-String).Trim()
}

function Test-AdbDevice {
    try {
        $raw = Invoke-Adb @('devices', '-l')
    } catch {
        Add-Result -Name 'ADB 可执行' -Status FAIL -Detail 'adb 不在 PATH 上'
        return $false
    }
    $lines = $raw -split "`n" | Where-Object { $_ -match '\S' } | Select-Object -Skip 1
    $devices = @($lines | Where-Object { $_ -match '\s+device(\s|$)' })
    $unauthorized = @($lines | Where-Object { $_ -match 'unauthorized' })

    if ($unauthorized.Count -gt 0) {
        Add-Result -Name 'ADB 授权' -Status FAIL -Detail '设备处于 unauthorized，请在手机上确认授权（不要撤销全局授权）'
        return $false
    }
    if ($devices.Count -ne 1) {
        Add-Result -Name 'ADB 单设备' -Status FAIL -Detail "期望恰好 1 台 device，实际 $($devices.Count) 台"
        return $false
    }
    Add-Result -Name 'ADB 单设备' -Status PASS -Detail ($devices[0].Trim())
    return $true
}

function Test-DeviceIdentity {
    $model = Invoke-Adb @('shell', 'getprop', 'ro.product.model')
    $release = Invoke-Adb @('shell', 'getprop', 'ro.build.version.release')
    $sdk = Invoke-Adb @('shell', 'getprop', 'ro.build.version.sdk')
    $abi = Invoke-Adb @('shell', 'getprop', 'ro.product.cpu.abi')
    Add-Result -Name '设备标识' -Status PASS -Detail "$model / Android $release / API $sdk"

    if ($abi -match 'arm64-v8a') {
        Add-Result -Name 'ABI 为 arm64-v8a' -Status PASS -Detail $abi
    } else {
        Add-Result -Name 'ABI 为 arm64-v8a' -Status FAIL -Detail "实际 $abi，与 frida-server 二进制不匹配"
    }
    return [pscustomobject]@{ Model = $model; Release = $release; Sdk = $sdk; Abi = $abi }
}

function Test-Root {
    $id = Invoke-RootCommand 'id'
    if ($id -match 'uid=0') {
        $context = if ($id -match 'context=(\S+)') { $Matches[1] } else { 'unknown' }
        Add-Result -Name 'Root 可用' -Status PASS -Detail "uid=0, context=$context"
        return $true
    }
    Add-Result -Name 'Root 可用' -Status FAIL -Detail "su -c id 未返回 uid=0：$id（不要尝试切换 Root 实现）"
    return $false
}

function Test-Selinux {
    $enforce = Invoke-Adb @('shell', 'getenforce')
    if ($enforce -eq 'Enforcing') {
        Add-Result -Name 'SELinux Enforcing' -Status PASS -Detail $enforce
    } else {
        Add-Result -Name 'SELinux Enforcing' -Status FAIL -Detail "实际 $enforce；本计划禁止 setenforce 0，请恢复 Enforcing"
    }
}

function Test-HostFrida {
    $python = Join-Path $FridaHome 'python.exe'
    if (-not (Test-Path $python)) {
        Add-Result -Name 'PC Frida 环境' -Status FAIL -Detail "未找到 $python（conda 不在 PATH 上，必须使用绝对路径）"
        return $null
    }
    $version = (& $python -c "import frida; print(frida.__version__)" 2>&1 | Out-String).Trim()
    if ($version -eq $ExpectedFridaVersion) {
        Add-Result -Name 'PC Frida 版本' -Status PASS -Detail $version
    } else {
        Add-Result -Name 'PC Frida 版本' -Status FAIL -Detail "期望 $ExpectedFridaVersion，实际 $version"
    }
    return $version
}

function Test-DeviceFridaServer {
    $binary = "/data/local/tmp/frida-server-$ExpectedFridaVersion"
    $listing = Invoke-RootCommand "ls -la $binary"
    if ($listing -match 'No such file') {
        Add-Result -Name '手机 frida-server 存在' -Status FAIL -Detail "缺少 $binary（不要从非官方来源自动下载）"
        return
    }
    Add-Result -Name '手机 frida-server 存在' -Status PASS -Detail $binary

    $version = Invoke-RootCommand "$binary --version"
    if ($version -eq $ExpectedFridaVersion) {
        Add-Result -Name '手机 frida-server 版本' -Status PASS -Detail $version
    } else {
        Add-Result -Name '手机 frida-server 版本' -Status FAIL -Detail "期望 $ExpectedFridaVersion，实际 $version"
    }

    # argv[0] is the basename, not the full path, so match on that. The bracket
    # filter drops the kernel-thread style [frida-server-17] entry that KernelSU
    # also lists for the same server.
    $processName = Split-Path $binary -Leaf
    $running = Invoke-RootCommand 'ps -A -o PID,ARGS'
    $live = @($running -split "`n" | Where-Object { $_ -match [regex]::Escape($processName) -and $_ -notmatch '\[' })
    if ($live.Count -gt 0) {
        Add-Result -Name 'frida-server 运行中' -Status PASS -Detail "$($live.Count) 个实例，复用不重启"
    } else {
        Add-Result -Name 'frida-server 运行中' -Status FAIL -Detail "未运行，按计划 7.3 启动后重跑 preflight"
    }
}

function Test-FridaForward {
    $forwards = Invoke-Adb @('forward', '--list')
    if ($forwards -match "tcp:$FridaPort\s+tcp:$FridaPort") {
        Add-Result -Name "ADB forward tcp:$FridaPort" -Status PASS -Detail '已存在，复用'
    } else {
        Add-Result -Name "ADB forward tcp:$FridaPort" -Status FAIL -Detail "缺失，执行 adb forward tcp:$FridaPort tcp:$FridaPort"
    }
}

function Test-FridaEndpoint {
    param([string]$TargetPackage)
    $fridaPs = Join-Path $FridaHome 'Scripts\frida-ps.exe'
    if (-not (Test-Path $fridaPs)) {
        Add-Result -Name 'frida-ps 可用' -Status FAIL -Detail "未找到 $fridaPs"
        return
    }
    $apps = (& $fridaPs -H "127.0.0.1:$FridaPort" -ai 2>&1 | Out-String)
    if ($apps -match 'Failed to|unable to connect|Connection refused') {
        Add-Result -Name 'frida 端点可达' -Status FAIL -Detail '无法连接到 frida-server，检查 server 与 forward'
        return
    }
    Add-Result -Name 'frida 端点可达' -Status PASS -Detail "127.0.0.1:$FridaPort"

    if ($TargetPackage) {
        if ($apps -match [regex]::Escape($TargetPackage)) {
            Add-Result -Name '目标 App 可见' -Status PASS -Detail $TargetPackage
        } else {
            Add-Result -Name '目标 App 可见' -Status FAIL -Detail "$TargetPackage 未在 frida-ps 输出中（未安装或未启动）"
        }
    }
}

function Test-Tshark {
    if (-not (Test-Path $Tshark)) {
        Add-Result -Name 'TShark 可用' -Status FAIL -Detail "未找到 $Tshark"
        return
    }
    $version = (& $Tshark --version 2>&1 | Select-Object -First 1 | Out-String).Trim()
    Add-Result -Name 'TShark 可用' -Status PASS -Detail $version
}

function Test-OwnModuleAbsent {
    $packages = Invoke-Adb @('shell', 'pm', 'list', 'packages')
    $found = @($script:OwnModulePackages | Where-Object { $packages -match [regex]::Escape($_) })
    if ($found.Count -eq 0) {
        Add-Result -Name '本项目模块未安装' -Status PASS -Detail '无 RFCOMM 争用与自证据污染风险'
    } else {
        Add-Result -Name '本项目模块未安装' -Status FAIL -Detail "检测到 $($found -join ', ')：必须在 LSPosed 中对 com.android.bluetooth 停用并重启蓝牙进程，否则抓包会混入本项目自己的流量"
    }
}

function Get-BondedDevices {
    # Bonded lines look like: "  XX:XX:XX:XX:4B:1D [ DUAL ][ 0x240404 ] WH-1000XM4"
    # dumpsys redacts the first four octets even for root, which is fine here:
    # the check is by name, and redacted output must never be logged verbatim.
    $dump = Invoke-RootCommand 'dumpsys bluetooth_manager'
    $devices = @()
    $inSection = $false
    foreach ($line in ($dump -split "`n")) {
        if ($line -match 'Bonded devices:') { $inSection = $true; continue }
        if (-not $inSection) { continue }
        if ($line -match '^\s*$') { break }
        if ($line -match '^\s*(\S+)\s+\[\s*\S+\s*\]\[[^\]]+\]\s+(.+?)\s*$') {
            $devices += [pscustomobject]@{ Address = $Matches[1]; Name = $Matches[2] }
        } else { break }
    }
    return $devices
}

function Test-TargetPaired {
    param($Config)
    if (-not $Config) { return }
    $names = @()
    if ($Config.PSObject.Properties.Name -contains 'targetDeviceNames') {
        $names = @($Config.targetDeviceNames)
    }
    if ($names.Count -eq 0) {
        Add-Result -Name '目标耳机已配对' -Status SKIP -NonCritical -Detail '场景未声明 targetDeviceNames'
        return
    }

    $bonded = Get-BondedDevices
    $matched = @($bonded | Where-Object { $device = $_; $names | Where-Object { $device.Name -match [regex]::Escape($_) } })
    if ($matched.Count -gt 0) {
        Add-Result -Name '目标耳机已配对' -Status PASS -Detail (($matched | ForEach-Object { $_.Name }) -join ', ')
    } else {
        $available = ($bonded | ForEach-Object { $_.Name }) -join ', '
        Add-Result -Name '目标耳机已配对' -Status FAIL -Detail "未找到 $($names -join ' / ')；当前已配对：$available"
    }
}

function Test-SnoopEnabled {
    $dump = Invoke-Adb @('shell', 'dumpsys', 'bluetooth_manager')
    $setting = if ($dump -match 'sSnoopLogSettingAtEnable\s*=\s*(\S+)') { $Matches[1] } else { 'UNKNOWN' }

    if ($setting -eq 'DISABLED') {
        Add-Result -Name 'HCI snoop 已启用' -Status FAIL -Detail 'sSnoopLogSettingAtEnable = DISABLED；请在开发者选项启用蓝牙 HCI 信息收集后关闭再开启蓝牙'
    } elseif ($setting -eq 'UNKNOWN') {
        Add-Result -Name 'HCI snoop 已启用' -Status FAIL -Detail 'dumpsys 中未找到 sSnoopLogSettingAtEnable，无法确定性判定'
    } else {
        Add-Result -Name 'HCI snoop 已启用' -Status PASS -Detail "sSnoopLogSettingAtEnable = $setting"
    }

    # Read-only supporting evidence. The plan forbids writing these.
    # These properties read back empty as the shell user and only return a value
    # under root, so an empty snapshot means the read was wrong, not that the
    # property is unset.
    $props = [ordered]@{}
    foreach ($name in @('persist.bluetooth.btsnooplogmode', 'persist.bluetooth.btsnoopenable', 'persist.bluetooth.btsnoopdefaultmode')) {
        $props[$name] = Invoke-RootCommand "getprop $name"
    }
    $rendered = ($props.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ', '
    $empty = @($props.Values | Where-Object { [string]::IsNullOrWhiteSpace($_) }).Count
    if ($empty -gt 0) {
        Add-Result -Name 'snoop 属性快照' -Status WARN -NonCritical -Detail "$empty 项读取为空，属性读取需要 root：$rendered"
    } else {
        Add-Result -Name 'snoop 属性快照' -Status PASS -NonCritical -Detail $rendered
    }
    return [pscustomobject]@{ Setting = $setting; Properties = $props }
}

function Get-SnoopFiles {
    # Search bt*snoo* rather than *snoop*: with snoop disabled the stack still
    # rotates /data/misc/bluetooth/logs/btsnooz_hci.log, spelled with a z, which
    # an *snoop* pattern misses entirely.
    $found = @()
    foreach ($root in @('/data/misc/bluetooth', '/data/misc/bluedroid')) {
        $listing = Invoke-RootCommand "find $root -type f -iname bt*snoo*"
        if ($listing -match 'No such file|Permission denied') { continue }
        $found += @($listing -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ -match '\S' })
    }
    return $found
}

function Test-SnoopFiles {
    $files = Get-SnoopFiles
    if ($files.Count -eq 0) {
        Add-Result -Name 'HCI 日志文件' -Status FAIL -Detail '未找到任何 bt*snoo* 文件'
        return @()
    }

    $capture = @($files | Where-Object { (Split-Path $_ -Leaf) -notmatch '^btsnooz' })
    $ring = @($files | Where-Object { (Split-Path $_ -Leaf) -match '^btsnooz' })

    if ($ring.Count -gt 0) {
        Add-Result -Name 'btsnooz 环形缓冲已识别' -Status WARN -NonCritical -Detail "$($ring -join ', ')：带合法 btsnoop magic 但内容截断，禁止进入 derived/sanitized"
    }

    if ($capture.Count -gt 0) {
        $details = foreach ($file in $capture) {
            $stat = Invoke-RootCommand "stat -c %s:%Y:%i $file"
            "$file ($stat)"
        }
        Add-Result -Name '完整 snoop 日志存在' -Status PASS -Detail ($details -join '; ')
    } else {
        Add-Result -Name '完整 snoop 日志存在' -Status FAIL -Detail '只找到 btsnooz 环形缓冲，视为 snoop 未启用'
    }
    return $capture
}

function Test-OutputRoot {
    $parent = Split-Path $OutputRoot -Qualifier
    if (-not (Test-Path $parent)) {
        Add-Result -Name '输出根可用' -Status FAIL -Detail "驱动器 $parent 不存在"
        return
    }

    if (-not (Test-Path $OutputRoot)) {
        if ($CreateOutputRoot) {
            New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
            Add-Result -Name '输出根可用' -Status PASS -Detail "$OutputRoot（已创建）"
        } else {
            Add-Result -Name '输出根可用' -Status FAIL -Detail "$OutputRoot 不存在，使用 -CreateOutputRoot 创建"
            return
        }
    } else {
        Add-Result -Name '输出根可用' -Status PASS -Detail $OutputRoot
    }

    # Raw captures contain unsanitized MACs and link keys. They must never sit
    # inside a git work tree where they could be committed.
    $probe = (Resolve-Path $OutputRoot).Path
    $inGit = $false
    while ($probe) {
        if (Test-Path (Join-Path $probe '.git')) { $inGit = $true; break }
        $next = Split-Path $probe -Parent
        if ($next -eq $probe -or [string]::IsNullOrEmpty($next)) { break }
        $probe = $next
    }
    if ($inGit) {
        Add-Result -Name '输出根不在 Git 工作区' -Status FAIL -Detail "$OutputRoot 位于 Git 工作区内，原始材料可能被提交"
    } else {
        Add-Result -Name '输出根不在 Git 工作区' -Status PASS -Detail '原始材料与仓库隔离'
    }

    $drive = (Get-PSDrive -Name (Split-Path $OutputRoot -Qualifier).TrimEnd(':'))
    $freeGiB = [math]::Round($drive.Free / 1GB, 1)
    if ($freeGiB -ge $MinFreeGiB) {
        Add-Result -Name "磁盘空间 >= $MinFreeGiB GiB" -Status PASS -Detail "$freeGiB GiB 可用"
    } else {
        Add-Result -Name "磁盘空间 >= $MinFreeGiB GiB" -Status FAIL -Detail "仅 $freeGiB GiB 可用"
    }
}

function Get-ScenarioConfig {
    if (-not $Scenario) { return $null }
    $path = Join-Path $script:ScenarioDir "$Scenario.json"
    if (-not (Test-Path $path)) {
        Add-Result -Name '场景文件' -Status FAIL -Detail "未找到 $path"
        return $null
    }
    $config = Get-Content $path -Raw | ConvertFrom-Json
    Add-Result -Name '场景文件' -Status PASS -Detail "$Scenario（$($config.steps.Count) 步，重复 $($config.repeat) 轮）"
    return $config
}

function Test-ScenarioApp {
    param($Config)
    if (-not $Config) { return }

    $package = $Config.appPackage
    if ($script:ProcessDenylist -contains $package) {
        Add-Result -Name 'Hook 目标不在 denylist' -Status FAIL -Detail "$package 是系统进程，禁止注入"
        return
    }
    Add-Result -Name 'Hook 目标不在 denylist' -Status PASS -Detail $package

    $dump = Invoke-Adb @('shell', 'dumpsys', 'package', $package)
    if ($dump -notmatch 'versionName=') {
        Add-Result -Name '目标 App 已安装' -Status FAIL -Detail "$package 未安装"
        return
    }
    $versionName = if ($dump -match 'versionName=(\S+)') { $Matches[1] } else { 'unknown' }
    Add-Result -Name '目标 App 已安装' -Status PASS -Detail "$package $versionName"

    $expected = $Config.PSObject.Properties.Name -contains 'expectedAppVersion'
    if ($expected -and $Config.expectedAppVersion) {
        if ($versionName -eq $Config.expectedAppVersion) {
            Add-Result -Name '目标 App 版本匹配静态样本' -Status PASS -Detail $versionName
        } else {
            Add-Result -Name '目标 App 版本匹配静态样本' -Status FAIL -Detail "期望 $($Config.expectedAppVersion)，实际 $versionName；混淆名失效，只能使用通用 transport Hook"
        }
    }
}

function Write-Report {
    param([int]$FailCount, [int]$WarnCount)

    Write-Host ''
    Write-Host '================ PREFLIGHT ================' -ForegroundColor Cyan
    foreach ($result in $script:Results) {
        $color = switch ($result.Status) {
            'PASS' { 'Green' }
            'FAIL' { 'Red' }
            'WARN' { 'Yellow' }
            default { 'DarkGray' }
        }
        $tag = if ($result.Critical) { '' } else { ' (非关键)' }
        Write-Host ("[{0}] {1}{2}" -f $result.Status, $result.Name, $tag) -ForegroundColor $color
        if ($result.Detail) { Write-Host ("       {0}" -f $result.Detail) -ForegroundColor DarkGray }
    }
    Write-Host '===========================================' -ForegroundColor Cyan

    if ($FailCount -eq 0) {
        Write-Host "PREFLIGHT PASS（$WarnCount 项警告）" -ForegroundColor Green
    } else {
        Write-Host "PREFLIGHT FAIL：$FailCount 项关键检查未通过，不得进入采集阶段" -ForegroundColor Red
    }

    if ($ReportPath) {
        $payload = [pscustomobject]@{
            mode        = $Mode
            scenario    = $Scenario
            failCount   = $FailCount
            warnCount   = $WarnCount
            results     = $script:Results
        }
        $payload | ConvertTo-Json -Depth 6 | Set-Content -Path $ReportPath -Encoding utf8
        Write-Host "报告已写入 $ReportPath" -ForegroundColor DarkGray
    }
}

function Invoke-Preflight {
    Write-Host '运行 preflight（只读，不改变设备状态）...' -ForegroundColor Cyan

    $scenarioConfig = Get-ScenarioConfig

    if (-not (Test-AdbDevice)) {
        Write-Report -FailCount 1 -WarnCount 0
        return 1
    }

    Test-DeviceIdentity | Out-Null
    $rootOk = Test-Root
    Test-Selinux

    Test-HostFrida | Out-Null
    if ($rootOk) { Test-DeviceFridaServer }
    Test-FridaForward
    $targetPackage = if ($scenarioConfig) { $scenarioConfig.appPackage } else { $null }
    Test-FridaEndpoint -TargetPackage $targetPackage
    Test-Tshark

    Test-OwnModuleAbsent
    Test-ScenarioApp -Config $scenarioConfig
    if ($rootOk) { Test-TargetPaired -Config $scenarioConfig }

    Test-SnoopEnabled | Out-Null
    if ($rootOk) { Test-SnoopFiles | Out-Null }

    Test-OutputRoot

    $failCount = @($script:Results | Where-Object { $_.Status -eq 'FAIL' -and $_.Critical }).Count
    $warnCount = @($script:Results | Where-Object { $_.Status -eq 'WARN' }).Count
    Write-Report -FailCount $failCount -WarnCount $warnCount
    return [int]($failCount -gt 0)
}

# ---------------------------------------------------------------------------
# M1: HCI extraction
# ---------------------------------------------------------------------------

function Get-SessionDir {
    param([Parameter(Mandatory)][string]$Id)
    return Join-Path $OutputRoot $Id
}

function Get-HciInventory {
    <#
        Snapshot of every snoop file with the frozen attributes needed to tell
        later which files changed. This ROM rotates and timestamps its snoop
        files (btsnoop_hci_<YYMMDD>_<HHMMSS>.log), so a session can span several
        files and new ones can appear mid-window. Size deltas alone are not
        enough; new paths must be picked up too.
    #>
    $inventory = @()
    foreach ($file in (Get-SnoopFiles)) {
        $leaf = Split-Path $file -Leaf
        if ($leaf -match '^btsnooz') { continue }   # ring buffer, never evidence
        $stat = Invoke-RootCommand "stat -c %s:%Y:%i $file"
        $parts = $stat -split ':'
        if ($parts.Count -lt 3) { continue }
        $inventory += [pscustomobject]@{
            Path  = $file
            Size  = [long]$parts[0]
            Mtime = [long]$parts[1]
            Inode = [long]$parts[2]
        }
    }
    return $inventory
}

function Get-DeviceClock {
    $epoch = Invoke-Adb @('shell', 'date', '+%s')
    return [pscustomobject]@{
        DeviceEpoch = [long]$epoch
        HostEpoch   = [long][DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
        HostIso     = (Get-Date).ToString('o')
    }
}

function Export-HciFile {
    <#
        Freeze, pull, verify, clean up. The copy is made first so the hash is
        taken against a stable snapshot rather than a file the stack is still
        appending to. Only the temp copy is chmod'ed; the system log is never
        modified, and the exact temp path is removed afterwards.
    #>
    param(
        [Parameter(Mandatory)][string]$RemotePath,
        [Parameter(Mandatory)][string]$DestinationDir,
        [Parameter(Mandatory)][string]$Id
    )

    $leaf = Split-Path $RemotePath -Leaf
    $temp = "/data/local/tmp/$Id-$leaf"
    $local = Join-Path $DestinationDir $leaf

    Invoke-RootCommand "cp $RemotePath $temp" | Out-Null
    Invoke-RootCommand "chmod 644 $temp" | Out-Null
    $remoteHash = (Invoke-RootCommand "sha256sum $temp").Split(' ')[0]

    & adb pull $temp $local 2>&1 | Out-Null
    Invoke-RootCommand "rm $temp" | Out-Null

    if (-not (Test-Path $local)) {
        throw "adb pull 失败：$RemotePath"
    }
    $localHash = (Get-FileHash $local -Algorithm SHA256).Hash.ToLower()
    if ($localHash -ne $remoteHash) {
        throw "SHA-256 不一致：$RemotePath（remote=$remoteHash local=$localHash）"
    }

    return [pscustomobject]@{
        RemotePath = $RemotePath
        LocalPath  = $local
        Sha256     = $localHash
        SizeBytes  = (Get-Item $local).Length
    }
}

function Invoke-Inspector {
    param(
        [Parameter(Mandatory)][string]$CapturePath,
        [string]$JsonPath
    )
    $script = Join-Path $script:AnalysisDir 'inspect_btsnoop.py'
    $inspectorArgs = @($script, $CapturePath)
    if ($JsonPath) { $inspectorArgs += @('--json', $JsonPath) }
    $output = & $script:PythonExe @inspectorArgs 2>&1 | Out-String
    Write-Host $output
    if ($JsonPath -and (Test-Path $JsonPath)) {
        return Get-Content $JsonPath -Raw | ConvertFrom-Json
    }
    return $null
}

function Get-TargetFilter {
    param(
        [Parameter(Mandatory)][string]$CapturePath,
        [string]$Address
    )
    $script = Join-Path $script:AnalysisDir 'inspect_btsnoop.py'
    $filterArgs = @($script, $CapturePath, '--print-filter')
    if ($Address) { $filterArgs += @('--address', $Address) }
    $filter = (& $script:PythonExe @filterArgs 2>&1 | Out-String).Trim()
    if ([string]::IsNullOrWhiteSpace($filter)) { return $null }
    return $filter
}

function Get-CaptureClockSkew {
    <#
        Android writes LOCAL time into the btsnoop timestamp field, which the
        format specifies as UTC. On this device the stored times run exactly one
        UTC offset ahead (+8h measured), so filtering by true wall-clock matches
        nothing. Recover the offset empirically instead of assuming a timezone:
        compare the last captured frame against the device clock read at session
        end, then round to the nearest quarter hour since every real UTC offset
        is a multiple of 15 minutes.

        Returns $null when the residual is too large to trust, which happens if
        the link went quiet well before the session ended. Callers must then skip
        time trimming rather than silently emit a wrong window.
    #>
    param(
        [Parameter(Mandatory)][long]$CaptureLastUnixUs,
        [Parameter(Mandatory)][long]$SessionEndEpoch
    )
    if ($CaptureLastUnixUs -le 0) { return $null }
    $raw = [long]([math]::Floor($CaptureLastUnixUs / 1000000)) - $SessionEndEpoch
    $rounded = [long]([math]::Round($raw / 900.0) * 900)
    if ([math]::Abs($raw - $rounded) -gt 450) { return $null }
    if ([math]::Abs($rounded) -gt 50400) { return $null }   # beyond +/-14h is not a timezone
    return $rounded
}

function Invoke-TsharkAnalysis {
    param(
        [Parameter(Mandatory)][string]$CapturePath,
        [Parameter(Mandatory)][string]$DerivedDir,
        [string]$TargetFilter,
        [long]$WindowStartEpoch,
        [long]$WindowEndEpoch,
        [object]$ClockSkewSeconds
    )

    $leaf = [IO.Path]::GetFileNameWithoutExtension($CapturePath)
    $produced = @()

    # First pass: what protocols are actually present.
    $phs = Join-Path $DerivedDir "$leaf.protocol-hierarchy.txt"
    & $Tshark -r $CapturePath -q -z io,phs 2>&1 | Set-Content -Path $phs -Encoding utf8
    $produced += $phs

    # Second pass: the profile layers the plan cares about.
    $profileJson = Join-Path $DerivedDir "$leaf.rfcomm-att.json"
    & $Tshark -r $CapturePath -Y 'btrfcomm || btatt' -T json 2>&1 | Set-Content -Path $profileJson -Encoding utf8
    $produced += $profileJson

    if (-not $TargetFilter) {
        Write-Host '  跳过 target-only 输出：捕获窗口内没有可证明的连接句柄' -ForegroundColor Yellow
        return $produced
    }

    # Count handle matches before trimming so an empty result is explainable:
    # "the target never spoke" and "the window excluded it" need different fixes.
    $handleOnly = (& $Tshark -r $CapturePath -Y $TargetFilter -T fields -e frame.number 2>&1 | Measure-Object).Count

    # Time-window trim: BEGIN - 10s to END + 10s, per plan 8.2.
    # tshark parses frame.time literals in LOCAL time, so these must be rendered
    # as local time. Formatting them as UTC silently drops every frame on any
    # host that is not on UTC.
    $filter = $TargetFilter
    $trimmed = $false
    if ($WindowStartEpoch -gt 0 -and $WindowEndEpoch -gt 0 -and $null -ne $ClockSkewSeconds) {
        $skew = [long]$ClockSkewSeconds
        $from = [DateTimeOffset]::FromUnixTimeSeconds($WindowStartEpoch + $skew - 10).LocalDateTime.ToString('yyyy-MM-dd HH:mm:ss')
        $to = [DateTimeOffset]::FromUnixTimeSeconds($WindowEndEpoch + $skew + 10).LocalDateTime.ToString('yyyy-MM-dd HH:mm:ss')
        $filter = "($TargetFilter) && frame.time >= `"$from`" && frame.time <= `"$to`""
        $trimmed = $true
    } elseif ($null -eq $ClockSkewSeconds) {
        Write-Host '    未能可靠判定 btsnoop 时钟偏移，跳过时间窗裁剪，仅按连接句柄过滤' -ForegroundColor Yellow
    }

    # Always emit the handle-filtered file. Dropping the unmapped vendor handle
    # is what makes the capture dissect at all: with 0x0EDC present TShark
    # invents L2CAP CIDs and garbles names, and with it removed the same bytes
    # resolve cleanly into L2CAP/ATT. The window-trimmed file is written
    # separately so an empty window never discards the evidence.
    $targetOnly = Join-Path $DerivedDir "$leaf.target-only.pcapng"
    & $Tshark -r $CapturePath -Y $TargetFilter -w $targetOnly 2>&1 | Out-Null
    if (Test-Path $targetOnly) {
        Write-Host "  target-only：$targetOnly（$handleOnly 帧）" -ForegroundColor Green
        $produced += $targetOnly
    }

    if (-not $trimmed) { return $produced }

    $targetWindow = Join-Path $DerivedDir "$leaf.target-window.pcapng"
    & $Tshark -r $CapturePath -Y $filter -w $targetWindow 2>&1 | Out-Null
    if (Test-Path $targetWindow) {
        $frames = (& $Tshark -r $targetWindow -T fields -e frame.number 2>&1 | Measure-Object).Count
        $color = if ($frames -gt 0) { 'Green' } else { 'Yellow' }
        Write-Host "  target-window：$targetWindow（$frames 帧）" -ForegroundColor $color
        if ($frames -eq 0 -and $handleOnly -gt 0) {
            Write-Host '    该连接的流量全部落在会话时间窗之外，窗口未覆盖目标活动' -ForegroundColor Yellow
        }
        $produced += $targetWindow
    }
    return $produced
}

function Invoke-HciBegin {
    if (-not $SessionId) {
        $SessionId = '{0}-{1}' -f (Get-Date -Format 'yyyyMMdd-HHmmss'), ($Scenario ? $Scenario : 'adhoc')
    }
    $sessionDir = Get-SessionDir -Id $SessionId
    foreach ($sub in @('', 'raw', 'derived', 'sanitized')) {
        $path = if ($sub) { Join-Path $sessionDir $sub } else { $sessionDir }
        New-Item -ItemType Directory -Path $path -Force | Out-Null
    }

    $snoop = Test-SnoopEnabled
    if ($snoop.Setting -eq 'DISABLED' -or $snoop.Setting -eq 'UNKNOWN') {
        Write-Host "拒绝开始：sSnoopLogSettingAtEnable = $($snoop.Setting)" -ForegroundColor Red
        return 1
    }

    $clock = Get-DeviceClock
    $inventory = Get-HciInventory

    $session = [pscustomobject]@{
        sessionId          = $SessionId
        scenario           = $Scenario
        beginHostIso       = $clock.HostIso
        beginHostEpoch     = $clock.HostEpoch
        beginDeviceEpoch   = $clock.DeviceEpoch
        snoopSettingBegin  = $snoop.Setting
        snoopProperties    = $snoop.Properties
        preInventory       = $inventory
    }
    $session | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $sessionDir 'session.json') -Encoding utf8

    Write-Host "会话已开始：$SessionId" -ForegroundColor Green
    Write-Host "  目录          : $sessionDir"
    Write-Host "  snoop 状态    : $($snoop.Setting)"
    Write-Host "  快照文件数    : $($inventory.Count)"
    Write-Host "  主机/设备时钟 : $($clock.HostEpoch) / $($clock.DeviceEpoch)（差 $($clock.HostEpoch - $clock.DeviceEpoch) 秒）"
    Write-Host ''
    Write-Host "执行场景操作，完成后运行：" -ForegroundColor Cyan
    Write-Host "  .\capture.ps1 -Mode hci-end -SessionId $SessionId"
    return 0
}

function Invoke-HciEnd {
    if (-not $SessionId) { throw '-Mode hci-end 需要 -SessionId' }
    $sessionDir = Get-SessionDir -Id $SessionId
    $sessionFile = Join-Path $sessionDir 'session.json'
    if (-not (Test-Path $sessionFile)) { throw "未找到会话：$sessionFile" }

    $session = Get-Content $sessionFile -Raw | ConvertFrom-Json
    $snoop = Test-SnoopEnabled
    $clock = Get-DeviceClock
    $postInventory = Get-HciInventory

    $preByPath = @{}
    foreach ($item in @($session.preInventory)) { $preByPath[$item.Path] = $item }

    # A file is in scope if it appeared during the window or grew during it.
    $changed = @($postInventory | Where-Object {
        (-not $preByPath.ContainsKey($_.Path)) -or
        ($preByPath[$_.Path].Size -ne $_.Size) -or
        ($preByPath[$_.Path].Inode -ne $_.Inode)
    })

    Write-Host "会话结束：$SessionId" -ForegroundColor Cyan
    Write-Host "  窗口内变化的 snoop 文件：$($changed.Count) / $($postInventory.Count)"

    if ($snoop.Setting -eq 'DISABLED') {
        Write-Host '  警告：窗口结束时 snoop 已被关闭，该会话标记为 incomplete' -ForegroundColor Yellow
    }

    $rawDir = Join-Path $sessionDir 'raw'
    $derivedDir = Join-Path $sessionDir 'derived'
    $extracted = @()
    $inspections = @()

    foreach ($file in $changed) {
        Write-Host "  提取 $($file.Path)" -ForegroundColor DarkGray
        $result = Export-HciFile -RemotePath $file.Path -DestinationDir $rawDir -Id $SessionId
        Write-Host "    SHA-256 校验通过，$($result.SizeBytes) bytes" -ForegroundColor DarkGray
        $extracted += $result

        $jsonPath = Join-Path $derivedDir ((Split-Path $result.LocalPath -Leaf) + '.inspect.json')
        $inspection = Invoke-Inspector -CapturePath $result.LocalPath -JsonPath $jsonPath
        $inspections += $inspection

        $skew = $null
        if ($inspection -and $inspection.lastTimestampUnixUs) {
            $skew = Get-CaptureClockSkew -CaptureLastUnixUs ([long]$inspection.lastTimestampUnixUs) `
                -SessionEndEpoch ([long]$clock.DeviceEpoch)
            if ($null -ne $skew) {
                Write-Host "    btsnoop 时钟偏移：$skew 秒（$([math]::Round($skew / 3600.0, 2)) 小时）" -ForegroundColor DarkGray
            }
        }

        $filter = Get-TargetFilter -CapturePath $result.LocalPath
        Invoke-TsharkAnalysis -CapturePath $result.LocalPath -DerivedDir $derivedDir `
            -TargetFilter $filter `
            -WindowStartEpoch $session.beginDeviceEpoch `
            -WindowEndEpoch $clock.DeviceEpoch `
            -ClockSkewSeconds $skew | Out-Null
    }

    $incomplete = ($snoop.Setting -eq 'DISABLED') -or ($session.snoopSettingBegin -eq 'DISABLED')
    $hasConnections = @($inspections | Where-Object { $_ -and $_.connections.Count -gt 0 }).Count -gt 0

    $session | Add-Member -NotePropertyName endHostIso -NotePropertyValue $clock.HostIso -Force
    $session | Add-Member -NotePropertyName endDeviceEpoch -NotePropertyValue $clock.DeviceEpoch -Force
    $session | Add-Member -NotePropertyName snoopSettingEnd -NotePropertyValue $snoop.Setting -Force
    $session | Add-Member -NotePropertyName postInventory -NotePropertyValue $postInventory -Force
    $session | Add-Member -NotePropertyName extracted -NotePropertyValue $extracted -Force
    $session | Add-Member -NotePropertyName incomplete -NotePropertyValue $incomplete -Force
    $session | Add-Member -NotePropertyName hasProvenConnections -NotePropertyValue $hasConnections -Force
    $session | ConvertTo-Json -Depth 10 | Set-Content $sessionFile -Encoding utf8

    Write-Host ''
    if ($incomplete) {
        Write-Host '会话 incomplete：snoop 在窗口内被关闭，不得产出 fixture' -ForegroundColor Red
        return 1
    }
    if (-not $hasConnections) {
        Write-Host '会话无可证明连接：捕获窗口内没有连接建立事件，不能产出 target-only 证据' -ForegroundColor Yellow
        return 2
    }
    Write-Host "会话完成，产物位于 $sessionDir" -ForegroundColor Green
    return 0
}

switch ($Mode) {
    'preflight' { exit (Invoke-Preflight) }
    'hci-begin' { exit (Invoke-HciBegin) }
    'hci-end'   { exit (Invoke-HciEnd) }
}
