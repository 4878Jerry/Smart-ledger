$ips = @('140.82.112.3','140.82.113.3','140.82.114.3','140.82.121.3','140.82.116.3','20.205.243.166','192.30.255.112')
foreach ($ip in $ips) {
  $t = Test-NetConnection $ip -Port 443 -WarningAction SilentlyContinue
  Write-Output ($ip + " => " + $t.TcpTestSucceeded)
}
