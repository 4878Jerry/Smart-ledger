Write-Output '---GIT PROXY---'
git config --global --get http.proxy
git config --global --get https.proxy
Write-Output '---ENV PROXY---'
Write-Output ("http_proxy=" + $env:http_proxy)
Write-Output ("https_proxy=" + $env:https_proxy)
Write-Output '---CONNECT TEST github.com:443---'
$t = Test-NetConnection github.com -Port 443 -WarningAction SilentlyContinue
Write-Output ("TcpTestSucceeded=" + $t.TcpTestSucceeded + " RemoteAddress=" + $t.RemoteAddress)
Write-Output '---CONNECT TEST api.github.com:443---'
$t2 = Test-NetConnection api.github.com -Port 443 -WarningAction SilentlyContinue
Write-Output ("TcpTestSucceeded=" + $t2.TcpTestSucceeded + " RemoteAddress=" + $t2.RemoteAddress)
