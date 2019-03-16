#!/bin/sh

set -e
bazel coverage $@
COVERAGE=bazel-testlogs/coverage
mkdir -p $COVERAGE
traces=$(find bazel-testlogs/ -name coverage.dat)
rm -fr $COVERAGE/*
ln -s $PWD/src/main/java/build $COVERAGE/build
genhtml -f -o $COVERAGE $traces
cd $COVERAGE
python -m SimpleHTTPServer
