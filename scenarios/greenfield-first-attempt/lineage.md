# Decision lineage

Hashes are the first 10 characters of SHA-256.

| stage | revision | input | output | model | commit | built from |
|---|---|---|---|---|---|---|
| requirements | 1 | 1b258f4b7e | 524bd9e6fc | claude-opus-5.5 | 9fab3724a9 | - |
| design | 1 | b3613c4034 | 9e52e93755 | claude-opus-5.5 | 67841d0367 | requirements=524bd9e6fc |
| tests | 1 | b110cdb4b2 | 38eb13c893 | claude-sonnet-5.5 | 4345410061 | requirements=524bd9e6fc, design=9e52e93755 |
| code | 1 | b110cdb4b2 | c94b15c7e0 | claude-sonnet-5.5 | 33b6119ad1 | requirements=524bd9e6fc, design=9e52e93755 |
