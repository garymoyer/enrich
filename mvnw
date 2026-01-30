#!/bin/sh
# Maven Wrapper script

MAVEN_PROJECTBASEDIR="${MAVEN_BASEDIR:-${0%/*}}"
MAVEN_OPTS="$(concat_lines "$MAVEN_PROJECTBASEDIR/.mvn/jvm.config") $MAVEN_OPTS"

exec mvn "$@"
