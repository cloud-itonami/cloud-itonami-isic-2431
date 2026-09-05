(ns foundrymfg.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: it previously had no
  demo page and no generator at all. This namespace drives the REAL
  actor stack (`foundrymfg.operation` -> `foundrymfg.governor` ->
  `foundrymfg.phase` -> `foundrymfg.store`) and then renders the page
  by READING BACK the resulting SSoT + append-only ledger. Nothing on
  the page is typed in by hand except the `action-gate-rows` table,
  which is a static description of this actor's own fixed op/gate
  contract (documentation-of-code, marked as such below).

  PROVENANCE OF EVERY SUBJECT ID
  ------------------------------
  `foundrymfg.store/sample-data!` seeds exactly five domain entities:

    batches   : batch-001 (ductile-iron, verified+registered, 50000 kg
                            logged, 10000 kg already shipped)
                batch-002 (cast-steel, verified+registered, 8000 kg
                            logged, 7500 kg already shipped -- almost
                            no headroom left)
                batch-003 (gray-iron, UNVERIFIED + unregistered)
    equipment : furnace-001  (induction furnace, verified+registered)
                shakeout-002 (shakeout line, UNVERIFIED + unregistered)

  Every `:log-production-batch` subject below, and every `:batch-id` /
  `:equipment-id` a maintenance / shipment / safety-concern proposal
  references, is one of those five seeded ids -- no invented entity.

  The `:schedule-maintenance` / `:coordinate-shipment` /
  `:flag-safety-concern` SUBJECT ids (mnt-*, ship-*, concern-*) are NOT
  seeded and cannot be: `store/mem-store` starts with `:maintenance {}`,
  `:shipments {}` and `:safety-concerns []`, and those records are
  MINTED by `store/commit-record!` itself (it calls
  `foundrymfg.registry/register-maintenance` / `register-shipment` to
  issue the MNT-/SHP- record number). They are draft-record identifiers
  produced by this run, exactly as in this repo's own
  `foundrymfg.sim` demo driver, and each one points at a seeded
  equipment/batch id. Nothing else about them is authored here -- their
  record numbers, `:scheduled?` flags and store rows all come back out
  of the store after the real run.

  Scenario request parameters (a scheduled date, a shipment weight, a
  concern severity/description) are INPUTS to the real ops, the same
  way `foundrymfg.sim` supplies them. Everything RENDERED is read back
  out of `store/all-batches` / `all-equipment` / `all-maintenance` /
  `shipment` / `safety-concerns` / `maintenance-history` /
  `shipment-history` / `ledger` after the actor has actually run.

  DETERMINISM: the advisor is `foundrymfg.advisor/mock-advisor` (pure),
  the governor/registry/phase layers are pure, `physics-2d` is a
  fixed-timestep integrator with no wall clock, and nothing here reads
  a clock or a random source -- two consecutive runs are byte-identical.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jp-go-dds.skin]
            [foundrymfg.operation :as op]
            [foundrymfg.registry :as registry]
            [foundrymfg.robotics :as robotics]
            [foundrymfg.store :as store]
            [langgraph.graph :as g]))

(def ^:private coordinator
  "The human operator context every request below runs under. Phase 3
  (`foundrymfg.phase/phases`) is this actor's most permissive rollout
  phase -- only `:log-production-batch` may auto-commit even here."
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context coordinator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}}
          {:thread-id tid :resume? true}))

(defn- reject! [actor tid]
  (g/run* actor {:approval {:status :rejected :by "coord-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a freshly seeded store through a scenario that reaches every
  disposition this actor can produce. Returns the store.

  CLEAN LIFECYCLE (all four ops, one seeded plant):
    1. `:log-production-batch` on batch-001, re-logging the batch's OWN
       recorded `:alloy-grade` -- governor-clean, and the only op in
       any phase's `:auto` set, so it AUTO-COMMITS at phase 3.
    2. `:schedule-maintenance` mnt-1 against furnace-001 (verified +
       registered) -- governor-clean but NEVER auto-eligible at any
       phase, so it escalates; the human plant supervisor approves and
       it commits (issuing MNT-000000, and stamping furnace-001's own
       `:last-scheduled-maintenance-date`).
    3. `:flag-safety-concern` concern-1 against furnace-001 -- ALWAYS
       escalates (`:stake :coordination/safety-concern` is in the
       governor's `high-stakes` set); approved and committed.
    4. `:coordinate-shipment` ship-1 against batch-001 for 5000 kg --
       within the batch's own independently recomputed headroom
       (10000 already shipped + 5000 <= 50000 logged), escalates,
       approved, commits (issuing SHP-000000 and advancing batch-001's
       own `:shipped-weight-kg` to 15000).

  SOFT ESCALATION THE HUMAN REJECTS (the one hold that is NOT a HARD
  governor violation, and the reason `status-cell` below must tell the
  two apart):
    5. `:coordinate-shipment` ship-4 against batch-001 for 2500 kg --
       governor-clean, escalates, and the approver REJECTS it. The
       store records `:approval-rejected` with basis `:approver-rejected`.

  HARD HOLDS -- each a DIFFERENT rule, none of which ever reaches a
  human (`foundrymfg.operation`'s `:decide` node routes HARD straight
  to `:hold`):
    6.  mnt-2  against shakeout-002 (UNVERIFIED/unregistered equipment)
        -> :equipment-not-verified
    7.  ship-2 against batch-003 (UNVERIFIED/unregistered batch)
        -> :batch-not-verified
    8.  ship-3 against batch-002 for 1000 kg (7500 already shipped of
        8000 logged) -> :shipment-weight-exceeded
    9.  mnt-3  against furnace-001 with `:actuate-furnace? true`
        -> :furnace-actuate-blocked (permanent, no override, ever)
    10. mnt-1  a second time -> :already-scheduled
    11. batch-001 patched with a fabricated `:alloy-grade`
        -> :invalid-alloy-grade
    12. batch-001 patched with an implausible `:defect-rate-percent`
        -> :invalid-defect-rate
    13. a caller whose own request `:effect` is `:direct-write`
        -> :not-propose-effect (structural bypass attempt)
    14. an op outside the closed allowlist
        -> :unknown-op + :furnace-control-blocked (two rules at once)"
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]

    ;; ---- clean lifecycle -------------------------------------------------
    (exec! actor "b1-log"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:alloy-grade :ductile-iron}})

    (exec! actor "m1-sched"
           {:op :schedule-maintenance :effect :propose :subject "mnt-1"
            :value {:equipment-id "furnace-001"
                    :maintenance-type :refractory-inspection
                    :scheduled-date "2026-08-01"
                    :actuate-furnace? false}})
    (approve! actor "m1-sched")

    (exec! actor "c1-flag"
           {:op :flag-safety-concern :effect :propose :subject "concern-1"
            :value {:equipment-id "furnace-001" :severity :moderate
                    :description "溶解炉周辺の輻射熱上昇、湯漏れの兆候"}})
    (approve! actor "c1-flag")

    (exec! actor "s1-ship"
           {:op :coordinate-shipment :effect :propose :subject "ship-1"
            :value {:batch-id "batch-001" :weight-kg 5000.0
                    :destination "buyer-yard-north"}})
    (approve! actor "s1-ship")

    ;; ---- soft escalation the human rejects -------------------------------
    (exec! actor "s4-ship"
           {:op :coordinate-shipment :effect :propose :subject "ship-4"
            :value {:batch-id "batch-001" :weight-kg 2500.0
                    :destination "buyer-yard-west"}})
    (reject! actor "s4-ship")

    ;; ---- HARD holds ------------------------------------------------------
    (exec! actor "m2-sched"
           {:op :schedule-maintenance :effect :propose :subject "mnt-2"
            :value {:equipment-id "shakeout-002"
                    :maintenance-type :screen-inspection
                    :scheduled-date "2026-08-01"
                    :actuate-furnace? false}})

    (exec! actor "s2-ship"
           {:op :coordinate-shipment :effect :propose :subject "ship-2"
            :value {:batch-id "batch-003" :weight-kg 1000.0
                    :destination "buyer-yard-south"}})

    (exec! actor "s3-ship"
           {:op :coordinate-shipment :effect :propose :subject "ship-3"
            :value {:batch-id "batch-002" :weight-kg 1000.0
                    :destination "buyer-yard-east"}})

    (exec! actor "m3-sched"
           {:op :schedule-maintenance :effect :propose :subject "mnt-3"
            :value {:equipment-id "furnace-001" :maintenance-type :force-run
                    :scheduled-date "2026-09-01"
                    :actuate-furnace? true}})

    (exec! actor "m1-again"
           {:op :schedule-maintenance :effect :propose :subject "mnt-1"
            :value {:equipment-id "furnace-001"
                    :maintenance-type :refractory-inspection
                    :scheduled-date "2026-08-01"
                    :actuate-furnace? false}})

    (exec! actor "b1-alloy"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:alloy-grade :unobtainium}})

    (exec! actor "b1-defect"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:defect-rate-percent 999.0}})

    (exec! actor "b1-direct"
           {:op :log-production-batch :effect :direct-write :subject "batch-001"
            :patch {:alloy-grade :ductile-iron}})

    (exec! actor "x-unknown"
           {:op :actuate-melting-furnace :effect :propose :subject "batch-001"})

    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- nm
  "Render one basis/cite element. `:basis` holds keywords for holds
  (`(mapv :rule violations)`) and whatever the advisor cited for
  commits (`(:cites proposal)`, which is keywords for a batch patch and
  the referenced entity id string for the other three ops)."
  [x]
  (if (keyword? x) (name x) (str x)))

(defn- join-basis [coll]
  (if (seq coll) (str/join ", " (map nm coll)) ""))

(defn- yes-no [b] (if b "yes" "no"))

(defn- ledger-for
  "All ledger facts whose `:subject` is `id`, in append order."
  [ledger id]
  (filter #(= (:subject %) id) ledger))

(defn- hard-hold?
  "A ledger hold that came from the governor's own HARD violation set,
  as opposed to the phase gate (`:phase-reason`) or a human rejection
  (`:t :approval-rejected`)."
  [f]
  (and (= :governor-hold (:t f))
       (nil? (:phase-reason f))
       (seq (:violations f))))

(defn- status-cell
  "Last ledger disposition for `id`.

  Branches ONLY on fact types `foundrymfg.store/append-ledger!` is
  actually reached with: `foundrymfg.operation`'s `:commit` node writes
  `:committed`, and its `:hold` node writes whichever of
  `:governor-hold` / `:approval-rejected` is last in the audit channel.
  `:approval-granted` and `:approval-requested` go to the in-memory
  `:audit` channel ONLY and are never appended to the ledger, so
  branching on them would be dead code."
  [ledger id]
  (let [f (last (ledger-for ledger id))]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"

      (= :committed (:t f)) "<span class=\"ok\">committed</span>"

      (hard-hold? f)
      (str "<span class=\"critical\">HARD hold &middot; "
           (esc (join-basis (:basis f))) "</span>")

      (= :approval-rejected (:t f))
      "<span class=\"warn\">rejected by approver</span>"

      (= :governor-hold (:t f))
      (str "<span class=\"warn\">held &middot; "
           (esc (nm (or (:phase-reason f) :phase-gate))) "</span>")

      :else "<span class=\"muted\">in progress</span>")))

(defn- ground-truth-cell
  "The two independent ground-truth facts the governor re-derives for
  itself (`registry/equipment-ready?` / `registry/batch-ready?`),
  displayed from the entity's OWN stored fields."
  [ready? verified? registered?]
  (if ready?
    "<span class=\"ok\">verified &amp; registered</span>"
    (str "<span class=\"critical\">not ready &middot; verified=" (yes-no verified?)
         " registered=" (yes-no registered?) "</span>")))

(defn- batch-row [ledger {:keys [id alloy-grade material output-form weight-kg
                                 shipped-weight-kg defect-rate-percent
                                 coupon-mass-kg last-assessed] :as b}]
  (format (str "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td>"
               "<td class=\"num\">%s</td><td class=\"num\">%s</td><td class=\"num\">%s</td>"
               "<td>%s</td><td>%s</td><td>%s</td></tr>")
          (esc id) (esc (nm alloy-grade)) (esc material) (esc (nm output-form))
          (esc weight-kg) (esc shipped-weight-kg) (esc defect-rate-percent)
          (if (number? coupon-mass-kg)
            (esc coupon-mass-kg)
            "<span class=\"muted\">no coupon on file</span>")
          (ground-truth-cell (registry/batch-ready? b)
                             (registry/batch-verified? b)
                             (registry/batch-registered? b))
          (str (status-cell ledger id)
               (when last-assessed
                 (str " <span class=\"muted\">(last assessed " (esc last-assessed) ")</span>")))))

(defn- equipment-row [{:keys [id kind last-maintenance-date
                              last-scheduled-maintenance-date] :as e}]
  (format (str "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td>"
               "<td>%s</td><td>%s</td></tr>")
          (esc id) (esc (nm kind))
          (ground-truth-cell (registry/equipment-ready? e)
                             (registry/equipment-verified? e)
                             (registry/equipment-registered? e))
          (if last-maintenance-date
            (esc last-maintenance-date)
            "<span class=\"muted\">none on file</span>")
          (if last-scheduled-maintenance-date
            (str "<span class=\"ok\">" (esc last-scheduled-maintenance-date) "</span>")
            "<span class=\"muted\">none this run</span>")))

(defn- maintenance-row [ledger {:keys [id equipment-id maintenance-type scheduled-date
                                       actuate-furnace? scheduled? maintenance-number]}]
  (format (str "        <tr><td><code>%s</code></td><td><code>%s</code></td><td>%s</td>"
               "<td>%s</td><td>%s</td><td>%s</td><td><code>%s</code></td><td>%s</td></tr>")
          (esc id) (esc equipment-id) (esc (nm maintenance-type))
          (esc scheduled-date)
          (if actuate-furnace?
            "<span class=\"critical\">true</span>"
            "<span class=\"ok\">false</span>")
          (yes-no scheduled?)
          (esc maintenance-number)
          (status-cell ledger id)))

(defn- shipment-row [ledger {:keys [id batch-id weight-kg destination shipment-number]}]
  (format (str "        <tr><td><code>%s</code></td><td><code>%s</code></td>"
               "<td class=\"num\">%s</td><td>%s</td><td><code>%s</code></td><td>%s</td></tr>")
          (esc id) (esc batch-id) (esc weight-kg) (esc destination)
          (esc shipment-number)
          (status-cell ledger id)))

(defn- concern-row [ledger {:keys [id equipment-id severity description]}]
  (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc equipment-id) (esc (nm severity)) (esc description)
          (status-cell ledger id)))

(defn- draft-record-row
  "One `foundrymfg.registry`-issued immutable draft record, straight out
  of `store/maintenance-history` / `store/shipment-history` (string keys
  -- these are the registry's own record maps, not domain entities)."
  [r]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td>%s</td></tr>"
          (esc (get r "record_id")) (esc (get r "kind"))
          (esc (or (get r "maintenance_id") (get r "shipment_id")))
          (yes-no (get r "immutable"))))

(defn- ledger-row [{:keys [t op subject disposition basis violations phase-reason] :as f}]
  (format (str "        <tr><td>%s</td><td><code>%s</code></td><td><code>%s</code></td>"
               "<td>%s</td><td>%s</td><td>%s</td></tr>")
          (cond (hard-hold? f) (str "<span class=\"critical\">" (esc (nm t)) "</span>")
                (= :approval-rejected t) (str "<span class=\"warn\">" (esc (nm t)) "</span>")
                :else (str "<span class=\"ok\">" (esc (nm t)) "</span>"))
          (esc (nm (or op :n-a))) (esc subject)
          (esc (nm (or disposition :n-a)))
          (esc (join-basis basis))
          ;; A human rejection carries no `:detail` (governor/hold-fact is
          ;; handed a synthetic `{:rule :approver-rejected}` violation) and
          ;; no `:summary`, so say so rather than emit an empty cell.
          (if-let [d (or (some-> violations first :detail)
                         (some-> phase-reason nm)
                         (:summary f))]
            (esc d)
            "<span class=\"muted\">no detail recorded</span>")))

(def ^:private action-gate-rows
  ;; Static description of this actor's own closed op contract, read off
  ;; `foundrymfg.governor/allowed-ops`, `allowed-proposal-effects`,
  ;; `high-stakes` and `foundrymfg.phase/phases` -- documentation of
  ;; FIXED behaviour, not runtime telemetry, so it is legitimately
  ;; hand-described here rather than derived from the run above.
  ["        <tr><td><code>:log-production-batch</code></td><td><code>:batch/upsert</code></td><td><span class=\"ok\">phase-3 auto-commit when governor-clean &middot; the ONLY op in any phase's :auto set</span></td><td>closed-set alloy-grade &middot; plausibility-bounded defect-rate</td></tr>"
   "        <tr><td><code>:schedule-maintenance</code></td><td><code>:maintenance/schedule</code></td><td><span class=\"warn\">ALWAYS human approval &middot; never in any phase's :auto set</span></td><td>equipment verified+registered (re-derived) &middot; no double-schedule &middot; <span class=\"critical\">:actuate-furnace? permanently blocked</span></td></tr>"
   "        <tr><td><code>:flag-safety-concern</code></td><td><code>:safety-concern/flag</code></td><td><span class=\"warn\">ALWAYS escalates &middot; :coordination/safety-concern is high-stakes at any confidence</span></td><td>never gated on the referenced equipment being verified -- a concern may be raised about anything</td></tr>"
   "        <tr><td><code>:coordinate-shipment</code></td><td><code>:shipment/propose</code></td><td><span class=\"warn\">ALWAYS human approval &middot; never in any phase's :auto set</span></td><td>batch verified+registered (re-derived) &middot; shipped-weight headroom independently recomputed &middot; un-checkable headroom is NOT headroom &middot; physics-2d tensile-load recheck</td></tr>"
   "        <tr><td colspan=\"4\"><span class=\"muted\">Any other <code>:op</code>, any other proposal <code>:effect</code>, or a caller request whose own <code>:effect</code> is not <code>:propose</code>, is a HARD hold before any domain check runs.</span></td></tr>"])

(defn render
  "Renders the operator console from a store `db` that has already been
  driven through `run-demo!` (or any other real scenario). Every value
  below is read out of `db` -- the store's own entities, the registry's
  own draft records, and the append-only ledger."
  [db]
  (let [ledger (vec (store/ledger db))
        batches (store/all-batches db)
        equipment (store/all-equipment db)
        maintenance (store/all-maintenance db)
        concerns (store/safety-concerns db)
        mnt-history (store/maintenance-history db)
        shp-history (store/shipment-history db)
        shipments (->> shp-history
                       (keep #(store/shipment db (get % "shipment_id")))
                       (sort-by :id))
        hard-holds (filter hard-hold? ledger)
        committed (filter #(= :committed (:t %)) ledger)]
    (str
     "<!DOCTYPE html>\n"
     "<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
     "<meta name=\"color-scheme\" content=\"light\">"
     "<title>cloud-itonami-isic-2431 &middot; Casting of iron and steel &middot; Operator Console</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Casting of iron and steel (ISIC 2431) — Operator Console</h1>\n"
     "</header>\n"
     "<p><span class=\"badge\">read-only sample</span> <span class=\"badge\">governor-gated</span> "
     "<span class=\"badge\">maintenance / safety / shipment always human-approved</span></p>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>This run</h2>\n"
     "    <p class=\"muted\">Build-time generated from <code>foundrymfg.store</code> by "
     "<code>foundrymfg.render-html</code> (<code>clojure -M:dev:render-html</code>). "
     "The numbers below are counts over the append-only ledger this run actually produced — "
     "no figure on this page is typed in by hand.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Ledger facts</th><th>Committed</th><th>HARD holds (never reached a human)</th>"
     "<th>Maintenance drafts issued</th><th>Shipment drafts issued</th><th>Safety concerns logged</th></tr></thead>\n"
     "      <tbody>\n"
     (format (str "        <tr><td class=\"num\">%s</td><td class=\"num\">%s</td>"
                  "<td class=\"num\">%s</td><td class=\"num\">%s</td>"
                  "<td class=\"num\">%s</td><td class=\"num\">%s</td></tr>")
             (count ledger) (count committed) (count hard-holds)
             (count mnt-history) (count shp-history) (count concerns))
     "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Production batches (heat / cast lots)</h2>\n"
     "    <p class=\"muted\">Ground truth the governor re-derives for itself before any shipment may be "
     "coordinated — never the advisor's own report. <code>:shipped-weight-kg</code> is the batch's own "
     "cumulative-shipped record, advanced only by a committed shipment.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Batch</th><th>Alloy grade</th><th>Material</th><th>Output form</th>"
     "<th>Logged weight (kg)</th><th>Shipped to date (kg)</th><th>Defect rate (%)</th>"
     "<th>Tensile coupon mass (kg)</th><th>Ground truth</th><th>Last ledger disposition</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial batch-row ledger) batches)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Plant equipment</h2>\n"
     "    <p class=\"muted\">Maintenance may never be scheduled against a unit that has not actually been "
     "inspected and commissioned. <em>Scheduled this run</em> is stamped onto the equipment record by a "
     "committed <code>:schedule-maintenance</code>.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Unit</th><th>Kind</th><th>Ground truth</th><th>Last maintenance on file</th>"
     "<th>Scheduled this run</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map equipment-row equipment)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Action gate (Foundry Plant Operations Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden and never reach a human. Directly actuating the "
     "melting furnace or pouring line is blocked permanently, at two independent layers "
     "(<code>foundrymfg.governor</code> and <code>foundrymfg.phase</code>).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Commit effect</th><th>Gate</th><th>Independently re-derived checks</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     (format (str "    <p class=\"muted\">The tensile-load recheck (a real <code>physics-2d</code> "
                  "keel-block/Y-block coupon simulation, floor %s N) is armed on every "
                  "<code>:coordinate-shipment</code>, but no seeded batch carries a "
                  "<code>:coupon-mass-kg</code> reading, so it does not fire in this run — "
                  "missing telemetry is never silently treated as a violation.</p>\n")
             (esc robotics/min-tensile-load-n))
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Maintenance windows (SSoT)</h2>\n"
     "    <p class=\"muted\">Only committed windows exist here — a held proposal writes nothing to the SSoT. "
     "<code>:scheduled?</code> is the dedicated double-schedule guard (never a status value).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Window</th><th>Equipment</th><th>Type</th><th>Scheduled date</th>"
     "<th>actuate-furnace?</th><th>scheduled?</th><th>Record no.</th><th>Last ledger disposition</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial maintenance-row ledger) maintenance)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Outbound shipments (SSoT)</h2>\n"
     "    <p class=\"muted\">Only committed shipments exist here; every held or rejected shipment proposal "
     "appears in the ledger below instead.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Shipment</th><th>Batch</th><th>Weight (kg)</th><th>Destination</th>"
     "<th>Record no.</th><th>Last ledger disposition</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial shipment-row ledger) shipments)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Safety concerns (append-only)</h2>\n"
     "    <p class=\"muted\">Always escalated to a human plant supervisor, at any confidence, and never "
     "gated on the referenced equipment being verified.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Concern</th><th>Equipment</th><th>Severity</th><th>Description</th>"
     "<th>Last ledger disposition</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial concern-row ledger) concerns)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Immutable draft records (foundrymfg.registry)</h2>\n"
     "    <p class=\"muted\">Every certificate this actor produces is UNSIGNED — signing is the human "
     "supervisor's/approver's act, not this actor's.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Record no.</th><th>Kind</th><th>Subject</th><th>Immutable</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map draft-record-row (concat mnt-history shp-history))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every commit, every HARD hold and every human "
     "rejection this scenario produced, in order. <code>:approval-requested</code> and "
     "<code>:approval-granted</code> are in-memory audit-channel events only and are deliberately absent "
     "here; the ledger records the outcome, not the handoff.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Disposition</th><th>Basis</th><th>Detail</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map ledger-row ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "</main>\n"
     "<footer>\n"
     "  <p>Generated by <code>foundrymfg.render-html</code> from a real "
     "<code>foundrymfg.operation</code> run against <code>foundrymfg.store/sample-data!</code>. "
     "Deterministic: no clock, no random source, no network. "
     "Regenerate with <code>clojure -M:dev:render-html</code>.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)
        ledger (store/ledger db)]
    (io/make-parents out)
    (spit (io/file out) html :encoding "UTF-8")
    (println "wrote" out
             "(" (count ledger) "ledger facts,"
             (count (filter hard-hold? ledger)) "HARD holds,"
             (count (store/maintenance-history db)) "maintenance drafts,"
             (count (store/shipment-history db)) "shipment drafts,"
             (count (store/safety-concerns db)) "safety concerns )")))
