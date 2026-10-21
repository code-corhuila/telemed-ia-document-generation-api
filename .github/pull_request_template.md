## User story

- `code-corhuila/telemed-ia-docs#NN`

## What changes and why

<!-- A few lines. -->

## How it was tested

<!-- Tests that cover the change and the result of `mvn -B verify` / ci.yml. -->

- [ ] `mvn -B verify` green locally
- [ ] `ci.yml` green on this PR

## Promotion trail

<!-- Only for PRs targeting qa or main: every re-applied commit with its
     "(cherry picked from commit <sha>)" line (numeral 10). -->

## Checklist

- [ ] No secrets, tokens or `.env` files versioned
- [ ] No schema change outside `telemed-ia-document-generation-db`
- [ ] Public contract respected (`07-api/contracts/openapi/document-service.yaml`)
- [ ] PR <= 400 lines (tests and generated files excluded)
- [ ] Conventional Commit in the title
