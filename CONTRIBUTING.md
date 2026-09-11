# Contributing to cloud-itonami-isic-0161

Thank you for your interest in contributing to the Support Activities For
Crop Production Coordination actor.

## Scope

This repository is a specialization of the cloud-itonami architecture for ISIC
0161 (support activities for crop production). Contributions should:

1. Extend or correct the **Governor rules** (crop-support-service safety/regulatory constraints)
2. Add **service types** or **jurisdictional requirements** to the facts registry
3. Improve **test coverage** for crop-support-service-specific scenarios
4. Clarify **documentation** and ADRs

## Prohibited Changes

Do **not**:

- Add direct field-equipment control (combine/sprayer/applicator operation remains exclusive to licensed field-equipment operators)
- Add authority to finalize a pesticide-application decision
- Modify the Governor to allow LLM confidence to override safety/regulatory hard holds
- Add JVM-only code (all source must be `.cljc` / portable)
- Change the AGPL-3.0-or-later license

## Process

1. Open an issue describing your proposed change
2. Link to the relevant ADR in the `kotoba-lang/industry` registry repository (or the `com-junkawasaki/root` superproject's `90-docs/adr/`)
3. Submit a pull request against `main`
4. Ensure all tests pass: `kbb -M:test`
5. Run linter: `kbb -M:lint`

## Code Style

- Use `.cljc` for all source (no `.clj` or `.cljs` only)
- Follow Clojure conventions (kebab-case, docstrings on public fns)
- Governor rules must be pure, side-effect-free predicates
- Test all new facts and registry entries

## Questions?

File an issue or reach out to the maintainers.
