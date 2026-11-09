# Decision lineage

Hashes are the first 10 characters of SHA-256.

| stage | revision | input | output | model | commit | built from |
|---|---|---|---|---|---|---|
| requirements | 1 | 52ac0ff989 | 5565992859 | claude-opus-5.5 | afae34e757 | - |
| impact-analysis | 1 | f802dcb76e | 6c622bf1f2 | claude-opus-5.5 | 712a39159d | requirements=5565992859 |
| design | 1 | 097e69c631 | d4d0c328b9 | claude-opus-5.5 | a12291cddd | requirements=5565992859, impact-analysis=6c622bf1f2 |
| design | 2 | 5e53b43e3f | 1c525342a2 | claude-opus-5.5 | 292707a827 | requirements=5565992859, impact-analysis=6c622bf1f2 |
| design | 3 | e181f10164 | be9e5aa731 | claude-opus-5.5 | ab9caa14db | requirements=5565992859, impact-analysis=6c622bf1f2 |
| code | 1 | d543524bb0 | e480108077 | claude-sonnet-5.5 | 3f964abec7 | requirements=5565992859, impact-analysis=6c622bf1f2, design=be9e5aa731 |
| tests | 1 | d543524bb0 | 5beaaabf1e | claude-sonnet-5.5 | 117baaa268 | requirements=5565992859, impact-analysis=6c622bf1f2, design=be9e5aa731 |
