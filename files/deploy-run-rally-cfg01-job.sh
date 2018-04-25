#!/bin/bash
if [ -z "$1" ]; then
  echo "Please specify job name"
  echo "==========================="
  echo "Avliable jobs are:"
  echo "$(ls templates)"
  exit 1
fi
if [ -z "$2" ]; then
  echo "Please specify cfg01 node ip"
  exit 1
fi
CFG_FILE="cfg01-jenkins-config.ini"
set -e
echo "========================================================="
echo "If you have any errors, please,"
echo "update you jenkins-jobs-builder to 2.0.0.0b2 or more"
echo "========================================================="
echo "pip install jenkins-job-builder>=2.0.0.0b2"
echo "---------------------------------------------------------"
cd "$(dirname "$0")"

sed 's,url=http:\/\/.*:8081,url=http:\/\/'"$2"':8081,' $CFG_FILE > /tmp/cfg01-jenkins-jobs.ini
jenkins-jobs --conf /tmp/cfg01-jenkins-jobs.ini test ./ $1
jenkins-jobs --conf /tmp/cfg01-jenkins-jobs.ini --flush-cache update ./ $1
