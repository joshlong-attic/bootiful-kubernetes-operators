#!/usr/bin/env bash
export JAVA_OPTS="-H:+ReportExceptionStackTraces"
mvn  -DskipTests=true -U -Pnative clean package && ./target/fabric8-sample-controller
