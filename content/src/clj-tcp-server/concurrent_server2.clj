(ns concurrent-server2
  (:require [clojure.java.io :as io]
            [clojure.core.async :as a])
  (:import (java.net ServerSocket)))

(defn handle-client
  [client db]
  (a/go
    (loop []
      (let [request (.readLine (io/reader client))
            writer (io/writer client)]
        (if (nil? request)
          (do
            (println "Nil response, closing client")
            (.close client))
          (do
            (.write writer "Hello 👋\n")
            (.flush writer)
            (recur)))))))


(defn main
  []
  (with-open [server-socket (ServerSocket. 3000)]
    (loop []
      (let [client-socket (.accept server-socket)
            db (hash-map)]
        (let [c (handle-client client-socket db)]
          (println c))
        (recur)))))

(main)
