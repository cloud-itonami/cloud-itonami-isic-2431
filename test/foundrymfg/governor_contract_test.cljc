(ns foundrymfg.governor-contract-test
  "The governor contract as executable tests -- this vertical's own
  scope boundary ('does NOT control the melting furnace or pouring
  line directly... does NOT authorize or execute maintenance/furnace
  operations') implemented faithfully. The single invariant under
  test:

    FoundryAdvisor never schedules maintenance, flags a safety
    concern, or coordinates a shipment the Foundry Plant Operations
    Governor would reject; `:schedule-maintenance`/`:flag-safety-
    concern`/`:coordinate-shipment` NEVER auto-commit at any phase;
    `:log-production-batch` (no physical/financial risk) MAY
    auto-commit when clean; and every decision (commit OR hold) leaves
    exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [foundrymfg.store :as store]
            [foundrymfg.operation :as op]))

(defn- fresh []
  (let [db (-> (store/mem-store) (store/sample-data!))]
    [db (op/build db)]))

(def coordinator {:actor-id "coord-1" :actor-role :plant-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(defn- reject! [actor tid]
  (g/run* actor {:approval {:status :rejected :by "coord-1"}} {:thread-id tid :resume? true}))

(deftest clean-log-production-batch-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :log-production-batch :effect :propose :subject "batch-001"
                   :patch {:alloy-grade :ductile-iron}} coordinator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :ductile-iron (:alloy-grade (store/batch db "batch-001"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest schedule-maintenance-always-needs-approval
  (testing "scheduling is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2"
                    {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                     :value {:equipment-id "furnace-001" :maintenance-type :refractory-inspection
                             :scheduled-date "2026-08-01" :actuate-furnace? false}}
                    coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (true? (:scheduled? (store/maintenance db "mnt-1"))))
        (is (= 1 (count (store/maintenance-history db))))))))

(deftest effect-not-propose-is-held
  (testing "a request whose own :effect is not :propose -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :log-production-batch :effect :direct-write :subject "batch-001"
                     :patch {:alloy-grade :ductile-iron}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:not-propose-effect} (-> (store/ledger db) first :basis))))))

(deftest unknown-op-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t4" {:op :actuate-melting-furnace :effect :propose :subject "x"} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:unknown-op} (-> (store/ledger db) first :basis)))))

(deftest equipment-not-verified-is-held-and-unoverridable
  (testing "scheduling against an unverified/unregistered equipment unit -> HOLD, settles immediately, no interrupt"
    (let [[db actor] (fresh)
          res (exec-op actor "t5"
                    {:op :schedule-maintenance :effect :propose :subject "mnt-2"
                     :value {:equipment-id "shakeout-002" :maintenance-type :screen-inspection
                             :scheduled-date "2026-08-01" :actuate-furnace? false}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:equipment-not-verified} (-> (store/ledger db) last :basis)))
      (is (empty? (store/maintenance-history db))))))

(deftest batch-not-verified-is-held-and-unoverridable
  (testing "coordinating a shipment against an unverified/unregistered batch -> HOLD, settles immediately, no interrupt"
    (let [[db actor] (fresh)
          res (exec-op actor "t6"
                    {:op :coordinate-shipment :effect :propose :subject "ship-2"
                     :value {:batch-id "batch-003" :weight-kg 1000.0
                             :destination "buyer-yard-south"}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:batch-not-verified} (-> (store/ledger db) last :basis)))
      (is (empty? (store/shipment-history db))))))

(deftest shipment-weight-exceeded-is-held-and-unoverridable
  (testing "a shipment proposal whose weight would exceed the batch's own logged weight -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t7"
                    {:op :coordinate-shipment :effect :propose :subject "ship-3"
                     :value {:batch-id "batch-002" :weight-kg 1000.0
                             :destination "buyer-yard-east"}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:shipment-weight-exceeded} (-> (store/ledger db) last :basis)))
      (is (empty? (store/shipment-history db))))))

(deftest tensile-test-out-of-tolerance-is-held
  (testing "ADR-2607999800: a batch whose own recorded :coupon-mass-kg yields a REAL physics-2d-simulated peak tensile load below the disclosed floor -> HOLD, wired into the SAME :coordinate-shipment proposal-gating function every other HARD check runs through"
    (let [[db actor] (fresh)
          _ (store/commit-record! db {:effect :batch/upsert :path ["batch-001"]
                                      :value {:coupon-mass-kg 1.0}})
          res (exec-op actor "t15"
                    {:op :coordinate-shipment :effect :propose :subject "ship-4"
                     :value {:batch-id "batch-001" :weight-kg 5000.0
                             :destination "buyer-yard-north"}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:tensile-test-out-of-tolerance} (-> (store/ledger db) last :basis)))
      (is (empty? (store/shipment-history db))))))

(deftest tensile-test-in-tolerance-does-not-block-shipment
  (testing "a batch whose own recorded :coupon-mass-kg clears the real disclosed floor is unaffected by this check -- still escalates for human approval like any clean shipment coordination, proving this ADR is purely additive"
    (let [[db actor] (fresh)
          _ (store/commit-record! db {:effect :batch/upsert :path ["batch-001"]
                                      :value {:coupon-mass-kg 5.0}})
          res (exec-op actor "t16"
                    {:op :coordinate-shipment :effect :propose :subject "ship-5"
                     :value {:batch-id "batch-001" :weight-kg 5000.0
                             :destination "buyer-yard-north"}}
                    coordinator)]
      (is (= :interrupted (:status res)) "pauses for human approval even when governor-clean")
      (let [r2 (approve! actor "t16")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/shipment-history db))))))))

(deftest missing-coupon-mass-never-blocks-shipment
  (testing "a batch with no :coupon-mass-kg on file (no tensile-test coupon poured/tested yet) never trips the new check -- missing telemetry != violation, still escalates normally like any clean shipment"
    (let [[db actor] (fresh)
          res (exec-op actor "t17"
                    {:op :coordinate-shipment :effect :propose :subject "ship-6"
                     :value {:batch-id "batch-002" :weight-kg 100.0
                             :destination "buyer-yard-east"}}
                    coordinator)]
      (is (= :interrupted (:status res)) "not HARD-held by the new check")
      (is (nil? (:coupon-mass-kg (store/batch db "batch-002")))
          "batch-002's seeded sample data never carried this field"))))

(deftest furnace-actuate-is-held-and-permanently-blocked
  (testing "a proposal that sets :actuate-furnace? true -> HOLD, PERMANENT, never reaches request-approval even though the equipment is verified and registered"
    (let [[db actor] (fresh)
          res (exec-op actor "t8"
                    {:op :schedule-maintenance :effect :propose :subject "mnt-3"
                     :value {:equipment-id "furnace-001" :maintenance-type :force-run
                             :scheduled-date "2026-09-01" :actuate-furnace? true}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:furnace-actuate-blocked} (-> (store/ledger db) last :basis)))
      (is (empty? (store/maintenance-history db))))))

(deftest schedule-maintenance-double-schedule-is-held
  (testing "scheduling the SAME maintenance record twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (exec-op actor "t9a" {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                                  :value {:equipment-id "furnace-001" :maintenance-type :refractory-inspection
                                          :scheduled-date "2026-08-01" :actuate-furnace? false}} coordinator)
          _ (approve! actor "t9a")
          res (exec-op actor "t9" {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                                   :value {:equipment-id "furnace-001" :maintenance-type :refractory-inspection
                                           :scheduled-date "2026-08-01" :actuate-furnace? false}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-scheduled} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/maintenance-history db))) "still only the one earlier schedule"))))

(deftest invalid-alloy-grade-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t10" {:op :log-production-batch :effect :propose :subject "batch-001"
                                  :patch {:alloy-grade :unobtainium}} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:invalid-alloy-grade} (-> (store/ledger db) last :basis)))
    (is (not= :unobtainium (:alloy-grade (store/batch db "batch-001"))) "fabricated alloy-grade never lands in the SSoT")))

(deftest invalid-defect-rate-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t11" {:op :log-production-batch :effect :propose :subject "batch-001"
                                  :patch {:defect-rate-percent 999.0}} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:invalid-defect-rate} (-> (store/ledger db) last :basis)))
    (is (not= 999.0 (:defect-rate-percent (store/batch db "batch-001"))) "fabricated defect-rate never lands in the SSoT")))

(deftest safety-concern-always-escalates-even-high-confidence
  (testing "flag-safety-concern always escalates -- never auto-committed, regardless of confidence"
    (let [[db actor] (fresh)
          res (exec-op actor "t12" {:op :flag-safety-concern :effect :propose :subject "concern-1"
                                    :value {:equipment-id "furnace-001" :severity :moderate
                                            :description "molten-metal splash risk near melting furnace"}}
                       coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t12")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/safety-concerns db))))))))

(deftest safety-concern-approval-rejected-leaves-no-record-only-a-hold-fact
  (let [[db actor] (fresh)
        _ (exec-op actor "t13" {:op :flag-safety-concern :effect :propose :subject "concern-2"
                                :value {:equipment-id "furnace-001" :severity :low :description "y"}}
                   coordinator)
        r (reject! actor "t13")]
    (is (= :hold (get-in r [:state :disposition])))
    (is (= 0 (count (store/safety-concerns db))) "rejected approval never reaches the commit node")
    (is (= 1 (count (store/ledger db))))))

(deftest coordinate-shipment-always-needs-approval
  (testing "a CLEAN shipment coordination is never auto-eligible -- always escalates, even below any weight threshold"
    (let [[db actor] (fresh)
          res (exec-op actor "t14" {:op :coordinate-shipment :effect :propose :subject "ship-1"
                                    :value {:batch-id "batch-001" :weight-kg 5000.0
                                            :destination "buyer-yard-north"}}
                       coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t14")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/shipment-history db))))))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N settled operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :log-production-batch :effect :propose :subject "batch-001"
                          :patch {:alloy-grade :ductile-iron}} coordinator)
      (exec-op actor "b" {:op :log-production-batch :effect :propose :subject "batch-001"
                          :patch {:alloy-grade :fabricated-grade}} coordinator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
