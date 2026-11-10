@echo off
setlocal
set MAVEN_CMD="C:\Users\mokum\OneDrive\Desktop\apache-maven-3.9.16-bin\apache-maven-3.9.16\bin\mvn.cmd"
if exist %MAVEN_CMD% (
    %MAVEN_CMD% %*
) else (
    mvn %*
)
endlocal
