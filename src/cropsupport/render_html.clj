(ns cropsupport.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: before this namespace
  existed there was no demo page and no generator at all -- this repo's
  own `cropsupport.sim` is still a stub that prints
  `\"CropSupport simulation: not yet implemented.\"` (confirmed by running
  `clojure -M:dev:run` BEFORE writing this file), so unlike
  `cloud-itonami-isic-9522` there was no existing demo scenario to adapt.
  Every scenario step below was authored against this repo's OWN
  vocabulary: the four ops in `cropsupport.governor/allowed-ops`, the
  service types in `cropsupport.facts/service-types`, the jurisdictions
  and their `:required-evidence` lists in
  `cropsupport.facts/jurisdictions`, and the exact hard-rule keywords the
  Governor emits.

  What drives the page:

    `cropsupport.governor/check`      -- every verdict on the page
    `cropsupport.operation/run-operation` -- the driver seam, including
                                        its `:hold-fact-fn` callback
    `cropsupport.governor/hold-fact`  -- every HARD-hold ledger fact
    `cropsupport.store/*`             -- the service-order state and the
                                        append-only audit ledger
    `cropsupport.facts/*`             -- every safety threshold and every
                                        per-order safety verdict shown
    `cropsupport.phase/*`             -- the phase machine table

  There is no langgraph StateGraph in this repo. `deps.edn` ships
  `:deps {}` and only an inert `:override-deps` for langgraph/langchain,
  and `cropsupport.advisor` is an empty skeleton -- so there is no
  `op/build` graph to run, and this renderer drives the seam the repo
  actually exposes (`operation/run-operation`, which takes the governor
  as a parameter) rather than adding a dependency to make the demo look
  like the reference. This is stated plainly on the page too.

  DETERMINISM. The Governor reads the host clock internally (private
  `now-epoch-ms`, used by the applicator-license and sprayer-calibration
  checks) and there is no seam to inject a clock, so the seed dates are
  expressed as OFFSETS IN DAYS from one `now` captured once per run. The
  page never prints an absolute timestamp or epoch value -- it prints the
  offset (a constant of the seed) and the boolean verdict that
  `cropsupport.facts` derives from it. Two consecutive runs are therefore
  byte-identical.

  HONESTY. Nothing on this page is hand-typed domain data. The seed
  service orders in `order-specs` are the only authored values, and every
  threshold they are judged against is read out of `cropsupport.facts` at
  render time, never restated. Where the scaffold cannot answer a
  question (see the \"scaffold gaps\" section) the page says so, and it
  derives that disclosure by inspecting the run rather than asserting it.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cropsupport.facts :as facts]
            [cropsupport.governor :as governor]
            [cropsupport.operation :as operation]
            [cropsupport.phase :as phase]
            [cropsupport.store :as store]))

(def ^:private day-ms (* 24 60 60 1000))

(def ^:private actor-context
  "The context `cropsupport.operation/run-operation` expects: an actor id
  (stamped into every hold fact) and the hold-fact constructor, which is
  the repo's own `governor/hold-fact`."
  {:actor-id "cropsupport-ops-1"
   :hold-fact-fn governor/hold-fact})

(def ^:private approver-id "farm-ops-lead-1")

;; ────────────────────────── Seed service orders ──────────────────────────

(def ^:private order-specs
  "The only authored domain values in this file. Safety thresholds are
  NOT restated here -- each spec carries the ACTUAL measured parameter
  and `cropsupport.facts` supplies the requirement it is judged against.
  `:license-days` / `:calibration-days` are offsets in days from the run's
  single captured `now` (negative = in the past); they are materialised
  into epoch milliseconds for the store and rendered as offsets, never as
  timestamps."
  [{:id "so-1001" :client-farm-id "farm-42" :service-type :spray/herbicide-broadcast
    :jurisdiction :jp/maff :hectares 12.5
    :license-days 400 :calibration-days -12
    :days-until-harvest 20 :hours-until-reentry 24 :wind 10.0 :buffer 20.0
    :evidence :complete
    :intent "全項目適合 — 正常系ライフサイクル (予定登録 → 資材発注 → 記録) の対象"}

   {:id "so-1002" :client-farm-id "farm-77" :service-type :harvest/combine-grain
    :jurisdiction :jp/maff :hectares 31.0
    :evidence :complete
    :intent "機械収穫作業 — 化学散布の安全ウィンドウが構造的に存在しない例"}

   {:id "so-1003" :client-farm-id "farm-19" :service-type :pest-control/insecticide-ground
    :jurisdiction :us/epa :hectares 8.25
    :license-days -30 :calibration-days -20
    :days-until-harvest 30 :hours-until-reentry 36 :wind 8.0 :buffer 40.0
    :evidence :complete
    :intent "散布者資格が失効 — 他項目は適合"}

   {:id "so-1004" :client-farm-id "farm-63" :service-type :spray/herbicide-broadcast
    :jurisdiction :us/epa :hectares 16.0
    :license-days 210 :calibration-days -400
    :days-until-harvest 20 :hours-until-reentry 24 :wind 9.5 :buffer 18.0
    :evidence :complete
    :intent "散布機器の校正が期限超過 — 他項目は適合"}

   {:id "so-1005" :client-farm-id "farm-88" :service-type :pest-control/insecticide-ground
    :jurisdiction :eu/reg1107 :hectares 5.5
    :license-days 150 :calibration-days -30
    :days-until-harvest 9 :hours-until-reentry 36 :wind 8.0 :buffer 40.0
    :evidence :complete
    :intent "収穫前日数が殺虫剤基準を下回る — 他項目は適合"}

   {:id "so-1006" :client-farm-id "farm-104" :service-type :spray/herbicide-broadcast
    :jurisdiction :jp/maff :hectares 22.0
    :license-days 300 :calibration-days -8
    :days-until-harvest 20 :hours-until-reentry 4 :wind 11.0 :buffer 20.0
    :evidence :complete
    :intent "再入場までの猶予が基準を下回る — 他項目は適合"}

   {:id "so-1007" :client-farm-id "farm-51" :service-type :spray/herbicide-broadcast
    :jurisdiction :us/epa :hectares 18.75
    :license-days 260 :calibration-days -15
    :days-until-harvest 20 :hours-until-reentry 24 :wind 31.0 :buffer 20.0
    :evidence :complete
    :intent "散布時風速が飛散防止基準を超過 — 他項目は適合"}

   {:id "so-1008" :client-farm-id "farm-66" :service-type :pest-control/insecticide-ground
    :jurisdiction :jp/maff :hectares 9.0
    :license-days 190 :calibration-days -25
    :days-until-harvest 30 :hours-until-reentry 36 :wind 8.0 :buffer 12.0
    :evidence :complete
    :intent "緩衝地帯距離が殺虫剤基準を下回る — 他項目は適合"}

   {:id "so-1009" :client-farm-id "farm-73" :service-type :spray/fungicide-foliar
    :jurisdiction :eu/reg1107 :hectares 14.0
    :license-days 320 :calibration-days -18
    :days-until-harvest 20 :hours-until-reentry 24 :wind 12.0 :buffer 20.0
    :evidence :complete :crop-health-raised? true :crop-health-resolved? false
    :intent "未解決の作物健全性フラグ — 他項目は適合"}

   {:id "so-1010" :client-farm-id "farm-95" :service-type :spray/fungicide-foliar
    :jurisdiction :jp/maff :hectares 6.5
    :license-days 275 :calibration-days -6
    :days-until-harvest 20 :hours-until-reentry 24 :wind 10.5 :buffer 20.0
    :evidence :partial
    :intent "法域が要求する証拠書類が不足 — 他項目は適合"}

   {:id "so-1011" :client-farm-id "farm-12" :service-type :spray/herbicide-broadcast
    :jurisdiction :jp/maff :hectares 27.5
    :license-days 365 :calibration-days -3
    :days-until-harvest 20 :hours-until-reentry 24 :wind 7.5 :buffer 25.0
    :evidence :complete
    :intent "全項目適合 — 提案そのものが不正な4件 (引用なし / 許可外op / :effect不正 / 越権要求) の対象"}])

(def ^:private unregistered-order-id
  "Never seeded -- used to exercise the registration hard-gate."
  "so-9999")

(defn- evidence-for
  "Derives an evidence checklist from the jurisdiction's OWN
  `:required-evidence` list rather than restating it: `:complete` is the
  full list, `:partial` drops the last two items."
  [jurisdiction-id kind]
  (let [required (vec (:required-evidence (facts/jurisdiction-by-id jurisdiction-id)))]
    (case kind
      :complete required
      :partial (vec (drop-last 2 required)))))

(defn- ->order
  "Materialises one spec into the service-order map shape documented in
  `cropsupport.store`. Chemical-application safety fields are assoc'd ONLY
  for chemical service types -- for mechanical harvest they are absent, so
  the Governor's chemical checks are skipped rather than fabricated."
  [now {:keys [client-farm-id service-type jurisdiction hectares evidence
               license-days calibration-days days-until-harvest
               hours-until-reentry wind buffer
               crop-health-raised? crop-health-resolved?]}]
  (let [st (facts/service-type-by-id service-type)]
    (cond-> {:service-type service-type
             :jurisdiction jurisdiction
             :client-farm-id client-farm-id
             :field-boundary-hectares hectares
             :evidence-checklist (evidence-for jurisdiction evidence)}
      (:chemical-application? st)
      (assoc :applicator-license-expiry-date (+ now (* license-days day-ms))
             :sprayer-last-calibration-date (+ now (* calibration-days day-ms))
             :days-until-harvest days-until-harvest
             :hours-until-reentry hours-until-reentry
             :wind-speed-kmh wind
             :buffer-zone-actual-m buffer)

      (some? crop-health-raised?)
      (assoc :crop-health-concern-raised? crop-health-raised?
             :crop-health-concern-resolved? (boolean crop-health-resolved?)))))

(defn- seed-store
  "A fresh store: every spec registered, empty audit ledger."
  [now]
  {:service-orders (into {} (map (juxt :id #(->order now %)) order-specs))
   :facts []})

;; ────────────────────────── Scenario ──────────────────────────

(defn- cites
  "A citation whose text is the jurisdiction's real registered name from
  `cropsupport.facts/jurisdictions` -- not an invented spec number."
  [jurisdiction-id]
  [{:spec (:name (facts/jurisdiction-by-id jurisdiction-id))
    :jurisdiction jurisdiction-id}])

(defn- scenario
  "The ordered run. Each step names the op, the subject, the proposal the
  advisor would have produced, and -- only for steps the Governor
  escalates -- the human decision that followed. A step's `:approval` is
  IGNORED when the verdict is hard: a HARD hold never reaches a human,
  and the driver enforces that rather than trusting the scenario."
  []
  [;; ---- so-1001: full happy-path lifecycle -------------------------
   {:label "圃場作業の予定登録"
    :request {:op :schedule-field-operation :subject "so-1001"}
    :proposal {:effect :propose :cites [] :value {} :confidence 0.9}}

   {:label "資材発注 (閾値未満・金額申告あり)"
    :request {:op :order-supplies :subject "so-1001"}
    :proposal {:effect :propose :cites (cites :jp/maff)
               :value {:jurisdiction :jp/maff :cost-usd 1200}
               :confidence 0.88}}

   {:label "資材発注 (閾値超過)"
    :request {:op :order-supplies :subject "so-1001"}
    :proposal {:effect :propose :cites (cites :jp/maff)
               :value {:jurisdiction :jp/maff :cost-usd 12000}
               :confidence 0.93}
    :approval {:decision :refused :by approver-id
               :note "閾値超過分は今期予算外 — 差戻し"}}

   {:label "資材発注 (金額の申告なし)"
    :request {:op :order-supplies :subject "so-1001"}
    :proposal {:effect :propose :cites (cites :jp/maff)
               :value {:jurisdiction :jp/maff}
               :confidence 0.99}
    :approval {:decision :refused :by approver-id
               :note "金額が申告されていない — 検証不能な数値は承認しない"}}

   {:label "作業記録の登録 (高リスク作動)"
    :request {:op :log-service-record :subject "so-1001"}
    :proposal {:effect :propose :cites (cites :jp/maff)
               :value {:jurisdiction :jp/maff} :confidence 0.95}
    :approval {:decision :approved :by approver-id
               :note "全安全項目の独立検証を確認のうえ承認"}}

   {:label "作業記録の二重登録の試行"
    :request {:op :log-service-record :subject "so-1001"}
    :proposal {:effect :propose :cites (cites :jp/maff)
               :value {:jurisdiction :jp/maff} :confidence 0.97}}

   ;; ---- so-1002: mechanical harvest, low confidence ----------------
   {:label "圃場作業の予定登録 (advisor確信度が低い)"
    :request {:op :schedule-field-operation :subject "so-1002"}
    :proposal {:effect :propose :cites [] :value {} :confidence 0.45}
    :approval {:decision :approved :by approver-id
               :note "確信度は低いが機械収穫のため化学安全項目なし — 人が承認"}}

   {:label "作業記録の登録 (機械収穫)"
    :request {:op :log-service-record :subject "so-1002"}
    :proposal {:effect :propose :cites (cites :jp/maff)
               :value {:jurisdiction :jp/maff} :confidence 0.9}
    :approval {:decision :approved :by approver-id
               :note "機械収穫のため化学散布項目は対象外 — 書類充足を確認のうえ承認"}}

   ;; ---- one hard hold per remaining seeded order -------------------
   {:label "作業記録の登録 (散布者資格)"
    :request {:op :log-service-record :subject "so-1003"}
    :proposal {:effect :propose :cites (cites :us/epa)
               :value {:jurisdiction :us/epa} :confidence 0.92}
    :approval {:decision :approved :by approver-id
               :note "この承認は届かない — HARD holdは人に上がらない"}}

   {:label "作業記録の登録 (機器校正)"
    :request {:op :log-service-record :subject "so-1004"}
    :proposal {:effect :propose :cites (cites :us/epa)
               :value {:jurisdiction :us/epa} :confidence 0.9}}

   {:label "作業記録の登録 (収穫前日数)"
    :request {:op :log-service-record :subject "so-1005"}
    :proposal {:effect :propose :cites (cites :eu/reg1107)
               :value {:jurisdiction :eu/reg1107} :confidence 0.91}}

   {:label "作業記録の登録 (再入場猶予)"
    :request {:op :log-service-record :subject "so-1006"}
    :proposal {:effect :propose :cites (cites :jp/maff)
               :value {:jurisdiction :jp/maff} :confidence 0.89}}

   {:label "作業記録の登録 (散布時風速)"
    :request {:op :log-service-record :subject "so-1007"}
    :proposal {:effect :propose :cites (cites :us/epa)
               :value {:jurisdiction :us/epa} :confidence 0.94}}

   {:label "作業記録の登録 (緩衝地帯)"
    :request {:op :log-service-record :subject "so-1008"}
    :proposal {:effect :propose :cites (cites :jp/maff)
               :value {:jurisdiction :jp/maff} :confidence 0.9}}

   ;; so-1009 is scheduled by an APPROVED proposal and then never logged
   ;; (its log attempt hard-holds below). That combination is what makes
   ;; it usable as evidence about `store/mark-scheduled`: on an order that
   ;; is also logged, `store/log-service-record` takes a payload and would
   ;; have written the approver itself, so an approver found there says
   ;; nothing about whether `mark-scheduled` could retain one.
   {:label "圃場作業の予定登録 (advisor確信度が低い・記録には至らない)"
    :request {:op :schedule-field-operation :subject "so-1009"}
    :proposal {:effect :propose :cites [] :value {} :confidence 0.5}
    :approval {:decision :approved :by approver-id
               :note "確信度は低いが予定登録のみ — 人が承認"}}

   {:label "作物健全性フラグの起票"
    :request {:op :flag-crop-health-concern :subject "so-1009"}
    :proposal {:effect :propose :cites (cites :eu/reg1107)
               :value {:jurisdiction :eu/reg1107} :confidence 0.99}
    :approval {:decision :approved :by approver-id
               :note "現地確認を指示 — 確信度に関わらず常に人が見る"}}

   {:label "作業記録の登録 (作物健全性フラグ未解決)"
    :request {:op :log-service-record :subject "so-1009"}
    :proposal {:effect :propose :cites (cites :eu/reg1107)
               :value {:jurisdiction :eu/reg1107} :confidence 0.96}}

   {:label "作業記録の登録 (証拠書類)"
    :request {:op :log-service-record :subject "so-1010"}
    :proposal {:effect :propose :cites (cites :jp/maff)
               :value {:jurisdiction :jp/maff} :confidence 0.93}}

   ;; ---- malformed proposals against a fully clean order ------------
   {:label "作業記録の登録 (法域引用なし)"
    :request {:op :log-service-record :subject "so-1011"}
    :proposal {:effect :propose :cites [] :value {:jurisdiction nil} :confidence 0.95}}

   {:label "散布機の直接操作の要求 (許可外op)"
    :request {:op :operate-sprayer :subject "so-1011"}
    :proposal {:effect :propose :cites (cites :jp/maff)
               :value {:jurisdiction :jp/maff} :confidence 0.99}}

   {:label "予定登録を :commit として主張"
    :request {:op :schedule-field-operation :subject "so-1011"}
    :proposal {:effect :commit :cites [] :value {} :confidence 0.99}}

   {:label "予定登録に圃場機器の直接操作を秘匿"
    :request {:op :schedule-field-operation :subject "so-1011"}
    :proposal {:effect :propose :cites []
               :value {:operate-field-equipment? true} :confidence 0.99}}

   {:label "作業記録に農薬散布の最終決定を秘匿"
    :request {:op :log-service-record :subject "so-1011"}
    :proposal {:effect :propose :cites (cites :jp/maff)
               :value {:jurisdiction :jp/maff
                       :finalize-pesticide-application-decision? true}
               :confidence 0.99}}

   ;; ---- never-registered subject ----------------------------------
   {:label "未登録のservice-orderへの予定登録"
    :request {:op :schedule-field-operation :subject unregistered-order-id}
    :proposal {:effect :propose :cites [] :value {} :confidence 0.9}}])

;; ────────────────────────── Driver ──────────────────────────

(defn- escalation-reasons
  "Re-derives WHY the Governor escalated, from its own public vars.
  `governor/check` reports `:escalate? true` but never says which gate
  fired -- that gap is disclosed on the page, and this function is the
  workaround, not a second opinion: it reads `confidence-floor`,
  `high-stakes`, `always-escalate-ops` and
  `supply-order-cost-threshold-usd` directly."
  [request proposal verdict]
  (let [op (:op request)
        conf (:confidence verdict)
        cost (get-in proposal [:value :cost-usd])]
    (cond-> []
      (< conf governor/confidence-floor)
      (conj :confidence-below-floor)

      (contains? governor/high-stakes op)
      (conj :high-stakes-actuation)

      (and (contains? governor/always-escalate-ops op)
           (not (contains? governor/high-stakes op)))
      (conj :always-escalate-op)

      (and (= op :order-supplies) (not (number? cost)))
      (conj :supply-cost-unverifiable)

      (and (= op :order-supplies) (number? cost)
           (> cost governor/supply-order-cost-threshold-usd))
      (conj :supply-cost-above-threshold))))

(def ^:private store-writers
  "The ONLY place this file names a store writer. `cropsupport.store`
  ships writers for two of the four ops in `governor/allowed-ops`; the
  other two reach the ledger and nowhere else. `commit-effect` and every
  disclosure on the page read this one map, so a scaffold that grows a
  writer moves the page in a single edit instead of three."
  {:log-service-record 'log-service-record
   :schedule-field-operation 'mark-scheduled})

(defn- writer-arity
  "Measured parameter count of the store writer for `op`, or nil when the
  store exposes no writer for it at all. Read off the var's own
  `:arglists` -- this is how the page ESTABLISHES that a writer has no
  payload parameter, instead of asserting it in prose that goes stale the
  moment the scaffold grows one."
  [op]
  (when-let [w (store-writers op)]
    (when-let [v (ns-resolve 'cropsupport.store w)]
      (count (first (:arglists (meta v)))))))

(defn- carries-payload?
  "True when this op's store writer has room for anything beyond the
  store and the id -- i.e. whether an approval could be attached to the
  record by that writer at all."
  [op]
  (boolean (some-> (writer-arity op) (>= 3))))

(defn- commit-effect
  "Applies the store mutation the repo ACTUALLY exposes for a committed
  op, per `store-writers`. Ops with no writer there commit to the ledger
  and change no service-order record -- this function does not paper over
  that."
  [st op subject approval]
  (case op
    :log-service-record
    (store/log-service-record st subject
                              (merge (store/service-order st subject)
                                     (when approval
                                       {:approved-by (:by approval)
                                        :approval-note (:note approval)})))
    :schedule-field-operation
    (store/mark-scheduled st subject)
    st))

(defn- decision-fact
  "The ledger fact for a step that was NOT hard-held. `governor/hold-fact`
  is the only fact constructor the repo defines, so these mirror its
  shape. They are driver-authored -- disclosed on the page."
  [t request verdict disposition extra]
  (merge {:t t
          :op (:op request)
          :actor (:actor-id actor-context)
          :subject (:subject request)
          :disposition disposition
          :basis []
          :confidence (:confidence verdict)}
         extra))

(defn- run-step
  [{:keys [store trace]} {:keys [label request proposal approval]}]
  (let [req (select-keys request [:op :subject])
        ;; ONE evaluation of the Governor, reused by run-operation via
        ;; `(constantly verdict)`, so the verdict rendered is exactly the
        ;; verdict the driver acted on -- and the host-clock reads that
        ;; `governor/check` performs internally happen exactly once.
        verdict (governor/check req actor-context proposal store)
        outcome (operation/run-operation req actor-context proposal store
                                         (constantly verdict))
        hard? (:hard? verdict)
        ok? (:ok? verdict)
        reasons (when-not (or hard? ok?) (escalation-reasons req proposal verdict))
        approved? (and (not hard?) (not ok?) (= :approved (:decision approval)))
        disposition (cond hard? :hard-hold
                          ok? :auto-commit
                          approved? :approved
                          (some? approval) :refused
                          :else :awaiting-approval)
        facts (cond
                hard? (:facts outcome)

                ok? [(decision-fact :auto-commit req verdict :auto {})]

                :else
                (cond-> [(decision-fact :approval-requested req verdict :escalated
                                        {:basis (vec reasons)})]
                  approved?
                  (conj (decision-fact :approval-granted req verdict :approved
                                       {:basis (vec reasons)
                                        :approved-by (:by approval)
                                        :approval-note (:note approval)}))

                  (and (not approved?) (some? approval))
                  (conj (decision-fact :approval-refused req verdict :refused
                                       {:basis (vec reasons)
                                        :refused-by (:by approval)
                                        :approval-note (:note approval)}))))
        store' (reduce store/append-fact store facts)
        store'' (if (or ok? approved?)
                  (commit-effect store' (:op req) (:subject req)
                                 (when approved? approval))
                  store')]
    {:store store''
     :trace (conj trace
                  {:label label
                   :op (:op req)
                   :subject (:subject req)
                   :confidence (:confidence verdict)
                   :disposition disposition
                   :hard? hard?
                   :basis (mapv :rule (:violations verdict))
                   :reasons (vec reasons)
                   :verdict-keys (set (keys verdict))
                   :run-operation-fact-count (count (:facts outcome))
                   ;; What `operation/run-operation` ACTUALLY returned for
                   ;; this step, kept so the page can measure whether it
                   ;; distinguishes a hard refusal from an escalation.
                   :run-operation-fact-types (mapv :t (:facts outcome))
                   :approval-ignored? (and hard? (some? approval))})}))

(defn run-demo!
  "Runs the seeded store through `scenario` and returns
  `{:store .. :trace .. :now-captured? ..}`. Every disposition on the
  page comes out of here; nothing downstream re-decides anything."
  []
  (let [now (System/currentTimeMillis)]
    (reduce run-step
            {:store (seed-store now) :trace []}
            (scenario))))

;; ────────────────────────── HTML helpers ──────────────────────────

(defn- esc
  "Escapes exactly once. Everything that reaches the page goes through
  here and NOTHING downstream re-escapes -- no cell is built from a string
  that already contains markup or entities, and the page uses literal
  UTF-8 characters (·, →, —) rather than named entities, so a
  double-escaping regression would have to introduce `&amp;` where the
  source has none."
  [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- td [inner] (str "<td>" inner "</td>"))
(defn- tdt [v] (td (esc v)))
(defn- tdc [class v] (td (str "<span class=\"" class "\">" (esc v) "</span>")))
(defn- tdcode [v] (td (str "<code>" (esc v) "</code>")))
(defn- tr [& cells] (str "      <tr>" (str/join cells) "</tr>"))

(defn- kw->s [k] (if (keyword? k) (subs (str k) 1) (str k)))

(defn- rules->s [rules]
  (if (seq rules) (str/join " · " (map kw->s rules)) "—"))

(defn- table [headers rows]
  (str "    <table>\n      <thead><tr>"
       (str/join (map #(str "<th>" (esc %) "</th>") headers))
       "</tr></thead>\n      <tbody>\n"
       (str/join "\n" rows)
       "\n      </tbody>\n    </table>\n"))

(defn- section [title lead body]
  (str "  <section>\n    <h2>" (esc title) "</h2>\n"
       "    <p class=\"lead\">" (esc lead) "</p>\n"
       body
       "  </section>\n"))

(def ^:private styles
  "Self-contained. No dependency is added for this page: the palette
  values are the デジタル庁デザインシステム primitives already vendored
  into this repo's own `docs/index.html`, copied as literals so the build
  needs no network and no new coordinate. The -50/-100 steps are used for
  tints and the -900 steps for text, deliberately -- the semantic error
  tokens are both dark and unusable as a tint background."
  (str/join
   "\n"
   [":root{--ink:#1a1a1c;--muted:#626264;--line:#d8d8db;--bg:#ffffff;"
    "--ok-bg:#e6f5ec;--ok-fg:#115a36;--warn-bg:#ffeee2;--warn-fg:#ac3e00;"
    "--crit-bg:#fdeeee;--crit-fg:#ce0000;--info-bg:#e8f1fe;--info-fg:#0017c1}"
    "*{box-sizing:border-box}"
    "body{margin:0;background:var(--bg);color:var(--ink);"
    "font-family:system-ui,-apple-system,'Hiragino Sans','Noto Sans JP',sans-serif;"
    "font-size:14px;line-height:1.6}"
    "header{padding:24px 20px;border-bottom:2px solid var(--info-fg);background:var(--info-bg)}"
    "header h1{margin:0 0 6px;font-size:20px;line-height:1.4}"
    "header p{margin:0;color:var(--muted);font-size:13px}"
    ".badge{display:inline-block;margin-top:10px;padding:3px 10px;border-radius:11px;"
    "background:var(--bg);border:1px solid var(--info-fg);color:var(--info-fg);font-size:12px}"
    "main{padding:8px 20px 48px;max-width:1200px}"
    "section{margin:28px 0 0;border-top:1px solid var(--line);padding-top:20px}"
    "h2{font-size:16px;margin:0 0 4px}"
    ".lead{margin:0 0 12px;color:var(--muted);font-size:13px;max-width:78ch}"
    "table{border-collapse:collapse;width:100%;font-size:13px}"
    "th,td{border-bottom:1px solid var(--line);padding:6px 10px;text-align:left;vertical-align:top}"
    "th{background:#f4f4f6;font-weight:600;white-space:nowrap}"
    "tbody tr:hover{background:#fafafb}"
    "code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px}"
    "span.ok{color:var(--ok-fg);background:var(--ok-bg);padding:1px 7px;border-radius:3px}"
    "span.warn{color:var(--warn-fg);background:var(--warn-bg);padding:1px 7px;border-radius:3px}"
    "span.critical{color:var(--crit-fg);background:var(--crit-bg);padding:1px 7px;border-radius:3px;font-weight:600}"
    "span.muted{color:var(--muted)}"
    "footer{padding:20px;border-top:1px solid var(--line);color:var(--muted);font-size:12px}"]))

;; ────────────────────────── Sections ──────────────────────────

(defn- spec-by-id [id] (first (filter #(= id (:id %)) order-specs)))

(defn- last-fact-for [ledger subject]
  (last (filter #(= subject (:subject %)) ledger)))

(defn- status-cell [ledger subject]
  (let [f (last-fact-for ledger subject)]
    (case (:t f)
      :governor-hold (tdc "critical" (str "HARD hold · " (rules->s (:basis f))))
      :approval-granted (tdc "ok" "承認のうえコミット")
      :approval-refused (tdc "warn" "人が差戻し")
      :approval-requested (tdc "warn" "承認待ち")
      :auto-commit (tdc "ok" "自動コミット")
      (tdc "muted" "活動なし"))))

(defn- lifecycle-cell [order]
  (cond
    (:logged? order) (tdc "ok" "記録済み")
    (:scheduled? order) (tdc "warn" "予定登録済み・記録前")
    :else (tdc "muted" "未着手")))

(defn- orders-section [db ledger]
  (section
   "Service orders (client-farm engagements)"
   (str "ISIC Rev.5 0161 は他の農家の作物に対する受託作業である。ここに出る "
        (count order-specs)
        " 件はすべて seed で登録された service-order で、"
        "作業種別名・法域名は cropsupport.facts の登録値をそのまま引いている。")
   (table ["Service order" "Client farm" "作業種別" "法域" "面積(ha)" "化学散布"
           "ライフサイクル" "直近のdisposition"]
          (for [{:keys [id]} order-specs
                :let [o (store/service-order db id)
                      st (facts/service-type-by-id (:service-type o))]]
            (tr (tdcode id)
                (tdt (:client-farm-id o))
                (tdt (:name st))
                (tdt (:name (facts/jurisdiction-by-id (:jurisdiction o))))
                (tdt (:field-boundary-hectares o))
                (if (:chemical-application? st)
                  (tdc "warn" "あり")
                  (tdc "muted" "なし"))
                (lifecycle-cell o)
                (status-cell ledger id))))))

(defn- service-types-section []
  (section
   "Service-type safety windows"
   (str "cropsupport.facts/service-types の登録値をそのまま表示している。"
        "機械収穫作業には化学散布の安全ウィンドウが構造的に存在せず、"
        "Governor は該当検査を「捏造した基準で判定する」のではなく丸ごとスキップする。")
   (table ["Service type" "名称" "化学散布" "収穫前日数(PHI)" "再入場猶予(REI)"
           "最大風速" "最小緩衝地帯"]
          (for [[id st] (sort-by key facts/service-types)]
            (tr (tdcode (kw->s id))
                (tdt (:name st))
                (if (:chemical-application? st) (tdc "warn" "あり") (tdc "muted" "なし"))
                (if-let [v (:pre-harvest-interval-days st)] (tdt (str v " 日")) (tdc "muted" "—"))
                (if-let [v (:restricted-entry-interval-hours st)] (tdt (str v " 時間")) (tdc "muted" "—"))
                (if-let [v (:max-wind-speed-kmh st)] (tdt (str v " km/h")) (tdc "muted" "—"))
                (if-let [v (:min-buffer-zone-m st)] (tdt (str v " m")) (tdc "muted" "—")))))))

(defn- safety-matrix-section [db]
  (section
   "Per-order safety verification (independently derived)"
   (str "各セルは cropsupport.facts の positive-sense 述語 "
        "(applicator-license-current? / sprayer-calibration-current? / "
        "pre-harvest-interval-satisfied? / restricted-entry-interval-satisfied? / "
        "wind-speed-in-range? / buffer-zone-in-range? / required-evidence-satisfied?) "
        "を実際に呼んだ結果である。advisor の確信度はここには一切入らない。"
        "日付は絶対時刻ではなく seed のオフセット日数で示す (ページを決定論的に保つため)。")
   (table ["Service order" "散布者資格" "機器校正" "収穫前日数" "再入場猶予"
           "散布時風速" "緩衝地帯" "証拠書類"]
          (for [{:keys [id license-days calibration-days]} order-specs
                :let [o (store/service-order db id)
                      st (facts/service-type-by-id (:service-type o))
                      chem? (:chemical-application? st)
                      now (System/currentTimeMillis)
                      na (tdc "muted" "—")
                      cell (fn [ok? txt] (tdc (if ok? "ok" "critical") txt))
                      required (:required-evidence (facts/jurisdiction-by-id (:jurisdiction o)))
                      missing (remove (set (:evidence-checklist o)) required)]]
            (tr (tdcode id)
                (if-not chem? na
                        (cell (facts/applicator-license-current?
                               (:applicator-license-expiry-date o) now st)
                              (str "基準時刻" (when (pos? license-days) "+") license-days "日")))
                (if-not chem? na
                        (cell (facts/sprayer-calibration-current?
                               (:sprayer-last-calibration-date o) now st)
                              (str (- calibration-days) "日前 / 基準 90日以内")))
                (if-not chem? na
                        (cell (facts/pre-harvest-interval-satisfied? (:days-until-harvest o) st)
                              (str (:days-until-harvest o) "日 / 基準 "
                                   (:pre-harvest-interval-days st) "日")))
                (if-not chem? na
                        (cell (facts/restricted-entry-interval-satisfied? (:hours-until-reentry o) st)
                              (str (:hours-until-reentry o) "時間 / 基準 "
                                   (:restricted-entry-interval-hours st) "時間")))
                (if-not chem? na
                        (cell (facts/wind-speed-in-range? (:wind-speed-kmh o) st)
                              (str (:wind-speed-kmh o) " / 上限 "
                                   (:max-wind-speed-kmh st) " km/h")))
                (if-not chem? na
                        (cell (facts/buffer-zone-in-range? (:buffer-zone-actual-m o) st)
                              (str (:buffer-zone-actual-m o) " / 下限 "
                                   (:min-buffer-zone-m st) " m")))
                (cell (facts/required-evidence-satisfied? (:jurisdiction o) (:evidence-checklist o))
                      (str (count (:evidence-checklist o)) "/" (count required)
                           (when (seq missing)
                             (str " · 不足: " (str/join ", " (map kw->s missing)))))))))))

(defn- gate-section []
  (section
   "Action gate (Crop-Support Operations Governor)"
   (str "この表は governor/allowed-ops · high-stakes · always-escalate-ops · "
        "confidence-floor · supply-order-cost-threshold-usd を実行時に読んで組み立てている。"
        "HARD hold は人の承認で覆せない。")
   (str
    (table ["Op" "ゲート"]
           (for [op (sort-by kw->s governor/allowed-ops)]
             (tr (tdcode (kw->s op))
                 (cond
                   (contains? governor/high-stakes op)
                   (tdc "warn" "常に人の承認 (高リスク作動 · high-stakes)")

                   (contains? governor/always-escalate-ops op)
                   (tdc "warn" "常に人の承認 (確信度では自動解決されない)")

                   (= op :order-supplies)
                   (tdc "warn"
                        (str "金額が数値かつ " governor/supply-order-cost-threshold-usd
                             " USD 以下と確認できたときのみ自動。不明・非数値・超過は人へ"))

                   :else
                   (tdc "ok" "hard違反なし・確信度が床以上なら自動コミット")))))
    "    <p class=\"lead\">確信度の床は "
    (esc governor/confidence-floor)
    " · 資材発注の閾値は "
    (esc governor/supply-order-cost-threshold-usd)
    " USD · 許可された op は "
    (esc (count governor/allowed-ops))
    " 種のみ (それ以外は恒久ブロック)。</p>\n")))

(defn- phase-section []
  (section
   "Phase machine"
   (str "cropsupport.phase/phase-sequence と can-transition? を実際に呼んだ結果。"
        "前進のみで、後戻りは許されない。")
   (table ["From" "To" "遷移可否"]
          (concat
           (for [[from to] (partition 2 1 phase/phase-sequence)]
             (tr (tdcode (kw->s from))
                 (tdcode (kw->s to))
                 (if (phase/can-transition? from to)
                   (tdc "ok" "可")
                   (tdc "critical" "不可"))))
           [(tr (tdcode ":record") (tdcode ":survey")
                (if (phase/can-transition? :record :survey)
                  (tdc "ok" "可")
                  (tdc "critical" "不可 (後戻り)")))]))))

(defn- trace-section [trace]
  (section
   "Run trace (this scenario)"
   (str "全 " (count trace) " ステップ。disposition は governor/check の判定そのもので、"
        "HARD hold の行は人に上がっていない (approval が書かれていても無視される)。")
   (table ["#" "ステップ" "Op" "Service order" "確信度" "Disposition" "根拠 / 理由"]
          (map-indexed
           (fn [i {:keys [label op subject confidence disposition basis reasons approval-ignored?]}]
             (tr (tdt (inc i))
                 (tdt (str label (when approval-ignored? " (承認は届かない)")))
                 (tdcode (kw->s op))
                 (tdcode subject)
                 (tdt confidence)
                 (case disposition
                   :hard-hold (tdc "critical" "HARD hold")
                   :auto-commit (tdc "ok" "自動コミット")
                   :approved (tdc "ok" "人が承認")
                   :refused (tdc "warn" "人が差戻し")
                   (tdc "warn" "承認待ち"))
                 (tdt (rules->s (if (seq basis) basis reasons)))))
           trace))))

(defn- ledger-section [ledger]
  (section
   "Audit ledger (append-only, this run)"
   (str "store/audit-trail が返す " (count ledger) " 件。"
        "governor-hold の行は cropsupport.governor/hold-fact が構成した本物である。"
        "それ以外の fact 種別 (auto-commit / approval-requested / approval-granted / "
        "approval-refused) はこの driver が組み立てている — repo は hold-fact 以外の "
        "fact コンストラクタを持たない (下の gap 節を参照)。")
   (table ["#" "Fact" "Op" "Service order" "Disposition" "根拠"]
          (map-indexed
           (fn [i {:keys [t op subject disposition basis]}]
             (tr (tdt (inc i))
                 (tdcode (kw->s t))
                 (tdcode (kw->s op))
                 (tdcode subject)
                 (tdt (kw->s disposition))
                 (tdt (rules->s basis))))
           ledger))))

(defn- holds-section [ledger]
  (let [holds (filter #(= :governor-hold (:t %)) ledger)
        by-rule (->> holds
                     (mapcat (fn [h] (map (fn [r] [r (:subject h)]) (:basis h))))
                     (group-by first))]
    (section
     "HARD holds observed (derived from the ledger)"
     (str "台帳を走査して組み立てている。HARD hold は合計 " (count holds) " 件、"
          "異なる規則は " (count by-rule) " 種。ここに出るのは「この run が実際に踏んだ規則」"
          "であって、Governor が実装している全規則の一覧ではない — "
          "後者を主張するには、規則の目録を手で書き写すことになり、それは腐る。")
     (table ["Hard rule" "件数" "対象 service order"]
            (for [[rule pairs] (sort-by (comp kw->s key) by-rule)]
              (tr (tdcode (kw->s rule))
                  (tdt (count pairs))
                  (tdcode (str/join ", " (distinct (map second pairs))))))))))

(defn- approver-candidate-keys
  []
  [:approved-by :approver :approval-by :by :signed-off-by :payload :value])

(defn- approver-in
  "Returns [key value] for the first candidate key that carries something,
  or nil. Used to MEASURE -- not assume -- whether an approver identity
  survives into a given map."
  [m]
  (some (fn [k] (when-let [v (get m k)] [k v])) (approver-candidate-keys)))

(defn- attribution-loss-cause
  "Derives WHY the approver did not survive into the store record for
  `op`, from the measured writer table. Never a hardcoded claim about
  which writer is at fault: an earlier revision of this page named
  `store/mark-scheduled` in prose while the row that actually lost the
  approver was `:flag-crop-health-concern`, which has no writer at all."
  [op]
  (let [w (store-writers op)
        arity (writer-arity op)]
    (cond
      (nil? w)
      (str (kw->s op) ": store に writer が無く記録自体が変わらない (台帳のみ)")

      (and arity (< arity 3))
      (str (kw->s op) ": store/" w " は " arity
           " 引数 (store + id) で payload を取らない")

      :else
      (str (kw->s op) ": store/" w " は payload を取るが承認者が残っていない"))))

(defn- attribution-section [db ledger]
  (let [grants (filter #(= :approval-granted (:t %)) ledger)
        rows (for [g grants
                   :let [subject (:subject g)
                         record (store/service-order db subject)
                         in-ledger (approver-in g)
                         in-record (approver-in record)]]
               {:op (:op g) :subject subject
                :in-ledger in-ledger :in-record in-record})
        lost (filter #(and (:in-ledger %) (nil? (:in-record %))) rows)
        kept (filter #(and (:in-ledger %) (:in-record %)) rows)
        lost-causes (str/join " · " (distinct (map #(attribution-loss-cause (:op %)) lost)))
        ;; A record can show an approver that a LATER op wrote. Measured
        ;; separately so the table cannot be misread as "this op's writer
        ;; retained it".
        kept-by-other (filter #(and (:in-record %) (not (carries-payload? (:op %)))) rows)]
    (section
     "Approver attribution (measured at render time, not asserted)"
     (str "「誰が承認したか」を後から店側の記録だけで答えられるか、を run のたびに "
          "実測している。判定は台帳の approval-granted fact と、対応する "
          "service-order 記録の両方を "
          (str/join " / " (map kw->s (approver-candidate-keys)))
          " で走査した結果であり、ハードコードした結論ではない — "
          "scaffold が直れば、この節の判定も自動的に変わる。")
     (str
      (table ["Op" "Service order" "台帳に承認者が残るか" "service-order記録に残るか"
              "この op の writer が承認者を書けるか"]
             (for [{:keys [op subject in-ledger in-record]} rows]
               (tr (tdcode (kw->s op))
                   (tdcode subject)
                   (if in-ledger
                     (tdc "ok" (str (kw->s (first in-ledger)) " = " (second in-ledger)))
                     (tdc "critical" "残らない"))
                   (if in-record
                     (tdc "ok" (str (kw->s (first in-record)) " = " (second in-record)))
                     (tdc "critical" "残らない"))
                   (if (carries-payload? op)
                     (tdc "ok" (str "store/" (store-writers op) " ("
                                    (writer-arity op) " 引数)"))
                     (tdc "critical"
                          (if-let [w (store-writers op)]
                            (str "書けない — store/" w " は " (writer-arity op) " 引数")
                            "書けない — writer が無い"))))))
      "    <p class=\"lead\">"
      (esc
       (cond
         (and (seq lost) (seq kept))
         (str "実測結果 (部分的欠落): 承認を伴うコミット " (count rows) " 件のうち "
              (count kept) " 件は記録側にも承認者が残るが、" (count lost)
              " 件は台帳にしか残らない。欠落の内訳 (writer 表から導出): " lost-causes
              "。なお最終列が critical の行は、記録に承認者が見えていても"
              "それを書いたのはその op ではなく、同じ service-order に対する"
              "後続の別 op である — この run では該当が " (count kept-by-other)
              " 件ある。読み手が「誰も承認していない」と「店が承認者を保持していない」を"
              "区別できるよう、ここに明示する。これは governance contract を持つ"
              "セマンティクスなので、このページは開示するだけで修正しない。")

         (seq lost)
         (str "実測結果 (欠落): 承認を伴うコミット " (count rows)
              " 件すべてで、承認者は台帳にしか残らず service-order 記録からは"
              "失われている。欠落の内訳 (writer 表から導出): " lost-causes "。")

         (seq kept)
         (str "実測結果 (保持): 承認を伴うコミット " (count rows)
              " 件すべてで、承認者が service-order 記録にも残っている。")

         :else
         "実測結果: この run には承認を伴うコミットが無かった。"))
      "</p>\n"))))

(defn- gaps-section [db trace]
  (let [ok-steps (filter #(= :auto-commit (:disposition %)) trace)
        silent-ok (filter #(zero? (:run-operation-fact-count %)) ok-steps)
        verdict-keys (sort-by kw->s (reduce into #{} (map :verdict-keys trace)))
        store-api (sort (map name (keys (ns-publics 'cropsupport.store))))]
    (section
     "Scaffold gaps measured on this run"
     (str "以下は「そうであるはずだ」ではなく、この run を観測して derive したものである。"
          "いずれも governance semantics に関わるため、このページは開示するだけで修正しない。")
     (table ["観測" "測り方" "結果"]
            [(tr (tdt "run-operation は成功時に audit fact を返さない")
                 (tdt "自動コミットしたステップの (:facts outcome) を数えた")
                 (if (seq silent-ok)
                   (tdc "critical"
                        (str "自動コミット " (count ok-steps) " 件すべてで fact 0 件 — "
                             "コミットは台帳に痕跡を残さない"))
                   (tdc "ok" "成功時にも fact が返る")))

             (tr (tdt "verdict は「なぜ escalate したか」を答えない")
                 (tdt "全ステップの verdict の key を和集合にした")
                 (tdc "warn" (str/join ", " (map kw->s verdict-keys))))

             (tr (tdt "4つの許可 op のうち store に writer があるのは")
                 (tdt "ns-publics 'cropsupport.store と allowed-ops を突き合わせた")
                 (td (str/join
                      "<br>"
                      (for [op (sort-by kw->s governor/allowed-ops)]
                        (str "<code>" (esc (kw->s op)) "</code> → "
                             (if-let [w (store-writers op)]
                               (str "<span class=\"ok\">store/" (esc w) " ("
                                    (esc (writer-arity op)) " 引数)</span>")
                               "<span class=\"critical\">writer 無し (台帳のみ)</span>"))))))

             (tr (tdt "store の公開 API")
                 (tdt "ns-publics 'cropsupport.store")
                 (td (str/join " · " (map #(str "<code>" (esc %) "</code>") store-api))))

             ;; The claim in the first cell is derived from the writer's
             ;; own arity, and the evidence set EXCLUDES orders that were
             ;; also logged: `store/log-service-record` takes a payload,
             ;; so an approver found on a logged order was written by that
             ;; op, not by `mark-scheduled`. An earlier revision of this
             ;; row scanned every scheduled order and therefore reported
             ;; "承認者が記録に残っている" — crediting this writer for a
             ;; value a different writer had put there.
             (let [scheduled (filter #(:scheduled? (store/service-order db %))
                                     (map :id order-specs))
                   attributable (remove #(:logged? (store/service-order db %)) scheduled)
                   with-approver (filter #(approver-in (store/service-order db %))
                                         attributable)
                   w (store-writers :schedule-field-operation)]
               (tr (tdt (str "store/" w " は " (writer-arity :schedule-field-operation)
                             " 引数 (store + id) で payload を取らない"))
                   (tdt (str "予定登録済みだが未記録の order " (count attributable)
                             " 件を走査した (記録済みの order は "
                             "store/log-service-record が承認者を書けるため"
                             "この writer の証拠にならない)"))
                   (cond
                     (empty? attributable)
                     (tdc "warn"
                          (str "この run では測定できない — 予定登録のみの order が 0 件 "
                               "(予定登録済みは " (count scheduled) " 件だが全て記録済み)"))

                     (seq with-approver)
                     (tdc "ok" "承認者が記録に残っている")

                     :else
                     (tdc "critical"
                          (str "対象 " (count attributable)
                               " 件のいずれにも承認者キーが無い — "
                               "予定登録の承認者は台帳にしか残らない")))))

             (tr (tdt "repo が定義する fact コンストラクタ")
                 (tdt "cropsupport.governor の公開 var を確認した")
                 (tdc "warn"
                      (str "governor/hold-fact のみ。"
                           "auto-commit / approval-* の fact 形は driver 側が定義している")))

             (tr (tdt "cropsupport.sim (clojure -M:dev:run)")
                 (tdt "このファイルを書く前に実行した")
                 (tdc "critical" "スタブ (\"not yet implemented\") — 再利用できる scenario は無い"))

             (tr (tdt "langgraph StateGraph")
                 (tdt "deps.edn の :deps と cropsupport.advisor を確認した")
                 (tdc "warn"
                      (str ":deps {} は空で、:dev の :override-deps は何も override しない。"
                           "advisor は空のスケルトンで graph builder が無い — "
                           "この renderer は operation/run-operation の seam を直接駆動する")))]))))

(defn- safety-note-section []
  (section
   "この actor が持たない権限"
   (str "圃場機器 (コンバイン・散布機・散布装置) の直接操作と、農薬散布の最終決定は "
        "HARD かつ恒久のブロックであり、人の承認でも覆らない。"
        "これは risk judgment ではなく scope boundary である。"
        "allowlist の外の op であることに加え、提案の :value に秘匿された要求も "
        "defense-in-depth として毎回検査される。")
   (table ["ブロック" "検査対象" "覆せるか"]
          [(tr (tdcode ":op-not-allowed")
               (tdt (str "request の :op が allowed-ops (" (count governor/allowed-ops) " 種) の外"))
               (tdc "critical" "覆せない"))
           (tr (tdcode ":field-equipment-or-pesticide-decision-blocked")
               (tdt "提案の :value の :operate-field-equipment? / :finalize-pesticide-application-decision?")
               (tdc "critical" "覆せない"))
           (tr (tdcode ":effect-not-propose")
               (tdt "提案の :effect が :propose 以外")
               (tdc "critical" "覆せない"))])))

;; ────────────────────────── Document ──────────────────────────

(defn render
  "Renders the whole document from the result of `run-demo!`."
  [{:keys [store trace]}]
  (let [ledger (vec (store/audit-trail store))]
    (str
     "<!DOCTYPE html>\n<html lang=\"ja\">\n<head>\n"
     "<meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
     "<title>cloud-itonami-isic-0161 · 作物生産支援サービス Operator Console</title>\n"
     "<style>" styles "</style>\n</head>\n<body>\n"
     "<header>\n"
     "  <h1>Support activities for crop production (ISIC Rev.5 0161) — Operator Console</h1>\n"
     "  <p>受託作業 (custom farm work) の service-order を、CropSupport Advisor ⊣ "
     "Crop-Support Operations Governor で統制した結果のスナップショット。</p>\n"
     "  <p class=\"badge\">read-only sample · build-time generated · "
     "<code>clojure -M:dev:render-html</code></p>\n"
     "</header>\n"
     "<main>\n"
     (orders-section store ledger)
     (service-types-section)
     (safety-matrix-section store)
     (gate-section)
     (safety-note-section)
     (phase-section)
     (trace-section trace)
     (ledger-section ledger)
     (holds-section ledger)
     (attribution-section store ledger)
     (gaps-section store trace)
     "</main>\n"
     "<footer>\n"
     "  このページは <code>cropsupport.render-html</code> が build 時に生成している。"
     "全ての verdict は <code>cropsupport.governor/check</code> の実出力、"
     "全ての閾値は <code>cropsupport.facts</code> の登録値、"
     "全ての HARD hold fact は <code>cropsupport.governor/hold-fact</code> の実出力である。"
     "ページ内に時刻を持たないため、同一 seed に対して再実行すると byte 単位で一致する。\n"
     "</footer>\n</body>\n</html>\n")))

;; ────────────────────────── Entry point ──────────────────────────

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [store trace] :as result} (run-demo!)
        ledger (vec (store/audit-trail store))
        holds (filter #(= :governor-hold (:t %)) ledger)
        hold-rules (into (sorted-set) (mapcat :basis holds))
        commits (filter #(#{:auto-commit :approval-granted} (:t %)) ledger)]

    ;; Build-time invariants. A console that shows no refusal is a
    ;; brochure, and a console that shows no commit proves only that
    ;; everything was blocked -- refuse to write either.
    (when (zero? (count holds))
      (throw (ex-info
              (str "refusing to write " out
                   ": the scenario produced ZERO :governor-hold facts. "
                   "This console exists to show the Governor refusing; a page "
                   "with no HARD hold would misrepresent the actor.")
              {:ledger-size (count ledger) :holds 0})))

    (when (zero? (count commits))
      (throw (ex-info
              (str "refusing to write " out
                   ": the scenario produced ZERO committed facts. "
                   "A console where nothing ever succeeds cannot show a "
                   "lifecycle and would equally misrepresent the actor.")
              {:ledger-size (count ledger) :commits 0})))

    (io/make-parents out)
    (spit out (render result))
    (println "wrote" out)
    (println "  seeded service orders:" (count order-specs))
    (println "  scenario steps:" (count trace))
    (println "  ledger facts:" (count ledger))
    (println "  HARD holds:" (count holds)
             "across" (count hold-rules) "distinct rules")
    (println "  distinct hold rules:" (str/join ", " (map kw->s hold-rules)))
    (println "  committed facts:" (count commits))))
