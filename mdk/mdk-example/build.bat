@echo off
REM Mirage Loader MDK 构建脚本
REM 用法：build.bat [输出jar名]

setlocal
set MOD_ID=mdk-example
set MOD_VERSION=1.0.0
set JAR_NAME=%1
if "%JAR_NAME%"=="" set JAR_NAME=%MOD_ID%-%MOD_VERSION%.jar

set JAVAC="C:\Program Files\Java\jdk-25.0.4\bin\javac.exe"
set JAR="C:\Program Files\Java\jdk-25.0.4\bin\jar.exe"

REM 路径配置（根据实际环境修改）
set MC_JAR=D:\bing\MC\mcmoderr-my\mc-shili\versions\26.2\26.2.jar
set MIRAGE_API=D:\bing\MC\mcmoderr-my\mirage-loader\build\mirage-launch-0.4.0.jar
set GSON=D:\bing\MC\mcmoderr-my\mc-shili\libraries\com\google\code\gson\gson\2.14.0\gson-2.14.0.jar
set DFU=D:\bing\MC\mcmoderr-my\mc-shili\libraries\com\mojang\datafixerupper\10.0.21\datafixerupper-10.0.21.jar

set OUT=build\classes
set DIST=dist

if exist build rmdir /s /q build
if exist dist rmdir /s /q dist
mkdir %OUT%
mkdir %DIST%

echo [1/3] Compiling...
%JAVAC% -proc:none -encoding UTF-8 -cp "%MC_JAR%;%MIRAGE_API%;%GSON%;%DFU%" -d %OUT% (src\main\java\com\example\mdk\*.java)
if errorlevel 1 goto :error

echo [2/3] Copying resources...
xcopy /E /I /Y src\main\resources\* %OUT%\

echo [3/3] Packaging...
pushd %OUT%
%JAR% cf ..\..\%DIST%\%JAR_NAME% .
popd

echo.
echo Done: %DIST%\%JAR_NAME%
echo Copy to mc-shili\versions\26.2-mirage\mods\ to install.
goto :eof

:error
echo Build failed!
exit /b 1
