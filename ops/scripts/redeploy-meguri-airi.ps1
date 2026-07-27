$ErrorActionPreference = 'Stop'

# Rebuild and redeploy the Java staging core, then rebuild and restart AIRI.
# This intentionally keeps the previous Java container as a stopped rollback target.

$meguriRoot = 'D:\program\meguri-pet'
$airiRoot = 'D:\program\airi-meguri'
$docker = 'C:\Users\IzumiShizuki\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe'
$dockerHost = 'tcp://111.228.35.186:2376'
$dockerCertPath = 'C:\Users\IzumiShizuki\.docker\cert_02'
$activeName = 'meguri-staging-core-1'
$edgeNetwork = 'meguri-staging-edge'
$internalNetwork = 'meguri-staging-internal'
$pythonSmokeImage = 'python:3.14-slim-bookworm'

if (-not (Test-Path -LiteralPath $docker)) {
    throw "Docker CLI not found: $docker"
}
if (-not (Test-Path -LiteralPath (Join-Path $meguriRoot 'java\meguri-core\pom.xml'))) {
    throw "Meguri repository not found: $meguriRoot"
}
if (-not (Test-Path -LiteralPath (Join-Path $airiRoot 'apps\stage-tamagotchi\package.json'))) {
    throw "AIRI repository not found: $airiRoot"
}

function Invoke-RemoteDocker {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    $output = & $docker @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Remote Docker command failed: docker $($Arguments -join ' ')"
    }
    return $output
}

function Test-RemoteContainerExists {
    param([Parameter(Mandatory = $true)][string]$Name)

    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $docker inspect $Name 1>$null 2>$null
        return ($LASTEXITCODE -eq 0)
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
}

function Invoke-PrivateSmoke {
    param(
        [Parameter(Mandatory = $true)][string]$HostName,
        [Parameter(Mandatory = $true)][string]$SessionId
    )

    $python = @'
import json, time, urllib.request
host='http://__HOST__:18080'
sid='__SESSION__'
key='__SESSION__-idempotency'
payload={'user_id':sid,'client_id':'website','session_id':sid,'message':'staging smoke: reply with a short acknowledgement','formal_memory_allowed':False}
def call(path, method='GET', body=None, headers=None, timeout=90):
    data=None if body is None else json.dumps(body).encode()
    req=urllib.request.Request(host+path,data=data,method=method,headers=headers or {})
    with urllib.request.urlopen(req,timeout=timeout) as res:
        return res.status,res.read().decode()
headers={'Content-Type':'application/json','Idempotency-Key':key}
status,raw=call('/v1/turns','POST',payload,headers)
created=json.loads(raw)
status2,raw2=call('/v1/turns','POST',payload,headers)
repeat=json.loads(raw2)
assert created['turn_id']==repeat['turn_id'], (created,repeat)
turn_id=created['turn_id']
req=urllib.request.Request(host+f'/v1/sessions/{sid}/events?after_sequence=0',headers={'Accept':'text/event-stream'})
terminal=None; seen=[]
with urllib.request.urlopen(req,timeout=90) as res:
    res.fp.raw._sock.settimeout(15)
    buf=''; deadline=time.time()+90
    while time.time()<deadline and terminal is None:
        chunk=res.read(4096)
        if not chunk: break
        buf += chunk.decode()
        frames=buf.split('\n\n'); buf=frames.pop()
        for frame in frames:
            event=None; dat=[]
            for line in frame.splitlines():
                if line.startswith('event:'): event=line[6:].strip()
                elif line.startswith('data:'): dat.append(line[5:].lstrip())
            if not dat: continue
            try: envelope=json.loads('\n'.join(dat))
            except Exception: continue
            if envelope.get('turn_id') != turn_id: continue
            seen.append(event)
            if event in ('turn.completed','turn.cancelled','turn.failed'):
                terminal=event; break
assert terminal=='turn.completed', (terminal,seen)
status3,raw3=call(f'/v1/turns/{turn_id}')
final=json.loads(raw3)
assert final.get('status')=='completed', final
print(json.dumps({'create_status':status,'repeat_status':status2,'turn_id':turn_id,'terminal':terminal,'event_count':len(seen),'final_status':final.get('status')},ensure_ascii=False))
'@
    $python = $python.Replace('__HOST__', $HostName).Replace('__SESSION__', $SessionId)
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($python))
    $command = "import base64; exec(compile(base64.b64decode('$encoded'),'smoke.py','exec'))"
    $result = Invoke-RemoteDocker @('run', '--rm', '--network', $edgeNetwork, $pythonSmokeImage, 'python', '-c', $command)
    $result | ForEach-Object { Write-Host $_ }
}

Write-Host '[1/7] Building Meguri Java Core...'
Push-Location $meguriRoot
try {
    & (Join-Path 'D:\environment' 'activate-dev-env.ps1') 21
    & mvn -q -f 'java\meguri-core\pom.xml' test package -DskipTests=false
    if ($LASTEXITCODE -ne 0) { throw 'Maven build failed.' }
}
finally {
    Pop-Location
}

Write-Host '[2/7] Building AIRI frontend...'
Push-Location $airiRoot
try {
    & (Join-Path 'D:\environment' 'activate-dev-env.ps1') 21
    & pnpm --filter @proj-airi/stage-tamagotchi build
    if ($LASTEXITCODE -ne 0) { throw 'AIRI build failed.' }
    & pnpm --filter @proj-airi/stage-tamagotchi exec vitest run src/main/libs/meguri/artifacts.test.ts
    if ($LASTEXITCODE -ne 0) { throw 'AIRI artifact tests failed.' }
}
finally {
    Pop-Location
}

$jar = Join-Path $meguriRoot 'java\meguri-core\target\meguri-core-0.1.0-SNAPSHOT.jar'
$jarHash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLower()
$hashShort = $jarHash.Substring(0, 12)
$nonce = '{0}-{1}' -f (Get-Date -Format 'yyyyMMdd-HHmmss'), (Get-Random -Minimum 1000 -Maximum 9999)
$imageTag = "harness20-$nonce-$hashShort"
$image = "meguri-core-java:$imageTag"
$candidateName = "meguri-staging-core-candidate-$nonce-$hashShort"
$backupName = "meguri-staging-core-pre-harness20-$nonce"
$releaseId = "java-harness20-$nonce"
$context = Join-Path $meguriRoot "tmp\remote-java-build-$imageTag"
$envFile = Join-Path $meguriRoot "tmp\meguri-deploy-$nonce.env"

Write-Host "[3/7] Preparing remote image $image ..."
New-Item -ItemType Directory -Path (Join-Path $context 'target') -Force | Out-Null
Copy-Item -LiteralPath $jar -Destination (Join-Path $context 'target\meguri-core-0.1.0-SNAPSHOT.jar') -Force
Copy-Item -LiteralPath (Join-Path $meguriRoot 'configs') -Destination (Join-Path $context 'configs') -Recurse -Force

$env:DOCKER_HOST = $dockerHost
$env:DOCKER_TLS_VERIFY = '1'
$env:DOCKER_CERT_PATH = $dockerCertPath
$env:DOCKER_BUILDKIT = '1'

$activeInspect = Invoke-RemoteDocker @('inspect', $activeName)
if (-not $activeInspect) { throw "Active container not found: $activeName" }
$activeObject = ($activeInspect -join "`n") | ConvertFrom-Json | Select-Object -First 1
$envLines = @($activeObject.Config.Env)
[IO.File]::WriteAllLines($envFile, $envLines, [Text.UTF8Encoding]::new($false))
$mounts = @($activeObject.Mounts)
$mountArgs = @()
foreach ($mount in $mounts) {
    $spec = "type=$($mount.Type),source=$($mount.Source),target=$($mount.Destination)"
    if ($mount.RW -eq $false) { $spec += ',readonly' }
    $mountArgs += @('--mount', $spec)
}
$networks = @($activeObject.NetworkSettings.Networks.PSObject.Properties.Name)
$edgeNetwork = $networks | Where-Object { $_ -like '*edge*' } | Select-Object -First 1
$internalNetwork = $networks | Where-Object { $_ -like '*internal*' } | Select-Object -First 1
if (-not $edgeNetwork -or -not $internalNetwork) { throw 'Could not discover the staging edge/internal networks.' }

Invoke-RemoteDocker @('build', '--pull=false', '--tag', $image, '--file', (Join-Path $meguriRoot 'java\meguri-core\Dockerfile'), $context) | ForEach-Object { Write-Host $_ }

Write-Host '[4/7] Starting and validating candidate...'
$candidateArgs = @('--name', $candidateName, '--env-file', $envFile, '--init', '--publish', '18081:18080') + $mountArgs + @('--network', $edgeNetwork, $image)
if (Test-RemoteContainerExists $candidateName) { throw "Candidate container already exists: $candidateName" }
try {
    $candidateCreateArgs = @('create') + $candidateArgs
    Invoke-RemoteDocker $candidateCreateArgs | ForEach-Object { Write-Host $_ }
    Invoke-RemoteDocker @('network', 'connect', $internalNetwork, $candidateName) | Out-Null
    Invoke-RemoteDocker @('start', $candidateName) | ForEach-Object { Write-Host $_ }
    Start-Sleep -Seconds 8

    $healthCode = "import urllib.request; print(urllib.request.urlopen('http://${candidateName}:18080/health',timeout=15).read().decode()); print(urllib.request.urlopen('http://${candidateName}:18080/health/ready',timeout=15).read().decode())"
    Invoke-RemoteDocker @('run', '--rm', '--network', $edgeNetwork, $pythonSmokeImage, 'python', '-c', $healthCode) | ForEach-Object { Write-Host $_ }
    Invoke-PrivateSmoke -HostName $candidateName -SessionId "private-staging-$nonce-candidate"
}
catch {
    if (Test-RemoteContainerExists $candidateName) {
        try { Invoke-RemoteDocker @('stop', $candidateName) | Out-Null } catch {}
    }
    throw
}
try { Invoke-RemoteDocker @('stop', $candidateName) | ForEach-Object { Write-Host $_ } } catch {}

$newActiveCreated = $false
$oldRenamed = $false
try {
    Write-Host '[5/7] Switching active staging container...'
    if (Test-RemoteContainerExists $backupName) { throw "Rollback container already exists: $backupName" }
    Invoke-RemoteDocker @('stop', $activeName) | ForEach-Object { Write-Host $_ }
    Invoke-RemoteDocker @('rename', $activeName, $backupName) | ForEach-Object { Write-Host $_ }
    $oldRenamed = $true

    $labels = @(
        '--label', 'io.meguri.environment=staging',
        '--label', "io.meguri.release-id=$releaseId",
        '--label', 'io.meguri.role=java-core',
        '--label', 'com.meguri.deployment.scope=harness20',
        '--label', "com.meguri.deployment.jar-sha256=$jarHash",
        '--label', "com.meguri.deployment.image=$image"
    )
    $activeArgs = @('--name', $activeName, '--env-file', $envFile, '--init', '--restart', 'unless-stopped', '--publish', '127.0.0.1:18080:18080') + $labels + $mountArgs + @('--network', $edgeNetwork, $image)
    $activeCreateArgs = @('create') + $activeArgs
    Invoke-RemoteDocker $activeCreateArgs | ForEach-Object { Write-Host $_ }
    $newActiveCreated = $true
    Invoke-RemoteDocker @('network', 'connect', $internalNetwork, $activeName) | Out-Null
    Invoke-RemoteDocker @('start', $activeName) | ForEach-Object { Write-Host $_ }
    Start-Sleep -Seconds 8

    $activeHealthCode = "import urllib.request; print(urllib.request.urlopen('http://${activeName}:18080/health',timeout=15).read().decode()); print(urllib.request.urlopen('http://${activeName}:18080/health/ready',timeout=15).read().decode())"
    Invoke-RemoteDocker @('run', '--rm', '--network', $edgeNetwork, $pythonSmokeImage, 'python', '-c', $activeHealthCode) | ForEach-Object { Write-Host $_ }
    Invoke-PrivateSmoke -HostName $activeName -SessionId "private-staging-$nonce-active"
}
catch {
    Write-Host 'Active switch failed; attempting rollback...'
    if ($newActiveCreated -and (Test-RemoteContainerExists $activeName)) {
        try { Invoke-RemoteDocker @('stop', $activeName) | Out-Null } catch {}
        try { Invoke-RemoteDocker @('rm', $activeName) | Out-Null } catch {}
    }
    if ($oldRenamed -and (Test-RemoteContainerExists $backupName)) {
        try {
            Invoke-RemoteDocker @('rename', $backupName, $activeName) | Out-Null
            Invoke-RemoteDocker @('start', $activeName) | Out-Null
        }
        catch { Write-Host 'Rollback attempt failed; inspect the remote containers manually.' }
    }
    throw
}

Write-Host '[6/7] Restarting AIRI Electron...'
$renderers = @(Get-CimInstance Win32_Process | Where-Object {
    $_.Name -eq 'electron.exe' -and
    $_.CommandLine -like "*--app-path=*$airiRoot\apps\stage-tamagotchi*"
})
$mainIds = @($renderers.ParentProcessId | Sort-Object -Unique)
foreach ($mainId in $mainIds) {
    if (Get-Process -Id $mainId -ErrorAction SilentlyContinue) {
        Stop-Process -Id $mainId -Force
    }
}
if ($mainIds.Count -gt 0) { Start-Sleep -Seconds 2 }
& (Join-Path $meguriRoot 'ops\scripts\start-meguri-airi.ps1') | Out-Host

Write-Host '[7/7] Final local checks...'
$gateway = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:5173/' -TimeoutSec 10
if ($gateway.StatusCode -ne 200) { throw "AIRI gateway returned HTTP $($gateway.StatusCode)." }
$headers = @{
    Origin = 'null'
    'User-Agent' = 'Mozilla/5.0 Electron/43.1.1'
    'X-Meguri-Desktop-Client' = 'airi'
}
$coreHealth = Invoke-RestMethod -Uri 'http://127.0.0.1:5173/core/health' -Headers $headers -TimeoutSec 10
$ttsHealth = Invoke-RestMethod -Uri 'http://127.0.0.1:9880/health' -TimeoutSec 10
if ($coreHealth.status -ne 'ok' -or $coreHealth.runtime -ne 'java') { throw 'AIRI local core health check failed.' }
if (-not $ttsHealth.ready) { throw 'AIRI local TTS is not ready.' }
$airiDeadline = (Get-Date).AddSeconds(30)
do {
    $airiRenderers = @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -eq 'electron.exe' -and
        $_.CommandLine -like "*--app-path=*$airiRoot\apps\stage-tamagotchi*"
    })
    if ($airiRenderers.Count -gt 0) { break }
    Start-Sleep -Seconds 1
} while ((Get-Date) -lt $airiDeadline)
if ($airiRenderers.Count -eq 0) { throw 'AIRI Electron did not start.' }

Write-Host ''
Write-Host 'Redeploy completed.'
Write-Host "Java release: $releaseId"
Write-Host "Java image:   $image"
Write-Host "Java jar sha: $jarHash"
Write-Host "AIRI renderers: $($airiRenderers.Count)"
Write-Host "Rollback:     $backupName"
