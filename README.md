# cloud-itonami-isic-2431: Casting of iron and steel

Open Business Blueprint for **ISIC Rev.5 2431**: casting of iron and steel — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office **casting foundry plant operations**: production-batch data logging (alloy-grade/weight/defect-rate), melting-furnace/mold/shakeout-equipment maintenance scheduling, safety-concern flagging, and outbound iron/steel casting shipment coordination.

This repository designs a forkable OSS business for iron-and-steel
casting foundry plant operations: run by a qualified operator so a
foundry keeps its own operating records instead of renting a closed
SaaS.

## Scope: casting foundry, not steelmaking or downstream machining

ISIC 2431 covers the **casting foundry** that melts iron/steel in a
furnace (cupola, electric-arc, or induction), pours the molten metal
into sand or permanent molds, then cools and shakes out the solidified
castings — producing gray-iron, ductile-iron, malleable-iron, or
cast-steel parts (engine blocks, valve bodies, structural castings,
etc.), ready to sell or ship, or to pass on to a downstream machining/
finishing operation. This is distinct from `cloud-itonami-isic-2410`
(Manufacture of Basic Iron and Steel), which is the primary
steelmaking/rolling-mill vertical upstream of a foundry's own melt
stock, and from any downstream machining/finishing actor that would
consume this foundry's castings as raw parts. This actor's own hazard
profile is centered on molten-metal handling: splash/burn risk at the
furnace and pour, furnace radiant-heat exposure, mold/core-binder fume
exposure, and shakeout dust/noise.

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — alloy-grade/weight/defect-rate data logging (administrative, not an operational decision)
- `:schedule-maintenance` — furnace/mold/shakeout-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface a molten-metal-hazard (splash/burn, furnace radiant-heat, mold/core-binder fume exposure)/equipment-safety concern (always escalates)
- `:coordinate-shipment` — outbound iron/steel casting shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-critical domain**
(melting furnace, molten-metal splash/burn hazard, furnace radiant-heat
exposure, mold/core-binder fume exposure):

- Does NOT control the melting furnace or pouring line equipment directly
- Does NOT make plant-safety or molten-metal-safety decisions (that's the plant supervisor's exclusive human authority)
- Does NOT actuate the melting furnace or pouring line (human plant supervisor decides)
- ONLY proposes/coordinates operations back-office; all actuation requires explicit human approval
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`foundrymfg.operation/build`, a langgraph-clj StateGraph):
1. **`foundrymfg.advisor`** (sealed intelligence node, `FoundryAdvisor`): proposes decisions only, never commits
2. **`foundrymfg.governor`** (independent, `Foundry Plant Operations Governor`): validates against domain rules, re-derived from `foundrymfg.registry`'s pure functions and `foundrymfg.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Foundry/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct furnace/pouring-line-equipment control)
     - Directly actuating the melting furnace or pouring line (`:actuate-furnace? true`) is a PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped weight past its own logged production weight (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:alloy-grade` value on a production-batch patch
     - No physically implausible `:defect-rate-percent` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`foundrymfg.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`foundrymfg.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
