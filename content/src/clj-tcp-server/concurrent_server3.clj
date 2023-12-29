(ns concurrent-server3
  (:require [clojure.java.io :as io]
            [clojure.core.async :as a])
  (:import (java.net ServerSocket)))

(defn handle-client
  [client channel]
  (a/go
    (loop []
      (let [request (.readLine (io/reader client))
            writer (io/writer client)
            message {:channel (a/chan) :client client}]
        (if (nil? request)
          (do (println "Nil response, closing client")
              (.close client))
          (do (a/>! channel message)
              (let [result (a/<! (:channel message))]
                (.write writer (str "OK, " result "\n"))
                (.flush writer)
                (recur))))))))

(defn handle-db
  [command-channel]
  (a/go
    (loop [db {}]
      (let [resp (a/<! command-channel)
            timestamp (System/currentTimeMillis)
            updated-db (assoc db (.hashCode (:client resp)) timestamp)]
        (println updated-db)
        (a/>! (:channel resp) timestamp)
        (recur updated-db)))))

(defn main
  []
  (let [command-channel (a/chan)]
    (with-open [server-socket (ServerSocket. 3000)]
      (handle-db command-channel)
      (loop []
        (let [client-socket (.accept server-socket)]
          (handle-client client-socket command-channel)
          (recur))))))

(main)
