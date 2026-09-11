(ns cropsupport.sim
  "Simulation driver for testing the crop-support-service operations
  actor end-to-end.

  For CLI: clojure -M:dev:run

  Example flow:
    1. Start with empty store
    2. Register a service order in :intake phase
    3. Propose a service order -> :record transition with safety
       parameters (applicator license / calibration / PHI / REI / wind /
       buffer zone)
    4. Governor validates parameters against facts
    5. If valid, audit fact is committed
    6. CLI prints audit trail")

(defn -main [& _args]
  (println "CropSupport simulation: not yet implemented.")
  (println "TODO: integrate langgraph-clj StateGraph when available."))
