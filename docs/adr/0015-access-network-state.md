# ADR 0015: ACCESS_NETWORK_STATE is declared, and an unknown host is "not-found" when online

Date: 2026-10-21. Status: accepted. Issue: REA-59. Deciders: the pipeline's triage for REA-59
(clarification: "Yes. WorkManager already merges ACCESS_NETWORK_STATE ... Declare it explicitly,
amend R-M09.5 ... with an ADR in the same PR").
Amends: srs.md R-M09.5 and R-M10.

## Context

A community test of 1.1.0 shared `https://nowebsite.invalid` while online and saw
`fetch-failed: offline`, though the DNS query was answered (NXDOMAIN). `Fetcher` mapped
`UnknownHostException` to `offline`, and Android throws the same exception when there is no
network, so the exception cannot tell the two apart. R-M09.5 said INTERNET was the only network
permission, but the merged manifest already carried ACCESS_NETWORK_STATE (from
`androidx.work:work-runtime` 2.12.0; seen in
`android/app/build/outputs/logs/manifest-merger-debug-report.txt`, and the release report agrees;
corrected 2026-10-23, an earlier text also named `react-android`, which neither report lists), so
the spec was out of date.

## Decision

- `AndroidManifest.xml` declares ACCESS_NETWORK_STATE. It is read-only and makes no network call.
- On `UnknownHostException`, `Fetcher` reads `hasValidatedNetwork` at that moment
  (`ConnectivityManager.activeNetwork` with `NET_CAPABILITY_VALIDATED`). Validated: reason
  `not-found`. Otherwise, or if the read throws: `offline`. The seam is an injected `() -> Boolean`
  so JVM tests cover both; `FetchWorker` passes the real one.
- `ConnectException` and `NoRouteToHostException` stay `offline`. The List shows
  `fetch-failed: not-found` with the same Retry, guidance and Delete. Fetcher's limits and
  "only network call site" (AGENTS.md 6-7) are unchanged; reasons stay fixed strings.
