#!/bin/sh
# same as start_server.bat, for Linux / Mac
cd "$(dirname "$0")"
javac -cp ".:mysql-connector-j-26.7.0.jar" *.java || exit 1
java -cp ".:mysql-connector-j-26.7.0.jar" WebServer 8080
