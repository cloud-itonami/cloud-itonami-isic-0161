(ns cropsupport.governor
  "Crop-Support Operations Governor -- the independent compliance layer
  that earns the CropSupportAdvisor the right to commit. The LLM has no
  notion of:
    - Whether a service order has been independently verified/registered
      in the store at all, for ANY of this actor's four proposal ops
    - Whether the applicator's pesticide-application license is current
      (only meaningful for chemical-application service types)
    - Whether the sprayer/applicator equipment's calibration is current
      (only meaningful for chemical-application service types)
    - Whether the gap between application and the client farm's expected
      harvest date meets the service type's pre-harvest interval (only
      meaningful for chemical-application service types)
    - Whether the planned gap before workers re-enter a treated field
      meets the service type's restricted-entry interval (only
      meaningful for chemical-application service types)
    - Whether the actual wind speed at time of application exceeded the
      service type's maximum safe spray-drift wind speed (only
      meaningful for chemical-application service types)
    - Whether the actual buffer-zone distance to the nearest sensitive
      site met the service type's minimum (only meaningful for
      chemical-application service types)
    - Whether a proposal is covertly requesting direct field-equipment
      control or a final pesticide-application decision
    - Whether a previously-raised crop-health concern has been resolved
    - Whether a service order has already been logged (double-commit)

  This MUST be a separate system able to *reject* a proposal and fall back
  to HOLD.

  Unlike direct field-equipment operation (combine, sprayer, applicator --
  NEVER done by this actor) or finalizing a pesticide-application decision
  (NEVER done by this actor -- both are HARD, permanent governor blocks,
  never overridable by human approval), the Governor operates on
  service-order metadata: client-farm identity, service parameters, and
  safety/compliance flags. This is crop-support-service OPERATIONS
  COORDINATION, not direct field-equipment operation authority.

  CRITICAL: `:flag-crop-health-concern` ALWAYS escalates to human sign-off
  at every phase, regardless of advisor confidence -- a pest/disease/
  spray-drift concern is never auto-resolved by advisor confidence alone.

  Hard violations (always HOLD, no override):
    1. Operation outside the closed allowlist (`:op-not-allowed`) --
       includes any proposal that would amount to direct field-equipment
       control
    2. Proposal asserting an `:effect` other than `:propose`
       (`:effect-not-propose`)
    3. Service order not independently verified/registered in the store
       -- applies to ALL FOUR allowed ops (`:service-order-not-registered`)
    4. No jurisdiction citation (`:no-spec-basis`)
    5. Evidence checklist incomplete (`:evidence-incomplete`)
    6. Pesticide-applicator license expired (`:applicator-license-expired`
       -- only when the service type is a chemical application)
    7. Sprayer/applicator equipment calibration overdue
       (`:sprayer-calibration-overdue` -- only when the service type is a
       chemical application)
    8. Pre-harvest interval violated (`:pre-harvest-interval-violated` --
       only when the service type is a chemical application)
    9. Restricted-entry interval violated
       (`:restricted-entry-interval-violated` -- only when the service
       type is a chemical application)
   10. Wind speed exceeded the safe spray-drift ceiling
       (`:wind-speed-exceeded` -- only when the service type is a
       chemical application)
   11. Buffer zone narrower than the service type's minimum
       (`:buffer-zone-violated` -- only when the service type is a
       chemical application)
   12. Proposal covertly requests direct field-equipment control or a
       final pesticide-application decision
       (`:field-equipment-or-pesticide-decision-blocked` -- a HARD,
       PERMANENT block, never overridable by human approval, evaluated
       against every op as defense-in-depth even though those actions are
       already outside the closed allowlist)
   13. Unresolved crop-health concern (`:crop-health-flag-unresolved`)
   14. Service order already logged (`:already-logged`, double-commit
       guard)

  Soft gates (always escalate for human):
    - Low confidence
    - `:log-service-record` -- the one real actuation event this actor
      performs (logging completed, billable field work into records)
    - `:flag-crop-health-concern` -- never auto-resolved by confidence
      alone
    - `:order-supplies` above the cost threshold
      (`supply-order-cost-threshold-usd`)

  This design mirrors `postharvest.governor` (ISIC 0163, post-harvest
  crop activities) in overall shape but specializes on crop-SUPPORT-
  SERVICE safety concerns -- applicator licensing, equipment calibration,
  pre-harvest/restricted-entry intervals, spray-drift wind, and buffer
  zones -- for custom farm work performed for OTHER farms' crops, never
  the operator's own."
  (:require [cropsupport.facts :as facts]
            [cropsupport.registry :as registry]
            [cropsupport.store :as store]))

(def confidence-floor 0.6)

(def supply-order-cost-threshold-usd
  "Supply orders (pesticide/equipment procurement) at or below this cost
  may auto-commit when the Governor is otherwise clean; orders above this
  threshold always require human sign-off, regardless of advisor
  confidence."
  5000)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Logging a completed service record (`:log-service-record`) is the one
  real-world actuation event this actor performs -- it commits billable
  field-work data (and, transitively, the safety/compliance facts that
  accompanied it) into the permanent record."
  #{:log-service-record})

(def always-escalate-ops
  "Operations that always require human sign-off, even when the
  Governor's hard checks are clean and confidence is high: the high-
  stakes actuation event (`high-stakes`) plus `:flag-crop-health-concern`
  -- a crop-health concern (pest, disease, spray-drift) is never
  auto-resolved by advisor confidence alone, it always needs a human
  look."
  (conj high-stakes :flag-crop-health-concern))

(def allowed-ops
  "Closed allowlist of proposal operations this actor may ever make. Any
  proposal for an operation outside this set -- most importantly direct
  field-equipment operation (combine, sprayer, applicator) -- is a hard,
  permanent block: this actor coordinates crop-support-service
  operations, it does not operate field equipment."
  #{:log-service-record :schedule-field-operation :flag-crop-health-concern :order-supplies})

;; ────────────────────────── Checks ──────────────────────────

(defn- op-not-allowed-violations
  "HARD, permanent block: any proposal outside the closed operation
  allowlist (e.g. direct field-equipment operation) is refused
  unconditionally -- this actor has no authority to make such a proposal
  at all, let alone commit it."
  [{:keys [op]} _proposal]
  (when-not (contains? allowed-ops op)
    [{:rule :op-not-allowed
      :detail (str op " はこのactorの許可された提案種別 (log-service-record/"
                  "schedule-field-operation/flag-crop-health-concern/order-supplies) "
                  "に含まれない -- 圃場機器の直接操作はこのactorに無い")}]))

(defn- effect-not-propose-violations
  "HARD invariant: this actor's proposals are always `:effect :propose` --
  it never claims direct write/actuation authority for itself. A proposal
  asserting any other effect is refused unconditionally."
  [_request proposal]
  (when-let [effect (:effect proposal)]
    (when (not= effect :propose)
      [{:rule :effect-not-propose
        :detail (str "この actor の提案は :propose 以外の :effect を持てない (got " effect ")")}])))

(defn- service-order-not-registered-violations
  "HARD invariant: a service-order/client-farm record must be
  independently verified/registered in the store BEFORE any of this
  actor's four proposal ops can be made against it -- coordinating work
  for an engagement this actor never checked in is out of scope.
  Evaluated across ALL FOUR allowed ops, not just one."
  [{:keys [op subject]} st]
  (when (contains? allowed-ops op)
    (when-not (store/service-order-registered? st subject)
      [{:rule :service-order-not-registered
        :detail (str subject " は独立に検証・登録されたservice-order記録が無い -- いかなる提案も進められない")}])))

(defn- spec-basis-violations
  "A proposal with no jurisdiction citation is a HARD violation -- never
  invent a jurisdiction's crop-support-service safety requirements."
  [{:keys [op]} proposal]
  (when (contains?
         #{:log-service-record :order-supplies :flag-crop-health-concern}
         op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :jurisdiction) (nil? (:jurisdiction value))))
        [{:rule :no-spec-basis
          :detail "公式仕様の引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:log-service-record`, verify the service order's evidence
  checklist is complete per jurisdiction requirements."
  [{:keys [op subject]} st]
  (when (= op :log-service-record)
    (let [o (store/service-order st subject)]
      (when-not (and o
                     (facts/required-evidence-satisfied?
                      (:jurisdiction o)
                      (:evidence-checklist o)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(service-order-record/field-boundary-map/application-record/applicator-license等)が充足していない状態での提案"}]))))

(defn- applicator-license-expired-violations
  "For `:log-service-record`, INDEPENDENTLY verify the applicator's
  license has not expired via `registry/applicator-license-expired?`.
  Only evaluated when the service type actually requires a license
  (chemical-application service types) -- mechanical harvest service
  types have nothing to check here, never a fabricated requirement."
  [{:keys [op subject]} st now-ms]
  (when (= op :log-service-record)
    (let [o (store/service-order st subject)
          st* (when o (facts/service-type-by-id (:service-type o)))]
      (when (and o st* (:chemical-application? st*) (:applicator-license-expiry-date o)
                 (registry/applicator-license-expired? (:applicator-license-expiry-date o) now-ms))
        [{:rule :applicator-license-expired
          :detail (str subject " の農薬散布者資格(applicator license)が失効している -- 記録提案は進められない")}]))))

(defn- sprayer-calibration-overdue-violations
  "For `:log-service-record`, INDEPENDENTLY verify the sprayer/applicator
  equipment's calibration is current via
  `registry/sprayer-calibration-overdue?`. Only evaluated when the
  service type actually requires calibration (chemical-application
  service types)."
  [{:keys [op subject]} st now-ms]
  (when (= op :log-service-record)
    (let [o (store/service-order st subject)
          st* (when o (facts/service-type-by-id (:service-type o)))]
      (when (and o st* (:chemical-application? st*) (:sprayer-last-calibration-date o)
                 (registry/sprayer-calibration-overdue? (:sprayer-last-calibration-date o) now-ms))
        [{:rule :sprayer-calibration-overdue
          :detail (str subject " の散布機器の校正が期限切れ -- 記録提案は進められない")}]))))

(defn- pre-harvest-interval-violated-violations
  "For `:log-service-record`, INDEPENDENTLY verify that the gap between
  application and the client farm's expected harvest date meets the
  service type's pre-harvest interval via
  `registry/pre-harvest-interval-violated?`. Only evaluated when the
  service type actually has a pre-harvest-interval spec (chemical-
  application service types) -- mechanical harvest service types have
  nothing to check here, never a fabricated target."
  [{:keys [op subject]} st]
  (when (= op :log-service-record)
    (let [o (store/service-order st subject)
          st* (when o (facts/service-type-by-id (:service-type o)))]
      (when (and o st* (:pre-harvest-interval-days st*) (:days-until-harvest o)
                 (registry/pre-harvest-interval-violated?
                  (:days-until-harvest o)
                  (:pre-harvest-interval-days st*)))
        [{:rule :pre-harvest-interval-violated
          :detail (str subject " の収穫前日数(" (:days-until-harvest o)
                      "日)が農薬使用基準の収穫前日数を下回る -- 記録提案は進められない")}]))))

(defn- restricted-entry-interval-violated-violations
  "For `:log-service-record`, INDEPENDENTLY verify that the planned gap
  before workers re-enter a treated field meets the service type's
  restricted-entry interval via
  `registry/restricted-entry-interval-violated?`. Only evaluated when the
  service type actually has a restricted-entry-interval spec."
  [{:keys [op subject]} st]
  (when (= op :log-service-record)
    (let [o (store/service-order st subject)
          st* (when o (facts/service-type-by-id (:service-type o)))]
      (when (and o st* (:restricted-entry-interval-hours st*) (:hours-until-reentry o)
                 (registry/restricted-entry-interval-violated?
                  (:hours-until-reentry o)
                  (:restricted-entry-interval-hours st*)))
        [{:rule :restricted-entry-interval-violated
          :detail (str subject " の再入場までの猶予(" (:hours-until-reentry o)
                      "時間)が再入場禁止期間基準を下回る -- 記録提案は進められない")}]))))

(defn- wind-speed-exceeded-violations
  "For `:log-service-record`, INDEPENDENTLY verify that the actual wind
  speed at time of application did not exceed the service type's maximum
  safe spray-drift wind speed via `registry/wind-speed-exceeded?`. Only
  evaluated when the service type actually has a wind-speed ceiling."
  [{:keys [op subject]} st]
  (when (= op :log-service-record)
    (let [o (store/service-order st subject)
          st* (when o (facts/service-type-by-id (:service-type o)))]
      (when (and o st* (:max-wind-speed-kmh st*) (:wind-speed-kmh o)
                 (registry/wind-speed-exceeded?
                  (:wind-speed-kmh o)
                  (:max-wind-speed-kmh st*)))
        [{:rule :wind-speed-exceeded
          :detail (str subject " の散布時風速(" (:wind-speed-kmh o)
                      "km/h)が飛散防止基準を超過 -- 記録提案は進められない")}]))))

(defn- buffer-zone-violated-violations
  "For `:log-service-record`, INDEPENDENTLY verify that the actual
  distance maintained to the nearest sensitive site met the service
  type's minimum buffer zone via `registry/buffer-zone-violated?`. Only
  evaluated when the service type actually has a buffer-zone minimum."
  [{:keys [op subject]} st]
  (when (= op :log-service-record)
    (let [o (store/service-order st subject)
          st* (when o (facts/service-type-by-id (:service-type o)))]
      (when (and o st* (:min-buffer-zone-m st*) (:buffer-zone-actual-m o)
                 (registry/buffer-zone-violated?
                  (:buffer-zone-actual-m o)
                  (:min-buffer-zone-m st*)))
        [{:rule :buffer-zone-violated
          :detail (str subject " の緩衝地帯距離(" (:buffer-zone-actual-m o)
                      "m)が最小基準を下回る -- 記録提案は進められない")}]))))

(defn- field-equipment-or-pesticide-decision-blocked-violations
  "HARD, PERMANENT block, defense-in-depth: any proposal whose `:value`
  covertly requests direct field-equipment control
  (`:operate-field-equipment?` true) or a final pesticide-application
  decision (`:finalize-pesticide-application-decision?` true) is refused
  unconditionally, regardless of which op it is nominally filed under and
  regardless of advisor confidence. Never overridable by human approval
  -- this is a scope boundary, not a risk judgment."
  [_request proposal]
  (let [value (:value proposal)]
    (when (or (true? (:operate-field-equipment? value))
              (true? (:finalize-pesticide-application-decision? value)))
      [{:rule :field-equipment-or-pesticide-decision-blocked
        :detail "圃場機器の直接操作または農薬散布の最終決定はこのactorの範囲外 -- 恒久的にブロックされる"}])))

(defn- crop-health-flag-unresolved-violations
  "An unresolved crop-health flag is a HARD, un-overridable hold. Crop-
  health concerns (suspected pest infestation, disease, spray drift)
  raised during service must be resolved before the service order can be
  logged. Evaluated UNCONDITIONALLY at `:log-service-record`."
  [{:keys [op subject]} st]
  (when (= op :log-service-record)
    (let [o (store/service-order st subject)]
      (when (and (true? (:crop-health-concern-raised? o))
                 (not (true? (:crop-health-concern-resolved? o))))
        [{:rule :crop-health-flag-unresolved
          :detail (str subject " は未解決の作物健全性フラグがある -- 記録提案は進められない")}]))))

(defn- already-logged-violations
  "For `:log-service-record`, refuse to log the SAME service order twice,
  off a dedicated `:logged?` fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :log-service-record)
    (when (store/service-order-already-logged? st subject)
      [{:rule :already-logged
        :detail (str subject " は既に記録済み")}])))

(defn- now-epoch-ms
  "Current time in epoch milliseconds, portable across Clojure/
  ClojureScript. Isolated to this single call site so the rest of the
  namespace (and all of `cropsupport.registry`) stays free of host-clock
  calls."
  []
  #?(:clj (System/currentTimeMillis)
     :cljs (js/Date.now)))

(defn- high-cost-supply-order?
  "Soft-gate helper: a supply order (pesticide/equipment procurement)
  whose declared cost exceeds `supply-order-cost-threshold-usd` always
  requires human sign-off, even when the Governor's hard checks are
  clean and confidence is high."
  [{:keys [op]} proposal]
  (and (= op :order-supplies)
       (some-> (get-in proposal [:value :cost-usd])
               (> supply-order-cost-threshold-usd))))

(defn check
  "Censors a CropSupportAdvisor proposal against the Governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}.

  Stakes (high-stakes actuation vs. always-escalate vs. high-cost supply
  order) are read off the REQUEST's `:op` (and, for supply-order cost,
  the proposal's own declared value) -- not off the advisor's self-
  reported stake -- since the operation being proposed is what determines
  whether a human must sign off."
  [request _context proposal st]
  (let [now-ms (now-epoch-ms)
        hard (into []
                   (concat (op-not-allowed-violations request proposal)
                           (effect-not-propose-violations request proposal)
                           (service-order-not-registered-violations request st)
                           (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (applicator-license-expired-violations request st now-ms)
                           (sprayer-calibration-overdue-violations request st now-ms)
                           (pre-harvest-interval-violated-violations request st)
                           (restricted-entry-interval-violated-violations request st)
                           (wind-speed-exceeded-violations request st)
                           (buffer-zone-violated-violations request st)
                           (field-equipment-or-pesticide-decision-blocked-violations request proposal)
                           (crop-health-flag-unresolved-violations request st)
                           (already-logged-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        actuation? (boolean (high-stakes (:op request)))
        escalate-op? (or (boolean (always-escalate-ops (:op request)))
                          (boolean (high-cost-supply-order? request proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not escalate-op?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? escalate-op?))
     :high-stakes? actuation?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
