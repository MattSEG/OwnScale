#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/env.sh
mkdir -p build/tests
javac -d build/tests android/src/org/ownscale/app/{Protocol,BodyFatModel,TrendMath,Stability,Lookback}.java tests/{ProtocolTest,BodyFatModelTest,TrendMathTest,StabilityTest,LookbackTest}.java
java -cp build/tests ProtocolTest
java -cp build/tests BodyFatModelTest
java -cp build/tests org.ownscale.app.TrendMathTest
java -cp build/tests org.ownscale.app.StabilityTest
java -cp build/tests org.ownscale.app.LookbackTest
python3 tests/export_test.py
