#!/usr/bin/env bash

mvn -DskipTests=true -Pnative clean package && ./target/fabric8-sample-controller
