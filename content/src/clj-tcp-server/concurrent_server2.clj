(ns concurrent-server2
  (:require [clojure.java.io :as io]
            [clojure.core.async :as a])
  (:import (java.net ServerSocket)))

(defn handle-client
  [client original-db]
  (a/go
    (loop [db original-db]
      (let [request (.readLine (io/reader client))
            writer (io/writer client)]
        (if (nil? request)
          (do
            (println "Nil response, closing client")
            (.close client))
          (let [updated-db (assoc db (System/currentTimeMillis) request)]
            (.write writer "Hello 👋\n")
            (.flush writer)
            (recur updated-db)))))))


(defn main
  []
  (with-open [server-socket (ServerSocket. 3000)]
    (loop []
      (let [client-socket (.accept server-socket)
            db (hash-map)]
        (handle-client client-socket db)
        (recur)))))

(main)
