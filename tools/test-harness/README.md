# Test Harness

Integration test runner for the AI-Camel project pipelines.

## Usage

```bash
# Ensure the app is running on localhost:8080
# Then run:
./run-regression.sh
```

The harness reads `datasets/golden/regression_cases/*.json` and verifies that:
- The correct number of RAG chunks are retrieved
- Keywords are present in answers
- Score values are within acceptable ranges

## Configuration

Set `AIBOOK_BASE_URL` to point at your running instance (default: `http://localhost:8080`).
