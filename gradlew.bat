@ECHO OFF

@REM Gradle startup script for Windows generated from standard Gradle wrapper template.

SET DIRNAME=%~dp0
IF "%DIRNAME%" == "" SET DIRNAME=.
SET APP_BASE_NAME=%~n0
SET APP_HOME=%DIRNAME%

FOR %%i IN ("%APP_HOME%") DO SET APP_HOME=%%~fi

SET DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

IF DEFINED JAVA_HOME GOTO findJavaFromJavaHome

SET JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
IF "%ERRORLEVEL%" == "0" GOTO execute

ECHO.
ECHO ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
ECHO.
ECHO Please set the JAVA_HOME variable in your environment to match the
ECHO location of your Java installation.

GOTO fail

:findJavaFromJavaHome
SET JAVA_HOME=%JAVA_HOME:"=%
SET JAVA_EXE=%JAVA_HOME%\bin\java.exe

IF EXIST "%JAVA_EXE%" GOTO execute

ECHO.
ECHO ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
ECHO.
ECHO Please set the JAVA_HOME variable in your environment to match the
ECHO location of your Java installation.
ECHO.

GOTO fail

:execute
SET CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

:end
@REM End local scope for the variables with windows NT shell
IF "%ERRORLEVEL%" == "0" GOTO mainEnd

:fail
EXIT /B 1

:mainEnd
IF "%OS%"=="Windows_NT" ENDLOCAL

