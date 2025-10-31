#!/bin/bash
set -euo pipefail

echo "🏗  Building application JAR..."
mvn -DskipTests package

echo "🧪 Running E2E suite..."
mvn -Pe2e test
