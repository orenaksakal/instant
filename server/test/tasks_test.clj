(ns tasks-test
  (:require [clojure.test :refer [deftest is]]
            [tasks :as tasks]))

(deftest jdbc-url-preserves-security-parameters
  (is (= (str "postgresql://operator:p%40ss@db.example.test:5432/instant"
              "?sslmode=verify-full&connectTimeout=10")
         (tasks/jdbc-url->postgres-url
          (str "jdbc:postgresql://db.example.test:5432/instant"
               "?user=operator&password=p%40ss"
               "&sslmode=verify-full&connectTimeout=10")))))
