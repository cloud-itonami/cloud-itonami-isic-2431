(ns foundrymfg.render-html
  "Build-time operator-console renderer for the iron-and-steel casting
  foundry plant-operations coordination actor.

  This namespace renders NOTHING of its own invention. It boots the
  REAL actor stack -- `foundrymfg.store`'s MemStore seed,
  `foundrymfg.operation/build`'s langgraph StateGraph (advisor ->
  governor -> phase gate -> commit|hold|approval), and
  `foundrymfg.governor`'s independent rules -- drives a fixed scenario
  through it with `langgraph.graph/run*`, and then projects the
  resulting SSoT + append-only audit ledger into HTML. Every batch,
  equipment unit, maintenance draft, shipment draft, safety concern,
  HARD hold and rule name on the page is read back out of the store
  the actor itself wrote.

  Consequences of that, deliberately:

    - Every subject id on the page either comes from
      `foundrymfg.store/sample-data!`'s seed (`batch-001`/`batch-002`/
      `batch-003`, `furnace-001`/`shakeout-002`) or is created HERE by
      the real `:log-production-batch` intake op (`batch-004`, via the
      store's own `:batch/upsert` commit path). No fabricated ids.

    - The HARD holds shown are produced by
      `foundrymfg.governor/check` reacting to deliberately
      non-compliant requests -- the rule names and the Japanese
      `:detail` strings are the governor's own output, never literals
      typed into this file.

    - Only fact types the store's ledger actually receives are
      branched on: `:committed` (the `:commit` node) and
      `:governor-hold` / `:approval-rejected` (the `:hold` node).
      `:approval-granted` and `:approval-requested` exist ONLY on the
      in-memory `:audit` channel in `foundrymfg.operation` and are
      never appended to the ledger, so this renderer never claims to
      read them back.

    - `foundrymfg.robotics/tensile-test-telemetry-for` is called for
      real on every batch that carries a `:coupon-mass-kg`, so the
      tensile-load column is the actual `physics-2d`-simulated peak
      load the governor itself re-derives -- not a stored number.

  Styling reuses the jp-go-digital-design-system (DADS) CSS this repo
  ALREADY vendors into `docs/index.html` (see the provenance comment
  at the top of that file's `<style>` block); the console is marked up
  with real `dads-*` / `dds-ext-*` classes. If that file is missing
  (standalone fork), a small fallback stylesheet keeps the page
  readable.

  Deterministic: no clock, no randomness, no map-iteration order
  leaks. Re-running produces byte-identical output.

    clojure -M:dev:render-html [out-file]   ; default docs/samples/operator-console.html"
  (:require [clojure.string :as str]
            [langgraph.graph :as g]
            [foundrymfg.governor :as governor]
            [foundrymfg.operation :as op]
            [foundrymfg.phase :as phase]
            [foundrymfg.registry :as registry]
            [foundrymfg.robotics :as robotics]
            [foundrymfg.store :as store]))

;; ----------------------------- driving the REAL actor -----------------------------

(def ^:private coordinator
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase phase/default-phase})

(defn- exec!
  "One coordination request = one actor run."
  [actor thread-id request]
  (g/run* actor {:request request :context coordinator} {:thread-id thread-id}))

(defn- resume!
  "Resume a run parked at `interrupt-before #{:request-approval}` with a
  human plant supervisor's/shipping approver's decision."
  [actor thread-id status]
  (g/run* actor {:approval {:status status :by "coord-1"}}
          {:thread-id thread-id :resume? true}))

(defn- step!
  "Drive one request through the real graph and record what the ACTOR
  decided (never what this file expected). `:approval` is only ever
  applied when the actor actually escalated -- a HARD hold never
  reaches a human, so it never reaches an approval here either."
  [trace actor thread-id label request & [{:keys [approval]}]]
  (let [r1        (exec! actor thread-id request)
        verdict   (get-in r1 [:state :verdict])
        escalated (= :escalate (get-in r1 [:state :disposition]))
        r2        (when (and escalated approval) (resume! actor thread-id approval))
        final     (or (:state r2) (:state r1))]
    (swap! trace conj
           {:thread      thread-id
            :label       label
            :op          (:op request)
            :subject     (:subject request)
            :req-effect  (:effect request)
            ;; the entity the request pointed AT (a held request never
            ;; reaches the store, so this is the only way an entity row
            ;; can show that it was targeted and refused)
            :batch-ref   (get-in request [:value :batch-id])
            :equip-ref   (get-in request [:value :equipment-id])
            :escalated?  escalated
            :approval    (when escalated approval)
            :confidence  (:confidence verdict)
            :hard?       (boolean (:hard? verdict))
            :rules       (mapv :rule (:violations verdict))
            :disposition (:disposition final)})
    final))

(defn run-demo!
  "Boot the real store + real actor and walk a foundry day through it.
  Returns {:db .. :trace [..]}."
  []
  (let [db    (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)
        trace (atom [])]

    ;; --- clean path -------------------------------------------------------
    (step! trace actor "t1" "既存バッチの定期記録更新 — governor clean、phase 3 の :auto 対象なので自動コミット"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:alloy-grade :ductile-iron :defect-rate-percent 1.1
                    :last-assessed "2026-08-12"}})

    ;; batch-004 does NOT exist in the seed. It is registered HERE, by the
    ;; real intake op, through the store's own :batch/upsert commit path --
    ;; the only way a new subject id may enter this console.
    (step! trace actor "t2" "新規ヒート batch-004 の受入登録（QC 検査結果つき） — この demo 内の intake op が実際に生成する subject"
           {:op :log-production-batch :effect :propose :subject "batch-004"
            :patch {:alloy-grade :gray-iron :output-form :sand-cast
                    :material "Gray Iron Pump-Housing Castings"
                    :weight-kg 30000.0 :defect-rate-percent 2.4
                    :verified? true :registered? true
                    :shipped-weight-kg 0.0
                    ;; keel-block/Y-block 試験片の有効参加質量 (ASTM A536/A216 系)
                    :coupon-mass-kg 2.0
                    :last-assessed "2026-08-12"}})

    (step! trace actor "t3" "furnace-001 の耐火物点検を予定（検証済み・登録済み設備） — 承認へ、人間が承認"
           {:op :schedule-maintenance :effect :propose :subject "mnt-1"
            :value {:equipment-id "furnace-001" :maintenance-type :refractory-inspection
                    :scheduled-date "2026-09-01" :actuate-furnace? false}}
           {:approval :approved})

    (step! trace actor "t4" "溶解炉まわりの安全懸念を報告 — 高 stake なので常に承認へ、人間が承認"
           {:op :flag-safety-concern :effect :propose :subject "concern-1"
            :value {:equipment-id "furnace-001" :severity :moderate
                    :description "溶解炉周辺の輻射熱上昇、湯漏れの兆候"}}
           {:approval :approved})

    (step! trace actor "t5" "batch-001 から 5,000 kg の出荷を調整（空き容量内） — 承認へ、人間が承認"
           {:op :coordinate-shipment :effect :propose :subject "ship-1"
            :value {:batch-id "batch-001" :weight-kg 5000.0
                    :destination "buyer-yard-north"}}
           {:approval :approved})

    ;; --- HARD holds: each one is a different governor rule ------------------
    ;; batch-004 is verified, registered, and has ample weight headroom, so the
    ;; ONLY thing that can stop this shipment is the real physics-2d tensile
    ;; simulation the governor recomputes fresh from :coupon-mass-kg.
    (step! trace actor "t6" "batch-004 から 4,000 kg の出荷を調整 — 検証済み・登録済み・容量内だが、独立再検証した実測引張荷重が許容下限未満"
           {:op :coordinate-shipment :effect :propose :subject "ship-2"
            :value {:batch-id "batch-004" :weight-kg 4000.0
                    :destination "buyer-yard-west"}}
           {:approval :approved})

    (step! trace actor "t7" "batch-002 から 1,000 kg の出荷を調整 — 生産量 8,000 kg に対し既出荷 7,500 kg"
           {:op :coordinate-shipment :effect :propose :subject "ship-3"
            :value {:batch-id "batch-002" :weight-kg 1000.0
                    :destination "buyer-yard-east"}}
           {:approval :approved})

    (step! trace actor "t8" "batch-003 から 1,000 kg の出荷を調整 — 当該バッチは未検証・未登録"
           {:op :coordinate-shipment :effect :propose :subject "ship-4"
            :value {:batch-id "batch-003" :weight-kg 1000.0
                    :destination "buyer-yard-south"}}
           {:approval :approved})

    (step! trace actor "t9" "shakeout-002 の保守を予定 — 当該設備は未検証・未登録"
           {:op :schedule-maintenance :effect :propose :subject "mnt-2"
            :value {:equipment-id "shakeout-002" :maintenance-type :screen-inspection
                    :scheduled-date "2026-09-05" :actuate-furnace? false}}
           {:approval :approved})

    (step! trace actor "t10" "furnace-001 を直接 actuate する保守提案 — 恒久禁止、人間の承認画面にすら到達しない"
           {:op :schedule-maintenance :effect :propose :subject "mnt-3"
            :value {:equipment-id "furnace-001" :maintenance-type :force-run
                    :scheduled-date "2026-09-10" :actuate-furnace? true}}
           {:approval :approved})

    (step! trace actor "t11" "mnt-1 を再度スケジュール — 二重予定"
           {:op :schedule-maintenance :effect :propose :subject "mnt-1"
            :value {:equipment-id "furnace-001" :maintenance-type :refractory-inspection
                    :scheduled-date "2026-09-01" :actuate-furnace? false}}
           {:approval :approved})

    (step! trace actor "t12" "batch-003 に存在しない合金種別を記録しようとする"
           {:op :log-production-batch :effect :propose :subject "batch-003"
            :patch {:alloy-grade :unobtainium}})

    (step! trace actor "t13" "batch-003 に物理的にありえない不良率を記録しようとする"
           {:op :log-production-batch :effect :propose :subject "batch-003"
            :patch {:defect-rate-percent 999.0}})

    (step! trace actor "t14" "caller が :effect :propose を名乗らずに書き込もうとする — 構造的な HARD hold"
           {:op :log-production-batch :effect :direct-write :subject "batch-001"
            :patch {:alloy-grade :ductile-iron}})

    (step! trace actor "t15" "許可リストに無い操作（溶解炉の直接操作）を要求する"
           {:op :actuate-melting-furnace :effect :propose :subject "batch-001"})

    ;; --- human says no ------------------------------------------------------
    (step! trace actor "t16" "batch-001 から 2,000 kg の出荷を調整 — 承認へ進むが、人間が却下"
           {:op :coordinate-shipment :effect :propose :subject "ship-5"
            :value {:batch-id "batch-001" :weight-kg 2000.0
                    :destination "buyer-yard-north"}}
           {:approval :rejected})

    {:db db :trace @trace}))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- nm [v] (if (keyword? v) (name v) (str v)))

(defn- fnum
  "Locale-independent number formatting (determinism: `format` would
  otherwise follow the build machine's default locale)."
  [x]
  (cond
    (nil? x)     "—"
    (integer? x) (str x)
    (number? x)  (String/format java.util.Locale/ROOT "%.1f" (into-array Object [(double x)]))
    :else        (str x)))

(defn- chip [color text]
  (str "<span class=\"dads-chip-label\" data-style=\"filled-1\" data-color=\"" color "\">"
       (esc text) "</span>"))

(defn- yes-no [b] (if b (chip "green" "true") (chip "red" "false")))

(defn- table
  "A DADS table: `headers` is a seq of strings, `rows` a seq of seqs of
  already-escaped HTML cell bodies."
  [caption headers rows]
  (str "<div class=\"dads-table\" data-size=\"dense\">"
       (when caption (str "<p class=\"dads-table__caption\">" (esc caption) "</p>"))
       "<table class=\"dads-table__table\" data-width=\"full\" data-cell-border=\"bottom\"><thead><tr>"
       (str/join (map #(str "<th class=\"dads-table__col-header\" scope=\"col\">" (esc %) "</th>") headers))
       "</tr></thead><tbody>"
       (if (seq rows)
         (str/join (map (fn [r] (str "<tr>" (str/join (map #(str "<td>" % "</td>") r)) "</tr>")) rows))
         (str "<tr><td colspan=\"" (count headers) "\">記録なし</td></tr>"))
       "</tbody></table></div>"))

(defn- section [id heading & body]
  (str "<section class=\"dds-ext-section\" id=\"" id "\">"
       "<h2 class=\"dads-heading\" data-size=\"24\" data-chip>" (esc heading) "</h2>"
       (str/join body)
       "</section>"))

;; ----------------------------- ledger projection -----------------------------
;; ONLY the fact types `foundrymfg.store`'s ledger actually receives:
;; :committed (the :commit node) and :governor-hold / :approval-rejected
;; (the :hold node). :approval-granted / :approval-requested live only on
;; the in-memory :audit channel and are never appended, so they are never
;; branched on here.

(def ^:private ledger-fact-types #{:committed :governor-hold :approval-rejected})

(defn- facts-for [ledger subject]
  (filter #(= subject (:subject %)) ledger))

(defn- status-chip [ledger subject]
  (let [f (last (facts-for ledger subject))]
    (case (:t f)
      :committed         (chip "green" (str "committed / " (nm (:op f))))
      :governor-hold     (chip "red" (str "HARD hold: " (str/join ", " (map nm (:basis f)))))
      :approval-rejected (chip "orange" "人間が却下")
      (chip "gray" "台帳に記録なし"))))

(defn- refs-cell
  "Which requests in this run pointed AT `id`, and what the actor did
  with them. A HARD-held request never reaches the store, so without
  this column a refused entity looks untouched."
  [trace k id]
  (let [hits (filter #(or (= id (:subject %)) (= id (get % k))) trace)]
    (if (seq hits)
      (str/join " "
                (for [s hits]
                  (str "<code>" (esc (:thread s)) "</code>&nbsp;"
                       (case (:disposition s)
                         :commit (chip "green" (nm (:op s)))
                         :hold   (if (:hard? s)
                                   (chip "red" (str (nm (:op s)) " · "
                                                    (str/join ", " (map nm (:rules s)))))
                                   (chip "orange" (str (nm (:op s)) " · 却下")))
                         (chip "gray" (nm (:op s)))))))
      "<span class=\"oc-muted\">参照なし</span>")))

;; ----------------------------- sections -----------------------------

(defn- batches-section [db ledger trace]
  (let [rows (for [b (store/all-batches db)]
               (let [tele (when (number? (:coupon-mass-kg b))
                            (robotics/tensile-test-telemetry-for b))
                     out? (robotics/simulation-out-of-tolerance? b)]
                 [(str "<code>" (esc (:id b)) "</code>")
                  (esc (or (:material b) "—"))
                  (str "<code>" (esc (nm (:alloy-grade b))) "</code>")
                  (str "<code>" (esc (nm (or (:output-form b) "—"))) "</code>")
                  (fnum (:weight-kg b))
                  (fnum (:shipped-weight-kg b))
                  (fnum (when (and (number? (:weight-kg b)) (number? (:shipped-weight-kg b)))
                         (- (:weight-kg b) (:shipped-weight-kg b))))
                  (fnum (:defect-rate-percent b))
                  (yes-no (registry/batch-verified? b))
                  (yes-no (registry/batch-registered? b))
                  (if tele
                    (str (fnum (:coupon-mass-kg b)) " kg → "
                         (fnum (:sim-tensile-load-n tele)) " N "
                         (if out? (chip "red" "許容下限未満") (chip "green" "合格")))
                    "<span class=\"oc-muted\">試験片データなし</span>")
                  (status-chip ledger (:id b))
                  (refs-cell trace :batch-ref (:id b))]))]
    (section
     "batches" "生産バッチ（ヒート／鋳造ロット）"
     "<p class=\"oc-note\">"
     (esc (str "空き容量・検証済み・登録済みは foundrymfg.registry の述語で判定している。"
               "引張荷重の列は foundrymfg.robotics/tensile-test-telemetry-for を実際に呼び、"
               "physics-2d の時間積分シミュレーションを毎回新しく回した結果（許容下限 "))
     (fnum robotics/min-tensile-load-n)
     (esc " N）。:coupon-mass-kg が無いバッチはこの検査の対象外で、欠測は違反として扱わない。")
     "</p>"
     (table nil
            ["batch" "material" "alloy-grade" "output-form" "weight kg" "shipped kg" "空き kg"
             "defect %" "verified?" "registered?" "実測引張荷重 (physics-2d)" "台帳上の最新"
             "この run での参照"]
            rows))))

(defn- equipment-section [db ledger trace]
  (section
   "equipment" "設備（溶解炉／造型／シェイクアウト）"
   (table nil
          ["equipment" "kind" "verified?" "registered?" "last-maintenance-date"
           "last-scheduled-maintenance-date" "台帳上の最新" "この run での参照"]
          (for [e (store/all-equipment db)]
            [(str "<code>" (esc (:id e)) "</code>")
             (str "<code>" (esc (nm (:kind e))) "</code>")
             (yes-no (registry/equipment-verified? e))
             (yes-no (registry/equipment-registered? e))
             (esc (or (:last-maintenance-date e) "—"))
             (esc (or (:last-scheduled-maintenance-date e) "—"))
             (status-chip ledger (:id e))
             (refs-cell trace :equip-ref (:id e))]))))

(defn- maintenance-section [db ledger]
  (let [records (into {} (for [r (store/maintenance-history db)]
                           [(get r "maintenance_id") r]))]
    (section
     "maintenance" "保守作業予定ドラフト"
     "<p class=\"oc-note\">"
     (esc "ドラフトのみ。foundrymfg.registry/register-maintenance が組み立てる証明書は常に未署名（署名は人間の行為）。")
     "</p>"
     (table nil
            ["maintenance" "equipment" "type" "scheduled-date" "scheduled?"
             "maintenance-number" "record kind" "台帳上の最新"]
            (for [m (store/all-maintenance db)]
              (let [r (get records (:id m))]
                [(str "<code>" (esc (:id m)) "</code>")
                 (str "<code>" (esc (:equipment-id m)) "</code>")
                 (str "<code>" (esc (nm (:maintenance-type m))) "</code>")
                 (esc (or (:scheduled-date m) "—"))
                 (yes-no (true? (:scheduled? m)))
                 (str "<code>" (esc (or (:maintenance-number m) "—")) "</code>")
                 (esc (or (get r "kind") "—"))
                 (status-chip ledger (:id m))]))))))

(defn- shipments-section [db ledger]
  (section
   "shipments" "出荷調整ドラフト"
   (table nil
          ["shipment" "batch" "weight kg" "destination" "shipment-number" "record kind" "台帳上の最新"]
          (for [r (store/shipment-history db)
                :let [sid (get r "shipment_id")
                      s   (store/shipment db sid)]]
            [(str "<code>" (esc sid) "</code>")
             (str "<code>" (esc (:batch-id s)) "</code>")
             (fnum (:weight-kg s))
             (esc (or (:destination s) "—"))
             (str "<code>" (esc (or (:shipment-number s) "—")) "</code>")
             (esc (or (get r "kind") "—"))
             (status-chip ledger sid)]))))

(defn- safety-section [db ledger]
  (section
   "safety" "安全懸念"
   (table nil
          ["concern" "equipment" "severity" "description" "台帳上の最新"]
          (for [c (store/safety-concerns db)]
            [(str "<code>" (esc (:id c)) "</code>")
             (str "<code>" (esc (or (:equipment-id c) "—")) "</code>")
             (str "<code>" (esc (nm (:severity c))) "</code>")
             (esc (:description c))
             (status-chip ledger (:id c))]))))

(defn- trace-section [trace]
  (section
   "trace" "実行トレース（1 リクエスト = 1 グラフ実行）"
   "<p class=\"oc-note\">"
   (esc "disposition・confidence・違反ルールはすべて actor 自身の出力。承認は actor が実際に escalate した時にだけ与えている（HARD hold は人間に届かない）。")
   "</p>"
   (table nil
          ["thread" "op" "request :effect" "subject" "シナリオ" "承認" "disposition" "confidence" "governor 違反"]
          (for [s trace]
            [(str "<code>" (esc (:thread s)) "</code>")
             (str "<code>" (esc (nm (:op s))) "</code>")
             (str "<code>" (esc (nm (:req-effect s))) "</code>")
             (str "<code>" (esc (:subject s)) "</code>")
             (esc (:label s))
             (cond
               (= :approved (:approval s)) (chip "green" "人間が承認")
               (= :rejected (:approval s)) (chip "orange" "人間が却下")
               (:escalated? s)             (chip "yellow" "承認待ち")
               :else                       "<span class=\"oc-muted\">不要</span>")
             (case (:disposition s)
               :commit   (chip "green" "commit")
               :hold     (if (:hard? s) (chip "red" "HARD hold") (chip "orange" "hold"))
               :escalate (chip "yellow" "escalate")
               (chip "gray" (str (:disposition s))))
             (fnum (:confidence s))
             (if (seq (:rules s))
               (str/join " " (map #(str "<code>" (esc (nm %)) "</code>") (:rules s)))
               "<span class=\"oc-muted\">なし</span>")]))))

(defn- holds-section [ledger]
  (let [holds (filter #(= :governor-hold (:t %)) ledger)]
    (section
     "holds" "ガバナが実際に発火させた HARD hold"
     "<p class=\"oc-note\">"
     (esc "ルール名と説明文は foundrymfg.governor が返した値そのもの。SSoT への書き込みは一切起きていない。")
     "</p>"
     (table nil
            ["rule" "op" "subject" "actor" "confidence" "ガバナの説明"]
            (for [h holds
                  v (:violations h)]
              [(str "<code>" (esc (nm (:rule v))) "</code>")
               (str "<code>" (esc (nm (:op h))) "</code>")
               (str "<code>" (esc (:subject h)) "</code>")
               (str "<code>" (esc (:actor h)) "</code>")
               (fnum (:confidence h))
               (esc (or (:detail v) "—"))])))))

(defn- gate-section [ledger trace]
  (let [ph        phase/default-phase
        {:keys [label writes auto]} (get phase/phases ph)
        observed  (reduce (fn [m s] (update m (:op s) (fnil conj #{}) (:disposition s)))
                          {} trace)
        committed (set (map :op (filter #(= :committed (:t %)) ledger)))]
    (section
     "gate" (str "アクションゲート（phase " ph " — " label "）")
     "<p class=\"oc-note\">"
     (esc "この表は foundrymfg.governor/allowed-ops と foundrymfg.phase/phases から導出している（文言のハードコードではない）。")
     "</p>"
     (table nil
            ["op" (str "phase " ph " で書き込み可") "governor clean なら自動コミット"
             "この demo で観測された disposition" "台帳に commit された"]
            (for [o (sort governor/allowed-ops)]
              [(str "<code>" (esc (nm o)) "</code>")
               (if (contains? writes o) (chip "green" "可") (chip "gray" "phase-disabled"))
               (if (contains? auto o)
                 (chip "green" "自動コミット")
                 (chip "yellow" "常に人間の承認 (phase-approval)"))
               (if-let [ds (get observed o)]
                 (str/join " " (map #(str "<code>" (esc (nm %)) "</code>") (sort ds)))
                 "<span class=\"oc-muted\">未実行</span>")
               (if (contains? committed o) (chip "green" "あり") (chip "gray" "なし"))]))
     "<p class=\"oc-note\">"
     (esc (str "proposal が宣言してよい effect の閉じた許可リスト: "
               (str/join ", " (map nm (sort governor/allowed-proposal-effects)))
               "。これ以外（溶解炉・注湯ラインの直接操作など）は恒久的に HARD hold。"
               "常に人間を要する stake: " (str/join ", " (map nm (sort governor/high-stakes)))
               "。confidence の下限: " (fnum governor/confidence-floor) "。"))
     "</p>")))

(defn- ledger-section [ledger]
  (section
   "ledger" "監査台帳（append-only）"
   (table nil
          ["#" "fact" "op" "subject" "actor" "disposition" "basis / summary"]
          (map-indexed
           (fn [i f]
             [(str (inc i))
              (str "<code>" (esc (nm (:t f))) "</code>")
              (str "<code>" (esc (nm (or (:op f) "—"))) "</code>")
              (str "<code>" (esc (:subject f)) "</code>")
              (str "<code>" (esc (:actor f)) "</code>")
              (str "<code>" (esc (nm (or (:disposition f) "—"))) "</code>")
              (if (= :committed (:t f))
                (esc (:summary f))
                (str/join " " (map #(str "<code>" (esc (nm %)) "</code>") (:basis f))))])
           ledger))))

;; ----------------------------- page -----------------------------

(def ^:private fallback-css
  (str "body{font:14px/1.7 system-ui,sans-serif;margin:0;color:#1a1a1a;background:#fff}"
       ".dds-ext-container{max-width:64rem;margin-inline:auto;padding-inline:1rem}"
       ".dds-ext-section{padding-block:2rem;border-top:1px solid #e5e5e5}"
       ".dds-ext-card{border:1px solid #e5e5e5;border-radius:12px;padding:1.5rem}"
       ".dads-table__table{border-collapse:collapse;width:100%;font-size:.85rem}"
       ".dads-table__table :is(td,th){padding:.4rem .5rem;border-bottom:1px solid #eee;text-align:left}"
       ".dads-chip-label{display:inline-block;border:1px solid #999;border-radius:8px;padding:1px 6px;font-size:.8rem}"
       "code{background:#f0f0f0;padding:1px 5px;border-radius:4px;font-size:.9em}"))

(def ^:private console-css
  ;; App-level CSS is deliberately tiny and only covers what DADS does not
  ;; ship (a wider console container, a dense data grid, a callout band).
  ;; Colours come from DADS' own semantic custom properties -- no raw hex,
  ;; nothing re-derived from the design system.
  (str ".oc-container{max-width:84rem}"
       ".dads-table{width:100%}"
       ".dads-table__table{font-size:.86rem}"
       ".dads-table__table :is(td,th){padding:.45rem .6rem;vertical-align:top}"
       ".dads-table__table code{white-space:nowrap}"
       ".dads-table__table .dads-chip-label{min-height:0;font-size:.8rem;white-space:nowrap}"
       ".oc-note{margin:.25rem 0 1rem;font-size:.875rem;line-height:1.9}"
       ".oc-muted{opacity:.65}"
       ".oc-head{padding-block:2.5rem 1rem}"
       ".oc-head .dads-heading{margin:0 0 .75rem}"
       ".oc-chips{display:flex;flex-wrap:wrap;gap:.5rem}"
       ".oc-alert{border-left:8px solid var(--color-semantic-error-1,#c00);margin-bottom:1rem}"
       ".oc-alert h2{margin:0 0 .5rem}"
       ".oc-alert p{margin:0 0 .5rem;line-height:1.9}"
       ".oc-alert p:last-child{margin-bottom:0}"
       ".oc-foot{padding-block:2rem 3rem;font-size:.875rem;line-height:1.9}"))

(defn- vendored-dads-css
  "Reuse the jp-go-digital-design-system CSS this repo already vendors
  into `docs/index.html` (single `<style>` block, generated upstream by
  scripts/vendor.cljs). Returns nil in a standalone fork where that
  file is absent -- the caller falls back to a minimal stylesheet."
  [path]
  (let [f (java.io.File. ^String path)]
    (when (.isFile f)
      (let [s (slurp f :encoding "UTF-8")
            i (str/index-of s "<style>")
            j (str/index-of s "</style>")]
        (when (and i j (< i j))
          (subs s (+ i (count "<style>")) j))))))

(defn render
  "Project the store the actor wrote (+ the decision trace) into HTML."
  [{:keys [db trace]} & [{:keys [css-source] :or {css-source "docs/index.html"}}]]
  (let [ledger    (vec (store/ledger db))
        unknown   (remove #(contains? ledger-fact-types (:t %)) ledger)
        _         (when (seq unknown)
                    (binding [*out* *err*]
                      (println "WARNING: ledger carries fact types this renderer does not project:"
                               (pr-str (distinct (map :t unknown))))))
        holds     (filter #(= :governor-hold (:t %)) ledger)
        rules     (sort (distinct (mapcat :basis holds)))
        committed (filter #(= :committed (:t %)) ledger)
        rejected  (filter #(= :approval-rejected (:t %)) ledger)
        css       (or (vendored-dads-css css-source) fallback-css)]
    (str
     "<!DOCTYPE html>\n<html lang=\"ja\">\n<head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
     "<meta name=\"color-scheme\" content=\"light\"><meta name=\"theme-color\" content=\"#ffffff\">"
     "<title>操作コンソール | cloud-itonami-isic-2431 foundrymfg</title>"
     "<meta name=\"description\" content=\"foundrymfg の実アクター（advisor → governor → phase gate → commit/hold/approval）を実際に駆動して生成した操作コンソール。\">"
     "<style>" css "\n" console-css "</style></head><body>"
     "<div class=\"dds-ext-container oc-container\">"

     ;; header
     "<header class=\"oc-head\">"
     "<h1 class=\"dads-heading\" data-size=\"36\">鋳造（鋳鉄・鋳鋼）工場 運用コンソール</h1>"
     "<div class=\"oc-chips\">"
     (chip "blue" "ISIC 2431 · foundrymfg")
     (chip "blue" (str "phase " phase/default-phase " · " (:label (get phase/phases phase/default-phase))))
     (chip "green" (str "committed " (count committed)))
     (chip "red" (str "HARD hold " (count holds)))
     (chip "orange" (str "人間が却下 " (count rejected)))
     "</div>"
     "<p class=\"oc-note\">"
     (esc (str "このページは build 時に foundrymfg.render-html が "
               "foundrymfg.store の MemStore を seed し、foundrymfg.operation/build の "
               "langgraph StateGraph を langgraph.graph/run* で実際に駆動し、"
               "その結果書き込まれた SSoT と append-only 監査台帳を投影したもの。"
               "手書きの HTML も、モックのアクターも、捏造した値も含まない。"))
     "</p>"
     "</header>"

     ;; the HARD-hold callout -- the count and every rule name below come
     ;; from the governor's own output, read back out of the ledger
     "<div class=\"dds-ext-card oc-alert\">"
     "<h2 class=\"dads-heading\" data-size=\"20\">"
     (esc (str "ガバナが " (count holds) " 件の HARD hold を発火（SSoT への書き込みは阻止済み）"))
     "</h2><p>"
     (esc "発火したルール: ")
     (str/join " " (map #(str "<code>" (esc (nm %)) "</code>") rules))
     "</p><p>"
     (esc (str "うち " (nm :furnace-actuate-blocked) " と " (nm :furnace-control-blocked)
               " は恒久的な禁止で、いかなる phase・いかなる人間の承認でも解除できない。"))
     "</p></div>"

     (batches-section db ledger trace)
     (equipment-section db ledger trace)
     (maintenance-section db ledger)
     (shipments-section db ledger)
     (safety-section db ledger)
     (trace-section trace)
     (holds-section ledger)
     (gate-section ledger trace)
     (ledger-section ledger)

     "<footer class=\"oc-foot\">"
     "<hr class=\"dads-divider\" data-color=\"solid-gray-420\" data-style=\"solid\" data-width=\"1\">"
     "<p>"
     (esc "再生成: ")
     "<code>clojure -M:dev:render-html</code>"
     (esc (str " — 出力は決定的（時刻・乱数・マップ順序に依存しない）。台帳に現れる fact 種別は "
               ":committed / :governor-hold / :approval-rejected の 3 つだけで、"
               ":approval-granted と :approval-requested は foundrymfg.operation の "
               ":audit チャネル内にのみ存在し台帳には追記されないため、このページは主張しない。"))
     "</p>"
     "<p>"
     (esc "スタイルは本リポジトリが docs/index.html に既に vendoring している jp-go-digital-design-system (DADS) の CSS を再利用している。")
     "</p>"
     "</footer>"
     "</div></body></html>\n")))

(defn -main [& args]
  (let [out    (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        html   (render result)
        f      (java.io.File. ^String out)]
    (when-let [p (.getParentFile f)] (.mkdirs p))
    (spit f html :encoding "UTF-8")
    (let [ledger (store/ledger (:db result))]
      (println (str "wrote " out
                    " (" (count html) " chars, "
                    (count (:trace result)) " actor runs, "
                    (count ledger) " ledger facts, "
                    (count (filter #(= :governor-hold (:t %)) ledger)) " HARD holds)")))))
