(ns final-server
  (:require [clojure.core.async :as a]
            [clojure.java.io :as io]
            [clojure.string :as string])
  (:import (java.net ServerSocket))
  (:gen-class))

(defn key-request
  "Helper to structure the basic parts of a command"
  [command key channel]
  {:command command :key key :resp channel})

(defn key-value-request
  "Helper to structure the various parts of a SET command"
  [command key value channel]
  (assoc (key-request command key channel) :value value))

(def valid-commands
  "Valid commands"
  #{"GET" "SET" "DEL" "INCR"})

(defn request-for-command
  "Return a structured representation of a client command"
  [command parts resp-channel]
  (cond
    (= command "GET")
    (key-request :get (get parts 1) resp-channel)
    (= command "SET")
    (key-value-request :set (get parts 1) (get parts 2) resp-channel)
    (= command "INCR")
    (key-request :incr (get parts 1) resp-channel)
    (= command "DEL")
    (key-request :del (get parts 1) resp-channel)))

(defn handle-client
  "Read from a connected client, and handles the various commands accepted by the server"
  [client-socket command-channel]
  (a/go (loop [resp-channel (a/chan)]
          (let [request (.readLine (io/reader client-socket))
                writer (io/writer client-socket)]
            (if (nil? request)
              (do
                (println "Nil request, closing")
                (a/close! resp-channel)
                (.close client-socket))
              (let [parts (string/split request #" ")
                    command (get parts 0)]
                (cond
                  (contains? valid-commands command)
                  (let [request (request-for-command command parts resp-channel)]
                    (a/>! command-channel request)
                    (let [value (a/<! resp-channel)]
                      (.write writer (str value "\n"))
                      (.flush writer)
                      (recur resp-channel)))
                  (= command "QUIT")
                  (do
                    (a/close! resp-channel)
                    (.close client-socket))
                  :else (do
                          (println "Unknown request:" request)
                          (recur resp-channel)))))))))

(defn atoi
  "Attempt to convert a string to integer, returns nil if it can't be parsed"
  [string]
  (try
    (Integer/valueOf string)
    (catch NumberFormatException _e
      nil)))

(defn process-command
  "Perform various operations depending on the command sent by the client"
  [db command key value]
  (cond
    (= command :get)
    (if key
      (let [value (get db key "")]
        {:updated db :response value})
      {:updated db :response "ERR wrong number of arguments for 'get' command"})
    (= command :set)
    (if (and key value)
      {:updated (assoc db key value) :response "OK"}
      {:updated db :response "ERR wrong number of arguments for 'set' command"})
    (= command :del)
    (if key
      (if (contains? db key)
        {:updated (dissoc db key) :response "1"}
        {:updated db :response "0"})
      {:updated db :response "ERR wrong number of arguments for 'del' command"})
    (= command :incr)
    (if key
      (if (contains? db key)
        (let [current-number (atoi (get db key))
              new-number (when current-number (str (+ current-number 1)))]
          (if current-number
            {:updated (assoc db key new-number) :response new-number}
            {:updated db :response "ERR value is not an integer or out of range"}))
        {:updated (assoc db key "1") :response "1"})
      {:updated db :response "ERR wrong number of arguments for 'incr' command"})
    :else {:updated db :response "Unknown command"}))

(defn handle-db
  "Run a go block in which we continuously wait for clients to send commands,
   process them, and send back a response through teh channel included in the
   received hash map"
  [command-channel]
  (a/go (loop [db {}]
          (let [request (a/<! command-channel)
                command (request :command)
                key (request :key)
                value (request :value)
                chan-resp (request :resp)
                result (process-command db command key value)
                new-db (result :updated)
                response (result :response)]
            (a/>! chan-resp response)
            (recur new-db)))))

(defn main
  "Start a server and continuously wait for new clients to connect"
  []
  (println "About to start ...")
  (let [command-channel (a/chan)]
    (handle-db command-channel)
    (with-open [server-socket (ServerSocket. 3000)]
      (loop []
        (let [client-socket (.accept server-socket)]
          (handle-client client-socket command-channel))
        (recur)))))

(main)
