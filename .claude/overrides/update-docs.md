# /update-docs - Read-Me additions

## Step 2a: extra claims to test in `AGENTS.md` "Known state"

| Claim | How to test it now |
|---|---|
| Build toolchain lines | `ls ~/Android/Sdk/build-tools`, `cat ~/Android/tools/jdk-path.txt` |
| "The GitHub repo is public" and the release state | `gh repo view --json visibility`; `gh release view v<ver> --json isDraft,targetCommitish` |
| A reproduced device defect | Reproduce it on the phone (rule 16), holding `.claude/device.lock/` |

A spec-check baseline moves only on a device observation per R-M14 (date, device, build).

## Step 2b: spikes

Spike answers live under their bullet in `srs.md` "Spikes". Decision-changing answers (SPIKE-06
replacing the separate-instances rule, SPIKE-05 revising R-M07's gap target) need an ADR in
`docs/adr/` and the affected requirement amended.

Before treating "Kotlin owns the database" (ADR 0001, roadmap F11) as a pending amendment, check:
`srs.md`'s change log already records it, so it is probably closed.

## Step 2c: `CONTEXT.md`

Source layout scope: `git ls-files src android/app/src/main | sed 's#/[^/]*$##' | sort -u`.
"Source layout" must keep the applicationId and the log tags; `/verify` and `/finish` read them.
Layering greps:

```bash
rg -n '\b(fetch|XMLHttpRequest|WebSocket)\b' src/                  # must be empty (rule 6)
rg -n -l 'openConnection|OkHttpClient|HttpURLConnection' android/   # only Fetcher (and BridgeServer's server socket)
```

## Step 3

`AGENTS.md` "Style": no em-dashes. Measurements cited from the plugin repo stay cited.
