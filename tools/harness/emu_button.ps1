# Invoke one button of the PICO emulator's own toolbar (UI Automation InvokePattern), e.g. home_button,
# camera_reset_button. Pinned by AVD + adb serial like emu_mode.ps1. Posted clicks do not reach these buttons.
param(
    [Parameter(Mandatory = $true)][string]$Name,
    [string]$Avd = "PICO_6_1_Factory3",
    [string]$Serial = "emulator-5564"
)
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
$AE = [System.Windows.Automation.AutomationElement]
$TS = [System.Windows.Automation.TreeScope]
$proc = Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'qemu-system-x86_64.exe' -and $_.CommandLine -match ("-avd " + [regex]::Escape($Avd) + " ") }
if (-not $proc) { Write-Output "ERROR: no emulator for $Avd"; exit 2 }
$avdName = (adb -s $Serial emu avd name | Select-Object -First 1).Trim()
if ($avdName -ne $Avd) { Write-Output "ERROR: $Serial runs '$avdName', expected '$Avd'"; exit 2 }
$pidCond = New-Object System.Windows.Automation.PropertyCondition($AE::ProcessIdProperty, [int]$proc.ProcessId)
$c = New-Object System.Windows.Automation.AndCondition($pidCond, (New-Object System.Windows.Automation.PropertyCondition($AE::NameProperty, $Name)))
$el = $AE::RootElement.FindFirst($TS::Descendants, $c)
if (-not $el) { Write-Output "ERROR: no element '$Name'"; exit 3 }
$el.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
Write-Output "invoked $Name (pid $($proc.ProcessId))"
