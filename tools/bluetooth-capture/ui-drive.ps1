<#
.SYNOPSIS
    Whitelisted UI driver for guided capture steps.

.DESCRIPTION
    Locates controls by visible text plus resource-id from the UIAutomator
    hierarchy and taps the centre of the resolved bounds. Coordinates are always
    derived from a fresh dump, never hardcoded, so a layout change makes a step
    fail loudly instead of tapping something else.

    Every tap is checked against a denylist first. Destructive entries are
    refused even when a caller asks for them by name, and the driver aborts the
    whole run if a forbidden term is present anywhere on the current screen.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Package,

    # Each action is "label" or "label=resource-id-suffix" for disambiguation.
    [Parameter(Mandatory)][string[]]$Actions,

    [int]$SettleSeconds = 8,

    [string]$DumpDir = "$env:TEMP\ui-drive"
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# Refused unconditionally. Tapping any of these is destructive or takes the
# session somewhere the capture plan forbids.
$script:Denylist = @(
    '固件升级', '固件更新', '恢复出厂设置', '重置', '解除配对', '删除设备',
    '查找设备', '查找耳机', '注销', '退出登录', '诊断', '上传日志'
)

# Not destructive to the device, but each of these ends the connection being
# captured. "设备管理" reads like a settings page and is not: in HeyMelody it is
# the multi-device switcher, and tapping it pops a confirmation to hand the
# headset to another phone, which would drop the capture mid-session.
$script:AvoidList = @('断开连接', '设备管理', '连接新设备')

# Any dialog carrying one of these must be dismissed, never confirmed.
$script:DismissOnSight = @('连接新设备')

New-Item -ItemType Directory -Path $DumpDir -Force | Out-Null

function Get-Hierarchy {
    param([string]$Tag)
    $remote = '/sdcard/ui-drive.xml'
    adb shell uiautomator dump $remote | Out-Null
    $local = Join-Path $DumpDir "$Tag.xml"
    adb pull $remote $local 2>&1 | Out-Null
    adb shell rm $remote | Out-Null
    return Get-Content $local -Raw
}

function Assert-NoForbiddenTerms {
    param([string]$Xml, [string]$Tag)
    $hits = @($script:Denylist | Where-Object { $Xml -match [regex]::Escape($_) })
    if ($hits.Count -gt 0) {
        throw "当前界面出现危险入口（$($hits -join ', ')），按计划 3.3 立即停止 UI 自动化。dump=$Tag"
    }
    $dialogs = @($script:DismissOnSight | Where-Object { $Xml -match [regex]::Escape($_) })
    if ($dialogs.Count -gt 0) {
        throw "当前界面是会中断采集的确认框（$($dialogs -join ', ')），停止并人工处置。dump=$Tag"
    }
}

function Find-Node {
    param([string]$Xml, [string]$Label, [string]$ResourceIdSuffix)

    if ($script:Denylist -contains $Label -or $script:AvoidList -contains $Label) {
        throw "拒绝点击受限控件：$Label"
    }

    $pattern = "<node[^>]*text=`"$([regex]::Escape($Label))`"[^>]*/?>"
    $matches = [regex]::Matches($Xml, $pattern)
    if ($matches.Count -eq 0) {
        throw "未找到控件：$Label"
    }

    $candidates = @()
    foreach ($m in $matches) {
        $node = $m.Value
        $rid = if ($node -match 'resource-id="([^"]*)"') { $Matches[1] } else { '' }
        if ($ResourceIdSuffix -and -not $rid.EndsWith($ResourceIdSuffix)) { continue }
        $clickable = ($node -match 'clickable="true"')
        if ($node -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
            $candidates += [pscustomobject]@{
                ResourceId = $rid
                Clickable = $clickable
                Left = [int]$Matches[1]
                Right = [int]$Matches[3]
                X = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
                Y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
            }
        }
    }

    if ($candidates.Count -eq 0) { throw "控件 $Label 没有可用 bounds" }
    if ($candidates.Count -gt 1) {
        throw "控件 $Label 不唯一（$($candidates.Count) 个），按计划 13 节回退 guided 模式"
    }

    $label_ = $candidates[0]
    if ($label_.Clickable) { return $label_ }

    # The visible caption is often a non-clickable TextView sitting under the
    # real control: on this app the ANC modes label a sibling ImageView. Tapping
    # the caption silently does nothing, so resolve to the clickable node that
    # shares this label's horizontal span and lies nearest to it vertically.
    $clickable = @()
    foreach ($m in [regex]::Matches($Xml, '<node[^>]*clickable="true"[^>]*/?>')) {
        if ($m.Value -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
            $clickable += [pscustomobject]@{
                Left = [int]$Matches[1]; Top = [int]$Matches[2]
                Right = [int]$Matches[3]; Bottom = [int]$Matches[4]
                ResourceId = if ($m.Value -match 'resource-id="([^"]*)"') { $Matches[1] } else { '' }
            }
        }
    }

    $best = $null
    $bestScore = [double]::MaxValue
    foreach ($node in $clickable) {
        $overlap = [Math]::Min($node.Right, $label_.Right) - [Math]::Max($node.Left, $label_.Left)
        if ($overlap -le 0) { continue }
        $centerY = ($node.Top + $node.Bottom) / 2
        $distance = [Math]::Abs($centerY - $label_.Y)
        if ($distance -lt $bestScore) { $bestScore = $distance; $best = $node }
    }

    if ($null -eq $best) { throw "控件 $Label 附近没有可点击节点" }
    return [pscustomobject]@{
        ResourceId = "$($label_.ResourceId) -> $($best.ResourceId)"
        Clickable = $true
        Left = $best.Left; Right = $best.Right
        X = [int](($best.Left + $best.Right) / 2)
        Y = [int](($best.Top + $best.Bottom) / 2)
    }
}

$results = @()
$stepIndex = 0

foreach ($action in $Actions) {
    $stepIndex++
    $label, $ridSuffix = $action -split '=', 2
    $tag = "step$stepIndex-$label"

    $xml = Get-Hierarchy -Tag "$tag-before"
    Assert-NoForbiddenTerms -Xml $xml -Tag "$tag-before"

    $node = Find-Node -Xml $xml -Label $label -ResourceIdSuffix $ridSuffix
    Write-Host "[$stepIndex] 点击 $label @ ($($node.X),$($node.Y)) rid=$($node.ResourceId)" -ForegroundColor Cyan

    adb shell log -t BT_CAPTURE "MARK $label BEGIN" | Out-Null
    adb shell input tap $node.X $node.Y | Out-Null
    Start-Sleep -Seconds $SettleSeconds
    adb shell log -t BT_CAPTURE "MARK $label END" | Out-Null

    $after = Get-Hierarchy -Tag "$tag-after"
    $results += [pscustomobject]@{
        Step = $stepIndex
        Label = $label
        X = $node.X
        Y = $node.Y
        ResourceId = $node.ResourceId
    }
}

Write-Host ''
Write-Host "已执行 $($results.Count) 步，hierarchy dump 位于 $DumpDir" -ForegroundColor Green
$results | Format-Table -AutoSize
