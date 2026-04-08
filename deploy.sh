#!/usr/bin/env bash
set -euo pipefail

cp pom.xml pom.xml.tmp
lein deploy clojars
cp pom.xml.tmp pom.xml
rm pom.xml.tmp
