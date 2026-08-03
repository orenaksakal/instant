(ns instant.model.app-backup-barrier-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [instant.jdbc.sql :as sql]
   [instant.model.app :as app-model]))

(deftest shared-backup-lock-uses-the-stable-app-key
  (let [captured (atom nil)
        app-id (random-uuid)]
    (with-redefs [sql/select-one
                  (fn [operation conn query]
                    (reset! captured [operation conn query])
                    {:acquired true :status "active"})]
      (app-model/acquire-backup-barrier-shared! ::connection app-id))
    (let [[operation conn [statement query-app-id seed status-app-id]] @captured]
      (is (= ::app-model/acquire-backup-barrier-shared! operation))
      (is (= ::connection conn))
      (is (re-find #"pg_try_advisory_xact_lock_shared" statement))
      (is (= app-id query-app-id))
      (is (= app-model/backup-barrier-lock-seed seed))
      (is (= app-id status-app-id)))))

(deftest shared-backup-lock-rejects-protected-and-disabled-apps
  (testing "an exclusive protected-backup lock fails fast"
    (with-redefs [sql/select-one (fn [& _] {:acquired false :status "active"})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (app-model/acquire-backup-barrier-shared!
                    ::connection (random-uuid))))))
  (testing "database status is authoritative"
    (with-redefs [sql/select-one (fn [& _] {:acquired true :status "disabled"})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (app-model/acquire-backup-barrier-shared!
                    ::connection (random-uuid)))))))
