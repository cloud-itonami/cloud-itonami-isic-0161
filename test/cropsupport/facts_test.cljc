(ns cropsupport.facts-test
  (:require [clojure.test :refer [deftest is testing]]
            [cropsupport.facts :as facts]))

;; ──────────────────────── Service-Type Lookups ──────────────────────

(deftest service-type-by-id-test
  (testing "herbicide-broadcast service type exists"
    (let [s (facts/service-type-by-id :spray/herbicide-broadcast)]
      (is (some? s))
      (is (= (:id s) :spray/herbicide-broadcast))
      (is (true? (:chemical-application? s)))
      (is (= (:pre-harvest-interval-days s) 14))))

  (testing "combine-grain harvest service type exists and has no chemical spec"
    (let [s (facts/service-type-by-id :harvest/combine-grain)]
      (is (some? s))
      (is (false? (:chemical-application? s)))
      (is (nil? (:pre-harvest-interval-days s)))
      (is (nil? (:max-wind-speed-kmh s)))))

  (testing "nonexistent service type returns nil"
    (is (nil? (facts/service-type-by-id :nonexistent/service)))))

;; ──────────────────────── Jurisdiction Lookups ──────────────────────

(deftest jurisdiction-by-id-test
  (testing "JP MAFF jurisdiction exists"
    (let [j (facts/jurisdiction-by-id :jp/maff)]
      (is (some? j))
      (is (contains? (set (:required-evidence j)) :applicator-license))))

  (testing "US EPA jurisdiction exists"
    (let [j (facts/jurisdiction-by-id :us/epa)]
      (is (some? j))
      (is (contains? (set (:required-evidence j)) :buffer-zone-assessment))))

  (testing "EU REG1107 jurisdiction exists"
    (let [j (facts/jurisdiction-by-id :eu/reg1107)]
      (is (some? j))
      (is (contains? (set (:required-evidence j)) :weather-log))))

  (testing "nonexistent jurisdiction returns nil"
    (is (nil? (facts/jurisdiction-by-id :xx/unknown)))))

;; ──────────────────────── Crop-Support-Service Safety Predicates ──────────

(deftest applicator-license-current-test
  (let [herbicide (facts/service-type-by-id :spray/herbicide-broadcast)
        combine (facts/service-type-by-id :harvest/combine-grain)]
    (testing "license expiring in the future is current"
      (is (true? (facts/applicator-license-current? 2000 1000 herbicide))))

    (testing "license expiring in the past is not current"
      (is (false? (facts/applicator-license-current? 500 1000 herbicide))))

    (testing "mechanical harvest service type never needs a license"
      (is (false? (facts/applicator-license-current? 2000 1000 combine))))))

(deftest sprayer-calibration-current-test
  (let [herbicide (facts/service-type-by-id :spray/herbicide-broadcast)
        combine (facts/service-type-by-id :harvest/combine-grain)
        now 1000000
        ten-days-ago (- now (* 10 24 60 60 1000))
        hundred-days-ago (- now (* 100 24 60 60 1000))]
    (testing "recent calibration is current"
      (is (true? (facts/sprayer-calibration-current? ten-days-ago now herbicide))))

    (testing "overdue calibration is not current"
      (is (false? (facts/sprayer-calibration-current? hundred-days-ago now herbicide))))

    (testing "mechanical harvest service type never needs calibration"
      (is (false? (facts/sprayer-calibration-current? ten-days-ago now combine))))))

(deftest pre-harvest-interval-satisfied-test
  (let [herbicide (facts/service-type-by-id :spray/herbicide-broadcast)
        combine (facts/service-type-by-id :harvest/combine-grain)]
    (testing "days-until-harvest at or above PHI passes"
      (is (true? (facts/pre-harvest-interval-satisfied? 14 herbicide)))
      (is (true? (facts/pre-harvest-interval-satisfied? 20 herbicide))))

    (testing "days-until-harvest below PHI fails"
      (is (false? (facts/pre-harvest-interval-satisfied? 5 herbicide))))

    (testing "mechanical harvest service type has no PHI to satisfy"
      (is (false? (facts/pre-harvest-interval-satisfied? 5 combine))))))

(deftest restricted-entry-interval-satisfied-test
  (let [pest-control (facts/service-type-by-id :pest-control/insecticide-ground)
        combine (facts/service-type-by-id :harvest/combine-grain)]
    (testing "hours-until-reentry at or above REI passes"
      (is (true? (facts/restricted-entry-interval-satisfied? 24 pest-control)))
      (is (true? (facts/restricted-entry-interval-satisfied? 48 pest-control))))

    (testing "hours-until-reentry below REI fails"
      (is (false? (facts/restricted-entry-interval-satisfied? 6 pest-control))))

    (testing "mechanical harvest service type has no REI to satisfy"
      (is (false? (facts/restricted-entry-interval-satisfied? 6 combine))))))

(deftest wind-speed-in-range-test
  (let [pest-control (facts/service-type-by-id :pest-control/insecticide-ground)
        combine (facts/service-type-by-id :harvest/combine-grain)]
    (testing "wind speed at or below ceiling passes"
      (is (true? (facts/wind-speed-in-range? 16.0 pest-control)))
      (is (true? (facts/wind-speed-in-range? 5.0 pest-control))))

    (testing "wind speed above ceiling fails"
      (is (false? (facts/wind-speed-in-range? 20.0 pest-control))))

    (testing "mechanical harvest service type has no wind-speed ceiling"
      (is (false? (facts/wind-speed-in-range? 5.0 combine))))))

(deftest buffer-zone-in-range-test
  (let [pest-control (facts/service-type-by-id :pest-control/insecticide-ground)
        combine (facts/service-type-by-id :harvest/combine-grain)]
    (testing "buffer distance at or above minimum passes"
      (is (true? (facts/buffer-zone-in-range? 30.0 pest-control)))
      (is (true? (facts/buffer-zone-in-range? 50.0 pest-control))))

    (testing "buffer distance below minimum fails"
      (is (false? (facts/buffer-zone-in-range? 10.0 pest-control))))

    (testing "mechanical harvest service type has no buffer-zone minimum"
      (is (false? (facts/buffer-zone-in-range? 50.0 combine))))))

;; ──────────────────────── Evidence Completeness ──────────────────────

(deftest required-evidence-satisfied-test
  (testing "complete evidence checklist passes"
    (let [j (facts/jurisdiction-by-id :jp/maff)
          evidence [:service-order-record :field-boundary-map :application-record
                    :applicator-license :weather-log :buffer-zone-assessment]]
      (is (true? (facts/required-evidence-satisfied? j evidence)))))

  (testing "incomplete evidence fails"
    (let [j (facts/jurisdiction-by-id :jp/maff)
          evidence [:service-order-record :field-boundary-map]]
      (is (false? (facts/required-evidence-satisfied? j evidence)))))

  (testing "raw jurisdiction id call convention also works"
    (let [evidence [:service-order-record :field-boundary-map :application-record
                    :applicator-license :weather-log :buffer-zone-assessment]]
      (is (true? (facts/required-evidence-satisfied? :us/epa evidence)))))

  (testing "unknown jurisdiction never satisfies"
    (is (false? (facts/required-evidence-satisfied? :xx/unknown [])))))
