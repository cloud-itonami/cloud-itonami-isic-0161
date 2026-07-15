# cloud-itonami-isic-0161: Support Activities For Crop Production Coordination Actor

**ISIC Rev. 5 0161** — Support Activities for Crop Production

A distributed actor for autonomous, compliant coordination of custom crop-support-service operations: service-order intake → field/crop-condition survey → treatment/scheduling advice → custom harvest/spray/pest-control field work → service-record logging → compliance audit. Sealed LLM advisor; independent Governor enforcement; append-only audit ledger. **Not field-equipment operation.** Combine/sprayer/applicator operation remains exclusive to licensed field-equipment operators, and this actor never finalizes a pesticide-application decision on its own.

## Scope

This actor coordinates **custom farm-work operations** for CUSTOM harvesting/spraying/pest-control services performed for OTHER farms' crops on a fee/contract basis — the operator never grows or owns the crop being serviced, which is what distinguishes ISIC 0161 from the growing divisions (011x) themselves:

- Service-record logging (custom-harvest/spray/pest-control service-hours/acreage data, safety/compliance parameters)
- Harvesting/spraying/pest-control service scheduling proposals
- Crop-health concern escalation (pest/disease/spray-drift, always escalates)
- Pesticide/equipment procurement proposals

**Out of scope:**
- Direct field-equipment operation (combine, sprayer, applicator — exclusive to licensed field-equipment operators)
- Finalizing a pesticide-application decision (permanent, un-overridable governor block)
- Growing the crop itself (that is ISIC 011x, the growing divisions)
- Regulatory interpretation (proposals cite jurisdiction specifications; the Governor enforces only published requirements)

## Design

### Governor (Independent Compliance Layer)

The Governor is the separation-of-powers enforcement. It never trusts the advisor's confidence for anything safety- or compliance-relevant, and it always wins over the advisor.

- **Hard HOLD** (un-overridable):
  - Operation outside the closed allowlist (`:op-not-allowed`) — includes any proposal that would amount to direct field-equipment control
  - Proposal asserting an `:effect` other than `:propose` (`:effect-not-propose`)
  - Service order not independently verified/registered in the store — applies to ALL FOUR allowed ops (`:service-order-not-registered`)
  - No jurisdiction citation (`:no-spec-basis`)
  - Evidence checklist incomplete (`:evidence-incomplete`)
  - Pesticide-applicator license expired (`:applicator-license-expired`) — only for chemical-application service types
  - Sprayer/applicator equipment calibration overdue (`:sprayer-calibration-overdue`) — only for chemical-application service types
  - Pre-harvest interval violated (`:pre-harvest-interval-violated`) — only for chemical-application service types
  - Restricted-entry interval violated (`:restricted-entry-interval-violated`) — only for chemical-application service types
  - Wind speed exceeded the safe spray-drift ceiling (`:wind-speed-exceeded`) — only for chemical-application service types
  - Buffer zone narrower than the service type's minimum (`:buffer-zone-violated`) — only for chemical-application service types
  - Proposal covertly requests direct field-equipment control or a final pesticide-application decision (`:field-equipment-or-pesticide-decision-blocked`) — a HARD, PERMANENT block, defense-in-depth against every op
  - Unresolved crop-health concern (`:crop-health-flag-unresolved`)
  - Service order already logged (`:already-logged`, double-commit guard)
- **Escalate** (human sign-off always required):
  - `:log-service-record` — the one real actuation event this actor performs, always requires human sign-off even when the Governor is otherwise clean
  - `:flag-crop-health-concern` — a crop-health concern (pest, disease, spray-drift) is never auto-resolved by advisor confidence alone
  - `:order-supplies` above `governor/supply-order-cost-threshold-usd` (5000 USD)
  - Low advisor confidence (below `governor/confidence-floor`, 0.6)
- **Commit** (advisor proposal approved; Governor clean; not a mandatory-escalation op):
  - Routine, low-stakes proposals only — in this actor's current allowlist that is effectively `:schedule-field-operation` when clean, or `:order-supplies` at or below the cost threshold

### Operations (Proposals)

Closed allowlist — the advisor may **only** ever propose these four operation types, all `:effect :propose`:

- **`:log-service-record`** — Log custom-harvest/spray/pest-control service-hours/acreage data, plus safety/compliance parameters, into service records (always requires human sign-off)
- **`:schedule-field-operation`** — Propose harvesting/spraying/pest-control service scheduling for a client farm (routine, low risk)
- **`:flag-crop-health-concern`** — Surface a crop-health concern (e.g. pest infestation, disease, spray drift); always escalates
- **`:order-supplies`** — Propose pesticide/equipment procurement (escalates above the cost threshold)

Any proposal for an operation outside this allowlist — most importantly anything that would amount to direct field-equipment control — is refused unconditionally by the Governor (`:op-not-allowed`), regardless of advisor confidence. Any proposal that covertly requests direct field-equipment control or a final pesticide-application decision, even nested inside an otherwise-allowed op, is likewise refused unconditionally (`:field-equipment-or-pesticide-decision-blocked`).

## Testing

```bash
# Run full test suite
clojure -M:test

# Check code quality
clojure -M:lint

# Run demo simulation
clojure -M:run
```

## Standalone Use

This repo is **forkable outside the workspace**. If cloning standalone (not in the kotoba-lang monorepo), override `:local/root` paths in `deps.edn`:

```clojure
{:deps {io.github.kotoba-lang/langchain {:git/url "https://github.com/kotoba-lang/langchain" :git/tag "v0.1.0"}
        io.github.kotoba-lang/langgraph {:git/url "https://github.com/kotoba-lang/langgraph" :git/tag "v0.1.0"}}}
```

## License

AGPL-3.0-or-later. Forking/contribution welcome; see `CONTRIBUTING.md`.

## Security

Report security issues to the issue tracker or private disclosure; see `SECURITY.md`.

---

Part of **cloud-itonami**: autonomous actor fleet for regulated industries. See [github.com/cloud-itonami](https://github.com/cloud-itonami).
