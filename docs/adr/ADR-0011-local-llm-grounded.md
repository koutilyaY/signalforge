# ADR-0011: A local, optional, evidence-grounded LLM

**Status:** Accepted · **Date:** 2026-08-07

## Context

Deterministic correlation produces a ranked list of contributing factors with supporting evidence.
That is precise but terse. A narrative summary is genuinely useful at 3am.

It is also the single most dangerous feature in the product: a language model that invents a cause
sends an engineer to debug a system that was never involved, during an outage.

## Decision

Optional local Ollama, off by default, with three structural guarantees.

**1. Never on the detection path.** No code in detection or correlation calls the AI. Summaries live
in their own table and are generated only when someone opens the RCA panel. An unreachable model
means a missing row, not a missing incident.

**2. The model sees only the evidence bundle.** It receives a rendered view of `EvidenceBundle` and
nothing else — no tools, no retrieval, no database access. It *cannot* cite a log line that was
never collected, because it has never seen one.

**3. Ungrounded output is discarded, not displayed.** Every returned cause is checked against the
supplied evidence. A cause must cite at least one evidence line or name a service, version or error
type that appears in the bundle. Anything else is dropped and counted in
`signalforge_ai_rejected_claims_total`. If nothing survives, the answer is "Insufficient evidence to
determine root cause."

Additionally, when the deterministic correlator found no evidence, the model is **not called at
all** — asking an LLM to explain an incident with no evidence is exactly how you get a confident
fabrication.

## Why local

The stated constraint was $0 and no paid API. The better reason: incident evidence contains service
names, error messages and deployment metadata. Shipping that to a third party is a data-egress
decision an SRE team should make deliberately, not one a monitoring tool makes on their behalf.

Temperature is pinned at 0.1 — this is extraction and summarisation of supplied facts, not creative
writing.

## Consequences

**Positive** — the platform is fully functional with `SF_AI_ENABLED=false`, which is the default;
hallucinated causes are structurally hard to surface; the evidence handed to the model is persisted
so any claim can be audited afterwards.

**Negative** — the grounding check is deliberately crude and will occasionally discard a fair
paraphrase. That is the correct direction to err: a dropped true statement costs nothing, a
retained invented one costs an engineer their night. Small local models also produce weaker prose
than a frontier hosted model, which is an accepted trade.
