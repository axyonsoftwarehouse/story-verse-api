@echo off
setlocal

set "WRAPPER_PROPERTIES=%~dp0.mvn\wrapper\maven-wrapper.properties"
if not exist "%WRAPPER_PROPERTIES%" (
	echo ERROR: Maven wrapper properties were not found: "%WRAPPER_PROPERTIES%"
	exit /b 1
)

for /f "usebackq tokens=1,* delims==" %%A in ("%WRAPPER_PROPERTIES%") do (
	if "%%A"=="distributionUrl" set "DISTRIBUTION_URL=%%B"
)
if not defined DISTRIBUTION_URL (
	echo ERROR: distributionUrl is missing from "%WRAPPER_PROPERTIES%"
	exit /b 1
)

if defined JAVA_HOME (
	if not exist "%JAVA_HOME%\bin\java.exe" (
		echo ERROR: JAVA_HOME does not contain bin\java.exe: "%JAVA_HOME%"
		exit /b 1
	)
) else (
	where java.exe >nul 2>nul
	if errorlevel 1 (
		echo ERROR: Java was not found. Install JDK 25 or set JAVA_HOME.
		exit /b 1
	)
)

for %%F in ("%DISTRIBUTION_URL%") do set "DISTRIBUTION_FILENAME=%%~nxF"
set "DISTRIBUTION_DIR=%DISTRIBUTION_FILENAME:-bin.zip=%"
if defined MAVEN_USER_HOME (
	set "MAVEN_CACHE=%MAVEN_USER_HOME%"
) else (
	set "MAVEN_CACHE=%USERPROFILE%\.m2"
)
set "MAVEN_HOME=%MAVEN_CACHE%\wrapper\dists\%DISTRIBUTION_DIR%"
set "MAVEN_EXECUTABLE="
for /f "delims=" %%M in ('where /r "%MAVEN_HOME%" mvn.cmd 2^>nul') do (
	if not defined MAVEN_EXECUTABLE set "MAVEN_EXECUTABLE=%%M"
)

if not defined MAVEN_EXECUTABLE (
	echo Downloading Maven from %DISTRIBUTION_URL%
	powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference = 'Stop'; $temp = Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString()); New-Item -ItemType Directory -Path $temp | Out-Null; try { $archive = Join-Path $temp $env:DISTRIBUTION_FILENAME; Invoke-WebRequest -Uri $env:DISTRIBUTION_URL -OutFile $archive; Expand-Archive -LiteralPath $archive -DestinationPath $temp; New-Item -ItemType Directory -Path $env:MAVEN_HOME -Force | Out-Null; $install = Join-Path $env:MAVEN_HOME $env:DISTRIBUTION_DIR; if (Test-Path -LiteralPath $install) { throw 'The Maven cache already contains an incomplete distribution at ' + $install }; Move-Item -LiteralPath (Join-Path $temp $env:DISTRIBUTION_DIR) -Destination $install } finally { Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue }"
	if errorlevel 1 (
		echo ERROR: Could not download or extract the configured Maven distribution.
		exit /b 1
	)
	for /f "delims=" %%M in ('where /r "%MAVEN_HOME%" mvn.cmd 2^>nul') do (
		if not defined MAVEN_EXECUTABLE set "MAVEN_EXECUTABLE=%%M"
	)
)

if not defined MAVEN_EXECUTABLE (
	echo ERROR: Maven executable was not found under "%MAVEN_HOME%".
	exit /b 1
)

call "%MAVEN_EXECUTABLE%" %*
exit /b %ERRORLEVEL%
