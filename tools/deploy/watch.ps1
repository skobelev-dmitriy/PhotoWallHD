<#
  Следит за веткой на GitHub и сам выкатывает каждый новый коммит на проектор (см. deploy.ps1).
  Запуск:  tools\deploy\watch.cmd [-Device 192.168.1.9:5555] [-Interval 20]
  Оставьте окно открытым (можно во вкладке Terminal в Android Studio). Остановка — Ctrl+C.
  Каждые -Interval секунд делается дешёвый запрос `git ls-remote` (ничего не скачивается, пока нет нового коммита).
#>
param(
    [string]$Branch = "ccr-9574af34-ocqfdd",
    [string]$Device = "",
    [int]$Interval = 20
)

$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $root
$stateFile = Join-Path $PSScriptRoot ".last-deployed"
$last = ""
if (Test-Path $stateFile) { $last = (Get-Content $stateFile -Raw).Trim() }
$failed = ""   # коммит, на котором сборка упала: не повторяем, пока не придёт новый

function Beep($ok) {
    try { if ($ok) { [System.Media.SystemSounds]::Asterisk.Play() } else { [System.Media.SystemSounds]::Hand.Play() } } catch { }
}

Write-Host "Слежу за origin/$Branch каждые $Interval с. Ctrl+C — остановить." -ForegroundColor Cyan
if ($last) { Write-Host ("Последняя выкаченная версия: {0}" -f $last.Substring(0, [Math]::Min(7, $last.Length))) }

while ($true) {
    try {
        $line = git ls-remote origin "refs/heads/$Branch" 2>$null
        $remote = ""
        if ($line) { $remote = (($line | Select-Object -First 1) -split "\s+")[0] }

        if ($remote -and $remote -ne $last -and $remote -ne $failed) {
            Write-Host ("`n[{0}] Новый коммит {1}: выкатываю…" -f (Get-Date -Format "HH:mm:ss"), $remote.Substring(0, 7)) -ForegroundColor Yellow
            $deployArgs = @("-ExecutionPolicy", "Bypass", "-File", (Join-Path $PSScriptRoot "deploy.ps1"), "-Branch", $Branch)
            if ($Device) { $deployArgs += @("-Device", $Device) }
            & powershell @deployArgs
            $code = $LASTEXITCODE
            if ($code -eq 0) {
                $last = $remote
                Set-Content -Path $stateFile -Value $last
                Beep $true
            } elseif ($code -eq 2) {
                Write-Host "Проектор недоступен; попробую снова через минуту." -ForegroundColor DarkYellow
                Start-Sleep -Seconds 60
            } else {
                $failed = $remote
                Write-Host "Выкатка не удалась. Повтор только после нового коммита (или перезапустите скрипт)." -ForegroundColor Red
                Beep $false
            }
        }
    } catch {
        Write-Host ("Ошибка наблюдателя: {0}" -f $_) -ForegroundColor Red
    }
    Start-Sleep -Seconds $Interval
}
