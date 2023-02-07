(ns concurrent-server
  (:require [clojure.java.io :as io]
            [clojure.core.async
             :as a
             :refer [go]])
  (:import (java.net ServerSocket)))

(defn handle-client
  [client]
  (go
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
      (let [client-socket (.accept server-socket)]
        (handle-client client-socket)
        (recur)))))

(main)
