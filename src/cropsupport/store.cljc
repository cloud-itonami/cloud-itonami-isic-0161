(ns cropsupport.store
  "Store abstraction for crop-support-service orders. Current
  implementation operates on plain data (`{:service-orders
  {service-order-id order-map} :facts [...]}`); production should migrate
  this seam to Datomic/kotoba-server (the same seam point all cloud-itonami
  actors use) while keeping the same pure-function surface.

  A service order is the minimal unit of work: one custom-harvest/spray/
  pest-control engagement performed for a CLIENT FARM (never the operator's
  own crop -- that is the defining shape of ISIC 0161, support activities
  for crop production). Representative service-order keys:
    - :service-type keyword service-type id (see `cropsupport.facts/service-types`)
    - :jurisdiction keyword jurisdiction id (see `cropsupport.facts/jurisdictions`)
    - :client-farm-id the served farm's identifier (never the operator's own)
    - :field-boundary-hectares serviced field area
    - :evidence-checklist evidence items present for the service order
    - :applicator-license-expiry-date epoch-ms of the applicator's license
      expiry (nil for mechanical harvest service types)
    - :sprayer-last-calibration-date epoch-ms of last sprayer/applicator
      equipment calibration (nil for mechanical harvest service types)
    - :days-until-harvest days between the application and the client
      farm's expected harvest date (nil for mechanical harvest)
    - :hours-until-reentry planned gap before workers re-enter the treated
      field (nil for mechanical harvest)
    - :wind-speed-kmh actual wind speed at time of application (nil for
      mechanical harvest)
    - :buffer-zone-actual-m actual distance maintained to the nearest
      sensitive site (nil for mechanical harvest)
    - :crop-health-concern-raised? / :crop-health-concern-resolved? open
      pest/disease/spray-drift concern flag
    - :logged? true once a `:log-service-record` proposal commits
    - :scheduled? true once a `:schedule-field-operation` proposal commits

  The ledger (`:facts`) is a separate append-only vector of audit facts,
  kept alongside `:service-orders` in the same store value.")

(defn service-order
  "Retrieve a service order by id, or nil if it does not exist / is not
  yet registered."
  [st service-order-id]
  (get-in st [:service-orders service-order-id]))

(defn service-order-registered?
  "True only if the service order exists in the store -- registration is
  the HARD invariant that must be independently verified before ANY of
  this actor's four proposal ops can be made against it."
  [st service-order-id]
  (some? (service-order st service-order-id)))

(defn service-order-already-logged?
  "True only if the service order exists and has already been marked
  logged."
  [st service-order-id]
  (true? (:logged? (service-order st service-order-id))))

(defn log-service-record
  "Register/update `order-data` under `service-order-id` and mark it
  logged (one-way flag). Used once a `:log-service-record` proposal
  commits."
  [st service-order-id order-data]
  (assoc-in st [:service-orders service-order-id] (assoc order-data :logged? true)))

(defn mark-scheduled
  "Mark an existing service order as scheduled (one-way flag). Used once
  a `:schedule-field-operation` proposal commits."
  [st service-order-id]
  (assoc-in st [:service-orders service-order-id :scheduled?] true))

(defn audit-trail
  "Return the append-only audit ledger (empty vector if none yet)."
  [st]
  (get st :facts []))

(defn append-fact
  "Append `fact` to the store's audit ledger."
  [st fact]
  (update st :facts (fnil conj []) fact))
