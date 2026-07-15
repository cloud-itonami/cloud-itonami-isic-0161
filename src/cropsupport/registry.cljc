(ns cropsupport.registry
  "Pure validation functions for crop-support-service safety parameters.
  These are called by the Governor to independently verify physical/
  regulatory constraints -- the advisor's confidence is NOT sufficient
  to override these checks.

  All functions here are pure arithmetic/boolean predicates with no
  host-clock or I/O calls, so this namespace stays trivially portable
  across Clojure/ClojureScript. Callers that need the current time (see
  `applicator-license-expired?` / `sprayer-calibration-overdue?`) obtain
  it themselves via a `:clj`/`:cljs` reader-conditional at the call site
  (see `cropsupport.governor`).")

(defn applicator-license-expired?
  "Independently verify that the pesticide-applicator license has NOT
  expired as of `now-epoch-ms`. An expired license means the person who
  performed a chemical-application service was not legally certified to
  do so -- a genuine regulatory hazard distinct from any equipment or
  weather concern."
  [expiry-epoch-ms now-epoch-ms]
  (< expiry-epoch-ms now-epoch-ms))

(defn sprayer-calibration-overdue?
  "Independently verify that the sprayer/applicator equipment was
  calibrated within the last 90 days. `last-calibration-epoch-ms` and
  `now-epoch-ms` are both epoch milliseconds -- callers obtain `now` via
  a `:clj`/`:cljs` reader-conditional, keeping this namespace free of any
  host-clock call. An out-of-calibration sprayer risks both under- and
  over-application of chemical product."
  [last-calibration-epoch-ms now-epoch-ms]
  (> (- now-epoch-ms last-calibration-epoch-ms)
     (* 90 24 60 60 1000)))

(defn pre-harvest-interval-violated?
  "Independently verify that the number of days between a chemical
  application and the client-farm's expected harvest date meets or
  exceeds the service type's pre-harvest interval. Applying a chemical
  too close to harvest leaves residue that has not had time to clear,
  a genuine food-safety hazard to the eventual consumer of the client
  farm's crop."
  [days-until-harvest phi-days]
  (< days-until-harvest phi-days))

(defn restricted-entry-interval-violated?
  "Independently verify that the planned gap before workers re-enter a
  treated field meets or exceeds the service type's restricted-entry
  interval. Re-entering too soon after a chemical application risks
  worker exposure to unsettled residue."
  [hours-until-reentry rei-hours]
  (< hours-until-reentry rei-hours))

(defn wind-speed-exceeded?
  "Independently verify that the actual wind speed at time of
  application did not exceed the service type's maximum safe spray-drift
  wind speed. Excess wind carries chemical product off-target onto
  neighboring fields, waterways, or people."
  [actual-kmh max-kmh]
  (> actual-kmh max-kmh))

(defn buffer-zone-violated?
  "Independently verify that the actual distance maintained to the
  nearest sensitive site (water body, school, residence) met or exceeded
  the service type's minimum buffer zone. A buffer zone that is too
  narrow risks chemical drift or runoff reaching the sensitive site."
  [actual-m min-m]
  (< actual-m min-m))
