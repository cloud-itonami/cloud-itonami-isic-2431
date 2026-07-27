(ns foundrymfg.registry
  "Pure-function domain logic for the iron-and-steel casting foundry
  plant-operations coordination actor -- equipment/batch verification,
  shipment-weight recompute, alloy-grade validation, defect-rate
  plausibility validation, and draft maintenance-schedule/shipment-
  coordination record construction.

  Per docs/adr/0001-architecture.md Decision 1: this vertical has NO
  pre-existing `kotoba-lang/foundrymfg`-style capability library to
  wrap (verified: no such repo exists). The domain logic therefore
  lives here as pure functions, re-verified INDEPENDENTLY by
  `foundrymfg.governor` -- the same 'ground truth, not self-report'
  discipline every sibling actor's own registry establishes (e.g.
  `resinmfg.registry/shipment-weight-exceeded?` from
  `cloud-itonami-isic-2013`, the closest architectural sibling): never
  trust a proposal's own self-reported weight/status when the inputs
  needed to recompute it independently are already on record.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real plant-operations system. It builds the DRAFT record
  a plant coordinator would keep (a scheduled maintenance window, a
  coordinated shipment), not the act of actuating a melting furnace or
  pouring line, or dispatching a real freight carrier (this actor
  NEVER does either -- see README `What this actor does NOT do`).

  SCOPE NOTE: ISIC 2431 (this actor) covers the CASTING FOUNDRY --
  melting furnace (cupola / electric-arc / induction) -> mold-pouring
  -> cooling/shakeout production line -- that produces iron and steel
  castings (heats poured into sand/permanent molds, shaken out and
  cooled into gray-iron/ductile-iron/cast-steel parts). This is a
  distinct plant, with a distinct hazard profile (molten-metal
  splash/burn, furnace-radiant-heat exposure, mold/core-binder fume
  exposure, shakeout dust/noise), from a rolling mill or forge -- the
  central physical hazard here is molten-metal handling at the
  furnace/pouring stage, not downstream hot-forming.")

;; ----------------------------- constants -----------------------------

(def valid-alloy-grades
  "The closed set of alloy-grade values a production-batch (heat/cast
  lot) record may declare -- the standard iron-and-steel casting
  families. Anything else is a fabricated/unrecognized alloy grade --
  the governor HARD-holds rather than let an invented grade pass
  through."
  #{;; cast irons
    :gray-iron :ductile-iron :malleable-iron :white-iron
    :ni-hard-iron :austempered-ductile-iron
    ;; cast steels
    :carbon-steel :alloy-steel :stainless-steel :cast-steel})

(def valid-output-forms
  "The closed set of PRIMARY FORM shapes this foundry's own output may
  take -- sand-cast, permanent-mold-cast, investment-cast, or
  centrifugally-cast castings. A casting foundry never ships raw
  molten metal or a wrought/rolled shape (those are a different
  actor's own downstream/upstream scope, not this actor's)."
  #{:sand-cast :permanent-mold-cast :investment-cast :centrifugal-cast})

(def defect-rate-min-percent
  "Physical floor for a batch's own defect/scrap-rate reading (zero
  defective output is the best possible outcome, never negative)."
  0.0)

(def defect-rate-max-percent
  "Physical ceiling for a batch's own defect/scrap-rate reading -- a
  batch cannot reject more than 100% of its own output. A reading
  above this is implausible sensor/QC data, not a real batch."
  100.0)

;; ----------------------------- equipment checks -----------------------------

(defn equipment-verified?
  "Ground-truth check: has `equipment`'s own record been marked
  verified (i.e. it has actually been inspected/commissioned and
  registered in the SSoT, not merely referenced from an unverified
  maintenance request)? A pure predicate over the equipment's own
  permanent field -- no proposal inspection needed."
  [equipment]
  (true? (:verified? equipment)))

(defn equipment-registered?
  "Ground-truth check: does `equipment`'s own record carry a
  `:registered?` true flag (i.e. it is on file in the foundry's
  equipment registry)? Scheduling maintenance against equipment that
  is not on file and registered is the exact scope violation this
  actor's HARD invariant ('foundry/batch record must be independently
  verified/registered before any action') exists to block."
  [equipment]
  (true? (:registered? equipment)))

(defn equipment-ready?
  "Combined ground-truth gate: the equipment must be both `verified?`
  AND `registered?` before ANY maintenance may be scheduled against
  it. Two independent facts on the equipment's own permanent record,
  neither inferred from the advisor's own rationale."
  [equipment]
  (and (equipment-verified? equipment) (equipment-registered? equipment)))

;; ----------------------------- batch checks -----------------------------

(defn batch-verified?
  "Ground-truth check: has `batch`'s own record been marked verified
  (i.e. its alloy-grade/weight/defect-rate claims have actually been
  QC-inspected, not merely logged from an unverified intake patch)?"
  [batch]
  (true? (:verified? batch)))

(defn batch-registered?
  "Ground-truth check: is `batch`'s own record on file in the
  foundry's production ledger? Coordinating a shipment against a batch
  that is not on file and registered is the exact scope violation this
  actor's HARD invariant ('foundry/batch record must be independently
  verified/registered before any action') exists to block."
  [batch]
  (true? (:registered? batch)))

(defn batch-ready?
  "Combined ground-truth gate: the batch must be both `verified?` AND
  `registered?` before ANY shipment may be coordinated against it."
  [batch]
  (and (batch-verified? batch) (batch-registered? batch)))

(defn shipment-weight-exceeded?
  "Ground-truth check for a `:coordinate-shipment` proposal:
  would `shipped-to-date-kg` + `new-weight-kg` exceed `batch`'s own
  recorded `:weight-kg` (the batch's own logged production weight)?
  Needs no proposal inspection or stored-verdict lookup -- its inputs
  are permanent fields already on the batch's own record, the same
  shape every sibling actor's own cost/total-matching check uses."
  [batch new-weight-kg]
  (let [capacity (:weight-kg batch)
        so-far (:shipped-weight-kg batch 0.0)]
    (and (number? capacity)
         (number? new-weight-kg)
         (number? so-far)
         ;; Compared at 1/10000 of a unit, not on raw doubles. A shipment
         ;; that fills a batch EXACTLY to its recorded capacity is legal,
         ;; and comparing the raw sum flagged such shipments as over
         ;; because the sum is not the double nearest the true total.
         (> (Math/round (* 10000 (+ (double so-far) (double new-weight-kg))))
            (Math/round (* 10000 (double capacity))))))) 

(defn shipment-weight-exceeded-checkable?
  "Can `batch`'s headroom actually be computed for `new-weight-kg`?

  `shipment-weight-exceeded?` answers only `over` / `not over`, and its
  `(and (number? ...) ...)` guard made every un-checkable case fall
  through as `not over` -- a batch with no recorded capacity, or a
  shipment stating no amount, passed the over-capacity check silently.
  Callers must ask this first: un-checkable is not headroom."
  [batch new-weight-kg]
  (boolean (and (map? batch)
                (number? (:weight-kg batch))
                (number? (:shipped-weight-kg batch 0.0))
                (number? new-weight-kg))))

(defn alloy-grade-valid?
  "Is `alloy-grade` one of the closed, known alloy-grade values (cast
  iron or cast steel family)? nil/blank is treated as invalid (a
  production-batch patch must declare a real alloy grade, not omit it
  silently)."
  [alloy-grade]
  (contains? valid-alloy-grades alloy-grade))

(defn defect-rate-valid?
  "Is `percent` a physically plausible batch defect/scrap-rate
  reading? Rejects nil, non-numbers, negative values, and values
  beyond `defect-rate-max-percent` -- a fabricated or sensor-error
  reading, never let through as a real batch fact."
  [percent]
  (and (number? percent)
       (>= (double percent) defect-rate-min-percent)
       (<= (double percent) defect-rate-max-percent)))

;; ----------------------------- draft record construction -----------------------------

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the human plant supervisor's/shipping approver's act, not this
  actor's."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-maintenance
  "Validate + construct the MAINTENANCE-SCHEDULE DRAFT -- a proposed
  furnace/mold/shakeout-equipment maintenance window against a
  verified, registered piece of equipment. Pure function -- does not
  actuate the furnace or pouring line or execute any maintenance; it
  builds the RECORD a plant coordinator would keep. `foundrymfg.governor`
  independently re-verifies the equipment's own verified/registered
  ground truth, and permanently blocks any attempt to directly actuate
  the furnace/pouring line (see README `Actuation`), before this is
  ever allowed to commit."
  [maintenance-id equipment-id sequence]
  (when-not (and maintenance-id (not= maintenance-id ""))
    (throw (ex-info "maintenance: maintenance_id required" {})))
  (when-not (and equipment-id (not= equipment-id ""))
    (throw (ex-info "maintenance: equipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "maintenance: sequence must be >= 0" {})))
  (let [maintenance-number (str "MNT-" (zero-pad sequence 6))
        record {"record_id" maintenance-number
                "kind" "maintenance-schedule-draft"
                "maintenance_id" maintenance-id
                "equipment_id" equipment-id
                "immutable" true}]
    {"record" record "maintenance_number" maintenance-number
     "certificate" (unsigned-certificate "MaintenanceSchedule" maintenance-number maintenance-number)}))

(defn register-shipment
  "Validate + construct the SHIPMENT-COORDINATION DRAFT -- a proposed
  outbound iron/steel casting shipment against a verified, registered
  production batch. Pure function -- does not dispatch any real
  freight carrier; it builds the RECORD a plant coordinator would
  keep. `foundrymfg.governor` independently re-verifies the shipment's
  own claimed weight against `shipment-weight-exceeded?`, before this
  is ever allowed to commit."
  [shipment-id sequence]
  (when-not (and shipment-id (not= shipment-id ""))
    (throw (ex-info "shipment: shipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "shipment: sequence must be >= 0" {})))
  (let [shipment-number (str "SHP-" (zero-pad sequence 6))
        record {"record_id" shipment-number
                "kind" "shipment-coordination-draft"
                "shipment_id" shipment-id
                "immutable" true}]
    {"record" record "shipment_number" shipment-number
     "certificate" (unsigned-certificate "ShipmentCoordination" shipment-number shipment-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
