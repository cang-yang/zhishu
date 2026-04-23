@echo off
chcp 65001 >nul
echo.
echo ═══════════════════════════════════════════════════════
echo   PaiSmart 本地开发服务检查
echo ═══════════════════════════════════════════════════════
echo.

set ERRORS=0

echo 【1】MySQL (localhost:3306)
powershell -Command "try { $c = New-Object Net.Sockets.TcpClient('localhost',3306); $c.Close(); Write-Host '  ✅ MySQL 可连接' } catch { Write-Host '  ❌ MySQL 不可连接'; exit 1 }" || set /a ERRORS+=1

echo.
echo 【2】Redis
powershell -Command "try { $c = New-Object Net.Sockets.TcpClient('%SPRING_DATA_REDIS_HOST%',6379); $c.Close(); Write-Host '  ✅ Redis 可连接' } catch { Write-Host '  ❌ Redis 不可连接 (检查 .env 中 SPRING_DATA_REDIS_HOST)'; exit 1 }" || set /a ERRORS+=1

echo.
echo 【3】Kafka (localhost:9092)
powershell -Command "try { $c = New-Object Net.Sockets.TcpClient('localhost',9092); $c.Close(); Write-Host '  ✅ Kafka 可连接' } catch { Write-Host '  ❌ Kafka 不可连接'; exit 1 }" || set /a ERRORS+=1

echo.
echo 【4】MinIO (localhost:9000)
powershell -Command "try { $c = New-Object Net.Sockets.TcpClient('localhost',9000); $c.Close(); Write-Host '  ✅ MinIO 可连接' } catch { Write-Host '  ❌ MinIO 不可连接'; exit 1 }" || set /a ERRORS+=1

echo.
echo 【5】Elasticsearch (localhost:9200)
powershell -Command "try { $c = New-Object Net.Sockets.TcpClient('localhost',9200); $c.Close(); Write-Host '  ✅ Elasticsearch 可连接' } catch { Write-Host '  ❌ Elasticsearch 不可连接'; exit 1 }" || set /a ERRORS+=1

echo.
echo ═══════════════════════════════════════════════════════
if %ERRORS% == 0 (
    echo   🎉 所有本地服务就绪！可以启动后端项目了
) else (
    echo   💥 有 %ERRORS% 个服务未启动，请先启动再运行项目
)
echo ═══════════════════════════════════════════════════════
echo.
pause
