# Decision lineage

Hashes are the first 10 characters of SHA-256.

| stage | revision | input | output | model | commit | built from |
|---|---|---|---|---|---|---|
| requirements | 1 | 1b258f4b7e | 93a774b54a | claude-opus-5.5 | 15955b52ff | - |
| design | 1 | f4edc4c61d | c507378e73 | claude-opus-5.5 | 704d6c5646 | requirements=93a774b54a |
| code | 1 | 8dd3a5d371 | 22f824d2ab | claude-sonnet-5.5 | 178bb79c25 | requirements=93a774b54a, design=c507378e73 |
| tests | 1 | 8dd3a5d371 | 87c94229e9 | claude-sonnet-5.5 | c6d15a4235 | requirements=93a774b54a, design=c507378e73 |
