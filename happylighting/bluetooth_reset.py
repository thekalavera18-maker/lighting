from __future__ import annotations

import asyncio


async def reset_ble_device(address: str) -> bool:
    """Force-close the WinRT connection object for one specific BLE device.

    Faster than toggling the whole radio — targets only the stuck device so
    other BT peripherals are unaffected.  No admin rights required.
    """
    hex_addr = address.replace(":", "").replace("-", "")
    script = f"""
$ErrorActionPreference = "SilentlyContinue"
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asGen = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {{
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
}})[0]
function Await($Task, $Type) {{
    $m = $asGen.MakeGenericMethod($Type)
    $t = $m.Invoke($null, @($Task))
    $t.Wait(-1) | Out-Null; $t.Result
}}
[Windows.Devices.Bluetooth.BluetoothLEDevice,Windows.Devices.Bluetooth,ContentType=WindowsRuntime] | Out-Null
$addrInt = [Convert]::ToUInt64("{hex_addr}", 16)
$dev = Await ([Windows.Devices.Bluetooth.BluetoothLEDevice]::FromBluetoothAddressAsync($addrInt)) `
    ([Windows.Devices.Bluetooth.BluetoothLEDevice])
if ($dev) {{ $dev.Dispose() }}
Write-Output "OK"
"""
    try:
        proc = await asyncio.create_subprocess_exec(
            "powershell", "-NoProfile", "-NonInteractive", "-Command", script,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        stdout, _ = await asyncio.wait_for(proc.communicate(), timeout=10.0)
        return b"OK" in stdout
    except Exception:
        return False


# Uses Windows Runtime via PowerShell — no admin required.
# Toggles the Bluetooth radio off then on to clear stuck adapter state.
_PS_SCRIPT = r"""
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asGen = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and
    $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
})[0]
function Await($Task, $Type) {
    $m = $asGen.MakeGenericMethod($Type)
    $t = $m.Invoke($null, @($Task))
    $t.Wait(-1) | Out-Null
    $t.Result
}
[Windows.Devices.Radios.Radio,Windows.System.Threading,ContentType=WindowsRuntime] | Out-Null
$radios = Await ([Windows.Devices.Radios.Radio]::GetRadiosAsync()) `
    ([System.Collections.Generic.IReadOnlyList[Windows.Devices.Radios.Radio]])
$bt = $radios | Where-Object { $_.Kind -eq [Windows.Devices.Radios.RadioKind]::Bluetooth }
if (-not $bt) { Write-Error "No Bluetooth radio found"; exit 1 }
$bt | ForEach-Object {
    Await ($_.SetStateAsync([Windows.Devices.Radios.RadioState]::Off)) `
        ([Windows.Devices.Radios.RadioAccessStatus]) | Out-Null
}
Start-Sleep -Seconds 3
$bt | ForEach-Object {
    Await ($_.SetStateAsync([Windows.Devices.Radios.RadioState]::On)) `
        ([Windows.Devices.Radios.RadioAccessStatus]) | Out-Null
}
Write-Output "OK"
"""


async def reset_bluetooth_radio() -> bool:
    """Toggle the Windows Bluetooth radio off then on. Returns True on success."""
    try:
        proc = await asyncio.create_subprocess_exec(
            "powershell", "-NoProfile", "-NonInteractive", "-Command", _PS_SCRIPT,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        stdout, _stderr = await asyncio.wait_for(proc.communicate(), timeout=20.0)
        return b"OK" in stdout
    except Exception:
        return False
