(ns cropsupport.phase
  "Phase machine: the states a crop-support-service order transits
  through.

  State machine:
    :intake -> :survey -> :advise -> :treat -> :record -> :audit

  `:intake` is service-request receiving (client farm, field, requested
  service); `:survey` is field/crop-condition assessment; `:advise` is
  the advisor's treatment/scheduling recommendation; `:treat` is the
  actual custom harvest/spray/pest-control field work performed for the
  client farm; `:record` is logging the completed service (evidence,
  safety parameters) into records; `:audit` is compliance audit, the
  terminal state. This sequence matches the registry's own registered
  `:operating-states` for ISIC 0161 exactly.

  Each transition can accept a proposal and yield an audit fact.")

(def all-phases
  "All valid phases in the crop-support-service workflow."
  [:intake :survey :advise :treat :record :audit])

(def phase-sequence
  "Ordered phases representing normal service-order progression."
  [:intake :survey :advise :treat :record :audit])

(defn valid-phase?
  "Check if a phase is valid."
  [phase]
  (contains? (set all-phases) phase))

(defn- index-of
  "Portable (Clojure/ClojureScript) index lookup -- `.indexOf` is a
  JVM-only `java.util.List` method that ClojureScript's PersistentVector
  does not implement, so it is avoided here even though `phase-sequence`
  is a plain vector. Returns -1 when `x` is not found, matching
  `java.util.List/indexOf`'s contract."
  [coll x]
  (or (first (keep-indexed (fn [i v] (when (= v x) i)) coll)) -1))

(defn can-transition?
  "Check if a transition from one phase to another is valid
  (must be forward-only in the sequence, no backtracking). Always returns a
  boolean (never nil), including when either phase is invalid."
  [from-phase to-phase]
  (boolean
   (and (valid-phase? from-phase) (valid-phase? to-phase)
        (let [from-idx (index-of phase-sequence from-phase)
              to-idx (index-of phase-sequence to-phase)]
          (and (>= from-idx 0) (>= to-idx 0) (< from-idx to-idx))))))
