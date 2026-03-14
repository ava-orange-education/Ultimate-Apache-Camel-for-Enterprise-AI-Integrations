# Schema Validators

JSON Schema definitions and validation utilities for all pipeline DTOs.

## Schemas

- `email-message.schema.json`    — Validates `EmailMessage` payloads
- `document-content.schema.json` — Validates `DocumentContent` payloads
- `scoring-request.schema.json`  — Validates `ScoringRequest` payloads

## Usage

```bash
# Validate a payload against its schema
npx ajv validate -s schema-validators/email-message.schema.json \
                 -d datasets/synthetic/text/email-001.json
```
