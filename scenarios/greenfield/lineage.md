# Decision lineage

Hashes are the first 10 characters of SHA-256.

| stage | revision | input | output | model | commit | built from |
|---|---|---|---|---|---|---|
| requirements | 1 | 1b258f4b7e | c01a47e3fe | claude-opus-5.5 | ccf5271bcb | - |
| design | 1 | 888c143491 | 811253ec28 | claude-opus-5.5 | f0a226d403 | requirements=c01a47e3fe |
| code | 1 | 2056c3df8d | cbb0aa6810 | claude-sonnet-5.5 | 9aab9146bf | requirements=c01a47e3fe, design=811253ec28 |
| tests | 1 | 2056c3df8d | 5c2975574e | claude-sonnet-5.5 | d46bdfd465 | requirements=c01a47e3fe, design=811253ec28 |
