(ns concurrent-server-atoms
  (:require [clojure.java.io :as io]
            [clojure.core.async :as a])
  (:import (java.net ServerSocket)))


(defn update-db
  [db key]
  (swap! db (fn [current-state]
              (let [timestamp (System/currentTimeMillis)
                    updated-db (assoc current-state key timestamp)]
                (println updated-db)
                updated-db))))

(defn handle-client
  [client db]
  (a/go
    (loop []
      (let [request (.readLine (io/reader client))
            writer (io/writer client)]
        (if (nil? request)
          (do (println "Nil response, closing client")
              (.close client))
          (let [key (.hashCode client)]
            (update-db db key)
            (let [result (get @db (.hashCode client))]
              (.write writer (str "OK, " result "\n"))
              (.flush writer)
              (recur))))))))

(defn main
  []
  (let [db (atom {})]
    (with-open [server-socket (ServerSocket. 3000)]
      (loop []
        (let [client-socket (.accept server-socket)]
          (handle-client client-socket db)
          (recur))))))

(main)
