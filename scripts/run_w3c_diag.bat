@echo off
chcp 65001 >nul
setlocal EnableExtensions
set "REPO=D:\bing\MC\mcmoderr-my\mirage-loader"
set "PS=%REPO%\scripts\verify_fakepack_w3_c.ps1"
set "JAVA_HOME=C:\Program Files\Java\jdk-25.0.4"
set "FAT=%REPO%\launcher\mirage-launch\build\libs\mirage-launch-0.1.0.jar"

echo ============================================================
echo   Mirage W3-C 诊断工具  (headless, 不拉 GUI / 不写 mc-shili)
echo ============================================================
echo   1^) 完整门禁   - 含 :launcher:mirage-launch:fatJar 重新构建（较慢）
echo   2^) 快速门禁   - 复用已有 fat jar（默认，需先构建过一次）
echo   3^) 仅内置探针 - 直接编译并运行 W3CBuiltinProbe 看原始输出
echo   4^) 退出
echo ============================================================
set "CHOICE=2"
set /p CHOICE="请选择 [2]: "

if "%CHOICE%"=="1" goto MODE_FULL
if "%CHOICE%"=="2" goto MODE_QUICK
if "%CHOICE%"=="3" goto MODE_PROBE
if "%CHOICE%"=="4" goto MODE_END
echo 无效选择，使用默认（快速门禁）
goto MODE_QUICK

:MODE_FULL
echo.
echo [运行] 完整门禁（含 fat jar 构建）...
powershell -NoProfile -ExecutionPolicy Bypass -File "%PS%" -JavaHome "%JAVA_HOME%" -Repo "%REPO%"
goto MODE_END

:MODE_QUICK
if not exist "%FAT%" (
  echo.
  echo [提示] 未发现已构建的 fat jar：%FAT%
  echo         自动改为「完整门禁」（含构建）...
  goto MODE_FULL
)
echo.
echo [运行] 快速门禁（SkipBuild，复用已有 fat jar）...
powershell -NoProfile -ExecutionPolicy Bypass -File "%PS%" -JavaHome "%JAVA_HOME%" -Repo "%REPO%" -SkipBuild
goto MODE_END

:MODE_PROBE
if not exist "%FAT%" (
  echo.
  echo [错误] 未发现已构建的 fat jar：%FAT%
  echo         请先选 1^) 完整门禁 构建一次。
  goto MODE_END
)
echo.
echo [编译并运行] W3CBuiltinProbe（BUILTIN_ON 场景，看 NEO_LOAD 等原始输出）...
set "PROBE_SRC=%REPO%\scripts\fixtures\fakepack-w3c\W3CBuiltinProbe.java"
set "WORK=%TEMP%\mirage-w3c-probe"
if exist "%WORK%" rmdir /s /q "%WORK%"
mkdir "%WORK%"
"%JAVA_HOME%\bin\javac.exe" -cp "%FAT%" -d "%WORK%" "%PROBE_SRC%"
if errorlevel 1 (
  echo [失败] 探针编译失败
  goto MODE_END
)
echo ----- BUILTIN_ON (-Dmirage.msr.fakepack.runtime=on) -----
"%JAVA_HOME%\bin\java.exe" -cp "%WORK%;%FAT%" -Dmirage.msr.fakepack.runtime=on probe.W3CBuiltinProbe "%FAT%"
echo.
echo ----- DEFAULT (不给 runtime，应全 CNFE) -----
"%JAVA_HOME%\bin\java.exe" -cp "%WORK%;%FAT%" probe.W3CBuiltinProbe "%FAT%"
goto MODE_END

:MODE_END
echo.
echo ============================================================
echo   诊断结束。结果见上方（门禁末行应为 RESULT PASS n/n）。
echo ============================================================
pause
