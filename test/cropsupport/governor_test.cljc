(ns cropsupport.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [cropsupport.governor :as governor]))

(def ^:private now-ms #?(:clj (System/currentTimeMillis) :cljs (.now js/Date)))
(def ^:private ten-days-ago (- now-ms (* 10 24 60 60 1000)))
(def ^:private hundred-days-ago (- now-ms (* 100 24 60 60 1000)))
(def ^:private ten-days-from-now (+ now-ms (* 10 24 60 60 1000)))

(def ^:private evidence-checklist
  [:service-order-record :field-boundary-map :application-record
   :applicator-license :weather-log :buffer-zone-assessment])

(def ^:private clean-herbicide-order
  "Baseline clean service order for a chemical-application service type
  (herbicide) -- has license/calibration/PHI/REI/wind/buffer specs."
  {:service-type :spray/herbicide-broadcast
   :jurisdiction :jp/maff
   :client-farm-id "farm-42"
   :applicator-license-expiry-date ten-days-from-now
   :sprayer-last-calibration-date ten-days-ago
   :days-until-harvest 20
   :hours-until-reentry 24
   :wind-speed-kmh 10.0
   :buffer-zone-actual-m 20.0
   :evidence-checklist evidence-checklist})

(def ^:private clean-combine-order
  "Baseline clean service order for a mechanical harvest service type
  (combine) -- has NO chemical-application safety-window fields at all."
  {:service-type :harvest/combine-grain
   :jurisdiction :jp/maff
   :client-farm-id "farm-77"
   :evidence-checklist evidence-checklist})

;; ──────────────────────── Registration Invariant ──────────────────────

(deftest service-order-not-registered-violation-test
  (testing "log-service-record against a never-registered service order is a hard block"
    (let [store {:service-orders {}}
          req {:op :log-service-record :subject "order-999"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :service-order-not-registered) (:violations result)))))

  (testing "schedule-field-operation against a never-registered service order is a hard block"
    (let [store {:service-orders {}}
          req {:op :schedule-field-operation :subject "order-999"}
          prop {:cites [] :value {} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :service-order-not-registered) (:violations result)))))

  (testing "flag-crop-health-concern against a never-registered service order is a hard block"
    (let [store {:service-orders {}}
          req {:op :flag-crop-health-concern :subject "order-999"}
          prop {:cites [{:spec "Field-Report"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :service-order-not-registered) (:violations result)))))

  (testing "order-supplies against a never-registered service order is a hard block"
    (let [store {:service-orders {}}
          req {:op :order-supplies :subject "order-999"}
          prop {:cites [{:spec "Supplier-Catalog"}] :value {:jurisdiction :jp/maff :cost-usd 100} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :service-order-not-registered) (:violations result))))))

;; ──────────────────────── Spec Basis ──────────────────────

(deftest spec-basis-violation-test
  (testing "proposal with no jurisdiction citation is a hard violation"
    (let [store {:service-orders {"order-001" clean-herbicide-order}}
          req {:op :log-service-record :subject "order-001"}
          prop {:cites [] :value {:jurisdiction nil}}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :no-spec-basis) (:violations result)))))

  (testing "proposal with proper citation passes spec basis check"
    (let [store {:service-orders {"order-001" clean-herbicide-order}}
          req {:op :log-service-record :subject "order-001"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:hard? result))))))

;; ──────────────────────── Applicator License Violations ──────────────────────

(deftest applicator-license-expired-violation-test
  (testing "expired applicator license triggers hard violation"
    (let [store {:service-orders {"order-001" (assoc clean-herbicide-order
                                                       :applicator-license-expiry-date hundred-days-ago)}}
          req {:op :log-service-record :subject "order-001"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :applicator-license-expired) (:violations result)))))

  (testing "current applicator license passes"
    (let [store {:service-orders {"order-001" clean-herbicide-order}}
          req {:op :log-service-record :subject "order-001"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:hard? result)))))

  (testing "mechanical harvest service type never triggers this rule"
    (let [store {:service-orders {"order-002" clean-combine-order}}
          req {:op :log-service-record :subject "order-002"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (not (some #(= (:rule %) :applicator-license-expired) (:violations result)))))))

;; ──────────────────────── Sprayer Calibration Violations ──────────────────────

(deftest sprayer-calibration-overdue-violation-test
  (testing "overdue sprayer calibration triggers hard violation"
    (let [store {:service-orders {"order-001" (assoc clean-herbicide-order
                                                       :sprayer-last-calibration-date hundred-days-ago)}}
          req {:op :log-service-record :subject "order-001"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :sprayer-calibration-overdue) (:violations result)))))

  (testing "mechanical harvest service type never triggers this rule"
    (let [store {:service-orders {"order-002" clean-combine-order}}
          req {:op :log-service-record :subject "order-002"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (not (some #(= (:rule %) :sprayer-calibration-overdue) (:violations result)))))))

;; ──────────────────────── Pre-Harvest Interval Violations ──────────────────────

(deftest pre-harvest-interval-violated-violation-test
  (testing "days-until-harvest below PHI triggers hard violation"
    (let [store {:service-orders {"order-001" (assoc clean-herbicide-order :days-until-harvest 3)}}
          req {:op :log-service-record :subject "order-001"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :pre-harvest-interval-violated) (:violations result)))))

  (testing "insecticide service type has a much stricter PHI than herbicide"
    (let [order (assoc clean-herbicide-order
                        :service-type :pest-control/insecticide-ground
                        :days-until-harvest 15)
          store {:service-orders {"order-002" order}}
          req {:op :log-service-record :subject "order-002"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :pre-harvest-interval-violated) (:violations result)))))

  (testing "mechanical harvest service type never triggers this rule"
    (let [store {:service-orders {"order-003" clean-combine-order}}
          req {:op :log-service-record :subject "order-003"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (not (some #(= (:rule %) :pre-harvest-interval-violated) (:violations result)))))))

;; ──────────────────────── Restricted-Entry Interval Violations ──────────────────────

(deftest restricted-entry-interval-violated-violation-test
  (testing "hours-until-reentry below REI triggers hard violation"
    (let [store {:service-orders {"order-001" (assoc clean-herbicide-order :hours-until-reentry 2)}}
          req {:op :log-service-record :subject "order-001"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :restricted-entry-interval-violated) (:violations result)))))

  (testing "mechanical harvest service type never triggers this rule"
    (let [store {:service-orders {"order-002" clean-combine-order}}
          req {:op :log-service-record :subject "order-002"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (not (some #(= (:rule %) :restricted-entry-interval-violated) (:violations result)))))))

;; ──────────────────────── Wind Speed Violations ──────────────────────

(deftest wind-speed-exceeded-violation-test
  (testing "wind speed above the service type's ceiling triggers hard violation"
    (let [store {:service-orders {"order-001" (assoc clean-herbicide-order :wind-speed-kmh 30.0)}}
          req {:op :log-service-record :subject "order-001"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :wind-speed-exceeded) (:violations result)))))

  (testing "insecticide service type has a much tighter wind ceiling than herbicide"
    (let [order (assoc clean-herbicide-order
                        :service-type :pest-control/insecticide-ground
                        :wind-speed-kmh 20.0)
          store {:service-orders {"order-002" order}}
          req {:op :log-service-record :subject "order-002"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :wind-speed-exceeded) (:violations result)))))

  (testing "mechanical harvest service type never triggers this rule"
    (let [store {:service-orders {"order-003" clean-combine-order}}
          req {:op :log-service-record :subject "order-003"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (not (some #(= (:rule %) :wind-speed-exceeded) (:violations result)))))))

;; ──────────────────────── Buffer Zone Violations ──────────────────────

(deftest buffer-zone-violated-violation-test
  (testing "buffer zone narrower than minimum triggers hard violation"
    (let [store {:service-orders {"order-001" (assoc clean-herbicide-order :buffer-zone-actual-m 5.0)}}
          req {:op :log-service-record :subject "order-001"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :buffer-zone-violated) (:violations result)))))

  (testing "buffer zone at or above minimum passes"
    (let [store {:service-orders {"order-002" clean-herbicide-order}}
          req {:op :log-service-record :subject "order-002"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:hard? result)))))

  (testing "mechanical harvest service type never triggers this rule"
    (let [store {:service-orders {"order-003" clean-combine-order}}
          req {:op :log-service-record :subject "order-003"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (not (some #(= (:rule %) :buffer-zone-violated) (:violations result)))))))

;; ──────────────────────── Evidence Completeness ──────────────────────

(deftest evidence-incomplete-violation-test
  (testing "incomplete evidence checklist triggers hard violation"
    (let [store {:service-orders {"order-001" (assoc clean-herbicide-order
                                                       :evidence-checklist [:service-order-record])}}
          req {:op :log-service-record :subject "order-001"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :evidence-incomplete) (:violations result))))))

;; ──────────────────────── Field-Equipment / Pesticide-Decision Block ──────────────────────

(deftest field-equipment-or-pesticide-decision-blocked-violation-test
  (testing "a proposal covertly requesting direct field-equipment control is a hard, permanent block"
    (let [store {:service-orders {"order-001" clean-herbicide-order}}
          req {:op :schedule-field-operation :subject "order-001"}
          prop {:cites [] :value {:operate-field-equipment? true} :confidence 0.99}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :field-equipment-or-pesticide-decision-blocked) (:violations result)))))

  (testing "a proposal covertly requesting a final pesticide-application decision is a hard, permanent block"
    (let [store {:service-orders {"order-001" clean-herbicide-order}}
          req {:op :log-service-record :subject "order-001"}
          prop {:cites [{:spec "ISO-12345"}]
                :value {:jurisdiction :jp/maff :finalize-pesticide-application-decision? true}
                :confidence 0.99}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :field-equipment-or-pesticide-decision-blocked) (:violations result))))))

;; ──────────────────────── Crop-Health Flag Violations ──────────────────────

(deftest crop-health-flag-unresolved-violation-test
  (testing "an unresolved crop-health flag triggers hard violation"
    (let [store {:service-orders {"order-001" (assoc clean-herbicide-order
                                                       :crop-health-concern-raised? true
                                                       :crop-health-concern-resolved? false)}}
          req {:op :log-service-record :subject "order-001"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :crop-health-flag-unresolved) (:violations result)))))

  (testing "a resolved crop-health flag does not trigger this rule"
    (let [store {:service-orders {"order-002" (assoc clean-herbicide-order
                                                       :crop-health-concern-raised? true
                                                       :crop-health-concern-resolved? true)}}
          req {:op :log-service-record :subject "order-002"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (not (some #(= (:rule %) :crop-health-flag-unresolved) (:violations result)))))))

;; ──────────────────────── Escalation (Low Confidence) ──────────────────────

(deftest low-confidence-escalation-test
  (testing "low confidence proposal escalates even when hard checks pass"
    (let [store {:service-orders {"order-001" clean-herbicide-order}}
          req {:op :schedule-field-operation :subject "order-001"}
          prop {:cites [] :value {} :confidence 0.5}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:ok? result)))
      (is (true? (:escalate? result)))
      (is (false? (:hard? result))))))

;; ──────────────────────── High Stakes Escalation ──────────────────────

(deftest high-stakes-escalation-test
  (testing "log-service-record escalates even when all checks pass"
    (let [store {:service-orders {"order-001" clean-herbicide-order}}
          req {:op :log-service-record :subject "order-001"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.95}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:ok? result)))
      (is (true? (:escalate? result)))
      (is (false? (:hard? result))))))

;; ──────────────────────── Crop-Health Concern Always Escalates ──────────────────────

(deftest crop-health-concern-always-escalates-test
  (testing "a clean flag-crop-health-concern proposal is never auto-ok"
    (let [store {:service-orders {"order-001" clean-herbicide-order}}
          req {:op :flag-crop-health-concern :subject "order-001"}
          prop {:cites [{:spec "Field-Report"}] :value {:jurisdiction :jp/maff} :confidence 0.99}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:ok? result)))
      (is (true? (:escalate? result)))
      (is (false? (:hard? result))))))

;; ──────────────────────── High-Cost Supply Order Escalation ──────────────────────

(deftest high-cost-supply-order-escalation-test
  (testing "a supply order above the cost threshold escalates even when clean"
    (let [store {:service-orders {"order-001" clean-herbicide-order}}
          req {:op :order-supplies :subject "order-001"}
          prop {:cites [{:spec "Supplier-Catalog"}]
                :value {:jurisdiction :jp/maff :cost-usd 10000}
                :confidence 0.9}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:ok? result)))
      (is (true? (:escalate? result)))
      (is (false? (:hard? result)))))


;; ──────────── The threshold gate must not read the number it doubts ────────────

(deftest threshold-gate-fails-safe-when-the-value-is-unverifiable
  (testing "`(some-> v (> threshold))` returned nil when `cost-usd` was ABSENT,
            so a proposal carrying no figure at all skipped the gate entirely"
    (let [store {:service-orders {"order-001" clean-herbicide-order}}
          req {:op :order-supplies :subject "order-001"}
          prop {:cites [{:spec "Supplier-Catalog"}]
                :value {:jurisdiction :jp/maff}
                :confidence 0.99}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:ok? result)))
      (is (true? (:escalate? result)))))

  (testing "a non-numeric figure escalates rather than being compared"
    (doseq [bad ["10000" :unknown {}]]
      (let [store {:service-orders {"order-001" clean-herbicide-order}}
            req {:op :order-supplies :subject "order-001"}
            prop {:cites [{:spec "Supplier-Catalog"}]
                  :value {:jurisdiction :jp/maff :cost-usd bad}
                  :confidence 0.99}
            result (governor/check req {:actor-id "gov-1"} prop store)]
        (is (false? (:ok? result))
            (str "non-numeric " (pr-str bad) " must escalate, not slip through"))))))
  (testing "a supply order at or below the cost threshold does not force escalation"
    (let [store {:service-orders {"order-002" clean-herbicide-order}}
          req {:op :order-supplies :subject "order-002"}
          prop {:cites [{:spec "Supplier-Catalog"}]
                :value {:jurisdiction :jp/maff :cost-usd 1000}
                :confidence 0.9}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:ok? result))))))

;; ──────────────────────── Already Logged Violation ──────────────────────

(deftest already-logged-violation-test
  (testing "service order already logged triggers hard violation"
    (let [store {:service-orders {"order-001"
                                   {:service-type :spray/herbicide-broadcast
                                    :logged? true}}}
          req {:op :log-service-record :subject "order-001"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :already-logged) (:violations result))))))

;; ──────────────────────── Op-Not-Allowed Violation ──────────────────────

(deftest op-not-allowed-violation-test
  (testing "an out-of-allowlist op (e.g. direct field-equipment operation) is a hard, permanent block"
    (let [store {:service-orders {"order-001" clean-herbicide-order}}
          req {:op :operate-sprayer :subject "order-001"}
          prop {:cites [{:spec "Sprayer-Manual"}] :value {:jurisdiction :jp/maff} :confidence 0.99}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :op-not-allowed) (:violations result))))))

;; ──────────────────────── Effect-Not-Propose Violation ──────────────────────

(deftest effect-not-propose-violation-test
  (testing "a proposal asserting a non-:propose effect is a hard, permanent block"
    (let [store {:service-orders {"order-001" clean-herbicide-order}}
          req {:op :schedule-field-operation :subject "order-001"}
          prop {:effect :commit :cites [] :value {} :confidence 0.9}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :effect-not-propose) (:violations result))))))
