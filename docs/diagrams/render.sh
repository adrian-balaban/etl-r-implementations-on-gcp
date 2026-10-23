#!/usr/bin/env bash
# Render every .puml here and one level down (beam-dataflow/, flink/, flink-jdbc/) to SVG.
# Needs a JRE and plantuml.jar (rendered with 1.2026.8).
#   PLANTUML_JAR=~/bin/plantuml.jar ./render.sh
# Graphviz is not required: diagrams use `!pragma layout smetana`; C4 comes from the stdlib.
set -euo pipefail
cd "$(dirname "$0")"
JAR="${PLANTUML_JAR:-$HOME/bin/plantuml.jar}"
[[ -f "$JAR" ]] || { echo "plantuml.jar not found at '$JAR' (set PLANTUML_JAR)" >&2; exit 1; }
java -Djava.awt.headless=true -jar "$JAR" -tsvg -failfast2 ./*.puml ./*/*.puml
# PlantUML draws deprecation warnings into the image instead of failing; refuse them.
if bad=$(grep -l 'This syntax is deprecated' ./*.svg ./*/*.svg); then
  echo "deprecated syntax rendered into: $bad" >&2; exit 1
fi
echo "rendered $(ls ./*.svg ./*/*.svg | wc -l) diagrams"
