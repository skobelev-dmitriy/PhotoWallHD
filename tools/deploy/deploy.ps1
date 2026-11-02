<#
  Один раз «забрать с GitHub, собрать и выкатить на проектор по ADB».
  Запуск:  powershell -ExecutionPolicy Bypass -File tools\deploy\deploy.ps1 [-Device 192.168.1.9:5555] [-Branch имя] [-NoPull] [-Release]
  Коды выхода: 0 — готово; 1 — ошибка (git/сборка/установка); 2 — проектор недоступен (можно повторить позже).
  Требуется: git, Android Studio с SDK (adb) и JDK, проектор с включённой отладкой по сети.
#>
param(
    [string]$Branch = "ccr-9574af34-ocqfdd",
    [string]$Device = "",
    [switch]$NoPull,
    [switch]$Release
)

# Ошибки нативных команд проверяем сами по $LASTEXITCODE, поэтому Continue.
$ErrorActionPreference = "Continue"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $root

function Step($m) { Write-Host ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $m) -ForegroundColor Cyan }
function Fail($m, $code = 1) { Write-Host ("[{0}] ОШИБКА: {1}" -f (Get-Date -Format "HH:mm:ss"), $m) -ForegroundColor Red; exit $code }

# --- adb: из PATH, иначе из Android SDK ---
$adb = $null
$cmd = Get-Command adb -ErrorAction SilentlyContinue
if ($cmd) { $adb = $cmd.Source }
foreach ($sdk in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA "Android\Sdk"))) {
    if (-not $adb -and $sdk) {
        $candidate = Join-Path $sdk "platform-tools\adb.exe"
        if (Test-Path $candidate) { $adb = $candidate }
    }
}
if (-not $adb) { Fail "adb не найден. Установите Android SDK Platform-Tools (Android Studio -> SDK Manager) или добавьте adb в PATH." }

# --- git: забрать ветку ---
if (-not $NoPull) {
    Step "git fetch origin $Branch"
    git fetch origin $Branch
    if ($LASTEXITCODE -ne 0) { Fail "git fetch не удался (нет сети или доступа к GitHub)." }
    $current = (git rev-parse --abbrev-ref HEAD).Trim()
    if ($current -ne $Branch) {
        Step "переключаюсь на ветку $Branch"
        git checkout $Branch
        if ($LASTEXITCODE -ne 0) { Fail "не удалось переключиться на $Branch." }
    }
    $dirty = git status --porcelain --untracked-files=no
    if ($dirty) { Fail "в рабочей папке есть незакоммиченные изменения; закоммитьте или уберите их (git stash), чтобы не потерять." }
    git merge --ff-only "origin/$Branch"
    if ($LASTEXITCODE -ne 0) { Fail "ветку нельзя обновить простым fast-forward (локальные коммиты расходятся с GitHub)." }
}
$sha = (git rev-parse --short HEAD).Trim()
$subject = (git log -1 --format=%s).Trim()
Step "версия $sha — $subject"

# --- проектор ---
if ($Device) {
    Step "adb connect $Device"
    & $adb connect $Device | Out-Host
    $env:ANDROID_SERIAL = $Device   # и adb, и Gradle ставят именно на него
}
$devices = @(& $adb devices | Select-String -Pattern "\tdevice$")
if ($devices.Count -eq 0) {
    Fail "нет подключённого устройства. Включите проектор, проверьте отладку по сети и адрес (-Device ip:5555); смотрите вывод 'adb devices'." 2
}
if ($devices.Count -gt 1 -and -not $Device) {
    Fail "подключено несколько устройств: укажите проектор параметром -Device ip:5555." 2
}

# --- сборка и установка (Gradle сам ставит APK через adb) ---
$task = if ($Release) { "installRelease" } else { "installDebug" }
Step "gradlew $task"
& (Join-Path $root "gradlew.bat") $task --console=plain
if ($LASTEXITCODE -ne 0) { Fail "сборка или установка не удалась (см. вывод выше)." }

# --- разрешения и запуск (повторные вызовы безопасны) ---
Step "выдаю разрешения и запускаю приложение"
& $adb shell pm grant app.photowall android.permission.RECORD_AUDIO 2>$null | Out-Null
& $adb shell appops set app.photowall SYSTEM_ALERT_WINDOW allow 2>$null | Out-Null
& $adb shell am force-stop app.photowall | Out-Null
& $adb shell am start -n app.photowall/.MainActivity | Out-Null
if ($LASTEXITCODE -ne 0) { Fail "приложение установилось, но не запустилось (am start)." }

Write-Host ("[{0}] ГОТОВО: {1} выкачена на проектор." -f (Get-Date -Format "HH:mm:ss"), $sha) -ForegroundColor Green
exit 0
