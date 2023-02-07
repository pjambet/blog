(ns tcp
  (:require [clojure.java.io :as io])
  (:import (java.net ServerSocket)))

(defn handle-client
  [client]
  (let [writer (io/writer client)]
    (.write writer "Hello 👋\n")
    (.flush writer)
    (.close client)))


(defn main
  []
  (with-open [server-socket (ServerSocket. 3000)]
    (loop []
      (let [client-socket (.accept server-socket)]
        (handle-client client-socket))
      (recur))))

(main)
