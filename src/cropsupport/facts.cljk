(ns cropsupport.facts
  "Reference facts for custom crop-support-service contractors: service-type
  safety windows (applicator-license currency, sprayer-equipment calibration,
  pre-harvest interval, restricted-entry interval, wind-speed drift ceiling,
  buffer-zone minimum), jurisdiction evidence-checklist requirements. This
  namespace contains pure lookup functions for crop-support-service safety
  compliance checks -- the Governor calls these to independently validate
  proposals; the advisor's confidence is never sufficient on its own.

  A crop-support-service contractor (ISIC Rev.5 0161) performs CUSTOM FARM
  WORK for OTHER farms on a fee/contract basis -- harvesting, spraying,
  pest-control, and similar field operations -- WITHOUT itself growing the
  crop being serviced. This is what distinguishes 0161 from the growing
  divisions themselves (011x): the client-farm's crop-lot is never owned by
  this actor's operator, only the SERVICE performed on it.

  Service types split into two safety shapes:
    - Mechanical harvest services (combine harvesting, hay baling) have NO
      chemical-application safety window at all -- pre-harvest-interval /
      restricted-entry-interval / max-wind-speed / min-buffer-zone are all
      nil for these, and the Governor's corresponding checks are skipped
      entirely rather than fabricating a target.
    - Chemical-application services (herbicide/fungicide/insecticide
      spraying, pest-control) carry a genuine pre-harvest interval (days
      before harvest a residue must clear), restricted-entry interval
      (hours before workers may safely re-enter the treated field),
      maximum safe wind speed (spray-drift risk), and minimum buffer zone
      (distance to sensitive sites such as water bodies, schools, or
      residences)."
  (:require [clojure.set :as set]))

(def service-types
  "Valid crop-support-service categories and their safety windows.
  `pre-harvest-interval-days`/`restricted-entry-interval-hours`/
  `max-wind-speed-kmh`/`min-buffer-zone-m` are nil for mechanical
  (non-chemical) service types -- the Governor's corresponding checks are
  skipped entirely for those types rather than fabricating a target."
  {:harvest/combine-grain
   {:id :harvest/combine-grain
    :name "穀物コンバイン収穫作業"
    :chemical-application? false
    :pre-harvest-interval-days nil
    :restricted-entry-interval-hours nil
    :max-wind-speed-kmh nil
    :min-buffer-zone-m nil}

   :harvest/hay-baling
   {:id :harvest/hay-baling
    :name "牧草刈取・梱包作業"
    :chemical-application? false
    :pre-harvest-interval-days nil
    :restricted-entry-interval-hours nil
    :max-wind-speed-kmh nil
    :min-buffer-zone-m nil}

   :spray/herbicide-broadcast
   {:id :spray/herbicide-broadcast
    :name "除草剤散布(ブロードキャスト)"
    :chemical-application? true
    :pre-harvest-interval-days 14
    :restricted-entry-interval-hours 12
    :max-wind-speed-kmh 24.0
    :min-buffer-zone-m 15.0}

   :spray/fungicide-foliar
   {:id :spray/fungicide-foliar
    :name "殺菌剤葉面散布"
    :chemical-application? true
    :pre-harvest-interval-days 7
    :restricted-entry-interval-hours 12
    :max-wind-speed-kmh 24.0
    :min-buffer-zone-m 15.0}

   :pest-control/insecticide-ground
   {:id :pest-control/insecticide-ground
    :name "地上防除(殺虫剤散布)"
    :chemical-application? true
    :pre-harvest-interval-days 21
    :restricted-entry-interval-hours 24
    :max-wind-speed-kmh 16.0
    :min-buffer-zone-m 30.0}})

(defn service-type-by-id [id]
  (get service-types id))

(def jurisdictions
  "Crop-support-service jurisdictions and their evidence-checklist
  requirements."
  {:jp/maff
   {:id :jp/maff
    :name "日本 (農薬取締法・農林水産省)"
    :required-evidence
    [:service-order-record
     :field-boundary-map
     :application-record
     :applicator-license
     :weather-log
     :buffer-zone-assessment]}

   :us/epa
   {:id :us/epa
    :name "United States (FIFRA / EPA Pesticide Regulation)"
    :required-evidence
    [:service-order-record
     :field-boundary-map
     :application-record
     :applicator-license
     :weather-log
     :buffer-zone-assessment]}

   :eu/reg1107
   {:id :eu/reg1107
    :name "European Union (Regulation (EC) No 1107/2009 on plant protection products)"
    :required-evidence
    [:service-order-record
     :field-boundary-map
     :application-record
     :applicator-license
     :weather-log
     :buffer-zone-assessment]}})

(defn jurisdiction-by-id [id]
  (get jurisdictions id))

(defn required-evidence-satisfied?
  "Verify that every item in the jurisdiction's `:required-evidence` list
  is present in `evidence`. `jurisdiction` may be a resolved jurisdiction
  map (as returned by `jurisdiction-by-id`) or a raw jurisdiction id --
  both call conventions are in use (tests pass a resolved map; the
  Governor passes the raw id straight off service-order metadata)."
  [jurisdiction evidence]
  (let [j (if (map? jurisdiction) jurisdiction (jurisdiction-by-id jurisdiction))]
    (if-not j
      false
      (set/subset? (set (:required-evidence j)) (set evidence)))))

(defn applicator-license-current?
  "Positive-sense convenience predicate: is the applicator license valid
  (not yet expired) as of `now-epoch-ms`? Returns false when the service
  type has no chemical-application license requirement at all -- there is
  nothing to be 'current' about for a mechanical harvest service."
  [expiry-epoch-ms now-epoch-ms service-type]
  (boolean
   (and (some? service-type)
        (true? (:chemical-application? service-type))
        (some? expiry-epoch-ms)
        (>= expiry-epoch-ms now-epoch-ms))))

(defn sprayer-calibration-current?
  "Positive-sense convenience predicate: was the sprayer/applicator
  equipment calibrated within the safety interval (90 days) of
  `now-epoch-ms`? Returns false when the service type has no
  chemical-application equipment-calibration requirement at all."
  [last-calibration-epoch-ms now-epoch-ms service-type]
  (boolean
   (and (some? service-type)
        (true? (:chemical-application? service-type))
        (some? last-calibration-epoch-ms)
        (<= (- now-epoch-ms last-calibration-epoch-ms)
            (* 90 24 60 60 1000)))))

(defn pre-harvest-interval-satisfied?
  "Positive-sense convenience predicate: does `days-until-harvest` meet
  or exceed the service type's pre-harvest interval? Returns false when
  the service type has no pre-harvest-interval spec at all (mechanical
  harvest service -- nothing to satisfy)."
  [days-until-harvest service-type]
  (boolean
   (and (some? service-type)
        (some? (:pre-harvest-interval-days service-type))
        (some? days-until-harvest)
        (>= days-until-harvest (:pre-harvest-interval-days service-type)))))

(defn restricted-entry-interval-satisfied?
  "Positive-sense convenience predicate: does `hours-until-reentry` meet
  or exceed the service type's restricted-entry interval? Returns false
  when the service type has no restricted-entry-interval spec at all."
  [hours-until-reentry service-type]
  (boolean
   (and (some? service-type)
        (some? (:restricted-entry-interval-hours service-type))
        (some? hours-until-reentry)
        (>= hours-until-reentry (:restricted-entry-interval-hours service-type)))))

(defn wind-speed-in-range?
  "Positive-sense convenience predicate: does `actual-kmh` stay at or
  below the service type's maximum safe spray-drift wind speed? Returns
  false when the service type has no wind-speed ceiling at all."
  [actual-kmh service-type]
  (boolean
   (and (some? service-type)
        (some? (:max-wind-speed-kmh service-type))
        (some? actual-kmh)
        (<= actual-kmh (:max-wind-speed-kmh service-type)))))

(defn buffer-zone-in-range?
  "Positive-sense convenience predicate: does `actual-m` meet or exceed
  the service type's minimum buffer-zone distance? Returns false when
  the service type has no buffer-zone minimum at all."
  [actual-m service-type]
  (boolean
   (and (some? service-type)
        (some? (:min-buffer-zone-m service-type))
        (some? actual-m)
        (>= actual-m (:min-buffer-zone-m service-type)))))
