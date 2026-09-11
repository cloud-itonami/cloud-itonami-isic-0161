(ns cropsupport.phase-test
  (:require [clojure.test :refer [deftest is testing]]
            [cropsupport.phase :as phase]))

;; ──────────────────────── Phase Validity ──────────────────────

(deftest valid-phase-test
  (testing "intake is valid"
    (is (true? (phase/valid-phase? :intake))))

  (testing "advise is valid"
    (is (true? (phase/valid-phase? :advise))))

  (testing "audit is valid"
    (is (true? (phase/valid-phase? :audit))))

  (testing "invalid phase returns false"
    (is (false? (phase/valid-phase? :invalid)))))

;; ──────────────────────── Phase Transitions ──────────────────────

(deftest can-transition-test
  (testing "intake -> survey is valid (forward progression)"
    (is (true? (phase/can-transition? :intake :survey))))

  (testing "intake -> advise is valid (skip survey)"
    (is (true? (phase/can-transition? :intake :advise))))

  (testing "survey -> intake is invalid (backward)"
    (is (false? (phase/can-transition? :survey :intake))))

  (testing "treat -> audit is valid (forward to end)"
    (is (true? (phase/can-transition? :treat :audit))))

  (testing "audit -> intake is invalid (backward from end)"
    (is (false? (phase/can-transition? :audit :intake))))

  (testing "same phase is invalid"
    (is (false? (phase/can-transition? :treat :treat))))

  (testing "invalid phases return false"
    (is (false? (phase/can-transition? :invalid :treat)))
    (is (false? (phase/can-transition? :treat :invalid)))))
