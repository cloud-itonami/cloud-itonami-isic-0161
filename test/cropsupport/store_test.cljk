(ns cropsupport.store-test
  (:require [clojure.test :refer [deftest is testing]]
            [cropsupport.store :as store]))

;; ──────────────────────── Service-Order Retrieval ──────────────────────

(deftest service-order-test
  (testing "retrieve an existing service order"
    (let [order-data {:service-type :spray/herbicide-broadcast :client-farm-id "farm-42"}
          st {:service-orders {"order-001" order-data}}
          result (store/service-order st "order-001")]
      (is (= result order-data))))

  (testing "nonexistent service order returns nil"
    (let [st {:service-orders {}}
          result (store/service-order st "nonexistent")]
      (is (nil? result)))))

(deftest service-order-registered-test
  (testing "registered service order returns true"
    (let [st {:service-orders {"order-001" {:client-farm-id "farm-42"}}}
          result (store/service-order-registered? st "order-001")]
      (is (true? result))))

  (testing "unregistered service order returns false"
    (let [st {:service-orders {}}
          result (store/service-order-registered? st "order-999")]
      (is (false? result)))))

;; ──────────────────────── Service-Order Status Checks ──────────────────────

(deftest service-order-already-logged-test
  (testing "logged service order is detected"
    (let [st {:service-orders {"order-001" {:logged? true}}}
          result (store/service-order-already-logged? st "order-001")]
      (is (true? result))))

  (testing "unlogged service order returns false"
    (let [st {:service-orders {"order-001" {:logged? false}}}
          result (store/service-order-already-logged? st "order-001")]
      (is (false? result))))

  (testing "nonexistent service order returns false"
    (let [st {:service-orders {}}
          result (store/service-order-already-logged? st "order-001")]
      (is (false? result)))))

;; ──────────────────────── Service-Order Logging ──────────────────────

(deftest log-service-record-test
  (testing "logging a service order marks it as logged"
    (let [st {:service-orders {}}
          order-data {:service-type :spray/herbicide-broadcast}
          result (store/log-service-record st "order-001" order-data)]
      (is (true? (get-in result [:service-orders "order-001" :logged?])))))

  (testing "logging preserves service-order data"
    (let [st {:service-orders {}}
          order-data {:service-type :spray/herbicide-broadcast :client-farm-id "farm-42"}
          result (store/log-service-record st "order-001" order-data)]
      (is (= (:service-type (get-in result [:service-orders "order-001"])) :spray/herbicide-broadcast))
      (is (= (:client-farm-id (get-in result [:service-orders "order-001"])) "farm-42")))))

;; ──────────────────────── Service-Order Scheduling ──────────────────────

(deftest mark-scheduled-test
  (testing "marking a service order marks it as scheduled"
    (let [st {:service-orders {"order-001" {:client-farm-id "farm-42"}}}
          result (store/mark-scheduled st "order-001")]
      (is (true? (get-in result [:service-orders "order-001" :scheduled?]))))))

;; ──────────────────────── Audit Trail ──────────────────────

(deftest audit-trail-test
  (testing "audit trail is initially empty"
    (let [st {:facts []}
          result (store/audit-trail st)]
      (is (empty? result))))

  (testing "appended facts appear in audit trail"
    (let [st {:facts []}
          fact1 {:t :test-fact :detail "test 1"}
          fact2 {:t :test-fact :detail "test 2"}
          st' (store/append-fact st fact1)
          st'' (store/append-fact st' fact2)
          result (store/audit-trail st'')]
      (is (= (count result) 2))
      (is (= (first result) fact1))
      (is (= (second result) fact2)))))

(deftest append-fact-test
  (testing "appending a fact increases ledger length"
    (let [st {:facts []}
          fact {:t :governor-hold :op :log-service-record}
          result (store/append-fact st fact)]
      (is (= (count (:facts result)) 1))
      (is (= (first (:facts result)) fact)))))
