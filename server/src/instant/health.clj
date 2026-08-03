(ns instant.health
  (:require [compojure.core :refer [defroutes GET] :as compojure]
            [instant.config :as config]
            [instant.util.tracer :as tracer]
            [instant.jdbc.aurora :as aurora]
            [instant.jdbc.sql :as sql]
            [instant.util.json :refer [->json]]
            [honey.sql :as hsql]
            [ring.util.http-response :as response])
  (:import [java.time Instant]))

(def send-agent (agent nil))

(defn mark-wal-unhealthy []
  (sql/execute!
   (aurora/conn-pool :write)
   (hsql/format
    {:insert-into :config
     :values [{:k "wal-errors"
               :v [:cast (->json {@config/process-id (str (Instant/now))}) :json]}]
     :on-conflict :k
     :do-update-set {:v [:|| [:cast :config.v :jsonb] [:cast :excluded.v :jsonb]]}})))

(defn mark-wal-healthy []
  (sql/execute!
   (aurora/conn-pool :write)
   (hsql/format
    {:update :config
     :set {:v [:- [:cast :v :jsonb] [:cast @config/process-id :text]]}
     :where [:= :k "wal-errors"]})))

(defn mark-wal-unhealthy-async []
  (send-off send-agent (fn [_]
                         (try
                           (mark-wal-unhealthy)
                           (catch Throwable t
                             (tracer/record-exception-span! t
                                                            {:name "health/mark-wal-unhealthy"
                                                             :escpaing? false}))))))

(defn mark-wal-healthy-async []
  (send-off send-agent (fn [_]
                         (try
                           (mark-wal-healthy)
                           (catch Throwable t
                             (tracer/record-exception-span! t
                                                            {:name "health/mark-wal-healthy"
                                                             :escpaing? false}))))))

(defn health-get [_req]
  ;; WAL health is process-local. A broken replica must be drained without
  ;; making every healthy replica return 500 from the shared database flag.
  (let [wal-error (sql/select-one
                   (aurora/conn-pool :read)
                   ["select coalesce(jsonb_exists(v::jsonb, ?::text), false) as unhealthy
                       from config
                      where k = 'wal-errors'"
                    (str @config/process-id)])
        slot-name (str "invalidator_" @config/process-id)
        local-slot (sql/select-one
                    (aurora/conn-pool :read)
                    ["select active, failover, invalidation_reason
                        from pg_replication_slots
                       where slot_name = ?"
                     slot-name])]
    (if (or (:unhealthy wal-error)
            (not (:active local-slot))
            (not (:failover local-slot))
            (:invalidation_reason local-slot))
      (response/internal-server-error {:wal :error})
      (response/ok {:wal :ok}))))

(defroutes routes
  (GET "/health/system" [] health-get))
