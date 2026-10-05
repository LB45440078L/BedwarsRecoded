# botdrive.ps1 - run the MinecraftC WinBot headless, keep it connected, and feed
# it commands at intervals. Used to exercise the BedwarsRecoded plugin on a real
# Spigot 26.3 server.
#
#   powershell -File botdrive.ps1 -Name BotAlpha -Seconds 120 -Join:$true
#
# stdin is kept open via redirected pipes (a file redirect hits EOF and the bot
# disconnects immediately). MSYS2's ucrt64 bin must be on PATH for zlib1.dll.

param(
    [Parameter(Mandatory = $true)][string]$Name,
    [int]$Seconds = 120,
    [string]$Host_ = "127.0.0.1",
    [int]$Port = 25565,
    [string]$Log = "",
    [string[]]$Commands = @("/bw join")
)

$ErrorActionPreference = "Stop"
if (-not $Log) { $Log = "C:\Users\thevi\Desktop\BedwarsTest\bot_$Name.log" }

$env:PATH = "C:\msys64\ucrt64\bin;" + $env:PATH

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = "C:\Users\thevi\projects\MinecraftC_WinBot\build\bot.exe"
$psi.Arguments = "--headless --host $Host_ --port $Port --name $Name"
$psi.WorkingDirectory = "C:\Users\thevi\projects\MinecraftC_WinBot"
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.CreateNoWindow = $true

Set-Content -Path $Log -Value "=== $Name start $(Get-Date -Format o) ==="
$proc = [System.Diagnostics.Process]::Start($psi)
$outTask = $proc.StandardOutput.ReadToEndAsync()
$errTask = $proc.StandardError.ReadToEndAsync()

# Give the login/configuration handshake time to finish before acting.
Start-Sleep -Seconds 6
foreach ($cmd in $Commands) {
    try {
        $proc.StandardInput.WriteLine($cmd)
        $proc.StandardInput.Flush()
        Add-Content -Path $Log -Value "SENT: $cmd"
    } catch {
        Add-Content -Path $Log -Value "SEND-FAILED: $cmd ($_)"
    }
    Start-Sleep -Seconds 3
}

# Stay connected so the match can run, then leave cleanly.
$remaining = $Seconds - 6 - (3 * $Commands.Count)
if ($remaining -gt 0) { Start-Sleep -Seconds $remaining }

try { $proc.StandardInput.WriteLine("pos"); $proc.StandardInput.WriteLine("quit"); $proc.StandardInput.Flush() } catch { }
if (-not $proc.WaitForExit(20000)) { $proc.Kill() }

$out = ""
$err = ""
try { $out = $outTask.GetAwaiter().GetResult() } catch { }
try { $err = $errTask.GetAwaiter().GetResult() } catch { }
Add-Content -Path $Log -Value $out
if ($err.Trim()) { Add-Content -Path $Log -Value ("STDERR: " + $err) }
Add-Content -Path $Log -Value "=== $Name done (exit $($proc.ExitCode)) ==="
