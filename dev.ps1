# Coding Agent 本机启停管理
#
#   dev.ps1 start      起 MySQL + 后端 + 前端
#   dev.ps1 stop       全停
#   dev.ps1 restart    重启
#   dev.ps1 status     看谁在跑（含页面地址）
#   dev.ps1 open       打开前端页面
#   dev.ps1 logs       跟某个服务的日志（需 -Service mysql|backend|frontend）
#
# 也能直接跑 dev.bat，等价于 dev.ps1 status。
#
# 设计取舍：停服务按端口找进程杀，而不是只杀启动时记下的 PID。
# 因为 mvn / npm 是批处理包装，真正干活的是它的子进程 java / node，
# 只杀父进程会留下孤儿占着端口，下次 start 就报 Address already in use。
#
# 编码注意：本文件必须存成 UTF-8 with BOM。Windows PowerShell 5.1 对无 BOM
# 文件按 GBK 解码，中文会吃掉引号导致整个脚本解析失败。

param(
    [Parameter(Position = 0)]
    [ValidateSet("start", "stop", "restart", "status", "logs", "open")]
    [string]$Command = "status",

    [ValidateSet("mysql", "backend", "frontend", "all")]
    [string]$Service = "all"
)

$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot
$RunDir = Join-Path $Root ".run"
$LogDir = Join-Path $RunDir "logs"

# 三个服务的定义。端口是身份 —— status 和 stop 都靠它认人。
$Services = @(
    @{ Name = "mysql"; Port = 3306; Desc = "MySQL 8.4.9 portable" }
    @{ Name = "backend"; Port = 8280; Desc = "Spring Boot (dev profile)" }
    @{ Name = "frontend"; Port = 5173; Desc = "Vite dev server" }
)

function Write-Info([string]$Msg) { Write-Host "  $Msg" -ForegroundColor Gray }
function Write-Ok([string]$Msg) { Write-Host "  $Msg" -ForegroundColor Green }
function Write-Warn([string]$Msg) { Write-Host "  $Msg" -ForegroundColor Yellow }
function Write-Bad([string]$Msg) { Write-Host "  $Msg" -ForegroundColor Red }
function Write-Title([string]$Msg) { Write-Host ""; Write-Host $Msg -ForegroundColor Cyan }

# ---------- 端口 -> 进程 ----------

function Get-PortProcess([int]$Port) {
    $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $conn) { return $null }
    return Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue
}

function Stop-ByPort([int]$Port, [string]$Name) {
    $proc = Get-PortProcess $Port
    if (-not $proc) {
        Write-Info "[$Name] not running"
        return
    }
    # 先杀子进程再杀自己：mvn -> java、npm -> node，只杀父进程会留孤儿占端口
    Get-CimInstance Win32_Process -Filter "ParentProcessId=$($proc.Id)" -ErrorAction SilentlyContinue | ForEach-Object {
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        Write-Info "[$Name] child $($_.ProcessId) ($($_.Name)) stopped"
    }
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    Write-Ok "[$Name] stopped (pid $($proc.Id), port $Port)"
}

# ---------- 读 .env ----------
# 后端要 CODING_AGENT_SECRET_KEY（缺失会拒绝启动），从 .env 里取，
# 免得每次 start 都手敲一遍环境变量。

function Get-EnvMap {
    $map = @{}
    $envFile = Join-Path $Root ".env"
    if (Test-Path $envFile) {
        Get-Content $envFile | ForEach-Object {
            $line = $_.Trim()
            if ($line -and -not $line.StartsWith("#")) {
                $i = $line.IndexOf("=")
                if ($i -gt 0) {
                    $map[$line.Substring(0, $i)] = $line.Substring($i + 1)
                }
            }
        }
    }
    return $map
}

# ---------- 启动 ----------

function Start-Service-Process([string]$Name) {
    $cfg = $Services | Where-Object { $_.Name -eq $Name }
    if (-not $cfg) { Write-Bad "unknown service: $Name"; return }

    if (Get-PortProcess $cfg.Port) {
        Write-Info "[$Name] already running (port $($cfg.Port))"
        return
    }

    $logOut = Join-Path $LogDir "$Name.out.log"
    $logErr = Join-Path $LogDir "$Name.err.log"
    $envMap = Get-EnvMap

    if ($Name -eq "mysql") {
        $mysqld = Join-Path $Root "mysql\mysql-8.4.9-winx64\bin\mysqld.exe"
        $ini = Join-Path $Root "mysql\my.ini"
        if (-not (Test-Path $mysqld)) { Write-Bad "[mysql] not found: $mysqld"; return }
        # --defaults-file 的值必须整体加引号：项目路径里有空格
        $args = @("--defaults-file=`"$ini`"")
        Start-Process -FilePath $mysqld -ArgumentList $args -WindowStyle Hidden -RedirectStandardOutput $logOut -RedirectStandardError $logErr
    }
    elseif ($Name -eq "backend") {
        # .env 里的值要带进子进程，否则 SecretCipher 直接拒绝启动
        foreach ($k in $envMap.Keys) {
            if ($envMap[$k]) { Set-Item -Path "Env:$k" -Value $envMap[$k] }
        }
        if (-not $env:CODING_AGENT_SECRET_KEY) {
            Write-Bad "[backend] CODING_AGENT_SECRET_KEY missing in .env, service will refuse to start"
            return
        }
        $args = @("spring-boot:run", "-Dspring-boot.run.profiles=dev", "-Dspring-boot.run.arguments=--server.port=8280")
        Start-Process -FilePath "mvn.cmd" -ArgumentList $args -WorkingDirectory (Join-Path $Root "backend") -WindowStyle Hidden -RedirectStandardOutput $logOut -RedirectStandardError $logErr
    }
    elseif ($Name -eq "frontend") {
        $args = @("run", "dev")
        Start-Process -FilePath "npm.cmd" -ArgumentList $args -WorkingDirectory (Join-Path $Root "frontend") -WindowStyle Hidden -RedirectStandardOutput $logOut -RedirectStandardError $logErr
    }
    Write-Ok "[$Name] starting... (log .run/logs/$Name.out.log)"
}

function Wait-ForPort([int]$Port, [string]$Name, [int]$TimeoutSec = 60) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Get-PortProcess $Port) { return $true }
        Start-Sleep -Milliseconds 500
    }
    Write-Bad "[$Name] port $Port not up within ${TimeoutSec}s, check .run/logs/$Name.err.log"
    return $false
}

# ---------- 状态 ----------

function Show-Status {
    Write-Title "Coding Agent services"
    foreach ($cfg in $Services) {
        $proc = Get-PortProcess $cfg.Port
        if ($proc) {
            $mem = [math]::Round($proc.WorkingSet64 / 1MB, 0)
            Write-Host ("  {0,-9}" -f $cfg.Name) -NoNewline
            Write-Ok ("running  port {0,-6} pid {1,-7} {2}MB  {3}" -f $cfg.Port, $proc.Id, $mem, $cfg.Desc)
        }
        else {
            Write-Host ("  {0,-9}" -f $cfg.Name) -NoNewline
            Write-Bad ("stopped  port {0,-6}                          {1}" -f $cfg.Port, $cfg.Desc)
        }
    }
    Write-Host ""
    Show-Urls
}

# 页面地址。跑了这么久的服务，最后要的就是这个链接——所以 start / status 都打出来，
# 免得还要自己拼 http://localhost:5173。open 命令直接用它拉起浏览器。
function Show-Urls {
    $frontend = "http://localhost:5173"
    $backend = "http://localhost:8280"

    Write-Host "  Pages" -ForegroundColor Cyan
    if (Get-PortProcess 5173) {
        Write-Host "    frontend  " -NoNewline
        Write-Ok $frontend
    }
    else {
        Write-Host "    frontend  " -NoNewline
        Write-Bad "$frontend  (stopped)"
    }
    if (Get-PortProcess 8280) {
        Write-Host "    backend   " -NoNewline
        Write-Ok "$backend/api"
    }
    else {
        Write-Host "    backend   " -NoNewline
        Write-Bad "$backend/api  (stopped)"
    }
    Write-Host "    login     " -NoNewline
    Write-Host "  admin / admin123" -ForegroundColor Gray
    Write-Host ""
}

# ---------- 主流程 ----------

if (-not (Test-Path $RunDir)) { New-Item -ItemType Directory -Path $RunDir | Out-Null }
if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir | Out-Null }

$targets = $Services.Name
if ($Service -ne "all") { $targets = @($Service) }

if ($Command -eq "start") {
    Write-Title "Starting services"
    # 顺序有依赖：后端连不上 MySQL 会起不来，前端代理不到后端会白屏
    foreach ($name in $targets) {
        Start-Service-Process $name
        if ($name -eq "mysql") { Wait-ForPort 3306 "mysql" | Out-Null }
        if ($name -eq "backend") { Wait-ForPort 8280 "backend" | Out-Null }
        if ($name -eq "frontend") { Wait-ForPort 5173 "frontend" | Out-Null }
    }
    Show-Status
}
elseif ($Command -eq "stop") {
    Write-Title "Stopping services"
    # 反序停：先断前端连接，再停后端，最后关数据库
    [array]::Reverse($targets)
    foreach ($name in $targets) {
        $cfg = $Services | Where-Object { $_.Name -eq $name }
        Stop-ByPort $cfg.Port $name
    }
    Show-Status
}
elseif ($Command -eq "restart") {
    & $PSCommandPath stop -Service $Service
    Start-Sleep -Seconds 1
    & $PSCommandPath start -Service $Service
}
elseif ($Command -eq "status") {
    Show-Status
}
elseif ($Command -eq "open") {
    if (-not (Get-PortProcess 5173)) {
        Write-Warn "frontend is not running, run:  dev.bat start"
        exit 1
    }
    Write-Ok "opening http://localhost:5173"
    Start-Process "http://localhost:5173"
}
elseif ($Command -eq "logs") {
    if ($Service -eq "all") {
        Write-Warn "specify -Service mysql|backend|frontend"
        exit 1
    }
    $logOut = Join-Path $LogDir "$Service.out.log"
    if (Test-Path $logOut) {
        Write-Title "$Service log (Ctrl+C to exit)"
        Get-Content $logOut -Wait -Tail 50
    }
    else {
        Write-Warn "no log for $Service yet, run start first"
    }
}
